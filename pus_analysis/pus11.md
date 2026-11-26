# PUS ST[11] — Time-based scheduling

Ground scheduling support consists of XTCE
commands/reports, optional TC[11,4] wrapping in `PusCommandPostprocessor`, and an optional ground
mirror of the on-board schedule, `Pus11ScheduleTracker`. The on-board scheduler is emulated by
`simulator/.../Pus11Service.java`.

## Current sources

- [Postprocessor and configuration manual](../docs/server-manual/links/command-postprocessor/pus.rst)
- [PusCommandPostprocessor](../yamcs-core/src/main/java/org/yamcs/pus/PusCommandPostprocessor.java)
- [pus11.xml](../examples/pus/src/main/yamcs/mdb/pus11.xml) and [base verifiers](../examples/pus/src/main/yamcs/mdb/pus.xml)
- [Pus11Service](../simulator/src/main/java/org/yamcs/simulator/pus/Pus11Service.java)
- [Pus11ScheduleTracker](../yamcs-core/src/main/java/org/yamcs/pus/Pus11ScheduleTracker.java),
  [Pus11Schedule](../yamcs-core/src/main/java/org/yamcs/pus/Pus11Schedule.java),
  [Pus11SummaryReport](../yamcs-core/src/main/java/org/yamcs/pus/Pus11SummaryReport.java),
  [TmGapReconcileTrigger](../yamcs-core/src/main/java/org/yamcs/pus/TmGapReconcileTrigger.java),
  [Pus11WrapperParser](../yamcs-core/src/main/java/org/yamcs/pus/Pus11WrapperParser.java)
- [Design plan and open issues](../docs/superpowers/plans/2026-10-22-time-tag-queue.md)
- [Postprocessor tests](../yamcs-core/src/test/java/org/yamcs/pus/PusCommandPostprocessorTest.java)
  and [scheduler tests](../simulator/src/test/java/org/yamcs/simulator/pus/Pus11ServiceTest.java)

## Scheduling an existing command

The `pus11ScheduleAt` timestamp command option wraps a postprocessed original command into
TC[11,4]. Enable it with a `pus11` map in the link's postprocessor arguments:

```yaml
commandPostprocessorClassName: org.yamcs.pus.PusCommandPostprocessor
commandPostprocessorArgs:
  errorDetection: {type: CRC-16-CCIIT}
  spareBytes: 4
  timeEncoding: {implicitPfield: false, pfield: 0x2f}
  tcoService: tco0
  pus11Apid: 1
  pus11:
    sourceId: 0
    ackFlags: 0xD
    subScheduleId: {bytes: 1, default: 0}
    groupId: {bytes: 1, default: 0}
```

This matches the example's one-byte sub-schedule/group layout. Each ID block is independent:
omit it to omit that field. Configured widths are 1–8 bytes. The matching per-command option
names are `pus11SubScheduleId` and `pus11GroupId`; capitalization matters. `pus11Apid` is
optional and falls back to the original command APID. Set it explicitly when the scheduler
has a dedicated APID. `pus11Crc` applies to any outer TC[11,4], including one encoded by the
MDB. `packetCrc` controls ordinary top-level TCs. `embeddedTcCrc` controls the inner TC in
each path. These checksum options require an `errorDetection` algorithm when enabled.

The former top-level `pus11SourceId`, `pus11AckFlags`, `pus11SubscheduleId`, and `pus11GroupId`
configuration keys are rejected at initialization. Scheduling options are registered when
the corresponding configuration blocks are present. Time and position scheduling options
cannot be combined on one command.

## Wrapper bytes and history

```text
CCSDS primary (6 B) + PUS TC secondary (5 B) + spare (4 B)
+ optional sub-schedule ID + N:u8=1
+ optional group ID + release time + finalized original TC + optional wrapper CRC
```

Overhead is `12 + spareBytes + subScheduleIdBytes + groupIdBytes + encodedTimeLength + crcBytes`.
The example's 1-byte IDs, 8-byte CUC, and 2-byte CRC give 28 bytes. The default jTYU profile
uses an implicit 6-byte release time with no wrapper CRC, giving 24 bytes. The release time
comes from `tcoService.getObt()` when configured, otherwise the configured epoch conversion.
Unavailable correlation fails the command. The default postprocessor epoch is UNIX.

| Command-history attribute | Meaning |
|---|---|
| `binary`, `ccsds-apid`, `ccsds-seqcount` | Original command after postprocessing |
| `pus11Binary`, `pus11Apid`, `pus11CcsdsSeqCount` | TC[11,4] wrapper and its request ID |
| `ccsds-seqcount:<argument path>` | Annotated embedded command sequence count, when applicable |

The generic attributes are not overwritten with the wrapper ID. The example MDB contains a
separate scheduling verifier using the `pus11*` attributes; original-command verifiers retain
the original request ID. Inspect the check windows before scheduling commands far in the
future: verifying insertion and verifying eventual execution are separate events.

## Direct ST[11] commands and reports

The example MDB contains TC[11,1–9/11/12/14–18/20–26] and TM[11,10/13/19/27]. Use its exact
MetaCommand names/arguments rather than reconstructing payloads from historical snippets.
Direct insertion uses an array of activities, including a release time and an embedded TC.
The shared annotated embedded type is finalized by the postprocessor; see
[embedded TC processing](pus_native_arch.md#embedded-tcs).

Request identifiers in service data contain `source_id:u16`, `apid:u11`, `seqcount:u14`.
The APID and sequence count are packed, without padding between fields. These identify the
embedded request, not the surrounding TC[11,4].

The simulator starts with the execution function disabled. It now stores group membership,
checks group state when releasing activities, validates unknown groups and group limits, and
rejects deletion of groups containing activities. Earlier claims that groups are bookkeeping
only are obsolete. Due activities are removed even when a disabling gate prevents release;
disabling the scheduler is therefore not a queue-preserving pause.

## Ground schedule tracker

`Pus11ScheduleTracker` is an instance service (configuration in the
[postprocessor manual](../docs/server-manual/links/command-postprocessor/pus.rst), "Schedule tracker").

- **Insert.** For each command with `pus11ScheduleAt`, `onInsert` parses `pus11Binary` with
  `Pus11WrapperParser`, using the `Pus11WrapperLayout` of the TC link's postprocessor, and adds a
  PENDING entry to `Pus11Schedule`. The release time is converted to ground time once, at insert.
  `CommandComplete` OK makes it SCHEDULED; any other result removes it.
- **Status.** Each entry's state is published to command history as the `pus11-release`
  acknowledgment (`SCHEDULED`, `DISABLED`, `TIMEOUT`), separate from `CommandComplete`, which only
  means the insert was accepted.
- **Drop flag.** TM[11,19] and TM[11,27] status reports set a drop reason on live entries whose
  sub-schedule or group is disabled (`Pus11Schedule.subScheduleStatus` / `groupStatus`).
- **Sweep.** `Pus11Schedule.sweep` marks entries past release time + `releaseGrace` PRESUMED_GONE.
- **Reconcile.** `TmGapReconcileTrigger` detects a pass from a TM gap; `requestReconcile` sends
  TC[11,17]; `Pus11SummaryReport` collects the TM[11,13] packets by CCSDS sequence flags and
  `Pus11Schedule.reconcile` replaces the live entries with the on-board ones.
- **Sequence counts.** With `persistSeqCount`, `CcsdsSeqCountFiller` saves its counters through
  `MementoSeqCountStore` so a restart does not reuse request IDs still in the on-board schedule.

Known issues (details and TODO list in the
[plan, §9](../docs/superpowers/plans/2026-10-22-time-tag-queue.md#9-review-findings-and-todo-2026-11-26)):
release acknowledgments are not read, so released commands end as TIMEOUT; paged TM[11,13]
reports from the simulator (`page_number`/`total_pages`) are not assembled; any TM[11,13],
including by-ID or by-filter reports, is applied as the full schedule; the inner request ID uses
the wrapper's source ID; manual INSERT_ACTIVITIES, deletes and shifts are not tracked; the model
is not persisted across restarts.

## Checks and remaining limitations

Start the [example](pus_native_arch.md#build-and-verification), create/enable the required group
and sub-schedule, and enable the execution function before expecting releases. Check the
wrapper ST[01] reports against `pus11Apid`/`pus11CcsdsSeqCount`, then check the original request's
reports at release. An autonomous simulator release produces TM acknowledgements; it does not
create a newly issued ground command automatically.

Filter commands carry time bounds selected by the filter type, followed by counted
sub-schedule and group lists. An empty list selects every ID in its category. TM[11,10]
uses an 8-bit activity count and carries complete inner TCs. TM[11,13] uses a 16-bit count
and carries the packed request IDs. Both include one-based pagination in the TM spare field.
The ST[11] time and identifier widths must agree across the active MDB, simulator, and
postprocessor.

Several repeated report fields use ordinary XML dynamic references. Their instance policy
must be checked for differing counts/embedded lengths; see the [aggregate guide](aggregate_member_value.md).
Yamcs command queues are ground pre-release queues, not an authoritative mirror of the
spacecraft schedule. The packet definitions alone establish no ground schedule; that is
the job of `Pus11ScheduleTracker` (see above), which reconciles but does not persist it.
