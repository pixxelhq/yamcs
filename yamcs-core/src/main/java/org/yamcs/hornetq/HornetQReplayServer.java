package org.yamcs.hornetq;

import static org.yamcs.api.Protocol.DATA_TO_HEADER_NAME;
import static org.yamcs.api.Protocol.REPLYTO_HEADER_NAME;
import static org.yamcs.api.Protocol.REQUEST_TYPE_HEADER_NAME;
import static org.yamcs.api.Protocol.decode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.hornetq.api.core.HornetQException;
import org.hornetq.api.core.SimpleString;
import org.hornetq.api.core.client.ClientMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.ProcessorFactory;
import org.yamcs.YProcessor;
import org.yamcs.YProcessorListener;
import org.yamcs.YamcsException;
import org.yamcs.api.Protocol;
import org.yamcs.api.YamcsApiException;
import org.yamcs.api.YamcsClient;
import org.yamcs.api.YamcsSession;
import org.yamcs.archive.ReplayServer;
import org.yamcs.parameter.ParameterValueWithId;
import org.yamcs.parameter.ParameterWithIdConsumer;
import org.yamcs.parameter.ParameterWithIdRequestHelper;
import org.yamcs.protobuf.Pvalue.ParameterData;
import org.yamcs.protobuf.Yamcs.Instant;
import org.yamcs.protobuf.Yamcs.NamedObjectId;
import org.yamcs.protobuf.Yamcs.NamedObjectList;
import org.yamcs.protobuf.Yamcs.PacketReplayRequest;
import org.yamcs.protobuf.Yamcs.ProtoDataType;
import org.yamcs.protobuf.Yamcs.ReplayRequest;
import org.yamcs.protobuf.Yamcs.ReplayStatus;
import org.yamcs.protobuf.Yamcs.StringMessage;
import org.yamcs.security.HqClientMessageToken;
import org.yamcs.security.Privilege;
import org.yamcs.xtce.MdbMappings;

import com.google.common.util.concurrent.AbstractExecutionThreadService;

/**
 * Provides connection to the replay server via hornetq
 * @author nm
 *
 */
public class HornetQReplayServer extends AbstractExecutionThreadService {
    static Logger log=LoggerFactory.getLogger(HornetQReplayServer.class);

    final String instance;

    final YamcsClient msgClient;
    final YamcsSession yamcsSession;
    final ReplayServer replayServer;
    static AtomicInteger count = new AtomicInteger();
    
    public HornetQReplayServer(ReplayServer replayServer) throws HornetQException, YamcsApiException {
        this.replayServer = replayServer;
        this.instance = replayServer.getInstance();
        yamcsSession = YamcsSession.newBuilder().build();
        msgClient = yamcsSession.newClientBuilder().setRpcAddress(Protocol.getYarchReplayControlAddress(instance)).build();
    }

    @Override
    protected void startUp() {
        Thread.currentThread().setName(this.getClass().getSimpleName()+"["+instance+"]");
    }

    @Override
    public void run() {
        try {
            while(isRunning()) {
                ClientMessage msg=msgClient.rpcConsumer.receive();
                if(msg==null) {
                    if(isRunning()) log.warn("Null message received from the control queue");
                    continue;
                }
                SimpleString replyto=msg.getSimpleStringProperty(REPLYTO_HEADER_NAME);
                SimpleString dataAddress=msg.getSimpleStringProperty(DATA_TO_HEADER_NAME);
                if(replyto==null) {
                    if(isRunning()) log.warn("Did not receive a replyto header. Ignoring the request");
                    continue;
                }
                try {
                    String request=msg.getStringProperty(REQUEST_TYPE_HEADER_NAME);
                    log.debug("received a new request: "+request);
                    if("createReplay".equalsIgnoreCase(request)) {
                        createReplay(msg, replyto, dataAddress);
                    } else  {
                        throw new YamcsException("Unknown request '"+request+"'");
                    }
                } catch (YamcsException e) {
                    msgClient.sendErrorReply(replyto, e);
                }
            }
        } catch (Exception e) {
            log.error("Got exception while processing the requests ", e);
        }
    }


    /**
     * create a new packet replay object
     */
    public void createReplay(ClientMessage msg, SimpleString replyto, SimpleString dataAddress) throws Exception {
        ReplayRequest replayRequest=(ReplayRequest)decode(msg, ReplayRequest.newBuilder());
        HqClientMessageToken authToken = null;

        if( Privilege.usePrivileges ) {
            Privilege priv = Privilege.getInstance();
            authToken = new HqClientMessageToken(msg, null);

            // Check privileges for requested parameters
            if (replayRequest.hasParameterRequest()) {
                List<NamedObjectId> invalidParameters = new ArrayList<NamedObjectId>();
                for( NamedObjectId noi : replayRequest.getParameterRequest().getNameFilterList() ) {
                    if( ! priv.hasPrivilege(authToken, Privilege.Type.TM_PARAMETER, noi.getName() ) ) {
                        invalidParameters.add( noi );
                    }
                }
                if( ! invalidParameters.isEmpty() ) {
                    NamedObjectList nol=NamedObjectList.newBuilder().addAllList( invalidParameters ).build();
                    log.warn( "Cannot create replay - No privilege for parameters: {}", invalidParameters );
                    throw new YamcsException("InvalidIdentification", "No privilege", nol);
                }
            }

            // Check privileges for requested packets
            if (replayRequest.hasPacketRequest()) {
                Collection<String> allowedPackets = priv.getTmPacketNames(instance, authToken, MdbMappings.MDB_OPSNAME);

                List<NamedObjectId> invalidPackets = new ArrayList<NamedObjectId>();

                for (NamedObjectId noi : replayRequest.getPacketRequest().getNameFilterList()) {
                    if (! allowedPackets.contains(noi.getName())) {
                        invalidPackets.add(noi);
                    }
                }
                if( ! invalidPackets.isEmpty() ) {
                    NamedObjectList nol=NamedObjectList.newBuilder().addAllList( invalidPackets ).build();
                    log.warn( "Cannot create replay - InvalidIdentification for packets: {}", invalidPackets );
                    throw new YamcsException("InvalidIdentification", "Invalid identification", nol);
                }

                // Even when no filter is specified, limit request to authorized packets only
                if (replayRequest.getPacketRequest().getNameFilterList().isEmpty()) {
                    PacketReplayRequest.Builder prr = PacketReplayRequest.newBuilder(replayRequest.getPacketRequest());
                    for (String allowedPacket : allowedPackets) {
                        prr.addNameFilter(NamedObjectId.newBuilder().setName(allowedPacket)
                                .setNamespace(MdbMappings.MDB_OPSNAME));
                    }
                    replayRequest = ReplayRequest.newBuilder(replayRequest).setPacketRequest(prr).build();
                }
            }
        }
       
        

        HornetQReplayListener listener = new HornetQReplayListener(dataAddress);
        
        YProcessor yproc = ProcessorFactory.create(instance, "HornetQReplay_"+count.incrementAndGet(), "ArchiveRetrieval", "internal", replayRequest);
        listener.yproc = yproc;
        
        ParameterWithIdRequestHelper pidrm = new ParameterWithIdRequestHelper(yproc.getParameterRequestManager(), listener);
        pidrm.addRequest(replayRequest.getParameterRequest().getNameFilterList(), authToken);
        
        yproc.addProcessorListener(listener);
        (new Thread(listener)).start();

        StringMessage addr=StringMessage.newBuilder().setMessage(listener.yclient.rpcAddress.toString()).build();
        msgClient.sendReply(replyto,"PACKET_REPLAY_CREATED", addr);
    }

    @Override
    public void triggerShutdown() {
        try {
            msgClient.close();
            yamcsSession.close();
        } catch (HornetQException e) {
            log.warn("Got exception when closing the session", e);
        }
    }
    
    
    static class HornetQReplayListener implements Runnable, YProcessorListener, ParameterWithIdConsumer {        
        YamcsSession ysession;
        YamcsClient yclient;
        SimpleString dataAddress;
        volatile boolean quitting = false;
        YProcessor yproc;
        
        public HornetQReplayListener( SimpleString dataAddress)  throws IOException, HornetQException, YamcsException, YamcsApiException {
            this.dataAddress = dataAddress;
            ysession = YamcsSession.newBuilder().build();
            yclient = ysession.newClientBuilder().setRpc(true).setDataProducer(true).build();
            Protocol.killProducerOnConsumerClosed(yclient.dataProducer, dataAddress);
        }
        
        
        @Override
        public void run() {
            try {
                while(!quitting) {
                    ClientMessage msg=yclient.rpcConsumer.receive();
                    if(msg==null) {
                        if(!quitting) log.warn("null message received from the control queue");
                        continue;
                    }
                    SimpleString replyto=msg.getSimpleStringProperty(REPLYTO_HEADER_NAME);
                    if(replyto==null) {
                        log.warn("did not receive a replyto header. Ignoring the request");
                        continue;
                    }
                    try {
                        String req=msg.getStringProperty(REQUEST_TYPE_HEADER_NAME);

                        log.debug("received a new request: "+req);
                        if("Start".equalsIgnoreCase(req)) {
                            yproc.start();
                            yclient.sendReply(replyto, "OK", null);
                        } else if("GetReplayStatus".equalsIgnoreCase(req)) {
                            ReplayStatus status=ReplayStatus.newBuilder().setState(yproc.getReplayState()).build();
                            yclient.sendReply(replyto, "REPLY_STATUS", status);
                        } else if("Pause".equalsIgnoreCase(req)){
                            yproc.pause();
                            yclient.sendReply(replyto, "OK", null);
                        } else if("Resume".equalsIgnoreCase(req)){
                            yproc.start();
                            yclient.sendReply(replyto, "OK", null);
                        } else if("Quit".equalsIgnoreCase(req)){
                            quit();
                            yclient.sendReply(replyto, "OK", null);
                        } else if("Seek".equalsIgnoreCase(req)){
                            Instant inst=(Instant) decode(msg, Instant.newBuilder());
                            yproc.seek(inst.getInstant());
                            yclient.sendReply(replyto, "OK", null);
                        } else  {
                            throw new YamcsException("Unknown request '"+req+"'");
                        }
                    } catch (YamcsException e) {
                        log.warn("sending error reply ", e);
                        yclient.sendErrorReply(replyto, e.getMessage());

                    }
                }
            } catch (Exception e) {
                log.warn("caught exception in packet reply: ", e);
                e.printStackTrace();
            }
        }
/*
        @Override
        public void newData(ProtoDataType type, MessageLite data) {
            try {
                yclient.sendData(dataAddress, type, data);
            } catch (HornetQException e) {
                log.warn("Got exception when sending data to client", e);
                quit();
            }
        }        
  */      
        public void quit() {
            quitting = true;
            yproc.quit();
        }


        @Override
        public void processorAdded(YProcessor processor) {
        }


        @Override
        public void yProcessorClosed(YProcessor processor) {
            if(processor!=yproc) return;
            sendProcessorState();
        }


        @Override
        public void processorStateChanged(YProcessor processor) {
            if(processor!=yproc) return;
            sendProcessorState();
        }

        private void sendProcessorState() {
            try { 
                ReplayStatus.Builder rsb=ReplayStatus.newBuilder().setState(yproc.getReplayState());
                yclient.sendData(dataAddress, ProtoDataType.STATE_CHANGE, rsb.build());
            } catch (HornetQException e) {
                log.warn("Got exception when signaling state change", e);
                quit();
            }
        }

        @Override
        public void update(int subscriptionId, List<ParameterValueWithId> params) {
            ParameterData.Builder pd = ParameterData.newBuilder();
            for(ParameterValueWithId pvwi:params) {
                pd.addParameter(pvwi.getParameterValue().toGpb(pvwi.getId()));
            }
            try {
                yclient.sendData(dataAddress, ProtoDataType.PARAMETER, pd.build());
            } catch (HornetQException e) {
                log.warn("Got exception when sending data to client", e);
                quit();
            }
            
        }
    }
}
