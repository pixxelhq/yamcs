# Ground-side mirror of the onboard PUS-11 time-tag queue

Plan for `feat/time_tag_command_queue` (= `../../pus_analysis/pus11.md` Gap #8, plus the release half of
Gap #5). Scope: a live ground view of what is *currently* in the spacecraft's ST[11] schedule, plus
search by release time (§8). Refreshed 2026-10-19 against `pixxel/master` `9a5f8648a` (branch
fast-forwarded from `f7820b796`); §4 caveats resolved 2026-09-24 (multi-sat = one instance per
satellite). Open questions re-checked against the code 2026-10-19 (§5): Q1, Q2, Q6 resolved, Q3 not blocking (ID width = uint8, 2026-10-22), Q4 + Q5 deferred; nothing in
§3/§8 is implemented yet.

**Primary assumption (2026-10-19):** the aim is a **native ground-only implementation** of this
feature in Yamcs. The ground design follows ECSS-E-ST-70-41C and the mission's needs; it is
never shaped around the simulator. The simulator (`simulator/.../pus/Pus11Service.java`) is a test
tool, and it is changed, updated or adapted to whatever the native implementation requires (e.g.
missing HK parameters, report formats, time margin). Where this plan says "the simulator does X",
read it as current test-bed behaviour, not a constraint on the ground design.

**What changed on 2026-10-19** (commits `bf11c53c8` PUS11 improvements, `07093d3ef` packed request
IDs, `db7466fe0` packed field widths, `0c604ad09` PUS22):
- Postprocessor config moved into a `pus11:` block; sub-schedule/group fields are now **optional
  and 1–8 bytes wide**; source id is **no longer in cmdhist**; cmdhist keys renamed (§1.2).
- New `tcoService` postprocessor option: TC[11,4] carries **OBT** when set (§1.2, §4.4).
- Request IDs in TC[11,5/7/9/12] and TM[11,13] are **bit-packed** (`u16/u11/u14`) (§1.6).
- TM[11,13] entries now include `group_id` (§1.6).
- Simulator implements groups (TC[11,22–26], TM[11,27]) → all three silent-drop paths testable (§1.5).
- New `examples/pus-frames` with real time correlation (§4.4).
- PUS22 position-based scheduling added — out of scope, see §1.7.
- `9a5f8648a`: PUS22 wrapper now publishes inner-ID keys and overwrites `ccsds-*` with the wrapper
  ID, same as PUS11 (§1.7).

Code references use method names, not line numbers.

---

## 1. Current state

### 1.1 No ground model exists

Nothing in `yamcs-core` tracks on-board schedule contents. `CommandQueue`/`CommandQueueManager` are
ground pre-release queues, unrelated to ST[11]. Greenfield.

### 1.2 Uplink path — `PusCommandPostprocessor.process()` / `buildScheduledTc()`

One `PreparedCommand` (the inner TC); `pus11ScheduleAt` is an attribute, registered only when the
link's postprocessor config has a `pus11:` block. The binary is swapped for a TC[11,4] wrapper
(`N=1`). Wrapper layout: `hdr(6) | pus(3) | sourceId(2) | subSchedId(0..8) | N(1) | groupId(0..8) |
releaseTime(CUC) | inner TC | CRC?`. Sub-schedule and group fields exist only when
`pus11.subScheduleId` / `pus11.groupId` are configured, with width `bytes` (1–8).

Config (`commandPostprocessorArgs`): `pus11Apid`, `pus11Crc`, `timeEncoding` (+ `epoch`),
`tcoService`, and `pus11: { sourceId, ackFlags, subScheduleId: {bytes, default}, groupId: {bytes,
default} }`. The old top-level `pus11SourceId`/`pus11AckFlags`/`pus11SubscheduleId`/`pus11GroupId`
are rejected at startup.

Command history per scheduled command:

| Key | Value |
|---|---|
| `ccsds-apid`, `ccsds-seqcount` | **wrapper** request ID (overwritten after wrapping; read by `pus.xml` verifiers) |
| `pus11-inner-apid`, `pus11-inner-seqcount` | **inner** request ID — the release/dequeue key |
| `pus11Apid`, `pus11CcsdsSeqCount` | wrapper request ID (renamed from `pus11-apid`/`pus11-ccsds-seqcount`) |
| `pus11SubScheduleId`, `pus11GroupId` | command-option attributes — **only if the operator set them**; the config default is not published |
| `pus11Binary` | full TC[11,4] (renamed from `pus11-binary`) |
| `pus11ScheduleAt` | release time (ground time) |

**No longer in cmdhist:** `pus11-source-id` and defaulted sub-schedule/group IDs. So the tracker
**must parse `pus11Binary`** to get `sourceId`, `subScheduleId`, `groupId` and `releaseObt`. The
parse needs the link's field widths. Expose them from `PusCommandPostprocessor` (getters, or a small
shared `Pus11WrapperLayout` record built in `init()`) rather than re-reading the YAML.

### 1.3 Two request IDs, two signals

- **Wrapper** ACKs arrive within seconds → *insert accepted, now queued*. The `pus.xml` verifiers now
  match these (Gap #5 part 1, fixed), so `CommandComplete = OK` means **insert accepted**, not
  "executed".
- **Inner** ACKs arrive at release time (hours later) → *released and ran*. No verifier covers this;
  `CheckWindow` can only be relative to `commandRelease`/`lastVerifier`. The tracker must subscribe
  to the decoded `/PUS/pus-tc-ack-*` parameters directly and match `(tc-ack-apid, tc-ack-seq)`
  against `pus11-inner-*`.

### 1.4 Intermittent link

Release ACKs may be dumped in bulk at AOS, out of order, or lost. So: key on the ACK packet's
**generation time**, never receipt time; no "latest wins"; a missing ACK means *unknown*, not
*not released*.

### 1.5 On-board semantics (ECSS-E-ST-70-41C §6.11)

- **§6.11.4.6** — an activity is disabled if the execution function, its sub-schedule, or its group
  is disabled. At release time it is released only if not disabled, and **deleted either way**.
  NOTE 2: no notification. → three silent-drop paths; TC[11,2] is not a pause.
- **§6.11.1.2** — release verification reports go to the request's `source_id`, "if any".
- **§6.11.4.5.g.2** — insert rejected if release time < now + *time margin* (mission value, Q4).
- **§6.11.11** — subservice must expose activity count + execution function status (drift detector).
- Delete/shift TCs ACK like any TC; the ACK confirms acceptance, not *which* activities matched.

Simulator (`simulator/.../pus/Pus11Service.java`) as porting source / test bed:
- Gates on execution function (starts **disabled** — send `ENABLE_SCHEDULER`), sub-schedule **and
  group** (groups implemented in `bf11c53c8`: TC[11,22–26], TM[11,27]). All 3 drop paths are
  testable.
- Groups exist only after `CREATE_SCHEDULING_GROUPS` (TC[11,22]); an insert naming an unknown group
  is rejected as a whole (§6.11.4.5g.3). Test setup must create groups first. TC[11,3] reset
  re-enables all groups but keeps them.
- At release, an activity whose release time has already passed is dropped with a log line, not run.
- Insert and time-shift are atomic: a rejected TC changes nothing. Negative shifts work (`int32`).
- Parsers: `insertActivities()`, `filterById()`, `filterByFilter()`, `runSchedule()`. Request IDs
  are read/written with `BitBuffer` using `PusPackedFields` (`APID_BITS = 11`, `SEQCOUNT_BITS = 14`).

### 1.6 TM[11,13] summary = complete snapshot

Per entry: `schedule_id:u8 | group_id:u8 | release_time:CUC | source_id:u16 | apid:u11 | seq:u14`,
preceded by `n:u16`. Fields are **bit-packed**: entries after the first do not start on an octet
boundary (`07093d3ef`). MDB: `/PUS11/SUMMARY_REPORT/*` with `/dt/pus_report_apid` and
`/dt/pus_report_seqcount`. The repeated `SUMMARY_REPORT_ELEMENT` yields flat repeated parameters,
so consume them in order per packet. This is the model's key space. Poll-only (TC[11,17]); may span
packets per spec (simulator sends one).

The same packed `u16/u11/u14` request ID applies to TC[11,5/7/9/12] arguments — the TC parser
(§8.4 step 3) must use `BitBuffer`, not `ByteBuffer`.

Status reports usable as inputs (§3 input 6): TM[11,19] sub-schedule status
(`SUBSCHEDULE_STATUS_REPORT`, via TC[11,18]) and TM[11,27] group status (`GROUP_STATUS_REPORT`, via
TC[11,26]). Still missing: execution-function status and activity count (§6.11.11).

### 1.7 PUS22 (position-based scheduling) — out of scope

`0c604ad09` adds `pus22OrbitNumber`/`pus22OrbitAngle` options that wrap a command into TC[22,4]
(config `pus22:` block, own sub-schedule/group widths). It publishes `pus22Apid`,
`pus22CcsdsSeqCount`, `pus22Binary`, and since `9a5f8648a` (`buildScheduledPositionTc`) also
`pus22-inner-apid`/`pus22-inner-seqcount`, with `ccsds-apid`/`ccsds-seqcount` overwritten by the
wrapper ID. So the Gap #5 verifier mismatch is fixed for PUS22 too, and the key layout matches
PUS11. Not part of this tracker. If PUS22 tracking is wanted later, keep `ScheduleEntry` release
keys generic (time *or* position) — no other change needed now.

### 1.8 Test-bed defects (found 2026-10-19)

- **`examples/pus-frames` MDB mis-decodes TM[11,13].** Its `pus11.xml` `SUMMARY_REPORT` declares
  `source`/`apid`/`seq` as `/dt/uint16`, but the simulator emits bit-packed `u16/u11/u14` (§1.6).
  `examples/pus` uses `/dt/pus_report_apid` / `/dt/pus_report_seqcount` correctly. `pus-frames`
  also has no `INSERT_ACTIVITIES` MetaCommand. Fix both before using `pus-frames` (the TCO test
  bed, §4.4) for §6 step 3.
- **`examples/pus` cannot schedule at all.** Its TC link sets `tcoService: tco0`, but nothing feeds that TCO
  samples, so every `pus11ScheduleAt` fails with "Time corelation coefficients not available". Use
  `examples/pus-frames` (real TCO) for PUS 11 work. (Found 2026-10-22.)
- **`examples/pus-frames/tests/test-pus11.py` `schedule_command()`** returns
  `command.attributes["ccsds-seqcount"]`, which is now the **wrapper** seq (§1.2), then passes it
  to `DELETE_ACTIVITIES_BY_ID`. It should read `pus11-inner-seqcount`. Not yet confirmed by a run.

---

## 2. Approach — reconcile-primary, ACK-corroborated

| Layer | Trigger | Role |
|---|---|---|
| A. Reconcile | TM[11,13] via TC[11,17], once per pass (TM-gap AOS, §5 Q1) + on demand | **authoritative**; replaces the model |
| B. Release ACK | `/PUS/pus-tc-ack-*` matched on inner ID, keyed on gentime | fast confirmation between reconciles |
| C. Model | cmdhist (`pus11ScheduleAt`) + parsed TC[11,3/4/5/6/7/8/15] | predicted state shown between passes |
| D. Sweep | `releaseTime + grace < now`, no ACK → `PRESUMED_GONE` | makes uncertainty visible |

B and D never override a fresh A. Only A catches silent drops and uplink loss.

---

## 3. Design — `Pus11ScheduleTracker extends AbstractYamcsService` (`yamcs-core/.../pus/`)

One instance-level service per Yamcs instance = one satellite (§4.6). It needs no satellite ID of
its own, because every key, table and API path is already per instance.

```java
record RequestId(int sourceId, int apid, int seqCount) {}

enum EntryState { PENDING, SCHEDULED, RELEASED, DELETED, PRESUMED_GONE, UNKNOWN }
// PENDING       - TC[11,4] sent, CommandComplete not yet OK
// SCHEDULED     - CommandComplete OK, or present in last TM[11,13]
// RELEASED      - inner release ACK seen, or absent from a fresh TM[11,13] after release time
// PRESUMED_GONE - past release + grace, no ACK, no reconcile. Ambiguous; UI must show it
//                 distinct from RELEASED
// UNKNOWN       - only in TM[11,13], no matching ground command

class ScheduleEntry {
    RequestId inner, wrapper;
    long releaseTime, releaseObt;
    Long subscheduleId, groupId; // null when the field is not configured on the link; up to 8 bytes wide
    String commandName;
    CommandId cmdId;
    EntryState state;
    boolean expectsReleaseAck; // always true in v1 (§7)
}
```

Index: `ConcurrentSkipListMap<Long, List<ScheduleEntry>>` by release time + `Map<RequestId, ScheduleEntry>`.

**Inputs**
1. **Reconcile** — decoded `/PUS11/SUMMARY_REPORT/...`; accumulate multi-packet reports before
   swapping. Trigger once per pass via a pluggable `ReconcileTrigger` (§5 Q1), and from a UI
   button. v1 trigger = **TM gap**: subscribe to `tm_realtime`; the first packet after > `aosGap`
   (default 10 min) of silence = AOS; send TC[11,17] after `aosSettle` (default 30 s). Skip, do not
   fail, when no TC link is enabled (downlink-only pass). Publish `lastReconcileTime`; UI shows
   "last reconciled N min ago". **Not** link-status polling: under the planned ground-station
   gateway (`link-management-redesign-feasibility.md` Option A) each instance has one always-up
   gateway link, so link status never shows AOS.
2. **cmdhist** — tuple with `pus11-inner-seqcount` → new `PENDING` entry, fields from parsing
   `pus11Binary` (§1.2); `CommandComplete = OK` → `SCHEDULED`; `NOK` → drop.
3. **Release ACKs** — see §1.3/§1.4.
4. **TC stream** — parse TC[11,4] binary for manual `/PUS11/INSERT_ACTIVITIES` (`N > 1`); parse
   deletes/shifts; apply only after `CommandComplete = OK` (simulator and spec reject atomically).
5. **Sweep timer** — `PRESUMED_GONE`, never evict.
6. **Drift detector** — **v1 = only the enable-status part.** The §6.11.11 count/execution-status
   checks are deferred (§5 Q5, §7). TM[11,19] / TM[11,27] give sub-schedule and group enable status. Flag
   `SCHEDULED` entries whose sub-schedule or group is disabled as "will be dropped at release"
   (§1.5 drop paths 2 and 3).

**Release status in cmdhist**: publish under a distinct key (e.g. `pus11-release_Status`), not
`CommandComplete` — that already means "insert accepted".

**Output**: REST + web page (§8.4). Cheapest v1: an `ItemProvider` (mirror `CommandItemProvider`)
so the Timeline view renders the queue.

**Persistence**: in-memory is truth; backed by the `pus11_schedule` table (§8). Rebuild on restart =
replay cmdhist for future `pus11ScheduleAt`, then reconcile.

---

## 4. Caveats

Resolved 2026-09-24.

1. **Duplicate request IDs.** The `change-seq-count` action is not a real source. Operators do not
   use it, and nothing in the repo calls it: the only caller is the generic web link-action dialog,
   and there is no script or Python client use. It is still exposed as
   `POST /api/links/{inst}/{link}/actions/change-seq-count`. The **real** source is restart:
   `CcsdsSeqCountFiller` is an in-memory `HashMap` with no persistence, so every Yamcs restart
   restarts each APID at 0 while pre-restart entries are still on board. A 14-bit wrap needs more
   than 16384 commands on one APID within the schedule horizon, which is unlikely.
   → Still key on `(RequestId, releaseTime)` and flag collisions at insert (a lookup in the existing
   `Map<RequestId, …>`, ~10 lines). Drop the "warn on seq reset" hook.
   **Fix (in scope, 2026-10-19): re-seed the counter on restart from cmdhist.** Build step 0b (§6).
   - **Where:** `CcsdsSeqCountFiller` gets an opt-in seed hook. `PusCommandPostprocessor.init()`
     enables it with a new option `seqCountSeed: true` (default `false`, so ISS/CFS and existing
     configs are unchanged).
   - **When:** lazily, on the first `fill()` of each counter, not in `init()`. Link init can run
     before `CommandHistoryRecorder` creates the `cmdhist` table. If the table is missing (fresh
     instance), skip the seed and start at 0.
   - **Query:** `SqlBuilder(CommandHistoryRecorder.TABLE_NAME)`, `gentime >= now − seqCountSeedLookback`
     (default: the schedule horizon, e.g. 30 d), descending by gentime. For each APID take the
     **most recent** seq, not the max: the counter wraps at 2^14. Seed = `(last + 1) mod 2^14`.
   - **Read three column pairs:** `ccsds-apid/ccsds-seqcount` (plain TCs and wrappers),
     `pus11-inner-apid/-seqcount`, and `pus22-inner-apid/-seqcount`. The wrapper overwrites
     `ccsds-*` (§1.2), so the inner seq of a scheduled TC survives only in the `-inner-` keys.
   - **Shared counters:** a named counter (`seqCounterName`) is seeded once, guarded by a `seeded`
     flag kept with the map in `namedSeqCounts`. Only APIDs with no entry yet are seeded, so
     `change-seq-count` and later fills are never overwritten.
   - **Crash gap:** cmdhist is written asynchronously, so a hard crash can lose the last few seqs.
     Add `seqCountSeedGap` (default `0`) to skip N seqs after the seed. Set it > 0 only if the
     spacecraft does not check TC seq continuity.
   - **Test:** send 3 TCs on one APID, restart the instance, send 1 more → its `ccsds-seqcount`
     is 3, not 0. Repeat with a TC[11,4] to check that the inner seq continues too.
2. **Filter divergence.** A separate PUS-9 service keeps TCO synced (assumed). The ground therefore
   evaluates filters and shifts **in raw OBT ticks**, never in ground time: the `releaseObt`
   exactly as encoded in the TC[11,4] and the filter bounds exactly as encoded in the TC[11,6/8]
   are both integers, so matching is exact. After this change TCO drift affects only the
   *displayed* ground time.
   **Fix detail (2026-10-19):**
   - **Store** `releaseObt` as a raw tick count: CUC coarse and fine bytes packed into one `long`,
     taken from the TC[11,4] binary. Never derive it from ground time.
   - **Filter** (TC[11,6/8]): decode `start_time`/`end_time` from the TC binary as raw ticks.
     Mirror the on-board bound rules exactly, inclusive on both ends: type 1 `start ≤ t ≤ end`,
     type 2 `t ≥ start`, type 3 `t ≤ end`. Confirm these against the spacecraft SW ICD.
   - **Shift offset** (TC[11,7/8/15]): today it is `int32` milliseconds (`time_offset_ms`). With
     2 fine bytes, 1 ms = 65.536 ticks, so ms → ticks is not an integer and the ground's rounding
     can differ from the on-board one by 1 tick. Read the offset from the TC binary as encoded
     (never from the operator's input), then either:
     a. **(Recommended)** the mission encodes the offset as a relative CUC in ticks. The ground
        adds it as an integer, which is exact. The MDB and simulator change to match.
     b. If the ICD keeps ms: apply the on-board rounding rule, taken from the ICD and set in the
        `pus11.shiftRounding` config (`floor`|`half-up`). Treat any residual difference as drift.
   - **Shift rejection:** on board, a shift is all-or-nothing. If any selected entry would land
     in the past, the whole TC is rejected (`COMPL_ERR_SCHEDULE_TIME_IN_THE_PAST`). Apply the
     shift to the model only on `CommandComplete = OK`, never on send.
   - **Safety net:** every TM[11,13] reconcile overwrites `releaseObt` with the on-board value,
     so a 1-tick error lasts at most until the next reconcile. Log it as drift, not as an error.
   - **Test bed (per the primary assumption):** the simulator stores `PusTime` in ms
     (`shiftByMillis`, `millis + offset`), so it drops sub-ms ticks and compares at ms resolution.
     Change it to keep raw CUC ticks; otherwise boundary tests cannot catch a 1-tick mismatch.
3. **`source_id` = per satellite instance.** `pus11.sourceId` sits in each TC link's
   `commandPostprocessorArgs` inside that satellite's instance file (§4.6). If one satellite has
   several TC links, give them all the same value — and the same `pus11.subScheduleId.bytes` /
   `groupId.bytes`, since the parser needs one layout per instance. It is **no longer published**
   to cmdhist (was `pus11-source-id`) → read it from the TC[11,4] secondary header in
   `pus11Binary` (bytes 9–10), the same way as for manual `/PUS11/INSERT_ACTIVITIES`.
4. **Time correlation.** How it syncs today: `PusPacketPreprocessor.processTimePacket()` reads
   TM[9,2] (APID 0, no secondary header) → `TimeCorrelationService.addSample(obt, ert)`. This
   happens only with `performTimeCorrelation: true` plus a `goodFrameStream` (ERT comes from VC-0
   frames). Least squares over `numSamples` (default 3) gives `gradient`/`offset`, persisted in
   table `tco_<name>` and reloaded at start. Coefficients reset when deviation > `validity`. They
   can also be forced (`TimeCorrelationApi` → `forceCoefficients`). `../../examples/pus-frames`
   (new, `bf11c53c8`) enables it (`performTimeCorrelation: true`, `tco0`) → the test bed for TCO
   paths. `../../examples/pus` names `tcoService: tco0` but feeds no samples. `bf11c53c8` also
   fixed `CucTimeDecoder.decode()` to consume all fine-time bytes (it stopped after 2, which
   desynced the stream from `decodeRaw()`/`encode()`). `yamcs-core` `Pus9Service` is an on-board
   **emulator** (emits TM[9,2] from the ground clock), not the sync.
   **Uplink side:** the postprocessor now takes `tcoService`. When set, `buildScheduledTc` writes
   `tco.getObt(scheduleTime)` via `encodeRaw` (fails the command if there are no coefficients);
   otherwise it writes ground time shifted to `timeEncoding.epoch`. The tracker must decode
   `releaseObt` from the binary with the **same** mode: raw OBT → `tco.getTime(obt)`; epoch mode →
   inverse of `shiftToEpoch`. What the tracker uses:
   - current OBT: `tco.getObt(timeService.getMissionTime())`, for the sweep in OBT.
   - freshness: `tco.getStatus().getCoefficientsTime()`, plus the last entry of `getSamplesList()`.
     Show "TCO stale" when it is older than N passes.
   - display: `tco.getTime(releaseObt)` at render time, so the ground time follows
     re-correlation.
   - `getObt()` returns `Long.MIN_VALUE` when there are no coefficients: skip the sweep and the
     predicted-time display (inserts already fail in `buildScheduledTc`).
   - On-board clock reset (coefficients jump, or `tco.reset()`): mark the model stale and force a
     reconcile. `TimeCorrelationService` has no listener, so poll `getStatus()` on the sweep timer.
5. **Uplink loss** — handled by `PENDING` → `SCHEDULED` only on `CommandComplete = OK`.
6. **Multi-spacecraft = one Yamcs instance per satellite** (decided 2026-09-24). Each satellite
   has its own `yamcs.<sat>.yaml`, listed under `instances:` in `yamcs.yaml`. All instances share
   one `HttpServer`. Each instance gets its own yarch root (`<dataDir>/<instance>`: cmdhist, tm,
   `tco_*`, `pus11_schedule`), its own MDB, processors, links and TCO. So the tracker is **one
   service per instance, with no spacecraft partitioning**: the `RequestId` key space is already
   per satellite. The API stays `/api/pus11/{instance}/...`, and the web instance selector gives
   one satellite per window.
   - **Gotcha: `seqCounterName` is JVM-wide.** `CcsdsSeqCountFiller.namedSeqCounts` is a `static`
     map keyed by name only. If two instance files use the same `seqCounterName` (e.g.
     copy-pasted), those satellites share one counter.
     → Make the name unique per satellite (e.g. `seqCounterName: <sat>`).
   - **Gotcha: several TC links to one satellite** (multiple ground stations). Without a shared
     `seqCounterName`, each link counts from 0 on its own → inner `(apid, seq)` collisions on the
     same spacecraft (§4.1).
     → All TC links of one instance must share one `seqCounterName`.
   - Onboarding a satellite: instance templates (`YamcsServer` `instanceTemplates`,
     `InstancesApi` create) remove the per-satellite copy-paste. Optional.

---

## 5. Open questions

1. ~~**Reconcile cadence**~~ **Resolved 2026-10-19: once per pass, at AOS detected by TM gap.**
   - **No contact schedule:** none exists today, and link management is moving out of the MCS
     (`link-management-redesign-feasibility.md`). yamcs-core has no AOS hook either.
   - **No link-status poll:** with the gateway, the per-instance link is always up (§3 input 1).
   - **AOS = TM gap:** first `tm_realtime` packet after > `aosGap` (10 min) silence; reconcile after
     `aosSettle` (30 s). `aosGap` must exceed the longest in-pass dropout and be shorter than the
     shortest inter-pass gap. Ignore dump/playback streams so old data does not look like a pass.
   - **No pre-LOS reconcile:** in-pass changes are already confirmed by `CommandComplete` and
     release ACKs; reconcile exists to catch on-board changes, which happen between passes.
   - **Downlink-only pass:** skip the reconcile when no TC link is enabled; the model stays stale
     and the UI age shows it.
   - **Later:** a gateway session event (`POST /missions/{m}/sessions {aos, los}` or
     `/GS/<station>/status`) becomes a second `ReconcileTrigger` without changing the tracker.
   - **Still needs ops input:** max expected queue size, to size the TM[11,13] downlink per pass.
2. ~~**Parse TC[11,4] binary or cmdhist only?**~~ **Resolved 2026-10-19: binary.** cmdhist no
   longer carries source id or defaulted sub-schedule/group IDs (§1.2).
3. **Groups and sub-schedules** — **Partly resolved 2026-10-20, width resolved 2026-10-22; not
   blocking the build.**
   - **Confirmed (OBSW, Gowtham, Slack 2026-10-22):** TC[11,4] `subScheduleId` and `groupId` are
     both **uint8** (1 byte). Value ranges not decided yet.
   - **Confirmed (OBSW, Gowtham, Slack 2026-10-20):** the spacecraft supports both groups and
     sub-schedules; "a valid value needs to be filled". So both `pus11.subScheduleId` and
     `pus11.groupId` blocks are mandatory on every TC link (a missing block drops the field and the
     spacecraft misparses TC[11,4]). The §3 input 6 "will be dropped" flag is v1 scope.
   - **ECSS-E-ST-70-41C facts** (checked against the standard's PUS-11 clause):
     - Widths are mission-defined: Fig. 8-91 types both as "enumerated", no size.
     - No ID is reserved; 0 is not "none" by the standard.
     - A new sub-schedule is created **disabled** (§6.11.4.5j), so nothing in it releases until
       TC[11,20].
     - An unknown group rejects the insert (§6.11.4.5g.3), so the default group must exist on
       board first (TC[11,22]).
     - TC[11,3] reset deletes sub-schedules but only re-enables groups (§6.11.4.4c). The simulator
       already matches all three (`Pus11Service.java:45`, `:182`).
   - **Build assumptions until OBSW confirms:** 0 valid for both; group 0 created by the simulator
     at boot. (Width is confirmed, not assumed: uint8.) Tracker and tests read width and defaults
     from the link config, never hard-coded.
   - **Still open (config/MDB/ops only, no code):** (1) ~~width of each ID~~ uint8, 2026-10-22;
     (1b) valid value range of each ID (OBSW: not decided yet); (2) is 0 valid; (3) does
     OBSW create a default group at boot, or must ops send TC[11,22]; (4) one fixed sub-schedule ID
     or a new one per operation (new ones need TC[11,20]).
   - Store `groupId` / `subscheduleId` nullable regardless (class stays generic).
   Code check 2026-10-19: the repo is 1 byte everywhere (MDB `GroupIdType` 8 bits, simulator
   `bb.get()`, both example configs `groupId.bytes: 1`). **Gotcha:** TM[11,13] and TM[11,27]
   `group_id` are fixed at 8 bits in the MDB, while the postprocessor allows 1–8 bytes. Moot now
   that the spacecraft width is uint8 (2026-10-22): MDB and configs already match. Keep the note in
   case the width ever grows past 1 byte — then change the MDB too, not just the link config.
4. ~~**Schedule time margin**~~ **Deferred 2026-10-20** (§7) — mission value (§6.11.4.2d), only
   needed to predict insert/time-shift rejections before uplink. The tracker is correct without it:
   a rejected insert stays `PENDING` until the NACK, then drops. Ask OBSW for the value with the Q3
   questions. Code check 2026-10-19: simulator margin = 0. It rejects only `releaseTime < now`, and
   at the **completion** stage (`COMPL_ERR_SCHEDULE_TIME_IN_THE_PAST`, after `ack_start`).
   **Not deferred** (found in the ECSS check 2026-10-20; tracker correctness, not the margin value):
   - §6.11.4.5h: a rejected insert gives a **failed start** (TM[1,4]), not a completion failure.
     The tracker drops on NOK at either stage; the simulator moves its rejection to start.
   - §6.11.4.5i: an N > 1 insert is **partially accepted**: valid instructions go in despite faulty
     ones. A NACK on a manual `/PUS11/INSERT_ACTIVITIES` with N > 1 therefore does not mean "nothing
     inserted": keep those entries uncertain until the next reconcile, do not drop. The simulator
     (currently all-or-nothing) changes to match; add a tracker test. N = 1 (`pus11ScheduleAt`)
     is unaffected.
5. ~~**§6.11.11 observables**~~ **Deferred 2026-10-20** (§7). ECSS §6.11.11 requires: execution
   function status, activity count, and (if supported) sub-schedule and group counts. Code check
   2026-10-19: absent from both `pus11.xml` files; the simulator keeps `enabled` internally but never
   reports it. v1 relies on per-pass reconcile; the sub-schedule/group "will be dropped" flag stays
   in v1 (§3 input 6). Ask OBSW (with the Q3/Q4 list) whether these HK parameters exist, and their
   names/APID/packet. Design notes for later, see §7.
6. ~~**`pus11_schedule` table key**~~ **Resolved 2026-10-19: keep the current PK** — see §8.3.

---

## 6. Build order

0. **Done 2026-10-22** (`Pus11WrapperLayout`, `Pus11WrapperParser`, 20 tests).
   Expose the TC[11,4] wrapper layout (widths, `tcoService`, epoch) from `PusCommandPostprocessor`
   + `Pus11WrapperParser` for `pus11Binary`. ~½ day. Run the parser tests at 1-byte **and**
   2-byte sub-schedule/group widths (§5 Q3), so a later width change is config only.
   0b. Re-seed `CcsdsSeqCountFiller` from cmdhist on restart (§4.1). ~½ day incl. restart test.
   Independent of the tracker; can ship first.
1. Model + cmdhist input (`PENDING` → `SCHEDULED` on `CommandComplete`) + sweep. ~1 day
   **Done 2026-10-22:** `Pus11Schedule` (model) + `Pus11ScheduleTracker` (service; reads `pus11Binary` and
   `CommandComplete` from `cmdhist_realtime`, publishes the `pus11-release` ack: SCHEDULED, then TIMEOUT when
   presumed gone). Layout from the TC link's postprocessor (`tcLink`, or the only PUS 11 link). Sweep compares
   in ground time via the layout, so it follows re-correlation. 19 tests. Wired in `examples/pus-frames`.
   Not yet: skip-list index by release time, `pus11_schedule` persistence (§8), manual N > 1 inserts.
2. Sub-schedule/group "will be dropped" flag from TM[11,19]/TM[11,27]. ~2 h. Test: TC[11,4]
   into a new sub-schedule never enabled → flagged, then `PRESUMED_GONE` after release (§5 Q3).
   Simulator: create group 0 at boot. (Execution-disabled banner + count drift: deferred, §5 Q5.)
3. TM[11,13] reconcile incl. bit-packed entries and multi-packet accumulation. ~1 day
   Decode test reports through the MDB, so a > 1-byte `group_id` (§5 Q3 gotcha) fails a test.
   Plus TM-gap `ReconcileTrigger` (§5 Q1) with a replayed-gap test. ~½ day
   (on `pus-frames`, fix its MDB first — §1.8, ~30 min)
4. Release-ACK correlation on `/PUS/pus-tc-ack-*`, keyed on gentime. ~½ day
5. TC[11,3/5/6/7/8/15] modelling from the TC stream. ~2 days (all four filter types + packed
   request IDs — long pole). Filters and shifts in raw ticks (§4.2). Plus ~½ day to switch the
   simulator `PusTime` to ticks, with boundary tests at `start`/`end` ± 1 tick.
6. Timeline `ItemProvider`, then a dedicated page if too thin.

Steps 0–3 are the minimum useful set. Upstream cmdhist keys are done, but renamed and reduced
(§1.2).

---

## 7. Deferred

- **`ackflags = 0` commands** — v1 assumes every scheduled command produces a release ACK. Later:
  read the inner ack flags at insert → `expectsReleaseAck`; skip the sweep for those (state
  `AWAITING_RECONCILE`) and force a reconcile after their release time. Keep the field from day one.
- Predicting insert rejections from the time margin (Q4).
- Group modelling beyond storing `groupId` (Q3).
- **§6.11.11 drift detector (Q5).** Execution-disabled banner (scheduler disabled at boot and after
  TC[11,3] → every release is silently dropped, §6.11.4.6) + activity-count drift check every HK
  cycle. When picked up: add the HK packet to the simulator and example MDB first; read parameter
  names from tracker config (e.g. `executionStatusParam`, `activityCountParam`), never hard-code;
  compare against `SCHEDULED` entries only and flag only if the mismatch persists 2 HK cycles
  (`PENDING` and just-released entries lag); count 0 + status disabled = schedule wiped (reboot or
  reset) → force a reconcile, not ordinary drift. ~2 h.

---

## 8. Search command history by release time

Mandatory: search time-tagged commands by **release time** and **command name**.

### 8.1 Why cmdhist can't do it

`cmdhist` PK is `(gentime, origin, seqNum)`; `pus11ScheduleAt` is a dynamic value column, so
filtering on it decodes every row in range. `histogram(cmdName)` is not an index. A secondary index
needs the column at `create table`, one per table, and leaks entries on `upsert_append`.

### 8.2 Options

| | A. Side table (**chosen**) | B. Scan cmdhist | C. Index cmdhist |
|---|---|---|---|
| Speed | range seek | row decode | range seek |
| `INSERT_ACTIVITIES` (N times) | yes | no | no |
| Migration | none | none | rewrite cmdhist |

### 8.3 Decisions

- Side table `pus11_schedule`, one row per activity; not partitioned (rows are updated).
- Search matches the **current** release time.
- Scope: `pus11ScheduleAt` and manual `/PUS11/INSERT_ACTIVITIES`. "Type" = MDB command name.
- UI: separate "Scheduled commands" page.
- **PK (decided 2026-10-19):** `(releaseTime, gentime, origin, seqNum, entryIdx)`, re-keyed
  (delete + insert) on every shift. The 2026-09-17 alternative (`releaseTime` as a value column +
  secondary index) is rejected: yarch writes secondary-index entries only on insert
  (`RdbTableWriter.writeToDb`), and `RdbTableWalker.executeAction` (UPDATE_VAL / UPDATE_ROW /
  DELETE) never touches the index, so every shift or delete would leave a stale index entry.
  Precedent: `TimelineItemDb` uses `primary key(start, uuid)`, and `updateItem()` does
  `doDeleteItem` + re-emit, with an in-memory cache by UUID for lookups. Mirror that: the
  tracker's `Map<RequestId, ScheduleEntry>` (§3) supplies the old key for the delete.

### 8.4 Design

**Columns**: `cmdName, releaseTime, releaseObt, sourceId, apid, seqCount, pusType, pusSubtype,
subscheduleId, groupId (nullable LONG), state (PENDING|SCHEDULED|DELETED), innerBinary`. `(gentime, origin, seqNum)`
links to cmdhist.

**`Pus11ScheduleRecorder`** (`yamcs-core/.../pus/`):
1. Subscribe to CMD_HIST; cache first tuple per `CommandId`.
2. `pus11ScheduleAt` set → inner ID from `pus11-inner-*`, everything else from `pus11Binary`
   (§1.2); else PUS type 11 → parse `binary`.
3. `Pus11TcParser` ported from `Pus11Service`: TC[11,4], by-ID [11,5]/[11,7], by-filter
   [11,6]/[11,8], [11,15] shift-all, [11,3] reset. Sub-schedule/group widths from the link layout
   (§6 step 0); request IDs bit-packed `u16/u11/u14` via `BitBuffer` (port `PusPackedFields`).
   Release time: `CucTimeDecoder.decodeRaw` → `TimeCorrelationService.getTime(obt)` when the
   postprocessor has `tcoService`, else inverse epoch shift (extract `configureTimeEncoding` /
   `shiftToEpoch` from the postprocessor into a shared helper).
4. Insert → `PENDING`; `SCHEDULED` on `CommandComplete = OK`, deleted on `NOK`.
   Deletes/shifts/reset applied on their own `CommandComplete = OK`, against `SCHEDULED` rows with
   `releaseTime > TC gentime`.

**API** `GET /api/pus11/{instance}/scheduled-commands` (`start`/`stop` on release time, `q`,
`state`, `order`, `limit`, `next`); proto mirroring `timeline/timeline.proto`; paging like
`CommandsApi.listCommands`; register via `addApi` in `HttpServer`.

**Web** — client method next to `getCommandHistoryEntries` in `YamcsClient.ts`; component
`commanding/scheduled-commands/` reusing `command-history-list.component.ts` filters; route
`scheduled` in `commanding.routes.ts`; nav item in `instance-layout.component.ts`. Rows link to
`history/:commandId`.

### 8.5 Caveats

1. Manual inserts carry `/PUS11/INSERT_ACTIVITIES` + PUS type/subtype; inner name not resolved.
2. Releases aren't tracked until §3 exists — grey out `releaseTime < now`.
3. No backfill of pre-deployment commands.

### 8.6 Build order

1. Table + recorder + parser (inserts; needs §6 step 0) — ~½ day
2. Deletes / shifts / reset — ~½ day
3. API — ~2 h
4. Web page — ~½ day

The live tracker (§3) later reuses `pus11_schedule` as its persistence.
