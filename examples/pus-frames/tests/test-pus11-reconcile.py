#!/usr/bin/env python3
"""
Manual test for the TM(11,13) reconcile of the Pus11ScheduleTracker (plan §6 step 3).

The tracker asks for the complete on-board schedule with TC(11,17) and reads the TM(11,13) summary
report back. The report is the authority over the ground model. Scenario:
  1. schedule activity A (sub-schedule 1, group 1) -> pus11-release = SCHEDULED
  2. delete A on board with TC(11,5), which the tracker does not model: a silent drop. The model
     still believes A is scheduled
  3. schedule activity B -> SCHEDULED
  4. TC(11,17) -> TM(11,13) lists only B. Reconcile: A is missing before its release time, so
     pus11-release = TIMEOUT "Not on board at reconcile, before its release time: dropped"; B stays
     SCHEDULED

The Yamcs log shows, in addition:
  "Reconciled with the on-board schedule of 1 activities: 0 confirmed, 1 missing, 0 released, 0 unknown"
and no "Cannot read the on-board schedule" warning.

Not covered here: the TM-gap trigger (needs a silence longer than aosGap, 600 s by default), a
multi-packet report (the simulator sends one packet), and UNKNOWN entries.

Run the example first:  ./run-example.sh pus-frames --data-dir yamcs-data
and wait until the time correlation is established (curl localhost:8090/api/tco/pus-frames/tco0/status
shows "coefficients"), otherwise the release time cannot be converted to on-board time.

Exits non-zero if an expectation fails.
"""

import os
import sys
import time
from datetime import datetime, timedelta, timezone

from yamcs.client import YamcsClient

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pus11_setup import BASE_GROUP, GroupStatus, ensure_groups, issue  # noqa: E402

RELEASE_KEY = "pus11-release"
SUB_SCHEDULE_ID = 1
SOURCE_ID = 0  # request IDs of the simulator's TC link, as in test-pus11-groups.py
APID = 1  # the pus-frames TC link's pus11 APID of the commanded activities (SWITCH_VOLTAGE_OFF)
DELAY_A = 150  # seconds from now to the release time of A
DELAY_B = 240  # seconds from now to the release time of B
REPORT_TIMEOUT = 20  # seconds to wait for the TM(11,13) reply and the reconcile


def check(what, actual, expected):
    print(f"  {what}: {actual} (expected {expected})")
    if actual != expected:
        sys.exit(f"FAIL: {what}")


def schedule(cmd_conn, delay):
    t = datetime.now(timezone.utc) + timedelta(seconds=delay)
    t_str = t.strftime("%Y-%m-%dT%H:%M:%S.001Z")
    command = cmd_conn.issue(
        "/SIMULATOR/SWITCH_VOLTAGE_OFF",
        args={"voltage_num": 1},
        extra={"pus11ScheduleAt": t_str, "pus11SubScheduleId": SUB_SCHEDULE_ID, "pus11GroupId": BASE_GROUP},
    )
    command.await_complete(timeout=15)
    if not command.is_success():
        sys.exit(f"FAIL: insert not accepted: {command.error}")
    seq = command.attributes["pus11-inner-seqcount"]
    print(f"Scheduled SWITCH_VOLTAGE_OFF at {t_str} (inner seq count {seq})")
    return command, seq


def release_ack(command):
    ack = command.acknowledgments.get(RELEASE_KEY)
    return (ack.status, ack.message) if ack else (None, None)


def wait_release_status(command, status, timeout=10):
    deadline = time.time() + timeout
    while time.time() < deadline and release_ack(command)[0] != status:
        time.sleep(0.2)
    return release_ack(command)


if __name__ == "__main__":
    client = YamcsClient("localhost:8090")
    processor = client.get_processor("pus-frames", "realtime")
    cmd_conn = processor.create_command_connection()

    print("0. setup: base group enabled, schedule enabled")
    ensure_groups(cmd_conn, GroupStatus(processor), {BASE_GROUP: "enabled"})
    issue(cmd_conn, "/PUS11/ENABLE_SCHEDULE", {"num_schedules": 1, "schedules": [SUB_SCHEDULE_ID]})

    print("1. schedule A")
    a_cmd, a_seq = schedule(cmd_conn, DELAY_A)
    status, _ = wait_release_status(a_cmd, "SCHEDULED")
    check(f"A {RELEASE_KEY}", status, "SCHEDULED")

    print("2. delete A on board (not seen by the tracker)")
    issue(cmd_conn, "/PUS11/DELETE_ACTIVITIES_BY_ID", {
        "num_requests": 1,
        "requests": [{"source_id": SOURCE_ID, "apid": APID, "seqcount": a_seq}],
    })
    status, _ = release_ack(a_cmd)
    check(f"A {RELEASE_KEY} still SCHEDULED in the model", status, "SCHEDULED")

    print("3. schedule B")
    b_cmd, _ = schedule(cmd_conn, DELAY_B)
    status, _ = wait_release_status(b_cmd, "SCHEDULED")
    check(f"B {RELEASE_KEY}", status, "SCHEDULED")

    print("4. request the on-board schedule (TC(11,17)) and wait for the reconcile")
    issue(cmd_conn, "/PUS11/GET_SUMMARY_REPORT")
    status, message = wait_release_status(a_cmd, "TIMEOUT", timeout=REPORT_TIMEOUT)
    if status != "TIMEOUT":
        sys.exit("FAIL: A not reported missing; check the Yamcs log for 'Cannot read the on-board schedule'")
    check(f"A {RELEASE_KEY}", status, "TIMEOUT")
    check("A message", "Not on board at reconcile" in (message or ""), True)

    status, _ = release_ack(b_cmd)
    check(f"B {RELEASE_KEY} after reconcile (on board)", status, "SCHEDULED")

    print("PASS - the Yamcs log shows 'Reconciled ... 0 confirmed, 1 missing, 0 released, 0 unknown'")
