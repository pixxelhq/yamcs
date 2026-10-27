#!/usr/bin/env python3
"""
Manual test for the PUS 11 scheduling groups, end to end through Yamcs: TC[11,22] create,
TC[11,23] delete, TC[11,26] / TM[11,27] status, and how inserts and the Pus11ScheduleTracker
depend on them (ECSS-E-ST-70-41C §6.11.6).

Scenario (groups 20-23, see pus11_setup.py):
  1. TC[11,22] creates group 20 enabled and group 21 disabled in one request -> TM[11,27] lists both
     with that status
  2. TC[11,22] for group 20 again -> rejected (the group exists)
  3. TC[11,22] for groups 22 and 20 -> rejected as a whole: group 22 is not created
  4. insert into group 23, which does not exist -> rejected, no pus11-release ack
  5. TC[11,22] creates group 23, the same insert -> accepted, pus11-release = SCHEDULED
  6. insert into group 21 (created disabled), TC[11,26] -> pus11-release = DISABLED
     "group 21 is disabled"
  7. TC[11,23] for group 21 -> rejected (it has an activity)
  8. TC[11,5] deletes the two activities, TC[11,23] deletes groups 20, 21 and 23 -> TM[11,27] lists
     none of them, the base group is still there

The inserts are released an hour from now, so nothing is executed. The tracker does not model
TC[11,5] yet (plan §6 step 5): the two deleted entries stay SCHEDULED / DISABLED until presumed
gone.

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
APID = 1
SUB_SCHEDULE_ID = 1
ENABLED_GROUP = 20
DISABLED_GROUP = 21
REJECTED_GROUP = 22
LATE_GROUP = 23
TEST_GROUPS = [ENABLED_GROUP, DISABLED_GROUP, REJECTED_GROUP, LATE_GROUP]
DELAY = 3600  # seconds from now to the release time: nothing is released during the test


def check(what, actual, expected):
    print(f"  {what}: {actual} (expected {expected})")
    if actual != expected:
        sys.exit(f"FAIL: {what}")


def create_groups(cmd_conn, groups, expect_success=True):
    return issue(cmd_conn, "/PUS11/CREATE_GROUPS", {
        "num_groups": len(groups),
        "groups": [{"group_id": g, "status": s} for g, s in groups.items()],
    }, expect_success)


def delete_groups(cmd_conn, ids, expect_success=True):
    return issue(cmd_conn, "/PUS11/DELETE_GROUPS", {"num_groups": len(ids), "group_ids": ids}, expect_success)


def schedule(cmd_conn, group_id):
    t = datetime.now(timezone.utc) + timedelta(seconds=DELAY)
    command = cmd_conn.issue(
        "/SIMULATOR/SWITCH_VOLTAGE_OFF",
        args={"voltage_num": 1},
        extra={
            "pus11ScheduleAt": t.strftime("%Y-%m-%dT%H:%M:%S.001Z"),
            "pus11SubScheduleId": SUB_SCHEDULE_ID,
            "pus11GroupId": group_id,
        },
    )
    command.await_complete(timeout=15)
    print(f"Insert into group {group_id}: {'accepted' if command.is_success() else 'rejected'}")
    return command


def release_status(command, expected=None, timeout=5):
    """the pus11-release status, after waiting up to timeout for the expected one"""
    deadline = time.time() + timeout
    while True:
        ack = command.acknowledgments.get(RELEASE_KEY)
        status, message = (ack.status, ack.message) if ack else (None, None)
        if status == expected or time.time() > deadline:
            return status, message
        time.sleep(0.2)


if __name__ == "__main__":
    client = YamcsClient("localhost:8090")
    processor = client.get_processor("pus-frames", "realtime")
    cmd_conn = processor.create_command_connection()
    group_status = GroupStatus(processor)

    ensure_groups(cmd_conn, group_status, {BASE_GROUP: "enabled"})
    issue(cmd_conn, "/PUS11/ENABLE_SCHEDULE", {"num_schedules": 1, "schedules": [SUB_SCHEDULE_ID]})
    leftovers = [g for g in group_status.request(cmd_conn) if g in TEST_GROUPS]
    if leftovers:
        print(f"Deleting groups {leftovers} left by an earlier run")
        if not delete_groups(cmd_conn, leftovers, expect_success=None).is_success():
            sys.exit(f"FAIL: cannot delete the groups {leftovers} left by an earlier run (activities still in"
                     " them?); restart the example")

    print("1. TC[11,22] create group", ENABLED_GROUP, "enabled and group", DISABLED_GROUP, "disabled")
    create_groups(cmd_conn, {ENABLED_GROUP: "enabled", DISABLED_GROUP: "disabled"})
    status = group_status.request(cmd_conn)
    check(f"group {ENABLED_GROUP}", status.get(ENABLED_GROUP), "enabled")
    check(f"group {DISABLED_GROUP}", status.get(DISABLED_GROUP), "disabled")

    print("2. TC[11,22] for an existing group")
    create_groups(cmd_conn, {ENABLED_GROUP: "enabled"}, expect_success=False)

    print("3. TC[11,22] for a new and an existing group")
    create_groups(cmd_conn, {REJECTED_GROUP: "enabled", ENABLED_GROUP: "enabled"}, expect_success=False)
    check(f"group {REJECTED_GROUP} created", REJECTED_GROUP in group_status.request(cmd_conn), False)

    print("4. insert into a group that does not exist")
    rejected = schedule(cmd_conn, LATE_GROUP)
    check("insert accepted", rejected.is_success(), False)
    check(RELEASE_KEY, release_status(rejected, timeout=1)[0], None)

    print("5. TC[11,22] create the group, then the same insert")
    create_groups(cmd_conn, {LATE_GROUP: "enabled"})
    late = schedule(cmd_conn, LATE_GROUP)
    check("insert accepted", late.is_success(), True)
    check(RELEASE_KEY, release_status(late, "SCHEDULED")[0], "SCHEDULED")

    print("6. insert into the group created disabled")
    disabled = schedule(cmd_conn, DISABLED_GROUP)
    check("insert accepted", disabled.is_success(), True)
    group_status.request(cmd_conn)
    status, message = release_status(disabled, "DISABLED")
    check(RELEASE_KEY, status, "DISABLED")
    check("message", (message or "").endswith(f"group {DISABLED_GROUP} is disabled"), True)

    print("7. TC[11,23] for a group with an activity")
    delete_groups(cmd_conn, [DISABLED_GROUP], expect_success=False)

    print("8. TC[11,5] delete the activities, then TC[11,23] the groups")
    seqs = [late.attributes["pus11-inner-seqcount"], disabled.attributes["pus11-inner-seqcount"]]
    issue(cmd_conn, "/PUS11/DELETE_ACTIVITIES_BY_ID", {
        "num_requests": len(seqs),
        "requests": [{"source_id": 0, "apid": APID, "seqcount": s} for s in seqs],
    })
    delete_groups(cmd_conn, [ENABLED_GROUP, DISABLED_GROUP, LATE_GROUP])
    status = group_status.request(cmd_conn)
    check("test groups left", sorted(g for g in status if g in TEST_GROUPS), [])
    check(f"base group {BASE_GROUP}", status.get(BASE_GROUP), "enabled")

    print("PASS")
