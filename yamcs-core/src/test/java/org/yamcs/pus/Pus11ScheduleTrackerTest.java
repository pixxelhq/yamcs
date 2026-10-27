package org.yamcs.pus;

import static org.junit.jupiter.api.Assertions.*;
import static org.yamcs.cmdhistory.StreamCommandHistoryPublisher.REALTIME_CMDHIST_STREAM_NAME;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yamcs.StandardTupleDefinitions;
import org.yamcs.YConfiguration;
import org.yamcs.cmdhistory.CommandHistoryPublisher;
import org.yamcs.cmdhistory.CommandHistoryPublisher.AckStatus;
import org.yamcs.cmdhistory.StreamCommandHistoryPublisher;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.events.EventProducerFactory;
import org.yamcs.parameter.AggregateValue;
import org.yamcs.parameter.ArrayValue;
import org.yamcs.parameter.EnumeratedValue;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.parameter.Value;
import org.yamcs.protobuf.Commanding.CommandHistoryAttribute;
import org.yamcs.protobuf.Commanding.CommandId;
import org.yamcs.protobuf.Yamcs.Value.Type;
import org.yamcs.pus.Pus11Schedule.EntryState;
import org.yamcs.pus.Pus11Schedule.RequestId;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.utils.ValueHelper;
import org.yamcs.utils.ValueUtility;
import org.yamcs.xtce.Parameter;
import org.yamcs.xtce.util.AggregateMemberNames;
import org.yamcs.yarch.Tuple;
import org.yamcs.yarch.YarchTestCase;

/**
 * The tracker fed through the real {@code cmdhist_realtime} stream, with the TC(11,4) wrappers built by a real
 * {@link PusCommandPostprocessor} (plan §6 step 1).
 */
public class Pus11ScheduleTrackerTest extends YarchTestCase {
    static final long GRACE = 60_000;
    static final String RELEASE_STATUS = Pus11ScheduleTracker.RELEASE_KEY + CommandHistoryPublisher.SUFFIX_STATUS;
    static final String RELEASE_MESSAGE = Pus11ScheduleTracker.RELEASE_KEY + CommandHistoryPublisher.SUFFIX_MESSAGE;
    static long scheduleAt;

    List<Tuple> cmdhist;
    CommandHistoryPublisher publisher;
    PusCommandPostprocessor pp;
    Pus11ScheduleTracker tracker;
    long now;

    @BeforeAll
    static void setUpTime() {
        TimeEncoding.setUp();
        EventProducerFactory.setMockup(false);
        scheduleAt = TimeEncoding.parse("2026-10-22T12:00:00Z");
    }

    @BeforeEach
    void setUpTracker() throws Exception {
        ydb.execute("create stream " + REALTIME_CMDHIST_STREAM_NAME
                + StandardTupleDefinitions.TC.getStringDefinition());
        cmdhist = new ArrayList<>();
        ydb.getStream(REALTIME_CMDHIST_STREAM_NAME).addSubscriber((s, t) -> cmdhist.add(t));
        publisher = new StreamCommandHistoryPublisher(instance);

        pp = new PusCommandPostprocessor();
        pp.setCommandHistoryPublisher(publisher);
        pp.init(null, YConfiguration.wrap(Map.of(
                "timeEncoding", Map.of("implicitPfield", false, "pfield", 0x2f, "epoch", "NONE"),
                "errorDetection", Map.of("type", "CRC-16-CCIIT"),
                "pus11Apid", 5,
                "pus11", Map.of("sourceId", 9,
                        "subScheduleId", Map.of("bytes", 1, "default", 0),
                        "groupId", Map.of("bytes", 1, "default", 0)))),
                null);

        now = scheduleAt - 3600_000;
        tracker = new Pus11ScheduleTracker(pp.getPus11WrapperLayout(), () -> now);
        tracker.init(instance, "pus11Schedule", YConfiguration.wrap(Map.of("releaseGrace", (int) (GRACE / 1000))));
        tracker.startAsync().awaitRunning();
    }

    @AfterEach
    void tearDownTracker() {
        tracker.stopAsync().awaitTerminated();
    }

    @Test
    public void testScheduledCommandIsPendingThenScheduled() {
        var cmdId = send(1, true);

        var e = tracker.getSchedule().getEntries().getFirst();
        assertEquals(EntryState.PENDING, e.getState());
        // inner request ID as published by the postprocessor, source id from the wrapper
        int innerSeq = (Integer) lastValue("pus11-inner-seqcount");
        assertEquals(new RequestId(9, 100, innerSeq), e.getInner());
        assertEquals(new RequestId(9, 5, (Integer) lastValue("pus11CcsdsSeqCount")), e.getWrapper());
        assertEquals(0L, e.getSubScheduleId());
        assertEquals(0L, e.getGroupId());
        assertEquals(scheduleAt, e.getReleaseTime());
        assertEquals(cmdId, e.getCmdId());
        assertNull(lastValue(RELEASE_STATUS), "nothing published while the insert is unconfirmed");

        complete(cmdId, AckStatus.OK);

        assertEquals(EntryState.SCHEDULED, e.getState());
        assertEquals("SCHEDULED", lastValue(RELEASE_STATUS));
        assertTrue(((String) lastValue(RELEASE_MESSAGE)).contains(TimeEncoding.toString(scheduleAt)));
    }

    @Test
    public void testRejectedInsertIsDropped() {
        var cmdId = send(1, true);

        complete(cmdId, AckStatus.NOK);

        assertTrue(tracker.getSchedule().getEntries().isEmpty());
        assertNull(lastValue(RELEASE_STATUS));
    }

    @Test
    public void testSweepPublishesTimeout() {
        var cmdId = send(1, true);
        complete(cmdId, AckStatus.OK);

        now = scheduleAt + GRACE;
        tracker.sweep();
        assertEquals("SCHEDULED", lastValue(RELEASE_STATUS));

        now = scheduleAt + GRACE + 1;
        tracker.sweep();
        assertEquals(EntryState.PRESUMED_GONE, tracker.getSchedule().getEntries().getFirst().getState());
        assertEquals("TIMEOUT", lastValue(RELEASE_STATUS));
    }

    @Test
    public void testImmediateCommandIsIgnored() {
        var cmdId = send(1, false);
        complete(cmdId, AckStatus.OK);

        assertTrue(tracker.getSchedule().getEntries().isEmpty());
    }

    @Test
    public void testMalformedPus11BinaryIsIgnored() {
        var cmdId = cmdId(1);
        publisher.publish(cmdId, Pus11ScheduleTracker.PUS11_BINARY_KEY, new byte[] { 0x18, 1, 2 });
        complete(cmdId, AckStatus.OK);

        assertTrue(tracker.getSchedule().getEntries().isEmpty());
    }

    @Test
    public void testSeveralCommandsAreTrackedIndependently() {
        var a = send(1, true);
        var b = send(2, true);
        complete(b, AckStatus.OK);

        var entries = tracker.getSchedule().getEntries();
        assertEquals(2, entries.size());
        assertEquals(EntryState.PENDING, entries.stream().filter(e -> e.getCmdId().equals(a)).findFirst()
                .orElseThrow().getState());
        assertEquals(EntryState.SCHEDULED, entries.stream().filter(e -> e.getCmdId().equals(b)).findFirst()
                .orElseThrow().getState());
    }

    // ---- helpers ----

    /** emits the command to cmdhist and runs it through the postprocessor, as the realtime processor does */
    private CommandId send(int seq, boolean scheduled) {
        var cmdId = cmdId(seq);
        var pc = new PreparedCommand(cmdId);
        pc.setBinary(innerCommand(100));
        if (scheduled) {
            pc.addAttribute(CommandHistoryAttribute.newBuilder().setName("pus11ScheduleAt")
                    .setValue(ValueHelper.newTimestampValue(scheduleAt)).build());
        }
        publisher.addCommand(pc);
        assertNotNull(pp.process(pc));
        return cmdId;
    }

    // ---- enable status (plan §6 step 2) ----

    @Test
    public void testDisabledSubScheduleThenEnabled() {
        var cmdId = send(1, true);
        complete(cmdId, AckStatus.OK);
        assertEquals("SCHEDULED", lastValue(RELEASE_STATUS));

        tracker.updateItems(0, List.of(subScheduleReport(0, "disabled", 1, "enabled")));
        assertEquals("DISABLED", lastValue(RELEASE_STATUS));
        assertEquals("Will be dropped at " + TimeEncoding.toString(scheduleAt) + ": sub-schedule 0 is disabled",
                lastValue(RELEASE_MESSAGE));

        tracker.updateItems(0, List.of(subScheduleReport(0, "enabled")));
        assertEquals("SCHEDULED", lastValue(RELEASE_STATUS));
        assertTrue(((String) lastValue(RELEASE_MESSAGE)).startsWith("Release at "));
    }

    @Test
    public void testPendingInsertIntoDisabledGroupIsDisabledOnComplete() {
        var cmdId = send(1, true);
        tracker.updateItems(0, List.of(groupReport(0, "disabled")));
        assertNull(lastValue(RELEASE_STATUS), "nothing published while the insert is unconfirmed");

        complete(cmdId, AckStatus.OK);

        assertEquals("DISABLED", lastValue(RELEASE_STATUS));
        assertTrue(((String) lastValue(RELEASE_MESSAGE)).endsWith(": group 0 is disabled"));
    }

    @Test
    public void testUnchangedStatusPublishesNothing() {
        var cmdId = send(1, true);
        complete(cmdId, AckStatus.OK);
        int n = cmdhist.size();

        tracker.updateItems(0, List.of(groupReport(0, "enabled")));

        assertEquals(n, cmdhist.size());
    }

    @Test
    public void testOtherParametersAreIgnored() {
        var cmdId = send(1, true);
        complete(cmdId, AckStatus.OK);
        var p = new Parameter("other");
        p.setQualifiedName("/PUS11/other");
        var pv = new ParameterValue(p);
        pv.setEngValue(ValueUtility.getUint32Value(0));

        tracker.updateItems(0, List.of(pv));

        assertEquals("SCHEDULED", lastValue(RELEASE_STATUS));
    }

    @Test
    public void testParseNumericAndBooleanStatus() {
        var report = Pus11ScheduleTracker.DEFAULT_GROUP_STATUS;
        var array = statusArray(report, new Value[] { ValueUtility.getUint32Value(0), ValueUtility.getUint32Value(1),
                ValueUtility.getBooleanValue(false) });
        assertEquals(Map.of(0L, false, 1L, true, 2L, false), Pus11ScheduleTracker.parseStatusReport(array, report));
    }

    @Test
    public void testParseRejectsWrongStructure() {
        var report = Pus11ScheduleTracker.DEFAULT_GROUP_STATUS;
        assertThrows(IllegalArgumentException.class,
                () -> Pus11ScheduleTracker.parseStatusReport(ValueUtility.getUint32Value(1), report));
        var wrongMembers = statusArray(Pus11ScheduleTracker.DEFAULT_SUBSCHEDULE_STATUS,
                new Value[] { new EnumeratedValue(1, "enabled") });
        assertThrows(IllegalArgumentException.class,
                () -> Pus11ScheduleTracker.parseStatusReport(wrongMembers, report));
        var unknownLabel = statusArray(report, new Value[] { ValueUtility.getStringValue("paused") });
        assertThrows(IllegalArgumentException.class,
                () -> Pus11ScheduleTracker.parseStatusReport(unknownLabel, report));
    }

    /** decoded TM(11,19): id, label, id, label... */
    private static ParameterValue subScheduleReport(Object... idsAndLabels) {
        return statusReport(Pus11ScheduleTracker.DEFAULT_SUBSCHEDULE_STATUS, idsAndLabels);
    }

    /** decoded TM(11,27): id, label, id, label... */
    private static ParameterValue groupReport(Object... idsAndLabels) {
        return statusReport(Pus11ScheduleTracker.DEFAULT_GROUP_STATUS, idsAndLabels);
    }

    private static ParameterValue statusReport(Pus11ScheduleTracker.StatusReport report, Object... idsAndLabels) {
        var status = new Value[idsAndLabels.length / 2];
        var ids = new int[status.length];
        for (int i = 0; i < status.length; i++) {
            ids[i] = (Integer) idsAndLabels[2 * i];
            String label = (String) idsAndLabels[2 * i + 1];
            status[i] = new EnumeratedValue(label.equals("enabled") ? 1 : 0, label);
        }
        String name = report.parameter();
        var p = new Parameter(name.substring(name.lastIndexOf('/') + 1));
        p.setQualifiedName(name);
        var pv = new ParameterValue(p);
        pv.setEngValue(statusArray(report, status, ids));
        return pv;
    }

    /** ids 0, 1, 2... */
    private static ArrayValue statusArray(Pus11ScheduleTracker.StatusReport report, Value[] status) {
        var ids = new int[status.length];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = i;
        }
        return statusArray(report, status, ids);
    }

    private static ArrayValue statusArray(Pus11ScheduleTracker.StatusReport report, Value[] status, int[] ids) {
        var names = AggregateMemberNames.get(new String[] { report.idMember(), report.statusMember() });
        var array = new ArrayValue(new int[] { status.length }, Type.AGGREGATE);
        for (int i = 0; i < status.length; i++) {
            var element = new AggregateValue(names);
            element.setMemberValue(report.idMember(), ValueUtility.getUint32Value(ids[i]));
            element.setMemberValue(report.statusMember(), status[i]);
            array.setElementValue(i, element);
        }
        return array;
    }

    private void complete(CommandId cmdId, AckStatus status) {
        publisher.publishAck(cmdId, CommandHistoryPublisher.CommandComplete_KEY, now, status, null);
    }

    private static CommandId cmdId(int seq) {
        return CommandId.newBuilder().setGenerationTime(scheduleAt - 7200_000).setOrigin("test")
                .setSequenceNumber(seq).setCommandName("/YSS/SIMULATOR/SWITCH_VOLTAGE_ON").build();
    }

    /** most recent value of a cmdhist key, across all commands */
    private Object lastValue(String key) {
        for (int i = cmdhist.size() - 1; i >= 0; i--) {
            if (cmdhist.get(i).hasColumn(key)) {
                return cmdhist.get(i).getColumn(key);
            }
        }
        return null;
    }

    private static byte[] innerCommand(int apid) {
        byte[] b = new byte[16];
        b[0] = (byte) (0x18 | (apid >> 8));
        b[1] = (byte) apid;
        b[2] = (byte) 0xc0;
        return b;
    }
}
