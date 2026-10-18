#!/usr/bin/env bash
# Stops everything started by run-local.sh (each supervisor and its child process group).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[ -f "$ROOT/logs/pids.txt" ] || { echo "Nothing to stop."; exit 0; }
while read -r pid; do kill -- -"$pid" 2>/dev/null || kill "$pid" 2>/dev/null; done < "$ROOT/logs/pids.txt"
rm -f "$ROOT/logs/pids.txt"
echo "Stopped."
