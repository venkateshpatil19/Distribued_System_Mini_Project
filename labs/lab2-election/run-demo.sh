#!/usr/bin/env bash
# Starts 5 election nodes as separate processes, kills the leader, then brings it back.
# Usage: ./run-demo.sh bully   (or ring)
MODE=${1:-bully}
PEERS="1:5001,2:5002,3:5003,4:5004,5:5005"
javac ElectionNode.java || exit 1
rm -f node*.log
for i in 1 2 3 4 5; do java ElectionNode $MODE $i $PEERS > node$i.log 2>&1 & echo $! > node$i.pid; done
echo "5 nodes started in $MODE mode; waiting for the first election..."
sleep 8
echo ">>> killing node 5 (the leader)"; kill $(cat node5.pid)
sleep 10
echo ">>> restarting node 5"; java ElectionNode $MODE 5 $PEERS >> node5.log 2>&1 & echo $! > node5.pid
sleep 8
for i in 1 2 3 4 5; do kill $(cat node$i.pid) 2>/dev/null; rm -f node$i.pid; done
echo; echo "===== merged log (sorted by time) ====="
cat node*.log | sort
