#!/usr/bin/env python3
"""
Manual test for the Pus11ScheduleTracker (ground model of the on-board PUS 11 schedule).

The tracker publishes a "pus11-release" acknowledgment on every command scheduled with
pus11ScheduleAt. Scenario:
  1. create scheduling group 1 (the TC link default group; the simulator rejects inserts into
     groups that do not exist) with TC[11,22] if missing (pus11_setup.py), and check with
     TM[11,27] that group 99 does not exist
  2. schedule into group 99, which does not exist -> CommandComplete NOK, the tracker logs
     DELETED and publishes no pus11-release ack
  3. schedule into group 1 -> CommandComplete OK, pus11-release = SCHEDULED
  4. wait until release time + releaseGrace (60 s in yamcs.pus-frames.yaml) + one sweep
     -> pus11-release = TIMEOUT ("presumed gone"). Expected for now: the release ACK from the
     on-board TC is not correlated yet, so even a released command ends up TIMEOUT.

Run the example first:  ./run-example.sh pus-frames
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
from pus11_setup import BASE_GROUP, GroupStatus, ensure_groups  # noqa: E402

RELEASE_KEY = "pus11-release"
UNKNOWN_GROUP = 99
DELAY = 10  # seconds from now to the release time
RELEASE_GRACE = 60  # Pus11ScheduleTracker releaseGrace in yamcs.pus-frames.yaml
SWEEP_INTERVAL = 10  # Pus11ScheduleTracker default sweepInterval


def schedule(cmd_conn, delay_seconds, group_id=None):
    t = datetime.now(timezone.utc) + timedelta(seconds=delay_seconds)
    extra = {"pus11ScheduleAt": t.strftime("%Y-%m-%dT%H:%M:%S.001Z")}
    if group_id is not None:
        extra["pus11GroupId"] = group_id
    print(f"Scheduling SWITCH_VOLTAGE_OFF at {extra['pus11ScheduleAt']} (group {group_id or 'default'})")
    command = cmd_conn.issue("/SIMULATOR/SWITCH_VOLTAGE_OFF", args={"voltage_num": 1}, extra=extra)
    command.await_complete(timeout=15)
    return command, t


def release_ack(command):
    ack = command.acknowledgments.get(RELEASE_KEY)
    return ack.status if ack else None


def check(what, actual, expected):
    print(f"  {what}: {actual} (expected {expected})")
    if actual != expected:
        sys.exit(f"FAIL: {what}")


if __name__ == "__main__":
    client = YamcsClient("localhost:8090")
    processor = client.get_processor("pus-frames", "realtime")
    cmd_conn = processor.create_command_connection()

    groups = ensure_groups(cmd_conn, GroupStatus(processor), {BASE_GROUP: "enabled"})
    check(f"group {UNKNOWN_GROUP} exists", UNKNOWN_GROUP in groups, False)

    # --- rejected insert: the group does not exist ---
    command, _ = schedule(cmd_conn, DELAY, group_id=UNKNOWN_GROUP)
    time.sleep(1)
    check("CommandComplete success", command.is_success(), False)
    check(RELEASE_KEY, release_ack(command), None)

    # --- accepted insert ---
    command, release_time = schedule(cmd_conn, DELAY)
    time.sleep(1)
    check("CommandComplete success", command.is_success(), True)
    check(RELEASE_KEY, release_ack(command), "SCHEDULED")

    deadline = release_time + timedelta(seconds=RELEASE_GRACE + SWEEP_INTERVAL + 5)
    print(f"Waiting until {deadline:%H:%M:%S} UTC for the sweep to presume the entry gone")
    while datetime.now(timezone.utc) < deadline and release_ack(command) != "TIMEOUT":
        time.sleep(2)
    check(RELEASE_KEY, release_ack(command), "TIMEOUT")

    print("PASS - the Yamcs log shows the PENDING / DELETED / SCHEDULED / PRESUMED_GONE transitions")
