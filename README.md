# pgVIP: PostgreSQL VIP failover manager

Automatic failover for PostgreSQL streaming replication clusters, with a virtual IP that follows the primary.
An **agent** runs next to every PostgreSQL server; one or three **watchers** decide and serve the web console.
Three watchers form an Apache Ratis (Raft) group; Raft is optional and a single watcher works on its own.

```
             clients ──► VIP 192.168.0.50 (on the primary)
                               │
   ┌───────────────┐   ┌───────┴───────┐   ┌───────────────┐
   │ pg-a1 primary │──►│ pg-a2 replica │   │ pg-dr1 replica│   PostgreSQL 13+ streaming replication
   │ pgvip-agent   │   │ pgvip-agent   │   │ pgvip-agent   │   agents: HTTPS + mutual TLS, port 8008
   └───────▲───────┘   └───────▲───────┘   └───────▲───────┘
           └─────────── poll, lease, commands ─────┘
                   ┌───────────────────────────┐
                   │ pgvip-watcher (1 or 3)    │   console + REST API, port 8443
                   │ Raft between 3 (optional) │   Raft port 9870
                   └───────────────────────────┘
```

PostgreSQL 13 or newer (the agent reloads `primary_conninfo` without a restart); tested with PostgreSQL 16.

## Safety rules

- **No VIP, no promote.** The VIP is brought up on the new primary with `sudo ip addr add` first. If the
  address still answers from another host (`arping -D`) or the command fails, the replica is not promoted
  and stays a replica. The agent enforces this itself: `/v1/pg/promote` refuses while the VIP is not on the
  local interface.
- **A node never starts as primary on its own.** Without `standby.signal`, the agent starts PostgreSQL only
  when the watcher explicitly allows it. Disable the PostgreSQL systemd unit and let the agent start it.
- **Epochs.** Every role change raises the cluster epoch in the shared state with a compare-and-set, so only
  one watcher can win it, and the new epoch is announced to every agent. Agents refuse commands from older
  epochs, so a watcher that has been superseded cannot steer a node.
- **Lease and self-fencing.** The leading watcher renews a lease on the primary every poll. With
  `self-fence: true`, a primary that stops getting renewals releases the VIP and stops PostgreSQL, and the
  watcher waits until any lease it or a previous leader granted has run out (TTL + margin) before promoting.
  In Raft mode a leader confirms its leadership with a majority (ReadIndex) before every renewal.
- **Confirmation before failover.** The watcher asks every reachable replica whether it still reaches the
  primary's PostgreSQL port or streams from it; any "yes" blocks the failover. In Raft mode a majority of
  watchers must also have seen the primary gone within `observation-fresh`.
- **Fencing.** A returning old primary, or any node that runs as primary although the state names another
  one, is fenced (VIP released, PostgreSQL stopped) and then rejoined as a replica with `pg_rewind`.
  A VIP found on a node that is not the primary is removed.

## Modules

| Module | What it is | Dependencies |
| --- | --- | --- |
| `pgvip-common` | Agent API model, JSON codec, LSN and TLS helpers | JDK only |
| `pgvip-agent` | Agent: status probe, start/stop/promote/follow/demote/rewind/reinit, VIP, lease, mTLS API | JDK only |
| `pgvip-core` | Watcher logic: polling, failover and switchover decisions, operations, alerts, mail rules | JDK only |
| `pgvip-raft` | Shared watcher state replicated across three watchers | Apache Ratis 3.3 |
| `pgvip-watcher` | Spring Boot 4 service: Vaadin 25 console, REST API, users, SMTP | Spring, Vaadin |

## Build

Java 21 and Maven 3.9:

```
mvn package
```

The jars are `pgvip-agent/target/pgvip-agent.jar` (JDK only, ~100 KB) and
`pgvip-watcher/target/pgvip-watcher.jar` (Spring Boot executable jar). The console uses Vaadin's pre-compiled
production bundle, so the build does not need npm.

## Try it on one machine

`scripts/local-lab.sh` runs three real PostgreSQL instances (`pg-a1`, `pg-a2` in DC1, `pg-dr1` in DR), their
agents and a standalone watcher. The VIP is simulated by `scripts/lab-fake-ip.sh`, so nothing needs root.
Run it as a normal user (PostgreSQL refuses to run as root):

```
mvn -DskipTests package
PGBIN=/usr/lib/postgresql/16/bin scripts/local-lab.sh up
scripts/local-lab.sh bootstrap        # start pg-a1 as primary, build pg-a2 and pg-dr1 from it
```

Open http://localhost:8080 (`admin` / `lab-admin-pw`). Then, for example:

```
scripts/local-lab.sh crash pg-a1      # power off the primary: pg-a2 is promoted, the VIP moves
scripts/local-lab.sh revive pg-a1     # it comes back and rejoins as a replica
scripts/local-lab.sh vip-fail pg-a2 on   # "ip addr add" fails there: no VIP, no promote
scripts/local-lab.sh down
```

## Installation

1. **Certificates.** `packaging/make-certs.sh /etc/pgvip/certs pg-a1=10.0.0.11 pg-a2=10.0.0.12 w1=10.0.9.1`
   makes a private CA and one PKCS#12 store per agent and watcher. The addresses go into the certificates'
   SAN and must match the `agent-url` the watcher uses. Keep `ca.key` offline.
2. **Agents** (every PostgreSQL server):
   - copy `pgvip-agent.jar` to `/opt/pgvip/`, `packaging/agent.properties.example` to
     `/etc/pgvip/agent.properties` (mode 600, owner postgres) and the node's `.p12`/`.pass` plus `ca.p12`/`ca.pass`;
   - install `packaging/sudoers-pgvip` as `/etc/sudoers.d/pgvip` with the node's VIP and interface;
   - set `wal_log_hints = on` (or use data checksums) so `pg_rewind` works, and give replicas a
     `replicator` role and `.pgpass`;
   - disable the PostgreSQL unit and enable `packaging/pgvip-agent.service`.
3. **Watcher** (one, or three for Raft): copy `pgvip-watcher.jar`, `packaging/watcher.yml.example` to
   `/etc/pgvip/watcher.yml`, create users with `echo 'pw' | java -jar pgvip-watcher.jar hash-password`, and
   enable `packaging/pgvip-watcher.service`. Back up `data-dir/secret.key`; with Raft copy it to all watchers.
4. **First start.** If one node already runs as primary, the watcher records it. Otherwise use
   *Start as primary* on one node in the console, then *Re-initialize* the others.

## Watcher

The watcher polls every agent (`poll-interval`), keeps the primary's lease, raises alerts and, on the leading
watcher, acts:

| Situation | What the watcher does |
| --- | --- |
| Primary lost for `failure-threshold` polls, agent still answers, PostgreSQL down | Restarts it in place (VIP first) once |
| Primary still lost | Replica confirmation (and Raft quorum), raise epoch, fence the old primary if reachable, lease wait (self-fence), VIP up on the best replica, promote, re-point the other replicas |
| Best replica cannot bring up the VIP | Failover fails, nothing is promoted; next attempt after `failover-retry` (another eligible replica is preferred) |
| No eligible replica (other DC with `DC_LOCAL`, too far behind, other timeline, `no-failover`) | Failover blocked, event and mail |
| Old primary or a stopped replica comes back | Re-pointed as a replica; `pg_rewind` when it diverged; `pg_basebackup` when auto reinit is on |
| A non-primary runs as primary, or holds the VIP | Fenced / VIP removed |

Operator actions (console or API, ADMIN only, written to the event log with the user name):

- **Switchover**: pre-checks, type the cluster name, then: pause automatic failover and raise the epoch,
  `CHECKPOINT`, clean stop of the primary with VIP removal, wait until the target has replayed the shutdown
  checkpoint, VIP check and VIP up on the target, promote, start the old primary as a replica, re-point the
  rest. If the VIP cannot be brought up, the target stays a replica and the old primary starts again as
  primary with its VIP.
- **Rejoin (pg_rewind)**, **Re-initialize (pg_basebackup)**, **Start as primary**, **Drop slot**.
- **Switches**: auto failover, maintenance mode, mail notifications, auto reinit.

### Raft mode

Set `pgvip.raft.enabled: true` and list the three (or five) watchers in `peers`. The Raft leader is the acting
watcher; followers poll and publish what they see, show the console read-only and link to the leader. Watchers
use the agent TLS stores between themselves (`raft.tls: true`). Self-fencing is recommended with Raft.

### REST API

HTTP Basic with the console users; reads need any user, changes need ADMIN.

| Call | Purpose |
| --- | --- |
| `GET /api/v1/cluster` | Overview: state, nodes, lag, lease, slots, watchers |
| `GET /api/v1/events?limit=` / `GET /api/v1/operations[/{id}]` | Event log / operations with steps and log |
| `GET /api/v1/switchover/checks?target=` | Pre-checks |
| `POST /api/v1/switchover` | `{"target":"pg-a2","confirm":"prod-main"}` |
| `POST /api/v1/nodes/{node}/rejoin` / `reinit` / `start-primary` | Node operations |
| `PUT /api/v1/controls` | `{"autoFailover":true,"maintenance":false,"mailEnabled":true,"autoReinit":false}` |
| `DELETE /api/v1/slots/{name}` | Drop an inactive slot that belongs to no node |
| `GET/PUT /api/v1/notifications/...` , `POST /api/v1/notifications/test` | Mail settings and test mail |

### Mail

SMTP server, recipient groups (all events / critical only / role changes), alert thresholds and per-event
rules with throttle windows are set on the *Notifications* page and shared by all watchers. The SMTP password
is stored AES-GCM encrypted with `secret.key` and never shown again. Only the leading watcher sends mail.

## Agent

```
java -jar pgvip-agent.jar /etc/pgvip/agent.properties
```

Runs as the `postgres` OS user and talks to PostgreSQL through `psql` over the Unix socket (peer
authentication, no stored password). All settings with defaults: `packaging/agent.properties.example`.

Every external command is a template and can be overridden (`pg.start.command`, `pg.stop.command`,
`vip.add.command`, `vip.del.command`, `vip.show.command`, `vip.dad.command`, `vip.garp.command`). Templates are
split on whitespace before placeholders are filled; no shell is involved.

### API

All calls are JSON over HTTPS with mutual TLS (optional bearer token as a second check). Every command that
changes the node carries `epoch`.

| Call | Purpose |
| --- | --- |
| `GET /v1/status` | Role, timeline, LSNs, replication, slots, VIP, lease, fence |
| `POST /v1/lease` | Grant or renew the primary lease (`ttlMs`, `fenceOnExpiry`) |
| `POST /v1/vip/acquire` / `release`, `GET /v1/vip/check` | VIP up / down / duplicate check |
| `POST /v1/pg/promote` | Promote; refused unless the VIP is up here |
| `POST /v1/pg/start` / `stop` / `checkpoint` | Lifecycle (`allowPrimary`, `mode`, `releaseVip`) |
| `POST /v1/pg/follow` | Point a replica at a new primary |
| `POST /v1/pg/demote` | Stop, optionally pg_rewind, start as replica (background) |
| `POST /v1/pg/reinit` | Rebuild with pg_basebackup (background) |
| `GET /v1/operations/{id}` | Progress of a background operation |
| `POST /v1/fence`, `/v1/fence/clear` | Self-fence / clear |
| `POST /v1/peer-check` | Can this node reach host:port (failover confirmation) |
| `POST /v1/slots/ensure`, `/v1/slots/drop` | Physical replication slots |

## Tests

`mvn verify` runs the unit tests: the watcher against an in-memory cluster that follows the agent's rules
(failover, "no VIP, no promote", replica confirmation, lease wait, DC scope, lag limit, switchover and its
rollback, rogue primary, rejoin, Raft quorum), three in-process Raft members, and the REST API and its roles.

Two opt-in tests use real PostgreSQL and must run as a non-root user:

```
PGVIP_IT_PGBIN=/usr/lib/postgresql/16/bin mvn -pl pgvip-common,pgvip-agent -Dtest=AgentPostgresIT -Dsurefire.failIfNoSpecifiedTests=false test
PGVIP_IT_PGBIN=/usr/lib/postgresql/16/bin mvn -pl pgvip-core -am -Dtest=WatcherPostgresIT -Dsurefire.failIfNoSpecifiedTests=false test
```

`WatcherPostgresIT` drives three instances through bootstrap, reinit, switchover, crash failover, automatic
rejoin, a failover refused because the VIP cannot come up, and the retry once it can.

## Limits

- One cluster per watcher (group). Run another watcher (group) for another cluster.
- A primary whose PostgreSQL hangs but keeps its port open is not failed over automatically (replicas still
  reach it); the console shows it and an operator decides.
- Wall clocks of the watchers should be in sync (NTP); Raft observations carry wall-clock times.
- Without self-fencing, a primary that is cut off from everyone but still serves clients on another network is
  only stopped from taking the VIP back; enable self-fencing (with Raft) where that matters.
