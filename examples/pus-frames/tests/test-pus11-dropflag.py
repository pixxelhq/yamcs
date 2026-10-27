#!/usr/bin/env python3
"""
Manual test for the "will be dropped" flag of the Pus11ScheduleTracker (plan §6 step 2).

On board, an activity whose sub-schedule or group is disabled at its release time is deleted
without being executed and without any report (ECSS-E-ST-70-41C §6.11.4.6). The tracker reads the
TM[11,19] sub-schedule and TM[11,27] group status reports and publishes the "pus11-release"
acknowledgment as DISABLED ("Will be dropped at ...") while the sub-schedule or group is reported
disabled, and back to SCHEDULED once it is reported enabled. Scenario:
  1. disabled sub-schedule: disable sub-schedule 7, ask for TM[11,19], schedule into it
     -> pus11-release = DISABLED "sub-schedule 7 is disabled" at once (the status is already known);
     after release time + releaseGrace -> TIMEOUT (presumed gone)
  2. disabled group: schedule into sub-schedule 1 / group 6 (reported enabled by the setup)
     -> SCHEDULED; disable group 6, ask for TM[11,27] -> DISABLED "group 6 is disabled"; enable
     group 6, ask again -> SCHEDULED

The tracker keeps the last reports for as long as Yamcs runs, so each case first makes the
reported status known, whatever an earlier run left.

Groups 1 and 6 are created with TC[11,22] if missing (pus11_setup.py). Sub-schedule 7 is disabled
explicitly first, so that the test can be run again: the simulator
creates the sub-schedule of an insert disabled (§6.11.4.5j), but keeps it once created.

Run the example first:  ./run-example.sh pus-frames --data-dir yamcs-data
and wait until the time correlation is established (curl localhost:8090/api/tco/pus-frames/tco0/status
shows "coefficients"), otherwise the release time cannot be converted to on-board time.

Exits non-zero if an acknowledgment is not the expected one.
"""

import os
import sys
import time
from datetime import datetime, timedelta, timezone

from yamcs.client import YamcsClient

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pus11_setup import BASE_GROUP, GroupStatus, ensure_groups, issue  # noqa: E402

RELEASE_KEY = "pus11-release"
DISABLED_SUB_SCHEDULE = 7
GROUP_ID = 6
DELAY = 30  # seconds from now to the release time; leaves time for the group run
RELEASE_GRACE = 60  # Pus11ScheduleTracker releaseGrace in yamcs.pus-frames.yaml
SWEEP_INTERVAL = 10  # Pus11ScheduleTracker default sweepInterval


def check(what, actual, expected):
    print(f"  {what}: {actual} (expected {expected})")
    if actual != expected:
        sys.exit(f"FAIL: {what}")


def schedule(cmd_conn, sub_schedule_id, group_id):
    t = datetime.now(timezone.utc) + timedelta(seconds=DELAY)
    t_str = t.strftime("%Y-%m-%dT%H:%M:%S.001Z")
    command = cmd_conn.issue(
        "/SIMULATOR/SWITCH_VOLTAGE_OFF",
        args={"voltage_num": 1},
        extra={"pus11ScheduleAt": t_str, "pus11SubScheduleId": sub_schedule_id, "pus11GroupId": group_id},
    )
    command.await_complete(timeout=15)
    if not command.is_success():
        sys.exit(f"FAIL: insert not accepted: {command.error}")
    print(f"Scheduled SWITCH_VOLTAGE_OFF at {t_str} (sub-schedule {sub_schedule_id}, group {group_id})")
    return command, t


def release_ack(command):
    ack = command.acknowledgments.get(RELEASE_KEY)
    return (ack.status, ack.message) if ack else (None, None)


def wait_release_status(command, status, timeout=5):
    deadline = time.time() + timeout
    while time.time() < deadline and release_ack(command)[0] != status:
        time.sleep(0.2)
    return release_ack(command)


if __name__ == "__main__":
    client = YamcsClient("localhost:8090")
    processor = client.get_processor("pus-frames", "realtime")
    cmd_conn = processor.create_command_connection()

    ensure_groups(cmd_conn, GroupStatus(processor), {BASE_GROUP: "enabled", GROUP_ID: "enabled"})
    issue(cmd_conn, "/PUS11/ENABLE_SCHEDULE", {"num_schedules": 1, "schedules": [1]})

    # --- 1. disabled sub-schedule ---
    issue(cmd_conn, "/PUS11/DISABLE_SCHEDULE", {"num_schedules": 1, "schedules": [DISABLED_SUB_SCHEDULE]})
    issue(cmd_conn, "/PUS11/GET_SCHEDULE_STATUS")
    sub_cmd, sub_t = schedule(cmd_conn, DISABLED_SUB_SCHEDULE, BASE_GROUP)
    status, message = wait_release_status(sub_cmd, "DISABLED")
    check(RELEASE_KEY, status, "DISABLED")
    check("message mentions the sub-schedule", f"sub-schedule {DISABLED_SUB_SCHEDULE} is disabled" in message, True)

    # --- 2. disabled, then enabled group ---
    group_cmd, _ = schedule(cmd_conn, 1, GROUP_ID)
    check(RELEASE_KEY, wait_release_status(group_cmd, "SCHEDULED")[0], "SCHEDULED")
    issue(cmd_conn, "/PUS11/DISABLE_GROUPS", {"num_groups": 1, "group_ids": [GROUP_ID]})
    issue(cmd_conn, "/PUS11/GET_GROUP_STATUS")
    status, message = wait_release_status(group_cmd, "DISABLED")
    check(RELEASE_KEY, status, "DISABLED")
    check("message mentions the group", f"group {GROUP_ID} is disabled" in message, True)

    issue(cmd_conn, "/PUS11/ENABLE_GROUPS", {"num_groups": 1, "group_ids": [GROUP_ID]})
    issue(cmd_conn, "/PUS11/GET_GROUP_STATUS")
    check(RELEASE_KEY, wait_release_status(group_cmd, "SCHEDULED")[0], "SCHEDULED")

    # --- 1 (end). the flagged entry is presumed gone after its release time ---
    deadline = sub_t + timedelta(seconds=RELEASE_GRACE + SWEEP_INTERVAL + 5)
    print(f"Waiting until {deadline:%H:%M:%S} UTC for the sweep to presume the flagged entry gone")
    while datetime.now(timezone.utc) < deadline and release_ack(sub_cmd)[0] != "TIMEOUT":
        time.sleep(2)
    check(RELEASE_KEY, release_ack(sub_cmd)[0], "TIMEOUT")

    print("PASS - the Yamcs log shows 'will be dropped at ...' for both commands")
