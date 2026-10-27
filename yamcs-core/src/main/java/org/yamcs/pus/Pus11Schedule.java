package org.yamcs.pus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.yamcs.logging.Log;
import org.yamcs.protobuf.Commanding.CommandId;
import org.yamcs.pus.Pus11WrapperParser.Activity;
import org.yamcs.pus.Pus11WrapperParser.InsertRequest;
import org.yamcs.utils.TimeEncoding;

/**
 * Ground model of the on-board PUS 11 time-based schedule: what the ground believes is currently queued on board.
 * <p>
 * Release times are kept as ground time, converted once when the TC(11,4) is parsed. Time drift and re-correlation
 * are not followed.
 * <p>
 * Thread safe.
 */
public class Pus11Schedule {
    static final Log log = new Log(Pus11Schedule.class);

    /** PUS request ID: the source id of the request and the CCSDS packet ID of the TC */
    public record RequestId(int sourceId, int apid, int seqCount) {
    }

    public enum EntryState {
        /** TC(11,4) sent, CommandComplete not yet OK */
        PENDING,
        /** CommandComplete OK, or present in the last TM(11,13) */
        SCHEDULED,
        /** release ACK seen, or absent from a fresh TM(11,13) after its release time */
        RELEASED,
        /** insert rejected, or deleted on board */
        DELETED,
        /** past release + grace with no release ACK and no reconcile; it may or may not have been released */
        PRESUMED_GONE,
        /** only in TM(11,13), no matching ground command */
        UNKNOWN
    }

    public static class ScheduleEntry {
        final RequestId inner;
        final RequestId wrapper;
        final long releaseTime;
        final Long subScheduleId;
        final Long groupId;
        final CommandId cmdId;
        final boolean duplicate;
        volatile EntryState state = EntryState.PENDING;

        ScheduleEntry(RequestId inner, RequestId wrapper, long releaseTime, Long subScheduleId, Long groupId,
                CommandId cmdId, boolean duplicate) {
            this.inner = inner;
            this.wrapper = wrapper;
            this.releaseTime = releaseTime;
            this.subScheduleId = subScheduleId;
            this.groupId = groupId;
            this.cmdId = cmdId;
            this.duplicate = duplicate;
        }

        /** request ID of the scheduled command, the one its release is reported with */
        public RequestId getInner() {
            return inner;
        }

        /** request ID of the TC(11,4) that inserted it */
        public RequestId getWrapper() {
            return wrapper;
        }

        /**
         * release time as ground time, {@link TimeEncoding#INVALID_INSTANT} if there were no time correlation
         * coefficients at insert
         */
        public long getReleaseTime() {
            return releaseTime;
        }

        /** null if the link has no sub-schedule id field */
        public Long getSubScheduleId() {
            return subScheduleId;
        }

        /** null if the link has no group id field */
        public Long getGroupId() {
            return groupId;
        }

        /**
         * Name of the command that inserted the entry: the scheduled command itself for {@code pus11ScheduleAt}, the
         * TC(11,4) for manual inserts
         */
        public String getCommandName() {
            return cmdId.getCommandName();
        }

        public CommandId getCmdId() {
            return cmdId;
        }

        /** another live entry had the same inner request ID when this one was inserted */
        public boolean isDuplicate() {
            return duplicate;
        }

        /** always true for now: commands scheduled with ack flags 0 are not supported */
        public boolean expectsReleaseAck() {
            return true;
        }

        public EntryState getState() {
            return state;
        }

        @Override
        public String toString() {
            return cmdId.getCommandName() + " " + inner + " " + state;
        }
    }

    final List<ScheduleEntry> entries = new ArrayList<>();
    final Map<RequestId, ScheduleEntry> byRequestId = new HashMap<>();
    final Map<CommandId, List<ScheduleEntry>> byCmdId = new HashMap<>();

    /**
     * Adds the activities of a TC(11,4) as {@link EntryState#PENDING} entries
     */
    public synchronized List<ScheduleEntry> insert(CommandId cmdId, InsertRequest req) {
        var wrapper = new RequestId(req.sourceId(), req.apid(), req.seqCount());
        List<ScheduleEntry> added = new ArrayList<>(req.activities().size());
        for (Activity a : req.activities()) {
            var inner = new RequestId(req.sourceId(), a.apid(), a.seqCount());
            var old = byRequestId.get(inner);
            boolean duplicate = old != null && isLive(old);
            if (duplicate) {
                log.warn("{}: request ID {} is already in the schedule ({}); has the sequence count been reset?",
                        cmdId.getCommandName(), inner, old);
            }
            var e = new ScheduleEntry(inner, wrapper, a.releaseTime(), req.subScheduleId(), a.groupId(), cmdId,
                    duplicate);
            entries.add(e);
            byRequestId.put(inner, e);
            added.add(e);
        }
        byCmdId.computeIfAbsent(cmdId, k -> new ArrayList<>()).addAll(added);
        return added;
    }

    /**
     * Applies the CommandComplete of the TC(11,4): OK moves its {@link EntryState#PENDING} entries to
     * {@link EntryState#SCHEDULED}, NOK removes them (as {@link EntryState#DELETED}).
     *
     * @return the entries that changed state
     */
    public synchronized List<ScheduleEntry> commandComplete(CommandId cmdId, boolean ok) {
        var l = byCmdId.get(cmdId);
        if (l == null) {
            return List.of();
        }
        List<ScheduleEntry> changed = new ArrayList<>();
        if (ok) {
            for (var e : l) {
                if (e.state == EntryState.PENDING) {
                    e.state = EntryState.SCHEDULED;
                    changed.add(e);
                }
            }
        } else {
            for (var e : l) {
                e.state = EntryState.DELETED;
                entries.remove(e);
                byRequestId.remove(e.inner, e);
                changed.add(e);
            }
            byCmdId.remove(cmdId);
        }
        return changed;
    }

    /**
     * Marks {@link EntryState#PRESUMED_GONE} the pending or scheduled entries whose release time is more than
     * {@code grace} ms before {@code now}. The entries stay in the model. Entries without a release time (no time
     * correlation at insert) are left alone.
     *
     * @return the entries that changed state
     */
    public synchronized List<ScheduleEntry> sweep(long now, long grace) {
        List<ScheduleEntry> gone = new ArrayList<>();
        for (var e : entries) {
            if (!isLive(e)) {
                continue;
            }
            long t = e.releaseTime;
            if (t != TimeEncoding.INVALID_INSTANT && t + grace < now) {
                e.state = EntryState.PRESUMED_GONE;
                gone.add(e);
            }
        }
        return gone;
    }

    /** the most recently inserted entry with this inner request ID, or null */
    public synchronized ScheduleEntry get(RequestId inner) {
        return byRequestId.get(inner);
    }

    /** snapshot of all the entries, sorted by release time */
    public synchronized List<ScheduleEntry> getEntries() {
        var l = new ArrayList<>(entries);
        l.sort(Comparator.comparingLong(ScheduleEntry::getReleaseTime));
        return l;
    }

    private static boolean isLive(ScheduleEntry e) {
        return e.state == EntryState.PENDING || e.state == EntryState.SCHEDULED;
    }
}
