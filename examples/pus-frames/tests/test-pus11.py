#!/usr/bin/env python3
"""
Manual test for the PUS 11 time-based scheduling with scheduling groups.

Scenario:
  1. enable the time-based schedule execution function (TC[11,1]); the simulator boots with it
     disabled and drops every activity at its release time otherwise
  2. create scheduling group 5 (enabled)
  3. schedule a command into sub-schedule 1 / group 5 a few seconds in the future
  4. enable sub-schedule 1 (a sub-schedule auto-created by an insert starts disabled)
  5. disable group 5 -> the activity is withheld: no TM[1,x] for it at the release time
  6. enable group 5, schedule again -> the detail report (TM[11,10], asked by request ID) lists
     it, the group status report (TM[11,27]) shows group 5 enabled, and it is released: a TM[1,x]
     for it arrives at the release time

An on-board activity is identified by the request ID of the embedded command, not of the
TC[11,4] wrapper: the postprocessor publishes it as the pus11-inner-seqcount attribute.

Run the example first:  ./run-example.sh pus-frames --data-dir yamcs-data
and wait until the time correlation is established (curl localhost:8090/api/tco/pus-frames/tco0/status
shows "coefficients"), otherwise the release time cannot be converted to on-board time.

Exits non-zero if an expectation fails.
"""

import sys
import threading
import time
from datetime import datetime, timedelta, timezone

from yamcs.client import YamcsClient

GROUP_ID = 5
SUB_SCHEDULE_ID = 1
APID = 1
DELAY = 6  # seconds from now to the release time
MARGIN = 3  # seconds after the release time before checking for the release

ACK_APID = "/PUS/tc-ack-apid"
ACK_SEQ = "/PUS/tc-ack-seq"
DETAIL_SEQ = "/PUS11/DETAIL_REPORT/seq"
GROUP_STATUS = "/PUS11/group_status_report"


class Observer:
    """Collects the request IDs of the TM[1,x] reports and of the detail-report entries."""

    def __init__(self):
        self.lock = threading.Lock()
        self.acked = set()  # (apid, seq) of every TM[1,x]
        self.detail_seqs = set()
        self.group_status = None

    def on_data(self, data):
        with self.lock:
            values = {}
            for pval in data.parameters:
                if pval.name == DETAIL_SEQ:
                    self.detail_seqs.add(pval.eng_value)
                elif pval.name == GROUP_STATUS:
                    self.group_status = pval.eng_value
                else:
                    values[pval.name] = pval.eng_value
            if ACK_APID in values and ACK_SEQ in values:
                self.acked.add((values[ACK_APID], values[ACK_SEQ]))

    def wait_for(self, predicate, timeout):
        deadline = time.time() + timeout
        while time.time() < deadline:
            with self.lock:
                if predicate():
                    return True
            time.sleep(0.2)
        with self.lock:
            return predicate()


def check(what, actual, expected):
    print(f"  {what}: {actual} (expected {expected})")
    if actual != expected:
        sys.exit(f"FAIL: {what}")


def issue(cmd_conn, name, args=None):
    command = cmd_conn.issue(name, args=args or {})
    command.await_complete(timeout=15)
    if not command.is_success():
        sys.exit(f"FAIL: {name} not completed: {command.error}")
    return command


def schedule_command(cmd_conn, delay_seconds):
    """Schedules SWITCH_VOLTAGE_OFF and returns (inner seq count, release time)."""
    t = datetime.now(timezone.utc) + timedelta(seconds=delay_seconds)
    t_str = t.strftime("%Y-%m-%dT%H:%M:%S.001Z")
    command = cmd_conn.issue(
        "/SIMULATOR/SWITCH_VOLTAGE_OFF",
        args={"voltage_num": 1},
        extra={
            "pus11ScheduleAt": t_str,
            "pus11GroupId": GROUP_ID,
            "pus11SubScheduleId": SUB_SCHEDULE_ID,
        },
    )
    command.await_complete(timeout=15)
    if not command.is_success():
        sys.exit(f"FAIL: insert not accepted: {command.error}")
    seq = command.attributes["pus11-inner-seqcount"]
    print(f"Scheduled SWITCH_VOLTAGE_OFF at {t_str} (group {GROUP_ID}, sub-schedule {SUB_SCHEDULE_ID}),"
          f" inner seq count {seq}")
    return seq, t


def sleep_until(t):
    time.sleep(max(0.0, (t - datetime.now(timezone.utc)).total_seconds()))


if __name__ == "__main__":
    client = YamcsClient("localhost:8090")
    processor = client.get_processor("pus-frames", "realtime")
    cmd_conn = processor.create_command_connection()
    observer = Observer()
    processor.create_parameter_subscription(
        [ACK_APID, ACK_SEQ, DETAIL_SEQ, GROUP_STATUS], on_data=observer.on_data
    )

    print("Enabling the time-based schedule execution function")
    issue(cmd_conn, "/PUS11/ENABLE_SCHEDULER")

    print("Creating scheduling group", GROUP_ID, "(NOK if it already exists, that is fine)")
    cmd_conn.issue(
        "/PUS11/CREATE_GROUPS",
        args={"num_groups": 1, "groups": [{"group_id": GROUP_ID, "status": "enabled"}]},
    ).await_complete(timeout=15)

    # --- withheld run: group disabled before the release time ---
    seq, t = schedule_command(cmd_conn, DELAY)
    issue(cmd_conn, "/PUS11/ENABLE_SCHEDULE", {"num_schedules": 1, "schedules": [SUB_SCHEDULE_ID]})
    print("Disabling group", GROUP_ID, "- the scheduled command must NOT be released")
    issue(cmd_conn, "/PUS11/DISABLE_GROUPS", {"num_groups": 1, "group_ids": [GROUP_ID]})
    sleep_until(t + timedelta(seconds=MARGIN))
    check("withheld command released", (APID, seq) in observer.acked, False)

    # --- released run: group enabled ---
    issue(cmd_conn, "/PUS11/ENABLE_GROUPS", {"num_groups": 1, "group_ids": [GROUP_ID]})
    seq, t = schedule_command(cmd_conn, DELAY)

    issue(cmd_conn, "/PUS11/GET_GROUP_STATUS")
    issue(
        cmd_conn,
        "/PUS11/GET_DETAIL_REPORT_BY_ID",
        {"num_requests": 1, "requests": [{"source_id": 0, "apid": APID, "seqcount": seq}]},
    )
    check("detail report lists the command", observer.wait_for(lambda: seq in observer.detail_seqs, 5), True)
    observer.wait_for(lambda: observer.group_status is not None, 5)
    status = {g["group_id"]: g["group_status"] for g in observer.group_status or []}
    check(f"group {GROUP_ID} status", status.get(GROUP_ID), "enabled")

    sleep_until(t)
    check("command released", observer.wait_for(lambda: (APID, seq) in observer.acked, MARGIN), True)
    print("PASS")
