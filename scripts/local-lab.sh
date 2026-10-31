#!/bin/bash
# A three-node BFM lab on one machine: real PostgreSQL, real agents, a standalone watcher, and a fake VIP
# (lab-fake-ip.sh) so nothing needs root. Run as a normal user; PostgreSQL refuses to run as root.
#
#   scripts/local-lab.sh up          init pg-a1, start three agents and the watcher (http://localhost:8080)
#   scripts/local-lab.sh bootstrap   start pg-a1 as primary and build pg-a2 / pg-dr1 from it, through the watcher
#   scripts/local-lab.sh crash NODE  power off a node: agent and PostgreSQL killed, its VIP stops answering
#   scripts/local-lab.sh revive NODE power it back on (agent only; the watcher rejoins it)
#   scripts/local-lab.sh vip-fail NODE on|off   make "ip addr add" fail on a node
#   scripts/local-lab.sh status      cluster state through the REST API
#   scripts/local-lab.sh restart-watcher   restart the watcher with the current settings (LAB_WRONG_AGENT)
#   scripts/local-lab.sh down        stop everything
#
# Settings: PGBIN (PostgreSQL 14+ bin directory), LAB (lab directory, default /tmp/bfm-lab), LAB_ENVIRONMENT
# (label shown in the console, default lab; try production to see its marker).
#
# A second cluster on the same machine (each with its own directory, ports and console):
#   LAB=/tmp/bfm-lab-crm LAB_CLUSTER=crm LAB_PREFIX=crm LAB_PORT_OFFSET=100 LAB_CONSOLE_PORT=8081 \
#     LAB_VIP=192.168.0.60 LAB_DR_VIP=10.20.0.60 scripts/local-lab.sh up
# A mistyped agent port: LAB_WRONG_AGENT=pg-dr1=18110 scripts/local-lab.sh restart-watcher points pg-dr1 at
# another agent's port; the console shows it as "Wrong identity" and leaves it alone.
# One watcher for both (the fleet console): stops each lab's own watcher and runs one for all of them, keeping the
# cluster ids their agents are bound to:
#   LAB=/tmp/bfm-lab-fleet LAB_FLEET="/tmp/bfm-lab /tmp/bfm-lab-crm" scripts/local-lab.sh fleet-up
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LAB=${LAB:-/tmp/bfm-lab}
# ls fails when one of the two patterns matches nothing, which is the normal case; under set -e that
# failure would end the script before it did anything.
PGBIN=${PGBIN:-$({ ls -d /usr/lib/postgresql/*/bin /usr/pgsql-*/bin 2>/dev/null || true; } | sort -V | tail -1)}
AGENT_JAR="$ROOT/bfm-agent/target/minipgagent.jar"
WATCHER_JAR="$ROOT/bfm-watcher/target/bfm-watcher.jar"
TOKEN=lab-token
ADMIN_PW=lab-admin-pw
CLUSTER=${LAB_CLUSTER:-prod-main}
P=${LAB_PREFIX:-pg}
OFF=${LAB_PORT_OFFSET:-0}
CONSOLE_PORT=${LAB_CONSOLE_PORT:-8080}
CONSOLE="http://localhost:$CONSOLE_PORT"
NODES=("$P-a1" "$P-a2" "$P-dr1")
declare -A PGPORT=(["$P-a1"]=$((55432 + OFF)) ["$P-a2"]=$((55433 + OFF)) ["$P-dr1"]=$((55434 + OFF)))
declare -A APORT=(["$P-a1"]=$((18008 + OFF)) ["$P-a2"]=$((18009 + OFF)) ["$P-dr1"]=$((18010 + OFF)))
declare -A DC=(["$P-a1"]=DC1 ["$P-a2"]=DC1 ["$P-dr1"]=DR)
declare -A VIP=(["$P-a1"]=${LAB_VIP:-192.168.0.50} ["$P-a2"]=${LAB_VIP:-192.168.0.50}
                ["$P-dr1"]=${LAB_DR_VIP:-10.20.0.50})
# node=port: the watcher reaches that node's agent on another port (a deliberate mistake)
WRONG_NODE=${LAB_WRONG_AGENT:-}; WRONG_NODE=${WRONG_NODE%%=*}
WRONG_PORT=${LAB_WRONG_AGENT:-}; WRONG_PORT=${WRONG_PORT#*=}

die() { echo "error: $*" >&2; exit 1; }

[ "$(id -u)" != 0 ] || die "run as a normal user (PostgreSQL refuses to run as root)"
[ -x "$PGBIN/initdb" ] || die "set PGBIN to a PostgreSQL bin directory"

agent_props() {
  local n=$1
  cat <<EOF
node.name=$n
listen.address=127.0.0.1
listen.port=${APORT[$n]}
tls.enabled=false
api.token=$TOKEN
state.dir=$LAB/$n/state
pg.bin=$PGBIN
pg.data=$LAB/$n/data
pg.port=${PGPORT[$n]}
pg.socket.dir=$LAB/sock
pg.start.command={bin}/pg_ctl -D {data} -l {state}/postgres.log -o -p{port} -w -t {timeout} start
replication.user=postgres
rewind.user=postgres
replication.conninfo.extra=
vip.enabled=true
vip.address=${VIP[$n]}
vip.prefix=24
vip.interface=$n
vip.show.command=$LAB/lab-fake-ip.sh show {iface} {vip} {prefix}
vip.add.command=$LAB/lab-fake-ip.sh add {iface} {vip} {prefix}
vip.del.command=$LAB/lab-fake-ip.sh del {iface} {vip} {prefix}
vip.dad.command=$LAB/lab-fake-ip.sh dad {iface} {vip}
vip.garp.command=$LAB/lab-fake-ip.sh garp {iface} {vip}
fence.action=stop
probe.interval.ms=1000
EOF
}

watcher_yml() {
  local admin_hash viewer_hash
  admin_hash=$(echo "$ADMIN_PW" | java -jar "$WATCHER_JAR" hash-password)
  viewer_hash=$(echo "lab-viewer-pw" | java -jar "$WATCHER_JAR" hash-password)
  cat <<EOF
server:
  port: $CONSOLE_PORT
bfm:
  watcher-id: w1
  data-dir: $LAB/watcher
  cluster:
    name: $CLUSTER
    environment: ${LAB_ENVIRONMENT:-lab}
    scope: DC_LOCAL
    timing:
      poll-interval: 2s
      failure-threshold: 3
    nodes:
EOF
  for n in "${NODES[@]}"; do
    local port=${APORT[$n]}
    [ -n "${LAB_WRONG_AGENT:-}" ] && [ "$n" = "$WRONG_NODE" ] && port=$WRONG_PORT
    cat <<EOF
      - name: $n
        agent-url: http://127.0.0.1:$port
        pg-host: 127.0.0.1
        pg-port: ${PGPORT[$n]}
        dc: ${DC[$n]}
EOF
  done
  cat <<EOF
  agent:
    tls:
      enabled: false
    token-file: $LAB/agent.token
  users:
    - name: admin
      password-hash: "$admin_hash"
      role: ADMIN
    - name: viewer
      password-hash: "$viewer_hash"
      role: VIEWER
EOF
}

start_agent() {
  local n=$1
  nohup java -jar "$AGENT_JAR" "$LAB/$n/agent.properties" >"$LAB/$n/agent.log" 2>&1 &
  echo $! >"$LAB/$n/agent.pid"
}

up() {
  [ -f "$AGENT_JAR" ] && [ -f "$WATCHER_JAR" ] || die "build first: mvn -DskipTests package"
  mkdir -p "$LAB/sock" "$LAB/net"
  cp "$ROOT/scripts/lab-fake-ip.sh" "$LAB/lab-fake-ip.sh"
  chmod +x "$LAB/lab-fake-ip.sh"
  echo "$TOKEN" >"$LAB/agent.token"
  for n in "${NODES[@]}"; do
    mkdir -p "$LAB/$n/state"
    agent_props "$n" >"$LAB/$n/agent.properties"
  done
  local first=${NODES[0]}
  if [ ! -d "$LAB/$first/data" ]; then
    "$PGBIN/initdb" -D "$LAB/$first/data" -U postgres --auth=trust --no-sync >/dev/null
    cat >>"$LAB/$first/data/postgresql.conf" <<EOF
listen_addresses = '127.0.0.1'
unix_socket_directories = '$LAB/sock'
wal_level = replica
wal_log_hints = on
max_wal_senders = 10
max_replication_slots = 10
hot_standby = on
wal_keep_size = '128MB'
fsync = off
EOF
  fi
  for n in "${NODES[@]}"; do
    start_agent "$n"
  done
  start_watcher
  echo "Then run: $0 bootstrap"
}

start_watcher() {
  watcher_yml >"$LAB/watcher.yml"
  nohup java -jar "$WATCHER_JAR" --spring.config.additional-location="file:$LAB/watcher.yml" \
    >"$LAB/watcher.log" 2>&1 &
  echo $! >"$LAB/watcher.pid"
  echo "Watcher of $CLUSTER starting on $CONSOLE (admin / $ADMIN_PW, viewer / lab-viewer-pw)."
}

restart_watcher() {
  [ -f "$LAB/watcher.pid" ] && kill "$(cat "$LAB/watcher.pid")" 2>/dev/null || true
  sleep 2
  start_watcher
}

api() {
  curl -fsS -u "admin:$ADMIN_PW" -H 'Content-Type: application/json' "$@"
}

wait_op() {
  local id=$1 state
  for _ in $(seq 1 120); do
    state=$(api "$CONSOLE/api/v1/operations/$id" | grep -o '"state":"[A-Z]*"' | head -1 | cut -d'"' -f4)
    [ "$state" != RUNNING ] && { echo "  $state"; return; }
    sleep 1
  done
  die "operation $id did not finish"
}

op_id() {
  sed -n 's/^{"id":"\([^"]*\)".*/\1/p'
}

bootstrap() {
  echo "Starting ${NODES[0]} as primary"
  wait_op "$(api -X POST "$CONSOLE/api/v1/nodes/${NODES[0]}/start-primary" | op_id)"
  for n in "${NODES[1]}" "${NODES[2]}"; do
    echo "Building $n with pg_basebackup"
    wait_op "$(api -X POST "$CONSOLE/api/v1/nodes/$n/reinit" | op_id)"
  done
}

crash() {
  local n=${1:?node}
  touch "$LAB/net/down@$n"
  kill -9 "$(cat "$LAB/$n/agent.pid")" 2>/dev/null || true
  "$PGBIN/pg_ctl" -D "$LAB/$n/data" -m immediate stop >/dev/null 2>&1 || true
  rm -f "$LAB/net/"*"@$n"
  touch "$LAB/net/down@$n"
  echo "$n is down"
}

revive() {
  local n=${1:?node}
  rm -f "$LAB/net/down@$n"
  start_agent "$n"
  echo "$n agent started; PostgreSQL stays down until the watcher rejoins it"
}

vip_fail() {
  local n=${1:?node} mode=${2:?on|off}
  if [ "$mode" = on ]; then touch "$LAB/net/fail-add@$n"; else rm -f "$LAB/net/fail-add@$n"; fi
}

# One watcher for several labs: their cluster sections become bfm.clusters, each with the id its agents are bound
# to (read from the first node's binding file), so nothing has to be re-bound.
fleet_up() {
  [ -n "${LAB_FLEET:-}" ] || die "set LAB_FLEET to the lab directories, for example \"/tmp/bfm-lab /tmp/bfm-lab-crm\""
  [ -f "$WATCHER_JAR" ] || die "build first: mvn -DskipTests package"
  local first_lab m first id
  first_lab=$(echo "$LAB_FLEET" | awk '{print $1}')
  mkdir -p "$LAB"
  for m in $LAB_FLEET; do
    [ -f "$m/watcher.yml" ] || die "$m is not a lab directory (no watcher.yml)"
    [ -f "$m/watcher.pid" ] && kill "$(cat "$m/watcher.pid")" 2>/dev/null || true
  done
  sleep 2
  {
    printf 'server:\n  port: %s\nbfm:\n  watcher-id: w1\n  data-dir: %s/watcher\n  clusters:\n' \
      "$CONSOLE_PORT" "$LAB"
    for m in $LAB_FLEET; do
      first=$(sed -n 's/^      - name: //p' "$m/watcher.yml" | head -1)
      id=$(head -1 "$m/$first/state/binding" 2>/dev/null || true)
      # "  cluster:" ... "  agent:" without those two lines, two spaces deeper, as one list item
      sed -n '/^  cluster:/,/^  agent:/p' "$m/watcher.yml" | sed '1d;$d' | sed 's/^/  /' | while IFS= read -r line; do
        if [ -n "${line##      name: *}" ] || [ -n "${done_first:-}" ]; then
          printf '%s\n' "$line"
        else
          printf '    - name: %s\n' "${line#      name: }"
          [ -n "$id" ] && printf '      id: %s\n' "$id"
          done_first=1
        fi
      done
    done
    sed -n '/^  agent:/,$p' "$first_lab/watcher.yml"
  } >"$LAB/watcher.yml"
  nohup java -jar "$WATCHER_JAR" --spring.config.additional-location="file:$LAB/watcher.yml" \
    >"$LAB/watcher.log" 2>&1 &
  echo $! >"$LAB/watcher.pid"
  echo "One watcher for $(echo "$LAB_FLEET" | wc -w) clusters on $CONSOLE (admin / $ADMIN_PW)."
}

down() {
  [ -f "$LAB/watcher.pid" ] && kill "$(cat "$LAB/watcher.pid")" 2>/dev/null || true
  for n in "${NODES[@]}"; do
    [ -f "$LAB/$n/agent.pid" ] && kill "$(cat "$LAB/$n/agent.pid")" 2>/dev/null || true
    [ -d "$LAB/$n/data" ] && "$PGBIN/pg_ctl" -D "$LAB/$n/data" -m fast stop >/dev/null 2>&1 || true
  done
  echo "Stopped. Remove $LAB to start from scratch."
}

case "${1:-}" in
  up) up ;;
  bootstrap) bootstrap ;;
  crash) crash "${2:-}" ;;
  revive) revive "${2:-}" ;;
  vip-fail) vip_fail "${2:-}" "${3:-}" ;;
  status) api "$CONSOLE/api/v1/cluster" ;;
  restart-watcher) restart_watcher ;;
  fleet-up) fleet_up ;;
  down) down ;;
  *) sed -n '2,23p' "$0"; exit 64 ;;
esac
