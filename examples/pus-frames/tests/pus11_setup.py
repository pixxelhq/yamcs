"""
Shared setup of the PUS 11 manual tests: the scheduling groups each test needs, created on board
with TC[11,22] and checked with TC[11,26] / TM[11,27].

The simulator follows ECSS-E-ST-70-41C §6.11.6: a group exists only after a TC[11,22], and an
insert naming a group that does not exist is rejected as a whole (§6.11.4.5g.3). Each test uses
its own group IDs, so the tests can run in any order, and again, on one simulator:

  BASE_GROUP (1)  the TC link default group (pus11 -> groupId -> default in yamcs.pus-frames.yaml),
                  used by every insert that does not set pus11GroupId
  5               test-pus11.py
  6               test-pus11-dropflag.py
  20-23           test-pus11-groups.py (deleted again at its end)
  99              never created: the "unknown group" case of test-pus11-tracker.py

The tests are run with "python -I", which leaves the script directory out of sys.path, so they
import this module with:

  sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
"""

import sys
import threading

BASE_GROUP = 1
GROUP_STATUS = "/PUS11/group_status_report"


def issue(cmd_conn, name, args=None, expect_success=True):
    """
    Issues a command and waits for its completion; exits if the outcome is not the expected one
    (expect_success None: any outcome).
    """
    command = cmd_conn.issue(name, args=args or {})
    command.await_complete(timeout=15)
    if expect_success is not None and command.is_success() != expect_success:
        sys.exit(f"FAIL: {name} {args or ''}: expected {'success' if expect_success else 'failure'},"
                 f" got {'success' if command.is_success() else command.error}")
    return command


class GroupStatus:
    """Reads the TM[11,27] group status report sent in reply to a TC[11,26]."""

    def __init__(self, processor):
        self.cond = threading.Condition()
        self.reports = 0
        self.status = {}
        processor.create_parameter_subscription([GROUP_STATUS], on_data=self._on_data, send_from_cache=False)

    def _on_data(self, data):
        with self.cond:
            for pval in data.parameters:
                if pval.name == GROUP_STATUS:
                    self.status = {g["group_id"]: g["group_status"] for g in pval.eng_value or []}
                    self.reports += 1
            self.cond.notify_all()

    def request(self, cmd_conn, timeout=5):
        """Sends a TC[11,26] and returns the reported status, {group_id: "enabled" | "disabled"}."""
        with self.cond:
            seen = self.reports
        issue(cmd_conn, "/PUS11/GET_GROUP_STATUS")
        with self.cond:
            if not self.cond.wait_for(lambda: self.reports > seen, timeout):
                sys.exit("FAIL: no TM[11,27] in reply to the TC[11,26]")
            return dict(self.status)


def ensure_groups(cmd_conn, group_status, groups):
    """
    Makes the groups {group_id: "enabled" | "disabled"} exist on board with that status: creates the
    missing ones with one TC[11,22], sets the status of the others with TC[11,24] / TC[11,25], then
    checks the result with a TM[11,27]. Exits if a group does not end up as requested.

    Returns the status of all the groups on board.
    """
    present = group_status.request(cmd_conn)
    missing = {g: s for g, s in groups.items() if g not in present}
    if missing:
        print(f"TC[11,22] creating scheduling groups {missing}")
        issue(cmd_conn, "/PUS11/CREATE_GROUPS", {
            "num_groups": len(missing),
            "groups": [{"group_id": g, "status": s} for g, s in missing.items()],
        })
    for status, command in (("enabled", "/PUS11/ENABLE_GROUPS"), ("disabled", "/PUS11/DISABLE_GROUPS")):
        ids = [g for g, s in groups.items() if s == status and g in present and present[g] != status]
        if ids:
            issue(cmd_conn, command, {"num_groups": len(ids), "group_ids": ids})

    present = group_status.request(cmd_conn)
    for g, s in groups.items():
        if present.get(g) != s:
            sys.exit(f"FAIL: scheduling group {g} is {present.get(g, 'missing')} on board, expected {s}")
    return present
