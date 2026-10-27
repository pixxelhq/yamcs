package org.yamcs.pus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.yamcs.AbstractYamcsService;
import org.yamcs.ConfigurationException;
import org.yamcs.InitException;
import org.yamcs.Spec;
import org.yamcs.Spec.OptionType;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.cmdhistory.CommandHistoryPublisher;
import org.yamcs.cmdhistory.CommandHistoryPublisher.AckStatus;
import org.yamcs.cmdhistory.StreamCommandHistoryPublisher;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.management.LinkManager;
import org.yamcs.protobuf.Commanding.CommandId;
import org.yamcs.pus.Pus11Schedule.EntryState;
import org.yamcs.pus.Pus11Schedule.ScheduleEntry;
import org.yamcs.tctm.AbstractTcDataLink;
import org.yamcs.tctm.AbstractTcTmParamLink;
import org.yamcs.tctm.CommandPostprocessor;
import org.yamcs.tctm.Link;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.StreamSubscriber;
import org.yamcs.yarch.Tuple;
import org.yamcs.yarch.YarchDatabase;

/**
 * Keeps a ground model ({@link Pus11Schedule}) of the on-board PUS 11 time-based schedule, from the commands scheduled
 * with {@code pus11ScheduleAt}.
 * <p>
 * An entry is added as PENDING when the TC(11,4) wrapper appears in the command history ({@code pus11Binary}), moves
 * to SCHEDULED when the wrapper's CommandComplete is OK, is dropped on NOK, and is marked PRESUMED_GONE by a periodic
 * sweep once its release time plus {@code releaseGrace} has passed.
 * <p>
 * The release status of each scheduled command is published to the command history under the {@value #RELEASE_KEY}
 * acknowledgment: SCHEDULED once queued on board, TIMEOUT once presumed gone.
 * <p>
 * The TC(11,4) layout is taken from the {@link PusCommandPostprocessor} of the TC link given by {@code tcLink}, or of
 * the only TC link able to schedule commands.
 *
 * <pre>
 * services:
 *   - class: org.yamcs.pus.Pus11ScheduleTracker
 *     args:
 *       releaseGrace: 300  # seconds after the release time before an unconfirmed entry is presumed gone
 *       sweepInterval: 10  # seconds
 *       tcLink: tc_realtime  # optional
 * </pre>
 */
public class Pus11ScheduleTracker extends AbstractYamcsService implements StreamSubscriber {
    /** command history acknowledgment carrying the release status, distinct from CommandComplete (insert accepted) */
    public static final String RELEASE_KEY = "pus11-release";
    static final String PUS11_BINARY_KEY = "pus11Binary";
    static final String COMMAND_COMPLETE_STATUS = CommandHistoryPublisher.CommandComplete_KEY
            + CommandHistoryPublisher.SUFFIX_STATUS;

    Pus11WrapperLayout layout;
    LongSupplier clock;
    long releaseGrace;
    long sweepInterval;

    Pus11Schedule schedule;
    Pus11WrapperParser parser;
    Stream cmdhistStream;
    CommandHistoryPublisher cmdHistoryPublisher;
    ScheduledExecutorService timer;

    public Pus11ScheduleTracker() {
    }

    /** for tests: no link lookup, and a fixed clock */
    Pus11ScheduleTracker(Pus11WrapperLayout layout, LongSupplier clock) {
        this.layout = layout;
        this.clock = clock;
    }

    @Override
    public Spec getSpec() {
        Spec spec = new Spec();
        spec.addOption("releaseGrace", OptionType.INTEGER).withDefault(300);
        spec.addOption("sweepInterval", OptionType.INTEGER).withDefault(10);
        spec.addOption("tcLink", OptionType.STRING);
        return spec;
    }

    @Override
    public void init(String yamcsInstance, String serviceName, YConfiguration config) throws InitException {
        super.init(yamcsInstance, serviceName, config);
        releaseGrace = config.getInt("releaseGrace", 300) * 1000L;
        sweepInterval = config.getInt("sweepInterval", 10) * 1000L;
        if (layout == null) {
            var linkManager = YamcsServer.getServer().getInstance(yamcsInstance).getLinkManager();
            layout = findLayout(linkManager, config.getString("tcLink", null));
        }
        if (clock == null) {
            var timeService = YamcsServer.getTimeService(yamcsInstance);
            clock = timeService::getMissionTime;
        }
        schedule = new Pus11Schedule();
        parser = new Pus11WrapperParser(layout);
    }

    private static Pus11WrapperLayout findLayout(LinkManager linkManager, String tcLink) {
        List<String> names = new ArrayList<>();
        Pus11WrapperLayout found = null;
        for (Link link : linkManager.getLinks()) {
            if (tcLink != null && !tcLink.equals(link.getName())) {
                continue;
            }
            CommandPostprocessor cpp = null;
            if (link instanceof AbstractTcDataLink l) {
                cpp = l.getCommandPostprocessor();
            } else if (link instanceof AbstractTcTmParamLink l) {
                cpp = l.getCommandPostprocessor();
            }
            if (cpp instanceof PusCommandPostprocessor pp && pp.isPus11Enabled()) {
                names.add(link.getName());
                if (found == null) {
                    found = pp.getPus11WrapperLayout();
                }
            }
        }
        if (found == null) {
            throw new ConfigurationException(tcLink == null
                    ? "No TC link with a PusCommandPostprocessor configured for PUS 11 (pus11Apid)"
                    : "Link " + tcLink + " has no PusCommandPostprocessor configured for PUS 11 (pus11Apid)");
        }
        if (names.size() > 1) {
            throw new ConfigurationException("Several TC links can schedule PUS 11 commands " + names
                    + "; set tcLink to the one whose TC(11,4) layout to use (all must be the same)");
        }
        return found;
    }

    @Override
    protected void doStart() {
        cmdhistStream = YarchDatabase.getInstance(yamcsInstance)
                .getStream(StreamCommandHistoryPublisher.REALTIME_CMDHIST_STREAM_NAME);
        if (cmdhistStream == null) {
            notifyFailed(new ConfigurationException(
                    "Cannot find stream " + StreamCommandHistoryPublisher.REALTIME_CMDHIST_STREAM_NAME));
            return;
        }
        cmdHistoryPublisher = new StreamCommandHistoryPublisher(yamcsInstance);
        cmdhistStream.addSubscriber(this);
        timer = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "Pus11ScheduleTracker-sweep"));
        timer.scheduleAtFixedRate(this::sweep, sweepInterval, sweepInterval, TimeUnit.MILLISECONDS);
        notifyStarted();
    }

    @Override
    protected void doStop() {
        cmdhistStream.removeSubscriber(this);
        timer.shutdownNow();
        notifyStopped();
    }

    @Override
    public void onTuple(Stream s, Tuple tuple) {
        if (tuple.hasColumn(PUS11_BINARY_KEY)) {
            onInsert(PreparedCommand.getCommandId(tuple), (byte[]) tuple.getColumn(PUS11_BINARY_KEY));
        }
        if (tuple.hasColumn(COMMAND_COMPLETE_STATUS)) {
            onCommandComplete(PreparedCommand.getCommandId(tuple), (String) tuple.getColumn(COMMAND_COMPLETE_STATUS));
        }
    }

    private void onInsert(CommandId cmdId, byte[] binary) {
        try {
            for (var e : schedule.insert(cmdId, parser.parse(binary))) {
                log.info("{} {}: PENDING, release at {}", e.getCommandName(), e.getInner(), releaseTimeString(e));
            }
        } catch (IllegalArgumentException e) {
            log.warn("{}: cannot parse {}: {}", cmdId.getCommandName(), PUS11_BINARY_KEY, e.getMessage());
        }
    }

    private void onCommandComplete(CommandId cmdId, String status) {
        boolean ok = AckStatus.OK.name().equals(status);
        if (!ok && !AckStatus.NOK.name().equals(status) && !AckStatus.TIMEOUT.name().equals(status)
                && !AckStatus.CANCELLED.name().equals(status)) {
            return;
        }
        for (var e : schedule.commandComplete(cmdId, ok)) {
            log.info("{} {}: {}", e.getCommandName(), e.getInner(), e.getState());
            if (e.getState() == EntryState.SCHEDULED) {
                cmdHistoryPublisher.publishAck(cmdId, RELEASE_KEY, clock.getAsLong(), AckStatus.SCHEDULED,
                        "Release at " + releaseTimeString(e));
            }
        }
    }

    /** marks PRESUMED_GONE the entries past release + grace */
    void sweep() {
        try {
            for (var e : schedule.sweep(clock.getAsLong(), releaseGrace)) {
                log.info("{} {}: PRESUMED_GONE, no release ACK {} s after {}", e.getCommandName(), e.getInner(),
                        releaseGrace / 1000, releaseTimeString(e));
                cmdHistoryPublisher.publishAck(e.getCmdId(), RELEASE_KEY, clock.getAsLong(), AckStatus.TIMEOUT,
                        "No release ACK within " + releaseGrace / 1000 + " s of the release time; presumed gone");
            }
        } catch (Exception e) {
            // keep the timer alive
            log.error("Error while sweeping the PUS 11 schedule", e);
        }
    }

    private static String releaseTimeString(ScheduleEntry e) {
        long t = e.getReleaseTime();
        return t == TimeEncoding.INVALID_INSTANT ? "unknown time (no time correlation at insert)"
                : TimeEncoding.toString(t);
    }

    public Pus11Schedule getSchedule() {
        return schedule;
    }
}
