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
#   scripts/local-lab.sh down        stop everything
#
# Settings: PGBIN (PostgreSQL 14+ bin directory), LAB (lab directory, default /tmp/bfm-lab), LAB_ENVIRONMENT
# (label shown in the console, default lab; try production to see its marker).
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
NODES=(pg-a1 pg-a2 pg-dr1)
declare -A PGPORT=([pg-a1]=55432 [pg-a2]=55433 [pg-dr1]=55434)
declare -A APORT=([pg-a1]=18008 [pg-a2]=18009 [pg-dr1]=18010)
declare -A DC=([pg-a1]=DC1 [pg-a2]=DC1 [pg-dr1]=DR)
declare -A VIP=([pg-a1]=192.168.0.50 [pg-a2]=192.168.0.50 [pg-dr1]=10.20.0.50)

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
  port: 8080
bfm:
  watcher-id: w1
  data-dir: $LAB/watcher
  cluster:
    name: prod-main
    environment: ${LAB_ENVIRONMENT:-lab}
    scope: DC_LOCAL
    timing:
      poll-interval: 2s
      failure-threshold: 3
    nodes:
EOF
  for n in "${NODES[@]}"; do
    cat <<EOF
      - name: $n
        agent-url: http://127.0.0.1:${APORT[$n]}
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
  if [ ! -d "$LAB/pg-a1/data" ]; then
    "$PGBIN/initdb" -D "$LAB/pg-a1/data" -U postgres --auth=trust --no-sync >/dev/null
    cat >>"$LAB/pg-a1/data/postgresql.conf" <<EOF
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
  watcher_yml >"$LAB/watcher.yml"
  nohup java -jar "$WATCHER_JAR" --spring.config.additional-location="file:$LAB/watcher.yml" \
    >"$LAB/watcher.log" 2>&1 &
  echo $! >"$LAB/watcher.pid"
  echo "Watcher starting on http://localhost:8080 (admin / $ADMIN_PW, viewer / lab-viewer-pw)."
  echo "Then run: $0 bootstrap"
}

api() {
  curl -fsS -u "admin:$ADMIN_PW" -H 'Content-Type: application/json' "$@"
}

wait_op() {
  local id=$1 state
  for _ in $(seq 1 120); do
    state=$(api "http://localhost:8080/api/v1/operations/$id" | grep -o '"state":"[A-Z]*"' | head -1 | cut -d'"' -f4)
    [ "$state" != RUNNING ] && { echo "  $state"; return; }
    sleep 1
  done
  die "operation $id did not finish"
}

op_id() {
  sed -n 's/^{"id":"\([^"]*\)".*/\1/p'
}

bootstrap() {
  echo "Starting pg-a1 as primary"
  wait_op "$(api -X POST http://localhost:8080/api/v1/nodes/pg-a1/start-primary | op_id)"
  for n in pg-a2 pg-dr1; do
    echo "Building $n with pg_basebackup"
    wait_op "$(api -X POST "http://localhost:8080/api/v1/nodes/$n/reinit" | op_id)"
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
  status) api http://localhost:8080/api/v1/cluster ;;
  down) down ;;
  *) sed -n '2,13p' "$0"; exit 64 ;;
esac
