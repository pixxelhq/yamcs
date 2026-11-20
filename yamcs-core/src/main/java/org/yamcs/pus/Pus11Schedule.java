package org.yamcs.pus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
 * The last reported enable status of the sub-schedules (TM(11,19)) and of the groups (TM(11,27)) is kept, and the live
 * entries whose sub-schedule or group is reported disabled are flagged with a {@link ScheduleEntry#getDropReason() drop
 * reason}: on board, a disabled activity is deleted without being executed when its release time comes (ECSS-E-ST-70-41C
 * §6.11.4.6). A sub-schedule or group not in the last report is unknown, and not flagged.
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
        // updated by a reconcile, which carries the on-board value
        volatile long releaseTime;
        final Long subScheduleId;
        final Long groupId;
        // null for an UNKNOWN entry: only on board, not inserted by the ground
        final CommandId cmdId;
        final boolean duplicate;
        volatile EntryState state = EntryState.PENDING;
        volatile String dropReason;

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

        /** request ID of the TC(11,4) that inserted it, null for an UNKNOWN entry */
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
            return cmdId == null ? "unknown command" : cmdId.getCommandName();
        }

        /** null for an UNKNOWN entry */
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

        /**
         * Why the entry will be dropped at its release time instead of being executed (its sub-schedule or group is
         * reported disabled), or null if it is not known to be disabled. Kept as it was when the entry stopped being
         * live.
         */
        public String getDropReason() {
            return dropReason;
        }

        @Override
        public String toString() {
            return getCommandName() + " " + inner + " " + state;
        }
    }

    final List<ScheduleEntry> entries = new ArrayList<>();
    final Map<RequestId, ScheduleEntry> byRequestId = new HashMap<>();
    final Map<CommandId, List<ScheduleEntry>> byCmdId = new HashMap<>();
    // last reported enable status, by sub-schedule id and by group id
    Map<Long, Boolean> subScheduleStatus = Map.of();
    Map<Long, Boolean> groupStatus = Map.of();

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
            e.dropReason = dropReason(e);
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

    /**
     * Replaces the enable status of the sub-schedules with the one of a TM(11,19) report, which lists all the
     * sub-schedules on board.
     *
     * @return the live entries whose drop reason changed
     */
    public synchronized List<ScheduleEntry> subScheduleStatus(Map<Long, Boolean> status) {
        subScheduleStatus = Map.copyOf(status);
        return updateDropReasons();
    }

    /**
     * Replaces the enable status of the groups with the one of a TM(11,27) report, which lists all the groups on board.
     *
     * @return the live entries whose drop reason changed
     */
    public synchronized List<ScheduleEntry> groupStatus(Map<Long, Boolean> status) {
        groupStatus = Map.copyOf(status);
        return updateDropReasons();
    }

    private List<ScheduleEntry> updateDropReasons() {
        List<ScheduleEntry> changed = new ArrayList<>();
        for (var e : entries) {
            if (!isLive(e)) {
                continue;
            }
            String reason = dropReason(e);
            if (!Objects.equals(reason, e.dropReason)) {
                e.dropReason = reason;
                changed.add(e);
            }
        }
        return changed;
    }

    private String dropReason(ScheduleEntry e) {
        if (e.subScheduleId != null && Boolean.FALSE.equals(subScheduleStatus.get(e.subScheduleId))) {
            return "sub-schedule " + e.subScheduleId + " is disabled";
        }
        if (e.groupId != null && Boolean.FALSE.equals(groupStatus.get(e.groupId))) {
            return "group " + e.groupId + " is disabled";
        }
        return null;
    }

    /**
     * What a reconcile changed.
     *
     * @param confirmed
     *            entries now {@link EntryState#SCHEDULED} that were not before (PENDING or PRESUMED_GONE)
     * @param missing
     *            live entries with a release time still ahead that are not on board: silently dropped. Removed from
     *            the model as {@link EntryState#DELETED}
     * @param released
     *            live entries with a release time already passed that are not on board: released (or dropped). Marked
     *            {@link EntryState#RELEASED}
     * @param retimed
     *            entries whose release time differs from the on-board one; they take the on-board value. A difference
     *            means that the ground time conversion disagrees with the spacecraft
     * @param unknown
     *            entries newly seen on board with no ground command; {@link EntryState#UNKNOWN}. The UNKNOWN entries of
     *            earlier reports that are still on board are kept without being listed again
     */
    public record Reconciliation(List<ScheduleEntry> confirmed, List<ScheduleEntry> missing,
            List<ScheduleEntry> released, List<ScheduleEntry> retimed, List<ScheduleEntry> unknown) {
    }

    /**
     * Replaces the live part of the model with a complete TM(11,13) report. The on-board release time overwrites the
     * ground one. Entries without a release time are not judged absent.
     *
     * @param report
     *            the complete on-board schedule
     * @param now
     *            the time of the report, ground time
     */
    public synchronized Reconciliation reconcile(List<Pus11SummaryReport.Entry> report, long now) {
        Map<RequestId, Pus11SummaryReport.Entry> reported = new HashMap<>();
        for (var r : report) {
            reported.put(r.inner(), r);
        }
        List<ScheduleEntry> confirmed = new ArrayList<>();
        List<ScheduleEntry> missing = new ArrayList<>();
        List<ScheduleEntry> released = new ArrayList<>();
        List<ScheduleEntry> retimed = new ArrayList<>();
        List<ScheduleEntry> unknown = new ArrayList<>();

        for (var e : List.copyOf(entries)) {
            // PENDING: its TC(11,4) may still be on its way, so absence means nothing yet
            if (e.state == EntryState.PENDING || e.state == EntryState.SCHEDULED
                    || e.state == EntryState.PRESUMED_GONE) {
                var r = reported.get(e.inner);
                if (r != null) {
                    if (r.releaseTime() != TimeEncoding.INVALID_INSTANT && r.releaseTime() != e.releaseTime) {
                        if (e.releaseTime != TimeEncoding.INVALID_INSTANT) {
                            log.warn("{} {}: on-board release time is {} ms off the ground one", e.cmdId == null
                                    ? "unknown command" : e.cmdId.getCommandName(), e.inner,
                                    r.releaseTime() - e.releaseTime);
                            retimed.add(e);
                        }
                        e.releaseTime = r.releaseTime();
                    }
                    if (e.state != EntryState.SCHEDULED) {
                        e.state = EntryState.SCHEDULED;
                        confirmed.add(e);
                    }
                } else if (e.state != EntryState.PENDING && e.releaseTime != TimeEncoding.INVALID_INSTANT) {
                    if (e.releaseTime <= now) {
                        e.state = EntryState.RELEASED;
                        released.add(e);
                    } else {
                        e.state = EntryState.DELETED;
                        entries.remove(e);
                        byRequestId.remove(e.inner, e);
                        missing.add(e);
                    }
                }
            } else if (e.state == EntryState.UNKNOWN && !reported.containsKey(e.inner)) {
                entries.remove(e);
                byRequestId.remove(e.inner, e);
            }
        }

        for (var r : report) {
            var existing = byRequestId.get(r.inner());
            if (existing != null && (isLive(existing) || existing.state == EntryState.UNKNOWN)) {
                continue;
            }
            var e = new ScheduleEntry(r.inner(), null, r.releaseTime(), r.subScheduleId(), r.groupId(), null, false);
            e.state = EntryState.UNKNOWN;
            e.dropReason = dropReason(e);
            entries.add(e);
            byRequestId.put(r.inner(), e);
            unknown.add(e);
        }
        return new Reconciliation(confirmed, missing, released, retimed, unknown);
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
