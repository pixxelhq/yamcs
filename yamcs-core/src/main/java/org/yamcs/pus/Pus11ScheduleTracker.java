package org.yamcs.pus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.yamcs.AbstractYamcsService;
import org.yamcs.ConfigurationException;
import org.yamcs.InitException;
import org.yamcs.Processor;
import org.yamcs.Spec;
import org.yamcs.Spec.OptionType;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.cmdhistory.CommandHistoryPublisher;
import org.yamcs.cmdhistory.CommandHistoryPublisher.AckStatus;
import org.yamcs.cmdhistory.StreamCommandHistoryPublisher;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.management.LinkManager;
import org.yamcs.mdb.MdbFactory;
import org.yamcs.parameter.AggregateValue;
import org.yamcs.parameter.ArrayValue;
import org.yamcs.parameter.EnumeratedValue;
import org.yamcs.parameter.ParameterConsumer;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.parameter.Value;
import org.yamcs.protobuf.Commanding.CommandId;
import org.yamcs.pus.Pus11Schedule.EntryState;
import org.yamcs.pus.Pus11Schedule.ScheduleEntry;
import org.yamcs.tctm.AbstractTcDataLink;
import org.yamcs.tctm.AbstractTcTmParamLink;
import org.yamcs.tctm.CommandPostprocessor;
import org.yamcs.tctm.Link;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.xtce.Parameter;
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
 * acknowledgment: SCHEDULED once queued on board, DISABLED while its sub-schedule or group is reported disabled (it
 * will be dropped at release unless enabled before), TIMEOUT once presumed gone.
 * <p>
 * The enable status of the sub-schedules and of the groups is read from the TM(11,19) and TM(11,27) reports, decoded
 * by the processor into array parameters of aggregates with an id member and a status member. The status is enabled
 * if its value is "enabled" (ignoring case), or non zero for a numeric value.
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
 *       processor: realtime  # the processor decoding the status reports
 *       subScheduleStatus:  # TM(11,19)
 *         parameter: /PUS11/status_report
 *         idMember: schedule_id
 *         statusMember: schedule_status
 *       groupStatus:  # TM(11,27)
 *         parameter: /PUS11/group_status_report
 *         idMember: group_id
 *         statusMember: group_status
 * </pre>
 */
public class Pus11ScheduleTracker extends AbstractYamcsService implements StreamSubscriber, ParameterConsumer {
    /** command history acknowledgment carrying the release status, distinct from CommandComplete (insert accepted) */
    public static final String RELEASE_KEY = "pus11-release";
    static final String PUS11_BINARY_KEY = "pus11Binary";
    static final String COMMAND_COMPLETE_STATUS = CommandHistoryPublisher.CommandComplete_KEY
            + CommandHistoryPublisher.SUFFIX_STATUS;

    /** where to find the enable status in a decoded TM(11,19) or TM(11,27) */
    record StatusReport(String parameter, String idMember, String statusMember) {
    }

    static final StatusReport DEFAULT_SUBSCHEDULE_STATUS = new StatusReport("/PUS11/status_report", "schedule_id",
            "schedule_status");
    static final StatusReport DEFAULT_GROUP_STATUS = new StatusReport("/PUS11/group_status_report", "group_id",
            "group_status");

    Pus11WrapperLayout layout;
    LongSupplier clock;
    long releaseGrace;
    long sweepInterval;
    String processorName;
    StatusReport subScheduleStatus;
    StatusReport groupStatus;
    // false in the unit tests, which feed the status reports with updateItems
    boolean subscribeParameters = true;

    Pus11Schedule schedule;
    Pus11WrapperParser parser;
    Stream cmdhistStream;
    CommandHistoryPublisher cmdHistoryPublisher;
    ScheduledExecutorService timer;
    Processor processor;
    int subscriptionId = -1;

    public Pus11ScheduleTracker() {
    }

    /** for tests: no link lookup, and a fixed clock */
    Pus11ScheduleTracker(Pus11WrapperLayout layout, LongSupplier clock) {
        this.layout = layout;
        this.clock = clock;
        this.subscribeParameters = false;
    }

    @Override
    public Spec getSpec() {
        Spec spec = new Spec();
        spec.addOption("releaseGrace", OptionType.INTEGER).withDefault(300);
        spec.addOption("sweepInterval", OptionType.INTEGER).withDefault(10);
        spec.addOption("tcLink", OptionType.STRING);
        spec.addOption("processor", OptionType.STRING).withDefault("realtime");
        spec.addOption("subScheduleStatus", OptionType.MAP).withSpec(statusReportSpec(DEFAULT_SUBSCHEDULE_STATUS))
                .withApplySpecDefaults(true);
        spec.addOption("groupStatus", OptionType.MAP).withSpec(statusReportSpec(DEFAULT_GROUP_STATUS))
                .withApplySpecDefaults(true);
        return spec;
    }

    private static Spec statusReportSpec(StatusReport defaults) {
        Spec spec = new Spec();
        spec.addOption("parameter", OptionType.STRING).withDefault(defaults.parameter());
        spec.addOption("idMember", OptionType.STRING).withDefault(defaults.idMember());
        spec.addOption("statusMember", OptionType.STRING).withDefault(defaults.statusMember());
        return spec;
    }

    private static StatusReport statusReport(YConfiguration config, String key, StatusReport defaults) {
        if (!config.containsKey(key)) {
            return defaults;
        }
        var c = config.getConfig(key);
        return new StatusReport(c.getString("parameter", defaults.parameter()),
                c.getString("idMember", defaults.idMember()),
                c.getString("statusMember", defaults.statusMember()));
    }

    @Override
    public void init(String yamcsInstance, String serviceName, YConfiguration config) throws InitException {
        super.init(yamcsInstance, serviceName, config);
        releaseGrace = config.getInt("releaseGrace", 300) * 1000L;
        sweepInterval = config.getInt("sweepInterval", 10) * 1000L;
        processorName = config.getString("processor", "realtime");
        subScheduleStatus = statusReport(config, "subScheduleStatus", DEFAULT_SUBSCHEDULE_STATUS);
        groupStatus = statusReport(config, "groupStatus", DEFAULT_GROUP_STATUS);
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
        if (subscribeParameters) {
            processor = YamcsServer.getServer().getProcessor(yamcsInstance, processorName);
            if (processor == null) {
                notifyFailed(new ConfigurationException("Cannot find processor " + processorName));
                return;
            }
            subscribeStatusReports();
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "Pus11ScheduleTracker-sweep"));
        timer.scheduleAtFixedRate(this::sweep, sweepInterval, sweepInterval, TimeUnit.MILLISECONDS);
        notifyStarted();
    }

    private void subscribeStatusReports() {
        var mdb = MdbFactory.getInstance(yamcsInstance);
        List<Parameter> params = new ArrayList<>();
        for (var report : List.of(subScheduleStatus, groupStatus)) {
            Parameter p = mdb.getParameter(report.parameter());
            if (p == null) {
                log.warn("Parameter {} not found in the MDB; scheduled commands will not be flagged on its status",
                        report.parameter());
            } else {
                params.add(p);
            }
        }
        if (!params.isEmpty()) {
            subscriptionId = processor.getParameterRequestManager().addRequest(params, this);
        }
    }

    @Override
    protected void doStop() {
        cmdhistStream.removeSubscriber(this);
        if (subscriptionId >= 0) {
            processor.getParameterRequestManager().removeRequest(subscriptionId);
        }
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
                publishReleaseStatus(e);
            }
        }
    }

    /** receives the decoded TM(11,19) and TM(11,27) */
    @Override
    public void updateItems(int subscriptionId, List<ParameterValue> items) {
        for (var pv : items) {
            String name = pv.getParameterQualifiedName();
            if (name.equals(subScheduleStatus.parameter())) {
                onStatusReport(pv, subScheduleStatus, "sub-schedule");
            } else if (name.equals(groupStatus.parameter())) {
                onStatusReport(pv, groupStatus, "group");
            }
        }
    }

    private void onStatusReport(ParameterValue pv, StatusReport report, String what) {
        Map<Long, Boolean> status;
        try {
            status = parseStatusReport(pv.getEngValue(), report);
        } catch (RuntimeException e) {
            log.warn("Cannot read the {} status from {}: {}", what, report.parameter(), e.getMessage());
            return;
        }
        log.debug("{} status: {}", what, status);
        var changed = report == subScheduleStatus ? schedule.subScheduleStatus(status) : schedule.groupStatus(status);
        for (var e : changed) {
            if (e.getState() == EntryState.SCHEDULED) {
                publishReleaseStatus(e);
            }
        }
    }

    /**
     * @return the enable status by id
     * @throws RuntimeException
     *             if the value does not have the expected structure
     */
    static Map<Long, Boolean> parseStatusReport(Value v, StatusReport report) {
        if (!(v instanceof ArrayValue array)) {
            throw new IllegalArgumentException("expected an array, got " + v.getType());
        }
        Map<Long, Boolean> status = new HashMap<>();
        for (int i = 0; i < array.flatLength(); i++) {
            if (!(array.getElementValue(i) instanceof AggregateValue element)) {
                throw new IllegalArgumentException("expected an array of aggregates");
            }
            Value id = element.getMemberValue(report.idMember());
            Value enabled = element.getMemberValue(report.statusMember());
            if (id == null || enabled == null) {
                throw new IllegalArgumentException(
                        "no member " + (id == null ? report.idMember() : report.statusMember()));
            }
            status.put(id.toLong(), isEnabled(enabled));
        }
        return status;
    }

    private static boolean isEnabled(Value v) {
        switch (v.getType()) {
        case ENUMERATED, STRING -> {
            String s = v.getStringValue();
            if ("enabled".equalsIgnoreCase(s)) {
                return true;
            } else if ("disabled".equalsIgnoreCase(s)) {
                return false;
            } else if (v instanceof EnumeratedValue) {
                return v.getSint64Value() != 0;
            }
            throw new IllegalArgumentException("unexpected status '" + s + "'");
        }
        case BOOLEAN -> {
            return v.getBooleanValue();
        }
        default -> {
            return v.toLong() != 0;
        }
        }
    }

    /** SCHEDULED, or DISABLED if the entry will be dropped at release */
    private void publishReleaseStatus(ScheduleEntry e) {
        String reason = e.getDropReason();
        if (reason == null) {
            cmdHistoryPublisher.publishAck(e.getCmdId(), RELEASE_KEY, clock.getAsLong(), AckStatus.SCHEDULED,
                    "Release at " + releaseTimeString(e));
        } else {
            log.warn("{} {}: will be dropped at {}: {}", e.getCommandName(), e.getInner(), releaseTimeString(e),
                    reason);
            cmdHistoryPublisher.publishAck(e.getCmdId(), RELEASE_KEY, clock.getAsLong(), AckStatus.DISABLED,
                    "Will be dropped at " + releaseTimeString(e) + ": " + reason);
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
