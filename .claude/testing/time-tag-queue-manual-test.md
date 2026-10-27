# Manual test: time-tag queue (§6 step 1) + simulator exit-with-parent

Branch `feat/time-tagged-commands`. Covers the uncommitted §6 step 1 work of
`.claude/plans/time-tag-queue-plan.md`: `Pus11Schedule`, `Pus11ScheduleTracker`, its wiring in
`examples/pus-frames`, and `SimulatorCommander --exit-with-parent`. About 15 min.

Last run: 2026-10-27, all PASS (after the pus-frames MDB request-ID and PUS5 PMON fixes, and with
the §6 step 2 drop flag).

## 0. One-time setup

```
python3 -m venv .claude/testing/venv
.claude/testing/venv/bin/pip install yamcs-client
```

`.claude/testing/venv` is local only; do not commit it.

## 1. Build (~2 min)

```
mvn -q -pl simulator,examples/pus-frames -am install -DskipTests
```

## 2. Start the example

```
./run-example.sh pus-frames --data-dir yamcs-data
```

- `--data-dir` is needed because `yamcs.yaml` points at `/storage/yamcs-data` (read-only on macOS).
  The path is relative to `examples/pus-frames/target/yamcs`; an absolute path with a space (this
  repo lives under `GSS Workspace`) is split by `run-example.sh` and fails with "Was passed main
  parameter …".
- Pass: log shows `Awaiting start of service Pus11ScheduleTracker` and `Yamcs started`, no
  "No TC link with a PusCommandPostprocessor configured for PUS 11".

Wait for time correlation (about 30 s):

```
curl -s localhost:8090/api/tco/pus-frames/tco0/status | grep coefficients
```

## 3. Tracker test (~90 s)

```
.claude/testing/venv/bin/python -I examples/pus-frames/tests/test-pus11-tracker.py
```

Pass: ends with `PASS`, exit code 0. Expected Yamcs log, in order:

| Step | Log (`Pus11ScheduleTracker`) | `pus11-release` ack |
|---|---|---|
| Insert into group 99 (missing) | `PENDING`, then simulator `Rejecting insert request: scheduling group 99 does not exist`, then `DELETED` | none |
| Insert into default group 1 | `PENDING, release at …`, then `SCHEDULED` | `SCHEDULED` "Release at …" |
| Release + 60 s grace + ≤10 s sweep | `PRESUMED_GONE, no release ACK 60 s after …` | `TIMEOUT` |

`TIMEOUT` is expected even when the simulator releases the command: release-ACK correlation is
§6 step 4. If the simulator was started fresh and `test-pus11.py` has not run, it also logs
`Dropping command … time-based schedule execution function is disabled` at the release time: it
boots with execution disabled (TC[11,1] enables it). Not a tracker issue.

Groups test (~30 s), same running example:

```
.claude/testing/venv/bin/python -I examples/pus-frames/tests/test-pus11.py
```

Pass: ends with `PASS`. Simulator log shows `Dropping command … scheduling group 5 is disabled`,
`Filter by ID source: 0, apid: 1, seqCount: <inner>` / `1 commands matched`, then `Executing
command`.

Drop-flag test (~2 min, plan §6 step 2), same running example:

```
.claude/testing/venv/bin/python -I examples/pus-frames/tests/test-pus11-dropflag.py
```

Pass: ends with `PASS`. Expected:

| Step | Log (`Pus11ScheduleTracker`) | `pus11-release` ack |
|---|---|---|
| Insert into disabled sub-schedule 7, then TC[11,18] | `will be dropped at …: sub-schedule 7 is disabled` | `SCHEDULED`, then `DISABLED` |
| Insert into group 6, disable it, TC[11,26] | `will be dropped at …: group 6 is disabled` | `DISABLED` |
| Enable group 6, TC[11,26] | none | `SCHEDULED` |
| Release + 60 s grace + ≤10 s sweep | `PRESUMED_GONE …` | `TIMEOUT` |

The flag follows the status reports only: the tracker does not request TM[11,19]/TM[11,27] and does
not model TC[11,20/21/24/25] yet, so after an enable/disable the flag is stale until the next report.

Groups test (~10 s), TC[11,22] end to end, same running example:

```
.claude/testing/venv/bin/python -I examples/pus-frames/tests/test-pus11-groups.py
```

Pass: ends with `PASS`. Covers: TC[11,22] creates groups 20 (enabled) and 21 (disabled) →
TM[11,27]; a TC[11,22] for an existing group, and one for [22, 20], are rejected and group 22 is not
created; an insert into group 23 is rejected until TC[11,22] creates it; an insert into group 21
gets `pus11-release` `DISABLED`; TC[11,23] on group 21 is rejected while it has an activity;
TC[11,5] + TC[11,23] remove everything again. Runs again without a restart.

All the tests create the groups they need with TC[11,22] via
`examples/pus-frames/tests/pus11_setup.py` (`ensure_groups`), so the order of the tests does not
matter.

Not covered (not built): restart persistence (the in-memory model starts empty), TM[11,13]
reconcile, manual `/PUS11/INSERT_ACTIVITIES` with N > 1.

## 4. Simulator exits with Yamcs (~15 s)

With the example from step 2 still running:

```
examples/pus-frames/tests/check-sim-exit-with-parent.sh
```

Pass: `PASS: simulator exited ~N s after Yamcs was killed` (N was 10 on 2026-10-22: the JDK polls
a non-child parent with a growing interval, then the shutdown hook stops the services).
Then `./run-example.sh pus-frames --data-dir yamcs-data` again must start without a port error.

## Findings from the runs

- **JCommander 3.0 toggles booleans.** With `exitWithParent = true` as the default, passing
  `--exit-with-parent` set it to *false*, so the watcher was never installed and the simulator
  outlived `kill -9`. Fixed 2026-10-22: the default is now `false`; `SimulatorCommander` passes
  the flag. Step 4 checks for the `process reaper` thread to catch a regression.
- **Group 1 must exist.** The TC link default group is 1 (`pus11 → groupId → default: 1`); the
  simulator rejects inserts into missing groups. Every test script creates it with TC[11,22] if
  missing (`pus11_setup.ensure_groups`).
- **The tracker keeps the last TM[11,19]/TM[11,27] while Yamcs runs.** An insert into a
  sub-schedule or group already reported disabled is `DISABLED` at once, with no `SCHEDULED` first.
  `test-pus11-dropflag.py` asks for the report before inserting, so a rerun gives the same result.
- **yamcs-client:** `command.await_acknowledgment("CommandComplete")` never returns; use
  `command.await_complete()`.
- **Clean-up:** a SIGTERM on an orphaned simulator can take a while: its shutdown hook waits in
  `ServiceManager.awaitStopped` while a failing `UdpTcFrameLink` calls `System.exit(1)`. Existing
  behaviour, not from these changes.
