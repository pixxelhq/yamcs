#!/usr/bin/env python3
"""
Manual test for a manually built PUS TC[11,4] (/PUS11/INSERT_ACTIVITIES) carrying a list of
embedded TC packets.

The activity "tc" argument type is annotated with Yamcs:EmbeddedTc, so the command post-processor
fills in the sequence count and the packet length of each embedded TC and (embeddedTcCrc: true)
appends its CRC. The embedded packets are obtained with a dry run, so they have no CRC and a zero
sequence count.

Scenario:
  1. create scheduling group 1 with TC[11,22] if missing (pus11_setup.py) and enable sub-schedule 1
  2. obtain the binary of SWITCH_VOLTAGE_OFF / SWITCH_VOLTAGE_ON with a dry run
  3. insert both as two activities of one TC[11,4], released 6 and 10 seconds from now
  4. print the sequence counts assigned to the embedded TCs and the binary sent

Check the simulator log: each scheduled command is printed with its sequence count and
"CRC valid"; the voltage 1 goes off and on again when the activities are released.

Run the example first:  ./run-example.sh pus-frames
"""

import os
import sys
import time
from datetime import datetime, timedelta, timezone

from yamcs.client import YamcsClient

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pus11_setup import BASE_GROUP, GroupStatus, ensure_groups  # noqa: E402

GROUP_ID = BASE_GROUP
SUB_SCHEDULE_ID = 1


def time_str(delay_seconds):
    t = datetime.now(timezone.utc) + timedelta(seconds=delay_seconds)
    return t.strftime("%Y-%m-%dT%H:%M:%S.001Z")


def dry_run_binary(processor, command_name, args):
    """the binary of the command as encoded from the MDB (no sequence count, length or CRC)"""
    return processor.issue_command(command_name, args=args, dry_run=True).binary


if __name__ == "__main__":
    client = YamcsClient("localhost:8090")
    processor = client.get_processor("pus-frames", "realtime")
    cmd_conn = processor.create_command_connection()

    ensure_groups(cmd_conn, GroupStatus(processor), {GROUP_ID: "enabled"})
    print("Enabling sub-schedule", SUB_SCHEDULE_ID)
    cmd_conn.issue(
        "/PUS11/ENABLE_SCHEDULE",
        args={"num_schedules": 1, "schedules": [SUB_SCHEDULE_ID]},
    )
    time.sleep(1)

    tc_off = dry_run_binary(processor, "/SIMULATOR/SWITCH_VOLTAGE_OFF", {"voltage_num": 1})
    tc_on = dry_run_binary(processor, "/SIMULATOR/SWITCH_VOLTAGE_ON", {"voltage_num": 1})
    print("dry run SWITCH_VOLTAGE_OFF:", tc_off.hex())
    print("dry run SWITCH_VOLTAGE_ON: ", tc_on.hex())

    command = cmd_conn.issue(
        "/PUS11/INSERT_ACTIVITIES",
        args={
            "sub_schedule_id": SUB_SCHEDULE_ID,
            "num_activities": 2,
            "activities": [
                {"group_id": GROUP_ID, "release_time": time_str(6), "tc": tc_off.hex()},
                {"group_id": GROUP_ID, "release_time": time_str(10), "tc": tc_on.hex()},
            ],
        },
    )
    ack = command.await_acknowledgment("Acknowledge_Sent")
    if ack.status != "OK":
        raise ValueError(f"Failed to send the command: {ack.status}")

    print("TC[11,4] ccsds-seqcount:", command.attributes.get("ccsds-seqcount"))
    for i in range(2):
        key = f"ccsds-seqcount:activities[{i}].tc"
        print(f"{key}:", command.attributes.get(key))
    # the binary as sent (after the post-processor), as recorded in the command history
    binary = command.attributes.get("binary")
    print("TC[11,4] binary sent:", binary.hex() if binary else None)

    time.sleep(12)
    print("Done - check the simulator log: both activities released with a valid CRC.")
