#!/usr/bin/env bash
# Starts 3 controllers, 3 agents and the dashboard (Linux, macOS, WSL, Git Bash).
# Each Java process runs under a small supervisor loop, so a crashed node restarts like it would in Kubernetes.
# Needs: Java 21 (and Node 20 for the dashboard). State is saved in data/controller-N.json.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/backend/target/deployguard.jar"
[ -f "$JAR" ] || { echo "Build first: (cd backend && mvn -DskipTests package)"; exit 1; }
mkdir -p "$ROOT/logs" "$ROOT/data"
: > "$ROOT/logs/pids.txt"
PEERS="1=localhost:9091,2=localhost:9092,3=localhost:9093"

# Runs a command forever in its own process group; the group id is saved so stop-local.sh can kill it all.
supervise() {
  local log=$1; shift
  setsid bash -c 'while true; do "$@" >> "'"$log"'" 2>&1; echo "process exited, restarting in 1 s" >> "'"$log"'"; sleep 1; done' _ "$@" &
  echo $! >> "$ROOT/logs/pids.txt"
}

for i in 1 2 3; do
  DG_CONTROLLER_ID=$i HTTP_PORT=808$i GRPC_PORT=909$i PEERS=$PEERS DATA_DIR="$ROOT/data" \
    supervise "$ROOT/logs/controller-$i.log" java -Xmx192m -jar "$JAR" controller
done
sleep 5
for i in 1 2 3; do
  # Agents know every controller and follow whichever one is the leader.
  DG_AGENT_ID=agent-$i HTTP_PORT=810$i CONTROLLERS=$PEERS \
    supervise "$ROOT/logs/agent-$i.log" java -Xmx128m -jar "$JAR" agent
done

if [ -d "$ROOT/frontend/.next" ]; then
  (cd "$ROOT/frontend" && CONTROLLER_URL=http://localhost:8081,http://localhost:8082,http://localhost:8083 supervise "$ROOT/logs/dashboard.log" npm start)
  echo "Dashboard: http://localhost:3000"
else
  echo "Dashboard not built yet: cd frontend && npm ci && npm run build, then run this script again."
fi
echo "Controllers: http://localhost:8081-8083   Logs: $ROOT/logs   Stop: scripts/stop-local.sh"
