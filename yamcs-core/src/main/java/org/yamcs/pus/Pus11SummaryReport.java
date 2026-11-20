package org.yamcs.pus;

import java.util.ArrayList;
import java.util.List;

import org.yamcs.pus.Pus11Schedule.RequestId;

/**
 * A TM(11,13) time-based schedule summary report: the on-board schedule, as a complete snapshot (plan §1.6).
 * <p>
 * A report can span several packets. The CCSDS sequence flags tell them apart: a report is complete at its
 * {@link #LAST} or {@link #STANDALONE} packet; {@link #FIRST} starts a new report and discards an unfinished one.
 * Packets are assumed to arrive in order. A packet lost in the middle of a report is not detected here, and the
 * reconcile then sees a partial snapshot; this is an open question for the spacecraft software (plan §5).
 */
public final class Pus11SummaryReport {
    /** CCSDS sequence flags of a packet: continuation of a report */
    public static final int CONTINUATION = 0;
    /** CCSDS sequence flags of a packet: first packet of a report */
    public static final int FIRST = 1;
    /** CCSDS sequence flags of a packet: last packet of a report */
    public static final int LAST = 2;
    /** CCSDS sequence flags of a packet: the whole report in one packet */
    public static final int STANDALONE = 3;

    /**
     * One activity of the on-board schedule.
     *
     * @param subScheduleId
     *            null if the layout has no sub-schedule id field
     * @param groupId
     *            null if the layout has no group id field
     * @param releaseTime
     *            release time as ground time, {@link org.yamcs.utils.TimeEncoding#INVALID_INSTANT} if it cannot be
     *            computed
     */
    public record Entry(RequestId inner, Long subScheduleId, Long groupId, long releaseTime) {
    }

    /**
     * Accumulates the entries of the packets of one report.
     */
    public static final class Collector {
        private final List<Entry> pending = new ArrayList<>();
        private boolean open;

        /**
         * @param grouping
         *            the CCSDS sequence flags of the packet
         * @param packetEntries
         *            the entries of the packet
         * @return the complete report, or null if the report continues in the next packets
         */
        public List<Entry> add(int grouping, List<Entry> packetEntries) {
            if (grouping == FIRST || grouping == STANDALONE || !open) {
                // a FIRST always starts over; a stray continuation without its FIRST cannot be completed
                pending.clear();
                open = grouping != STANDALONE;
                if (grouping == CONTINUATION || grouping == LAST) {
                    // the first packet of the report is missing: drop the orphan packet
                    open = false;
                    return null;
                }
            }
            pending.addAll(packetEntries);
            if (grouping == LAST || grouping == STANDALONE) {
                open = false;
                List<Entry> report = new ArrayList<>(pending);
                pending.clear();
                return report;
            }
            return null;
        }

        /** drops an unfinished report, e.g. after a parse error */
        public void reset() {
            pending.clear();
            open = false;
        }
    }

    private Pus11SummaryReport() {
    }
}
