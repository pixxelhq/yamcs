# Decisions and assumptions

Assumptions made while building features: field widths, ICD ambiguities, placeholder values. One
`## <Feature>` section per feature. When the real answer arrives, update the status and fix what the
entry affects. Format and rules: [CONTRIBUTING.md](../CONTRIBUTING.md#docs).

Status values: `Assumed`, `Confirmed <date>`, `Deferred`, `Wrong, fixed <date>`.

## Contents

- [PUS 11 time-tag queue](#pus-11-time-tag-queue)

## PUS 11 time-tag queue

Feature: ground mirror of the on-board ST[11] schedule (`Pus11ScheduleTracker`, `Pus11Schedule`,
`Pus11SummaryReport`, `TmGapReconcileTrigger`) and persisted TC sequence counts (`persistSeqCount`).
Plan: [2026-10-22-time-tag-queue.md](superpowers/plans/2026-10-22-time-tag-queue.md) (blockers in §6.1,
review findings in §9).

| ID | Assumption | Why | Affects | Status | Owner |
|---|---|---|---|---|---|
| TTQ-1 | TC[11,4] sub-schedule ID and group ID are uint8 (1 byte) | Confirmed by OBSW on Slack | `pus11.subScheduleId.bytes` / `groupId.bytes` in link configs; MDB `GroupIdType`; TM[11,13]/[11,27] decode | Confirmed 2026-10-22 | OBSW |
| TTQ-2 | Request IDs in TC[11,5/7/9/12] and TM[11,13] are bit-packed source ID u16, APID u11, seq count u14; TM[11,13] `n` is u16 | ECSS-E-ST-70-41C gives types, not widths (§8.11.2.13) | MDB `dt.xml`, `PusPackedFields`, summary decode (plan B2) | Assumed | Project, confirm with OBSW |
| TTQ-3 | A TM[11,13] longer than one packet is split with CCSDS grouping flags (first/continuation/last) | The standard is silent; the WIP ICD mentions grouping only for memory dumps | `Pus11SummaryReport.Collector`. **Conflicts** with master's simulator, which pages via `page_number`/`total_pages` (`2269069e0`), see plan §9 F1 | Assumed (likely wrong) | OBSW / FSW ICD owner (B1) |
| TTQ-4 | No scheduling group exists at boot; ops create groups with TC[11,22] before inserting | ECSS: an unknown group rejects the insert (§6.11.4.5g.3); no auto-creation | `examples/pus-frames/tests/pus11_setup.py` `ensure_groups()`; default group 1 in pus-frames | Assumed | OBSW (B4) |
| TTQ-5 | ID value 0 is valid for sub-schedules and groups; value ranges unknown | OBSW: ranges not decided | Test data, default IDs | Assumed | OBSW (B4) |
| TTQ-6 | AOS = first `tm_realtime` packet after 600 s of silence (`aosGap`); reconcile 30 s later (`aosSettle`) | No contact schedule or AOS hook exists; gateway link is always up | `TmGapReconcileTrigger`, tracker `reconcile` options | Assumed | Us / ops |
| TTQ-7 | An entry past release time + `releaseGrace` (default 300 s; 60 s in pus-frames) with no confirmation is PRESUMED_GONE | Release ACKs are not correlated yet (plan step 4) | `Pus11Schedule.sweep`; `pus11-release = TIMEOUT` | Assumed | Us |
| TTQ-8 | No time sync/drift handling: release time converted to ground time once at insert | Requirement change | `Pus11WrapperLayout`, `Pus11Schedule`. Contradicted by the reconcile overwrite (plan §9 F9) | Confirmed 2026-10-27 | Project |
| TTQ-9 | Every scheduled command produces a release ACK (`ackflags` ≠ 0) | Simplest v1 | Sweep / future step 4 | Deferred | Us |
| TTQ-10 | Schedule time margin = 0 (simulator) | Mission value unknown (§6.11.4.2d) | Insert-rejection prediction (not built) | Deferred | OBSW (B5) |
| TTQ-11 | One Yamcs instance per satellite | Decided for multi-spacecraft | Tracker is one service per instance, no spacecraft key | Confirmed 2026-09-24 | Project |
| TTQ-12 | TM time field is CUC 4+2 bytes | WIP ICD conflict: 8 bytes (§6.2) vs CUC 4+2 (Appendix 4) | TM[11,13] release-time decode | Assumed | FSW ICD owner (B7) |
| TTQ-13 | Persisted seq counters are keyed by link name (`ccsds-seqcount/link/<link>`) or `seqCounterName`; renaming a link restarts its counter at 0 | Simple, matches SDLS IV persistence | `MementoSeqCountStore`, `persistSeqCount` | Assumed | Us |
