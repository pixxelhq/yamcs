# PUS over CCSDS frames

This example carries the same PUS simulator as the [`pus`](../pus) example, but over **CCSDS
transfer frames** instead of a raw TCP packet link:

- **Telemetry**: CCSDS TM transfer frames (CCSDS 132.0-B), one virtual channel (VC 0), OCF
  (CLCW) present, CRC-16 frame error control. Received by `org.yamcs.tctm.ccsds.UdpTmFrameLink`
  on UDP port 10017.
- **Telecommand**: COP-1 / FOP-1 with BCH-encoded CLTUs, sent by
  `org.yamcs.tctm.ccsds.UdpTcFrameLink` on UDP port 10018. The FARM side lives in the simulator
  and its CLCW is reported back in every TM frame OCF, closing the COP-1 loop.

The main reason this example exists is **time correlation**. The simulator on-board clock is a
free-running clock; the ground recovers UTC by correlating the on-board time reported in the
ST[9] time packets against the *earth reception time* of the frame that carried them:

- the TM frame link publishes each good frame (with its reception time) on `good_frame_stream`;
- `PusPacketPreprocessor` is configured with `performTimeCorrelation: true` and
  `goodFrameStream: good_frame_stream`; for every ST[9] time packet it feeds an
  `(on-board time, earth reception time)` sample to the `tco0` `TimeCorrelationService`;
- after `numSamples` (4) samples `tco0` computes the correlation and from then on stamps every
  packet's generation time from it. Before that (and whenever the simulator restarts and its
  clock jumps back) it falls back to `earth reception time - delays` and re-correlates.
- `saveCoefficients: false` keeps each run independent, since the simulator clock restarts with
  the simulator.

The `PusCommandPostprocessor` also uses `tco0` here: when a command is scheduled with the
`pus11ScheduleAt` option, the TC[11,4] release time is converted from UTC to on-board time using
the correlation.

The `Pus11ScheduleTracker` service keeps a ground model of the on-board PUS 11 schedule from the
commands scheduled with `pus11ScheduleAt`, and publishes a `pus11-release` acknowledgment on each
of them: `SCHEDULED` once the TC[11,4] completes, `TIMEOUT` when no release is confirmed within
`releaseGrace` (60 s here) of the release time. The model is in memory only: it starts empty after
a restart.

## Running

```
./run-example.sh pus-frames
```

`yamcs.yaml` sets `dataDir: /storage/yamcs-data`. Where `/storage` is not writable (e.g. macOS),
override it; the path is relative to `examples/pus-frames/target/yamcs` and must not contain spaces:

```
./run-example.sh pus-frames --data-dir yamcs-data
```

Then, once a correlation has been established (`curl localhost:8090/api/tco/pus-frames/tco0/status`
shows `coefficients`, about 30 s after start), try the PUS 11 tests. They need
`pip install yamcs-client`.

```
python3 examples/pus-frames/tests/test-pus11.py
python3 examples/pus-frames/tests/test-pus11-tracker.py
python3 examples/pus-frames/tests/test-pus11-dropflag.py
python3 examples/pus-frames/tests/test-pus11-groups.py
```

The simulator creates no scheduling group at boot (ECSS-E-ST-70-41C §6.11.6), and rejects inserts
into a group that does not exist. Each test creates the groups it needs with TC[11,22]
(`/PUS11/CREATE_GROUPS`) if they are missing and checks them with TM[11,27], through
`tests/pus11_setup.py`. Group 1 is the TC link default; the other tests use their own group IDs
(listed in `pus11_setup.py`), so the tests can run in any order and again on one simulator.

`test-pus11.py` enables the execution function (TC[11,1]), then checks that an activity in a
disabled scheduling group is withheld and one in an enabled group is listed in the detail report
and released (a TM[1,x] for its request ID arrives); it exits non-zero on a mismatch.
`test-pus11-tracker.py` checks the `pus11-release` acknowledgment (rejected insert, `SCHEDULED`,
then `TIMEOUT`) and exits non-zero on a mismatch; it takes about 90 s. The tracker logs each
transition (`PENDING`, `SCHEDULED`, `DELETED`, `PRESUMED_GONE`) in the Yamcs log.
`test-pus11-dropflag.py` checks that `pus11-release` turns `DISABLED` ("Will be dropped at …") once
a TM[11,19] or TM[11,27] reports the command's sub-schedule or group disabled, and back to
`SCHEDULED` once it is reported enabled; it takes about 2 minutes.
`test-pus11-groups.py` checks TC[11,22] end to end: groups created enabled or disabled, a create
naming an existing group rejected as a whole, inserts rejected until the group is created,
TC[11,23] rejected while a group has activities, then the test groups deleted again.

The simulator is started with `--exit-with-parent`, so it stops when Yamcs is killed (even with
`kill -9`) instead of keeping its UDP ports. `tests/check-sim-exit-with-parent.sh` checks this: run it
from the repository root with the example running; it kills Yamcs and expects the simulator to exit
within 30 s.

The TC packets embedded in commands are demonstrated by:
- `test-pus11-embedded.py`: a TC[11,4] built by hand (`/PUS11/INSERT_ACTIVITIES`) with a list of
  activities
- `test-pus19-embedded.py`: an ST[19] event-action (`/PUS19/ADD_EVENT_ACTIONS`)

Both take the embedded commands from a dry run. See "Embedded TC packets" in the
[`pus` README](../pus/README.md).

```
python3 examples/pus-frames/tests/test-pus11-embedded.py
python3 examples/pus-frames/tests/test-pus19-embedded.py
```

## Notes

- This example demonstrates ST[11] (time-based scheduling). The [`pus`](../pus) example
  demonstrates ST[22] (position-based scheduling) instead 
- The simulator sends TM frames at 10 Hz; if it has no data it sends idle frames.
- The simulator rejects a TC[11,4] into a scheduling group that does not exist. The TC link
  default group is 1: create it first (`/PUS11/CREATE_GROUPS`), as both test scripts do.
- The simulator starts with the time-based schedule execution function disabled, so released
  activities are dropped ("the time-based schedule execution function is disabled") until
  TC[11,1] enables it (`/PUS11/ENABLE_SCHEDULER`, sent by `test-pus11.py`).
- An on-board activity is identified by the request ID of the embedded command, not of the
  TC[11,4] wrapper. Use the `pus11-inner-seqcount` command attribute (not `ccsds-seqcount`) in
  delete, time-shift and report-by-ID requests.
