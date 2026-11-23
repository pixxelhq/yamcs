# Decisions and assumptions log

One place for every decision or assumption made while building features, across all features.
Each entry says what was assumed, why, what it affects, and when to recheck it. When the real
answer arrives, change Status and fix the code or config listed under "Affects".

Entry format:
- **ID** — `<feature-prefix>-<n>` (e.g. `TTC-B2`). Keep IDs stable after they are used.
- **Status** — `Assumed` (used now, not confirmed), `Confirmed <date>`, `Deferred`, or `Wrong, fixed <date>`.
- **Owner** — who can confirm.

Add a new feature as a new `## <Feature>` section. Use the same fields for every entry.

---

## Time-tagged commands (PUS11)

Source: `time-tag-queue-plan.md` §6.1. Prefix `TTC`.

| ID | Topic | Assumption used now | Status | Owner |
|---|---|---|---|---|
| TTC-B1 | TM[11,13] split over packets, CCSDS grouping flags | Multi-packet, grouping flags first/continuation/last. Reconcile reassembles by flags. A single packet still works | Assumed | OBSW / FSW ICD |
| TTC-B2 | Request-ID field widths and valid ranges | source ID 16 bit, APID 11 bit, seq count 14 bit, `n` uint16. All values valid, no reserved values | Assumed | Project, confirm with OBSW |
| TTC-B3 | 1 ms gap between on-board and ground release time | Tick rounding. On-board time is authoritative. Logged as `retimed`, entry still matches | Assumed | Us (needs a run with known OBT) |
| TTC-B4 | Sub-schedule/group ID ranges, 0 valid?, default group at boot | Both uint8 (confirmed 2026-10-22). 0 valid for both. Simulator creates group 0 at boot. One fixed sub-schedule per link | Assumed (width confirmed) | OBSW (Gowtham) |
| TTC-B5 | Time margin value, §6.11.11 HK parameter names | Margin = 0 (simulator value). No insert-rejection prediction. Count check off until names exist | Deferred | OBSW |
| TTC-B6 | Max queue size per pass | No fixed cap in code. Tests use up to 50 entries. Sizing uses ~248 bytes user data per packet | Assumed (placeholder) | Ops |
| TTC-B7a | Incomplete-packet policy (ICD Appendix 3 vs §7.1) | "Required": a lost packet drops the whole TM[11,13] for that pass; next pass retries | Assumed | FSW ICD owner (Yogesh Modi) |
| TTC-B7b | TM frame length (ICD §4.1 1020 vs Appendix 3 980) | 1020 bytes. Affects frame config only | Assumed | FSW ICD owner |
| TTC-B7c | TM time field width (ICD §6.2 8 bytes vs Appendix 4 CUC 4+2) | CUC 4+2 = 6 bytes. Width kept in one MDB setting so it can be flipped | Assumed | FSW ICD owner |
| TTC-D1 | Inner request ID source after the merge with `pixxel/master` (option 1) | `ccsds-apid`/`ccsds-seqcount` hold the original command's IDs, as master's `651a792c4` defines. `pus11-inner-*` / `pus22-inner-*` removed; wrapper ID only in `pus11Apid`/`pus11CcsdsSeqCount`. Tracker release key read from `ccsds-*`. Affects: `Pus11ScheduleTrackerTest`, `test-pus11*.py`, plan §1.2/§1.3/§1.7, `pus-frames/README.md`, `PusCommandPostprocessor.getPus11WrapperLayout()`/`isPus11Enabled()`. Verify: end-to-end `examples/pus-frames` run (not yet done) | Assumed (merge decision, 2026-11-20) | Us, then FSW ICD owner if on-board verifiers depend on it |

**Details**

- **TTC-B1** — Affects: `TmGapReconcileTrigger`, `Pus11SummaryReport` (multi-packet assembly), MDB `grouping` (`examples/pus-frames/.../pus11.xml`). Simulator still sends one packet. Verify: is it split, which flag values. If not split, keep assembly as dead-safe code and note it.
- **TTC-B2** — Affects: MDB `dt.xml`, `PusPackedFields`, TM[11,13] decode, TC[11,5/7/9/12] parsing (must use `BitBuffer`). Verify: exact widths, reserved values. If a width changes, change the MDB types too, not only link config.
- **TTC-B3** — Affects: `Pus11ScheduleTracker` retimed classification, trust flag on `retimed` entries, filter boundary tests (step 5). Only observed case: step 3 manual run (`1 retimed`). If the gap is a conversion error (plan §4.4), fix the conversion and drop the tolerance.
- **TTC-B4** — Affects: link config (`subScheduleId`, `groupId` bytes), simulator boot (`Pus11Service`), tests. Tracker reads width and defaults from link config, never hard-coded. Verify: valid ranges, is 0 valid, default group at boot or must ops send TC[11,22], fixed vs per-operation sub-schedules.
- **TTC-B5** — Affects: future insert pre-check and count check (plan §7). Nothing in current build. A rejected insert stays `PENDING` until NACK, then drops.
- **TTC-B6** — Affects: tests (add a 50-entry case), ops sizing note in `pus.rst`. Verify: real per-pass maximum.
- **TTC-B7a–c** — Affects: TM[11,13] release-time decode (plan §1.6), MDB time parameter, frame config. Verify: owner sign-off in the FSW ICD table; update `jty-fsw-icd-summary.md` §11.

**Crosscheck steps when answers arrive**

1. Set Status and date for each answered entry.
2. For a wrong assumption, change the code/config listed under Affects.
3. Re-run `Pus11ScheduleTrackerTest`, `Pus11SummaryReportTest`, `Pus11ScheduleTest`, `examples/pus-frames/tests/test-pus11-reconcile.py`.
4. Update `time-tag-queue-plan.md` §6.1 and `pus.rst` "Schedule tracker" (B1/B7 caveats).

---

<!-- Add new feature sections above this line. -->
