package org.yamcs.tctm.pus.services.filetransfer.thirteen;

import static org.yamcs.tctm.pus.services.filetransfer.thirteen.ServiceThirteen.ETYPE_TRANSFER_PACKET_ERROR;
import static org.yamcs.tctm.pus.services.filetransfer.thirteen.ServiceThirteen.ETYPE_TRANSFER_PACKET_ERROR_NOK;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.yamcs.ErrorInCommand;
import org.yamcs.Processor;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsException;
import org.yamcs.YamcsServer;
import org.yamcs.cfdp.pdu.ConditionCode;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.events.EventProducer;
import org.yamcs.filetransfer.TransferMonitor;
import org.yamcs.http.BadRequestException;
import org.yamcs.http.InternalServerErrorException;
import org.yamcs.logging.Log;
import org.yamcs.mdb.CommandEncodingException;
import org.yamcs.mdb.MdbFactory;
import org.yamcs.protobuf.TransferState;
import org.yamcs.security.User;
import org.yamcs.tctm.pus.services.filetransfer.thirteen.packets.FileTransferPacket;
import org.yamcs.tctm.pus.services.filetransfer.thirteen.packets.UplinkS13Packet;
import org.yamcs.xtce.MetaCommand;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.StreamSubscriber;
import org.yamcs.yarch.Tuple;
import org.yamcs.mdb.Mdb;


public abstract class OngoingS13Transfer implements S13FileTransfer {
    protected final S13TransactionId s13TransactionId;
    protected TransferState state;

    protected final ScheduledThreadPoolExecutor executor;
    protected final EventProducer eventProducer;
    protected final Log log;
    protected final long startTime;
    protected final long wallclockStartTime;
    protected final long creationTime;
    protected final String yamcsInstance;

    protected String transferType;

    final TransferMonitor monitor;

    // transaction unique identifier (coming from a database)
    final long id;

    protected ScheduledFuture<?> inactivityFuture;
    final long inactivityTimeout;

    // accumulate the errors
    List<String> errors = new ArrayList<>();
    Stream cmdHistStream;
    int retries;
    String username;

    public enum FaultHandlingAction {
        SUSPEND, CANCEL, ABANDON;

        public static FaultHandlingAction fromString(String str) {
            for (FaultHandlingAction a : values()) {
                if (a.name().equalsIgnoreCase(str)) {
                    return a;
                }
            }
            return null;
        }

        public static List<String> actions() {
            return Arrays.stream(FaultHandlingAction.values()).map(a -> a.name().toLowerCase())
                    .collect(Collectors.toList());
        }
    }

    final Map<ConditionCode, FaultHandlingAction> faultHandlerActions;

    public OngoingS13Transfer(String yamcsInstance, Stream cmdHistStream, long creationTime, ScheduledThreadPoolExecutor executor,
            YConfiguration config, S13TransactionId s13TransactionId,
            EventProducer eventProducer, TransferMonitor monitor, String transferType, Integer retries, String username,
            Map<ConditionCode, FaultHandlingAction> faultHandlerActions) {
        this.yamcsInstance = yamcsInstance;
        this.s13TransactionId = s13TransactionId;
        this.cmdHistStream = cmdHistStream;
        this.state = TransferState.RUNNING;
        this.executor = executor;
        this.eventProducer = eventProducer;
        this.startTime = YamcsServer.getTimeService(yamcsInstance).getMissionTime();
        this.wallclockStartTime = System.currentTimeMillis();
        this.log = new Log(this.getClass(), yamcsInstance);
        this.id = s13TransactionId.getTransferId();
        this.username = username;
        this.creationTime = creationTime;
        if (monitor == null) {
            throw new NullPointerException("the monitor cannot be null");
        }
        this.transferType = transferType;
        this.monitor = monitor;
        this.inactivityTimeout = config.getLong("inactivityTimeout", 10000);

        this.retries = retries != null? retries : config.getInt("filePartRetries", 1);
        this.faultHandlerActions = faultHandlerActions;
    }

    public abstract void processPacket(FileTransferPacket packet);

    public PreparedCommand createS13Telecommand(String fullyQualifiedCmdName, Map<String, Object> assignments, User user) throws BadRequestException, 
            InternalServerErrorException {
        Processor processor = ServiceThirteen.getProcessor();
        MetaCommand cmd = processor.getMdb().getMetaCommand(fullyQualifiedCmdName);

        PreparedCommand pc = null;
        try {
            var origin = InetAddress.getLocalHost().getHostName();
            pc = processor.getCommandingManager().buildCommand(cmd, assignments, origin, 0, user);

        } catch (ErrorInCommand e) {
            throw new BadRequestException(e);
        } catch (YamcsException e) { // could be anything, consider as internal server error
            throw new InternalServerErrorException(e);
        } catch (UnknownHostException e) {
            throw new InternalServerErrorException(e);
        }

        return pc;
    }

    protected void pushError(String err) {
        errors.add(err);
    }

    public User getCommandReleaseUser() {
        User user = ServiceThirteen.getUserDirectory().getUser(username);
        if (user == null)
            return ServiceThirteen.getUserDirectory().getUser(ServiceThirteen.commandReleaseUser);

        return user;
    }

    protected void sendPacket(UplinkS13Packet packet) throws Exception {
        try {
            // Mutable holder for PreparedCommand
            PreparedCommand[] pcHolder = new PreparedCommand[1];
            StreamSubscriber sc = null;

            SharedMutex mutex = new SharedMutex();
            mutex.setStatus(true);

            // Create streamSubscriber and add to the cmdhist stream
            if (!packet.skipAcknowledgement()) {
                sc = new StreamSubscriber() {
                    @Override
                    public void streamClosed(Stream stream) {
                        // Should never happen
                        return;
                    }
        
                    @Override
                    public void onTuple(Stream stream, Tuple tuple) {
                        Mdb xtcedb = MdbFactory.getInstance(yamcsInstance);
                        PreparedCommand pc1 = PreparedCommand.fromTuple(tuple, xtcedb);

                        synchronized (mutex) {
                            if (pcHolder[0] != null 
                                    && pc1.getCommandId().equals(pcHolder[0].getCommandId())) {
                                String attr = pc1.getStringAttribute("CommandComplete_Status");
                                if (attr == null)
                                    return;
            
                                switch (attr) {
                                    case "OK" -> mutex.setStatus(true);
                                    case "NOK" -> mutex.setStatus(false);
                                }

                                mutex.notify();
                            }
                        }
                    }
                };

                cmdHistStream.addSubscriber(sc);
            }

            for(int index = 1; index <= retries; index++) {
                try {
                    PreparedCommand pc = packet.generatePreparedCommand(this);
                    pcHolder[0] = pc;

                    // Send the command
                    ServiceThirteen.getCommandingManager().sendCommand(getCommandReleaseUser(), pc);
                    if (log.isDebugEnabled()) {
                        log.debug("TXID{} sending StartUplinkS13 Packet | Qualified Name: {} | Part Sequence Number: {}",
                                s13TransactionId, packet.getFullyQualifiedName(), packet.getPartSequenceNumber());
                    }

                    // If set in fire-and-forget-mode, then no need to wait for ACK's
                    if (packet.skipAcknowledgement())
                        break;

                    synchronized (mutex) {
                        mutex.wait(3_1000);

                        if (mutex.getStatus())
                            break;

                        sendWarnEvent(ETYPE_TRANSFER_PACKET_ERROR_NOK, 
                                "LargePacketUplink | NOK | Transaction ID: " + s13TransactionId
                                        + " | CommandName: " + packet.getFullyQualifiedName() + "Part Sequence Number: "
                                        + packet.getPartSequenceNumber() + " was not acknowledged");
                    }

                } catch (BadRequestException | InternalServerErrorException e) {
                    log.error("TXID{} could not send StartUplinkS13 Packet: Qualified Name: {} | Part Sequence Number: {} | ERROR: {}",
                            s13TransactionId, packet.getFullyQualifiedName(), packet.getPartSequenceNumber(), e.toString());

                    sendWarnEvent(ETYPE_TRANSFER_PACKET_ERROR,
                            "Unable to construct the StartUplinkS13 Command | Transaction ID: " + s13TransactionId
                                    + " | CommandName: " + packet.getFullyQualifiedName() + " Part Sequence Number: "
                                    + packet.getPartSequenceNumber());

                    throw new CommandEncodingException(e.toString());
                }
            }

            // Remove stream subscriber
            if (sc != null)
                cmdHistStream.removeSubscriber(sc);

            if (!mutex.getStatus())
                throw new Exception("LargePacketUplink | NOK | Transaction ID: " + s13TransactionId
                                        + " | CommandName: " + packet.getFullyQualifiedName() + "Part Sequence Number: "
                                        + packet.getPartSequenceNumber() + " was not acknowledged");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedException("S13: cancel request received");
        }
    }

    public final boolean isOngoing() {
        return state == TransferState.RUNNING || state == TransferState.PAUSED;
    }

    public final TransferState getTransferState() {
        return state;
    }

    @Override
    public boolean cancellable() {
        return true;
    }

    @Override
    public boolean pausable() {
        return true;
    }

    protected abstract void onInactivityTimerExpiration();

    protected void cancelInactivityTimer() {
        if (inactivityFuture != null) {
            inactivityFuture.cancel(false);
        }
    }

    protected void rescheduleInactivityTimer() {
        cancelInactivityTimer();
        inactivityFuture = executor.schedule(this::onInactivityTimerExpiration, inactivityTimeout,
                TimeUnit.MILLISECONDS);
    }

    public OngoingS13Transfer pauseTransfer() {
        executor.submit(this::suspend);
        return this;
    }

    protected abstract void suspend();

    public OngoingS13Transfer resumeTransfer() {
        executor.submit(this::resume);
        return this;
    }

    protected abstract void resume();

    public OngoingS13Transfer cancelTransfer() {
        executor.submit(() -> {
            pushError("Cancel request received");
            cancel(ConditionCode.CANCEL_REQUEST_RECEIVED);
        });
        return this;
    }

    protected abstract void cancel(ConditionCode code);

    public OngoingS13Transfer abandonTransfer(String reason) {
        executor.submit(() -> failTransfer(reason));
        return this;
    }

    // @Override
    public S13TransactionId getTransactionId() {
        return s13TransactionId;
    }

    @Override
    public boolean isReliable() {
        return false;
    }

    @Override
    public long getStartTime() {
        return startTime;
    }

    protected void failTransfer(String failureReason) {
        pushError(failureReason);
        changeState(TransferState.FAILED);
    }

    protected void changeState(TransferState newState) {
        this.state = newState;
        monitor.stateChanged(this);
    }

    @Override
    public String getFailuredReason() {
        return String.join("; \n", errors);
    }

    @Override
    public long getId() {
        return id;
    }

    protected FaultHandlingAction getFaultHandlingAction(ConditionCode code) {
        FaultHandlingAction action = faultHandlerActions.get(code);
        return Objects.requireNonNullElse(action, FaultHandlingAction.CANCEL);
    }
    
    protected void sendInfoEvent(String type, String msg) {
        eventProducer.sendInfo(type, "TXID[" + s13TransactionId + "] " + msg);
    }

    protected void sendWarnEvent(String type, String msg) {
        eventProducer.sendWarning(type, "TXID[" + s13TransactionId + "] " + msg);
    }

    public long getCreationTime() {
        return creationTime;
    }

    @Override
    public long getInitiatorEntityId() {
        return s13TransactionId.getLargePacketTransactionId();
    }

    @Override
    public long getDestinationId() {
        return s13TransactionId.getLargePacketTransactionId();
    }

    private class SharedMutex {
        private boolean success;

        public void setStatus(boolean success) {
            this.success = success;
        }

        public boolean getStatus() {
            return this.success;
        }
    }
}
