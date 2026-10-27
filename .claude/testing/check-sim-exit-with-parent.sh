#!/bin/bash
# Checks that the simulator stops when Yamcs is killed with -9 (SimulatorCommander --exit-with-parent).
# Run from the repo root with examples/pus-frames already started (./run-example.sh pus-frames ...).
# Leaves nothing running: Yamcs is killed, and the simulator must exit by itself.

YAMCS=$(pgrep -f org.yamcs.YamcsServer)
SIM=$(pgrep -f "SimulatorCommander --exit-with-parent")
if [[ -z "$YAMCS" || -z "$SIM" ]]; then
    echo "FAIL: Yamcs ($YAMCS) or simulator ($SIM) not running"
    exit 1
fi
echo "Yamcs pid $YAMCS, simulator pid $SIM (parent $(ps -o ppid= -p "$SIM" | tr -d ' '))"

# without the watcher installed there is no reaper thread for the parent
if ! jstack "$SIM" | grep -q "process reaper (pid $YAMCS)"; then
    echo "FAIL: simulator does not watch pid $YAMCS (exitWithParent parsed as false?)"
    exit 1
fi

kill -9 "$YAMCS"
for i in $(seq 1 60); do
    kill -0 "$SIM" 2>/dev/null || break
    sleep 0.5
done
if kill -0 "$SIM" 2>/dev/null; then
    echo "FAIL: simulator $SIM still running 30 s after kill -9 of Yamcs; stopping it"
    kill -9 "$SIM"
    exit 1
fi
echo "PASS: simulator exited ~$((i / 2)) s after Yamcs was killed"
