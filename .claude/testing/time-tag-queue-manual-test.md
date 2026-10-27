# Manual test: time-tag queue (§6 step 1) + simulator exit-with-parent

Branch `feat/time-tagged-commands`. Covers the uncommitted §6 step 1 work of
`.claude/plans/time-tag-queue-plan.md`: `Pus11Schedule`, `Pus11ScheduleTracker`, its wiring in
`examples/pus-frames`, and `SimulatorCommander --exit-with-parent`. About 15 min.

Last run: 2026-10-27, all PASS (after the pus-frames MDB request-ID and PUS5 PMON fixes).

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

Not covered (not built): restart persistence (the in-memory model starts empty), TM[11,13]
reconcile, manual `/PUS11/INSERT_ACTIVITIES` with N > 1.

## 4. Simulator exits with Yamcs (~15 s)

With the example from step 2 still running:

```
.claude/testing/check-sim-exit-with-parent.sh
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
  simulator rejects inserts into missing groups. Both test scripts create it first.
- **yamcs-client:** `command.await_acknowledgment("CommandComplete")` never returns; use
  `command.await_complete()`.
- **Clean-up:** a SIGTERM on an orphaned simulator can take a while: its shutdown hook waits in
  `ServiceManager.awaitStopped` while a failing `UdpTcFrameLink` calls `System.exit(1)`. Existing
  behaviour, not from these changes.
