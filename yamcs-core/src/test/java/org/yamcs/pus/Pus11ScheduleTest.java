package org.yamcs.pus;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yamcs.events.EventProducerFactory;
import org.yamcs.protobuf.Commanding.CommandId;
import org.yamcs.pus.Pus11Schedule.EntryState;
import org.yamcs.pus.Pus11Schedule.RequestId;
import org.yamcs.pus.Pus11WrapperParser.Activity;
import org.yamcs.pus.Pus11WrapperParser.InsertRequest;
import org.yamcs.utils.ByteArrayUtils;
import org.yamcs.utils.TimeEncoding;

/**
 * Ground model of the on-board schedule (plan §6 step 1): PENDING on insert, SCHEDULED or dropped on CommandComplete,
 * PRESUMED_GONE after release + grace.
 */
public class Pus11ScheduleTest {
    static final long GRACE = 60_000;
    static long t0;

    Pus11Schedule schedule;

    @BeforeAll
    static void setUpTime() {
        TimeEncoding.setUp();
        EventProducerFactory.setMockup(false);
        t0 = TimeEncoding.parse("2026-10-22T12:00:00Z");
    }

    @BeforeEach
    void setUp() {
        schedule = new Pus11Schedule();
    }

    @Test
    public void testInsertCreatesPendingEntry() {
        var cmdId = cmdId("/YSS/SIMULATOR/SWITCH_VOLTAGE_ON", 1);

        var added = schedule.insert(cmdId, insert(42, 3L, act(7L, t0, 100, 11)));

        assertEquals(1, added.size());
        var e = added.getFirst();
        assertEquals(EntryState.PENDING, e.getState());
        assertEquals(new RequestId(9, 100, 11), e.getInner());
        assertEquals(new RequestId(9, 5, 42), e.getWrapper());
        assertEquals(3L, e.getSubScheduleId());
        assertEquals(7L, e.getGroupId());
        assertEquals(t0, e.getReleaseTime());
        assertEquals("/YSS/SIMULATOR/SWITCH_VOLTAGE_ON", e.getCommandName());
        assertEquals(cmdId, e.getCmdId());
        assertTrue(e.expectsReleaseAck());
        assertSame(e, schedule.get(new RequestId(9, 100, 11)));
        assertEquals(List.of(e), schedule.getEntries());
    }

    @Test
    public void testNullIdsWhenTheLinkHasNoSuchFields() {
        var e = schedule.insert(cmdId("C", 1), insert(42, null, act(null, t0, 100, 11))).getFirst();
        assertNull(e.getSubScheduleId());
        assertNull(e.getGroupId());
    }

    @Test
    public void testCommandCompleteOkSchedules() {
        var cmdId = cmdId("C", 1);
        schedule.insert(cmdId, insert(42, 0L, act(0L, t0, 100, 11)));

        var changed = schedule.commandComplete(cmdId, true);

        assertEquals(1, changed.size());
        assertEquals(EntryState.SCHEDULED, changed.getFirst().getState());
    }

    @Test
    public void testCommandCompleteNokDrops() {
        var cmdId = cmdId("C", 1);
        schedule.insert(cmdId, insert(42, 0L, act(0L, t0, 100, 11)));

        var changed = schedule.commandComplete(cmdId, false);

        assertEquals(1, changed.size());
        assertEquals(EntryState.DELETED, changed.getFirst().getState());
        assertTrue(schedule.getEntries().isEmpty());
        assertNull(schedule.get(new RequestId(9, 100, 11)));
    }

    @Test
    public void testCommandCompleteForUnknownCommandIsIgnored() {
        schedule.insert(cmdId("C", 1), insert(42, 0L, act(0L, t0, 100, 11)));

        assertTrue(schedule.commandComplete(cmdId("C", 2), true).isEmpty());
        assertEquals(EntryState.PENDING, schedule.getEntries().getFirst().getState());
    }

    @Test
    public void testManualInsertWithSeveralActivities() {
        var cmdId = cmdId("/PUS11/INSERT_ACTIVITIES", 1);
        // given out of release order: entries come back sorted by release time
        schedule.insert(cmdId, insert(42, 0L, act(1L, t0 + 5000, 100, 11), act(2L, t0, 101, 12)));

        var entries = schedule.getEntries();
        assertEquals(2, entries.size());
        assertEquals(new RequestId(9, 101, 12), entries.get(0).getInner());
        assertEquals(new RequestId(9, 100, 11), entries.get(1).getInner());
        assertEquals("/PUS11/INSERT_ACTIVITIES", entries.get(0).getCommandName());

        assertEquals(2, schedule.commandComplete(cmdId, true).size());
        assertTrue(entries.stream().allMatch(e -> e.getState() == EntryState.SCHEDULED));
    }

    @Test
    public void testSweepKeepsEntriesWithinGrace() {
        var cmdId = cmdId("C", 1);
        schedule.insert(cmdId, insert(42, 0L, act(0L, t0, 100, 11)));
        schedule.commandComplete(cmdId, true);

        assertTrue(schedule.sweep(t0 + GRACE, GRACE).isEmpty());
        assertEquals(EntryState.SCHEDULED, schedule.getEntries().getFirst().getState());
    }

    @Test
    public void testSweepMarksPresumedGoneOnceAndNeverEvicts() {
        var cmdId = cmdId("C", 1);
        schedule.insert(cmdId, insert(42, 0L, act(0L, t0, 100, 11)));
        schedule.commandComplete(cmdId, true);

        var gone = schedule.sweep(t0 + GRACE + 1, GRACE);

        assertEquals(1, gone.size());
        assertEquals(EntryState.PRESUMED_GONE, gone.getFirst().getState());
        assertTrue(schedule.sweep(t0 + GRACE + 2, GRACE).isEmpty(), "reported only once");
        assertEquals(1, schedule.getEntries().size(), "never evicted");
        assertNotNull(schedule.get(new RequestId(9, 100, 11)));
    }

    @Test
    public void testSweepAlsoCoversPendingEntries() {
        // insert never confirmed (e.g. CommandComplete still outstanding) and the release time is long gone
        schedule.insert(cmdId("C", 1), insert(42, 0L, act(0L, t0, 100, 11)));

        assertEquals(1, schedule.sweep(t0 + GRACE + 1, GRACE).size());
    }

    @Test
    public void testSweepSkipsEntriesWithoutReleaseTime() {
        // OBT release time, but the time correlation had no coefficients at insert: the ground time is unknown
        schedule.insert(cmdId("C", 1), insert(42, 0L, act(0L, TimeEncoding.INVALID_INSTANT, 100, 11)));

        assertTrue(schedule.sweep(Long.MAX_VALUE / 2, GRACE).isEmpty());
        assertEquals(EntryState.PENDING, schedule.getEntries().getFirst().getState());
    }

    @Test
    public void testOkAfterPresumedGoneKeepsPresumedGone() {
        var cmdId = cmdId("C", 1);
        schedule.insert(cmdId, insert(42, 0L, act(0L, t0, 100, 11)));
        schedule.sweep(t0 + GRACE + 1, GRACE);

        assertTrue(schedule.commandComplete(cmdId, true).isEmpty());
        assertEquals(EntryState.PRESUMED_GONE, schedule.getEntries().getFirst().getState());
    }

    @Test
    public void testDuplicateInnerRequestIdIsFlagged() {
        // e.g. Yamcs restarted and the seq counter started again at 0 (plan §4.1)
        var first = schedule.insert(cmdId("C", 1), insert(42, 0L, act(0L, t0, 100, 11))).getFirst();
        var second = schedule.insert(cmdId("C", 2), insert(43, 0L, act(0L, t0 + 1000, 100, 11))).getFirst();

        assertFalse(first.isDuplicate());
        assertTrue(second.isDuplicate());
        assertSame(second, schedule.get(new RequestId(9, 100, 11)), "lookup returns the most recent insert");
        assertEquals(2, schedule.getEntries().size());
    }

    @Test
    public void testSameRequestIdAfterTheFirstIsGoneIsNotADuplicate() {
        schedule.insert(cmdId("C", 1), insert(42, 0L, act(0L, t0, 100, 11)));
        schedule.commandComplete(cmdId("C", 1), false);

        var e = schedule.insert(cmdId("C", 2), insert(43, 0L, act(0L, t0, 100, 11))).getFirst();
        assertFalse(e.isDuplicate());
    }

    // ---- enable status (plan §6 step 2) ----

    @Test
    public void testDisabledSubScheduleFlagsEntry() {
        var cmdId = cmdId("C", 1);
        var e = schedule.insert(cmdId, insert(42, 3L, act(7L, t0, 100, 11))).getFirst();
        schedule.commandComplete(cmdId, true);
        assertNull(e.getDropReason());

        assertEquals(List.of(e), schedule.subScheduleStatus(Map.of(3L, false)));
        assertEquals("sub-schedule 3 is disabled", e.getDropReason());
        assertEquals(EntryState.SCHEDULED, e.getState(), "flagged, but still on board until its release");

        assertEquals(List.of(), schedule.subScheduleStatus(Map.of(3L, false)), "no change, nothing returned");

        assertEquals(List.of(e), schedule.subScheduleStatus(Map.of(3L, true)));
        assertNull(e.getDropReason());
    }

    @Test
    public void testDisabledGroupFlagsEntry() {
        var e = schedule.insert(cmdId("C", 1), insert(42, 3L, act(7L, t0, 100, 11))).getFirst();

        assertEquals(List.of(e), schedule.groupStatus(Map.of(7L, false, 8L, true)));
        assertEquals("group 7 is disabled", e.getDropReason());
    }

    @Test
    public void testSubScheduleReasonWinsOverGroup() {
        var e = schedule.insert(cmdId("C", 1), insert(42, 3L, act(7L, t0, 100, 11))).getFirst();
        schedule.groupStatus(Map.of(7L, false));
        schedule.subScheduleStatus(Map.of(3L, false));
        assertEquals("sub-schedule 3 is disabled", e.getDropReason());

        schedule.subScheduleStatus(Map.of(3L, true));
        assertEquals("group 7 is disabled", e.getDropReason());
    }

    @Test
    public void testIdNotInReportIsNotFlagged() {
        var e = schedule.insert(cmdId("C", 1), insert(42, 3L, act(7L, t0, 100, 11))).getFirst();

        assertEquals(List.of(), schedule.subScheduleStatus(Map.of(4L, false)));
        assertEquals(List.of(), schedule.groupStatus(Map.of(8L, false)));
        assertNull(e.getDropReason());
    }

    @Test
    public void testEntryWithoutIdsIsNeverFlagged() {
        var e = schedule.insert(cmdId("C", 1), insert(42, null, act(null, t0, 100, 11))).getFirst();
        schedule.subScheduleStatus(Map.of(0L, false));
        schedule.groupStatus(Map.of(0L, false));
        assertNull(e.getDropReason());
    }

    @Test
    public void testInsertAfterReportIsFlaggedAtOnce() {
        schedule.groupStatus(Map.of(7L, false));

        var e = schedule.insert(cmdId("C", 1), insert(42, 3L, act(7L, t0, 100, 11))).getFirst();

        assertEquals("group 7 is disabled", e.getDropReason());
    }

    @Test
    public void testEntryNoLongerLiveKeepsItsReason() {
        var cmdId = cmdId("C", 1);
        var e = schedule.insert(cmdId, insert(42, 3L, act(7L, t0, 100, 11))).getFirst();
        schedule.commandComplete(cmdId, true);
        schedule.groupStatus(Map.of(7L, false));
        schedule.sweep(t0 + GRACE + 1, GRACE);
        assertEquals(EntryState.PRESUMED_GONE, e.getState());

        assertEquals(List.of(), schedule.groupStatus(Map.of(7L, true)));
        assertEquals("group 7 is disabled", e.getDropReason(), "the status at its release time is kept");
    }

    // ---- helpers ----

    static CommandId cmdId(String name, int seq) {
        return CommandId.newBuilder().setGenerationTime(t0 - 3600_000).setOrigin("test").setSequenceNumber(seq)
                .setCommandName(name).build();
    }

    /** TC(11,4) from APID 5, source id 9 */
    static InsertRequest insert(int seqCount, Long subScheduleId, Activity... activities) {
        return new InsertRequest(5, seqCount, 9, subScheduleId, List.of(activities));
    }

    static Activity act(Long groupId, long releaseTime, int apid, int seqCount) {
        return new Activity(groupId, releaseTime, innerTc(apid, seqCount));
    }

    static byte[] innerTc(int apid, int seqCount) {
        byte[] b = new byte[16];
        b[0] = (byte) (0x18 | (apid >> 8));
        b[1] = (byte) apid;
        ByteArrayUtils.encodeUnsignedShort(0xC000 | seqCount, b, 2);
        ByteArrayUtils.encodeUnsignedShort(b.length - 7, b, 4);
        return b;
    }
}
