package org.yamcs.pus;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.yamcs.pus.Pus11Schedule.RequestId;
import org.yamcs.pus.Pus11SummaryReport.Collector;
import org.yamcs.pus.Pus11SummaryReport.Entry;

public class Pus11SummaryReportTest {
    static Entry entry(int seq) {
        return new Entry(new RequestId(9, 100, seq), 0L, 1L, 1000L);
    }

    @Test
    public void testStandalonePacketIsAReport() {
        var c = new Collector();
        var report = c.add(Pus11SummaryReport.STANDALONE, List.of(entry(1), entry(2)));
        assertEquals(List.of(entry(1), entry(2)), report);
    }

    @Test
    public void testMultiPacketReportCompletesOnLast() {
        var c = new Collector();
        assertNull(c.add(Pus11SummaryReport.FIRST, List.of(entry(1))));
        assertNull(c.add(Pus11SummaryReport.CONTINUATION, List.of(entry(2))));
        assertEquals(List.of(entry(1), entry(2), entry(3)), c.add(Pus11SummaryReport.LAST, List.of(entry(3))));
    }

    @Test
    public void testFirstDiscardsAnUnfinishedReport() {
        var c = new Collector();
        assertNull(c.add(Pus11SummaryReport.FIRST, List.of(entry(1))));
        assertNull(c.add(Pus11SummaryReport.FIRST, List.of(entry(2))));
        assertEquals(List.of(entry(2)), c.add(Pus11SummaryReport.LAST, List.of()));
    }

    @Test
    public void testOrphanContinuationIsDropped() {
        var c = new Collector();
        assertNull(c.add(Pus11SummaryReport.CONTINUATION, List.of(entry(1))));
        assertNull(c.add(Pus11SummaryReport.LAST, List.of(entry(2))));
        // the orphan does not leak into the next report
        assertEquals(List.of(entry(3)), c.add(Pus11SummaryReport.STANDALONE, List.of(entry(3))));
    }

    @Test
    public void testResetDropsTheOpenReport() {
        var c = new Collector();
        assertNull(c.add(Pus11SummaryReport.FIRST, List.of(entry(1))));
        c.reset();
        // the rest of the dropped report is now orphaned
        assertNull(c.add(Pus11SummaryReport.CONTINUATION, List.of(entry(2))));
        assertNull(c.add(Pus11SummaryReport.LAST, List.of(entry(3))));
    }

    @Test
    public void testTriggerFiresOnFirstPacketAfterGap() {
        var t = new TmGapReconcileTrigger(600_000);
        assertTrue(t.onPacket(0), "first packet ever starts a pass");
        assertFalse(t.onPacket(1000));
        assertFalse(t.onPacket(600_000), "a gap equal to the limit is still in the pass");
        assertTrue(t.onPacket(1_200_001), "a gap longer than the limit starts a new pass");
        assertFalse(t.onPacket(1_200_500));
    }
}
