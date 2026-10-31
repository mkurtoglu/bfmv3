# BFM: PostgreSQL failover manager

Automatic failover for PostgreSQL streaming replication clusters, with a virtual IP that follows the primary.
A **MiniPG agent** (`minipgagent`) runs next to every PostgreSQL server; one or three **watchers** decide and
serve the web console.
Three watchers form an Apache Ratis (Raft) group; Raft is optional and a single watcher works on its own.

```
             clients ──► VIP 192.168.0.50 (on the primary)
                               │
   ┌───────────────┐   ┌───────┴───────┐   ┌───────────────┐
   │ pg-a1 primary │──►│ pg-a2 replica │   │ pg-dr1 replica│   PostgreSQL 13+ streaming replication
   │ minipgagent   │   │ minipgagent   │   │ minipgagent   │   agents: HTTPS + mutual TLS, port 8008
   └───────▲───────┘   └───────▲───────┘   └───────▲───────┘
           └─────────── poll, lease, commands ─────┘
                   ┌───────────────────────────┐
                   │ bfm-watcher (1 or 3)      │   console + REST API, port 8443
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
  (With `pg.autostart.replica=true` the agent starts a stopped *replica* when it comes up, so a rebooted
  replica is back without a watcher; it still never starts a primary, a fenced node, or a data directory
  whose rewind or rebuild was interrupted.)
- **Epochs.** Every role change raises the cluster epoch in the shared state with a compare-and-set, so only
  one watcher can win it, and the new epoch is announced to every agent. Agents refuse commands from older
  epochs, fencing included, so a watcher that has been superseded cannot steer or stop a node.
- **Lease and self-fencing.** The leading watcher renews a lease on the primary every poll. With
  `self-fence: true`, a primary that stops getting renewals releases the VIP and stops PostgreSQL, and the
  watcher waits until any lease it or a previous leader granted has run out (TTL + margin) before promoting.
  In Raft mode a leader confirms its leadership with a majority (ReadIndex) before every renewal. The agent keeps
  the lease in `state.dir/lease` with its wall-clock deadline, so a restarted agent still fences on time, and it
  fences whenever PostgreSQL runs but cannot be confirmed to be a replica.
- **Unknown outcomes are not guessed.** Commands get their own long timeout (`bfm.agent.command-timeout`).
  When a promote ends without a clear answer, the watcher waits to see whether the node becomes primary; it
  never takes the VIP away from it or restarts the old primary while that is unknown. Before each irreversible
  step an operation checks that its watcher still leads and still holds the cluster.
- **Confirmation before failover.** The watcher asks every reachable replica whether it still reaches the
  primary's PostgreSQL port or streams from it; any "yes" blocks the failover. In Raft mode a majority of
  watchers must also have seen the primary gone within `observation-fresh`.
- **Fencing.** A returning old primary, or any node that runs as primary although the state names another
  one, is fenced (VIP released, PostgreSQL stopped) and then rejoined as a replica with `pg_rewind`.
  A VIP found on a node that is not the primary is removed.
- **The right node, the right database.** Every agent is bound to one cluster and refuses commands for another;
  the watcher checks that each agent's certificate names its node, and that each node's PostgreSQL system
  identifier is the cluster's. A node that fails a check (a mistyped port reaching another cluster's agent, a
  data directory replaced by `initdb`) shows as *Wrong identity* and is left alone — never fenced, promoted,
  followed or given a slot — and if it is the primary, automatic failover is blocked instead of started.

## Modules

| Module | What it is | Dependencies |
| --- | --- | --- |
| `bfm-common` | Agent API model, JSON codec, LSN and TLS helpers | JDK only |
| `bfm-agent` | The MiniPG agent (`minipgagent.jar`): status probe, start/stop/promote/follow/demote/rewind/reinit, VIP, lease, mTLS API | JDK only |
| `bfm-core` | Watcher logic: polling, failover and switchover decisions, operations, alerts, mail rules | JDK only |
| `bfm-raft` | Shared watcher state replicated across three watchers | Apache Ratis 3.3 |
| `bfm-watcher` | Spring Boot 4 service: Vaadin 25 console, REST API, users, SMTP | Spring, Vaadin |

## Build

Java 21 and Maven 3.9:

```
mvn package
```

The jars are `bfm-agent/target/bfm-agent.jar` (JDK only, ~100 KB) and
`bfm-watcher/target/bfm-watcher.jar` (Spring Boot executable jar). The console uses Vaadin's pre-compiled
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

Step by step from a running replication cluster to BFM managing it, including PostgreSQL preparation,
certificates, the first failover test, Raft and troubleshooting: **[docs/install.md](docs/install.md)**.

### Rocky Linux / RHEL 8 and 9 (RPM)

```
packaging/rpm/build.sh            # builds the jars and target/rpm/el8/*.rpm, target/rpm/el9/*.rpm
```

The packages are noarch and need `java-21-openjdk-headless` (Rocky 8.9+ / 9.3+), which `dnf` pulls in.

| Package | What it installs |
| --- | --- |
| `minipgagent` | `/usr/share/bfm/minipgagent.jar`, `/usr/bin/minipgagent`, `/etc/bfm/minipgagent/agent.properties` (mode 600, owner postgres), `/etc/sudoers.d/minipgagent`, `/var/lib/minipgagent`, `minipgagent.service`; creates the `postgres` user if PostgreSQL is not installed yet |
| `bfm-watcher` | `/usr/share/bfm/bfm-watcher.jar`, `/usr/bin/bfm-watcher`, `/usr/bin/bfm-make-certs`, `/etc/bfm/watcher.yml` (mode 640, group bfm), `/var/lib/bfm-watcher`, `bfm-watcher.service`; creates the `bfm` user |

Configuration files are `%config(noreplace)`: an upgrade never overwrites them. An upgrade does not restart the
services either, because a restart would interrupt a running `pg_basebackup`, `pg_rewind` or switchover; restart
them when the cluster is idle. JVM options go into `/etc/bfm/minipgagent/agent.env`
(`MINIPGAGENT_JAVA_OPTS`) and `/etc/bfm/watcher.env` (`BFM_WATCHER_JAVA_OPTS`).

The short version:

1. **Certificates**: `bfm-make-certs /etc/bfm/certs pg-a1=10.0.0.11 pg-a2=10.0.0.12 w1=10.0.9.1,bfm.example.com`
   makes a private CA and one PKCS#12 store per agent and watcher. The addresses go into the certificates' SAN
   and must match the `agent-url` the watcher uses. Keep `ca.key` offline.
2. **Agents** (`minipgagent`, on every PostgreSQL server): install the RPM, edit
   `/etc/bfm/minipgagent/agent.properties` and `/etc/sudoers.d/minipgagent` (the node's VIP and interface),
   copy the certificates, set `wal_log_hints = on` for `pg_rewind`, then `systemctl disable postgresql-16`
   (the agent starts PostgreSQL) and `systemctl enable --now minipgagent`.
3. **Watcher** (one, or three for Raft): install the RPM, edit `/etc/bfm/watcher.yml`, create users with
   `echo 'pw' | bfm-watcher hash-password`, point `server.ssl` at the watcher's own PKCS#12 store so the
   console is HTTPS, then `systemctl enable --now bfm-watcher`. Back up
   `/var/lib/bfm-watcher/secret.key`; with Raft copy it to all watchers.
4. **First start**: if one node already runs as primary, the watcher records it. Otherwise use *Start as
   primary* on one node in the console, then *Re-initialize* the others.

### Other distributions

Copy the jars to `/usr/share/bfm/`, the scripts from `packaging/bin/` to `/usr/bin/`, the units from
`packaging/` to `/etc/systemd/system/`, and the example settings to `/etc/bfm/`; create the `bfm` user for
the watcher. Then follow [docs/install.md](docs/install.md) from the certificates onwards.

## Watcher

The watcher polls every agent (`poll-interval`), keeps the primary's lease, raises alerts and, on the leading
watcher, acts:

| Situation | What the watcher does |
| --- | --- |
| Primary lost for `failure-threshold` polls, agent still answers, PostgreSQL down | Restarts it in place (VIP first) once |
| Primary still lost | Replica confirmation (and Raft quorum), raise epoch, fence the old primary if reachable, lease wait (self-fence), VIP up on the best replica, promote, re-point the other replicas |
| Best replica cannot bring up the VIP | Failover fails, nothing is promoted; next attempt after `failover-retry` (another eligible replica is preferred) |
| No eligible replica (other DC with `DC_LOCAL`, too far behind, other timeline, `no-failover`) | Failover blocked, event and mail |
| Old primary or a stopped replica comes back | Re-pointed as a replica; `pg_rewind` when it diverged; `pg_basebackup` when auto re-initialize is on |
| A non-primary runs as primary, or holds the VIP | Fenced / VIP removed |

Operator actions (console or API, ADMIN only, written to the event log with the user name):

- **Switchover**: pre-checks, type the cluster name, then: pause automatic failover and raise the epoch,
  `CHECKPOINT`, clean stop of the primary with VIP removal, wait until the target has replayed the shutdown
  checkpoint, VIP check and VIP up on the target, promote, start the old primary as a replica, re-point the
  rest. If the VIP cannot be brought up, the target stays a replica and the old primary starts again as
  primary with its VIP.
- **Rejoin (pg_rewind)**, **Re-initialize (pg_basebackup)**, **Start as primary**, **Drop slot**.
- **Switches**: auto failover, maintenance mode, mail notifications, auto re-initialize. Turning failover off,
  maintenance on or auto re-initialize on asks first, and so does the reverse while the primary is down (it starts
  a failover at once).

### Several clusters, one console

One watcher (or one Raft group) can run several clusters: list them under `bfm.clusters` instead of
`bfm.cluster`. Each cluster gets its own engine (poll loop, failover decisions, operations), its own part of the
shared state and its own event log; a failover in one never waits for another. The SMTP server is shared,
recipients, rules and thresholds are each cluster's. Agents are bound to their cluster's id, so a cluster moved
here from another watcher keeps that id: set `bfm.clusters[].id` to the id its old console showed.

The console then adds a **Fleet** page (every cluster in one line, worst first), a cluster switcher in the header,
and cluster pages under `/c/<cluster>/...`. The **Hosts** page shows which instances of which clusters run on each
server. A watcher with one cluster looks as before; its old addresses (`/`, `/replication`, ...) still work.

Upgrading a one-cluster watcher keeps everything: on its first start the leading watcher moves the cluster's state
and events under the cluster (keeping its id) and leaves the old keys for a downgrade.

### Console

Every cluster page shows the cluster's name, its environment (`environment`; production gets a red bar) and
its health in the header. Light or dark follows the operating system, or what the operator picks in the header.
The console works on a phone: node tables turn into cards and less important columns step aside.

| Page | What it answers |
| --- | --- |
| Overview | Healthy or not and why, in one line at the top; the last failover or switchover (how long it took, how much was lost) until an admin acknowledges it; when the primary is down, why no failover runs and the switch that starts it; node states with how long they have been in them, topology with the VIP on the node that really holds it, the cluster switches (with who changed them last) and recent events. Node actions are in each row's menu |
| Replication | Both sides of the stream: the primary's `pg_stat_replication` rows (sent / written / flushed per walsender and what a crash failover would lose) and what each replica reports about itself (recovery state, upstream, timeline, received and replayed LSN). Replication slots with the WAL they retain, the last hour of replay lag with its peak, and the same node menu (Rejoin, Re-initialize) |
| Switchover | Pre-checks for a target, then the confirmed switchover |
| Operations | Running and finished operations with their steps, how long each step took, and the log |
| Events | The event log, filterable by severity and text (`events?q=pg-a2`); a row opens to its full report |
| Notifications | SMTP settings, recipients, alert thresholds (`512 KB`, `1 GB`), which events send mail, test mail; unsaved changes are marked |
| Fleet (several clusters) | Every cluster: environment, health and why, primary and its host, replicas streaming, max lag, last role change (⚑ until acknowledged) |
| Hosts | Every server the agents report (`/etc/machine-id`) with the instances on it; marks a cluster with more than one node on one host |

### Raft mode

Set `bfm.raft.enabled: true` and list the three (or five) watchers in `peers`. The Raft leader is the acting
watcher; followers poll and publish what they see, show the console read-only and link to the leader. Watchers
use the agent TLS stores between themselves (`raft.tls: true`). Self-fencing is recommended with Raft.

### REST API

HTTP Basic with the console users; reads need any user, changes need ADMIN. Every call below exists for each
cluster as `/api/v1/clusters/{cluster}/...` (for example `/api/v1/clusters/erp/switchover`); the short form
`/api/v1/...` answers while the watcher runs one cluster, and 409 with the cluster names when it runs several. Answers are always JSON, errors
included: 401 with a `WWW-Authenticate` header when credentials are missing or wrong, 403 when a viewer tries
to change something, 409 when the cluster state does not allow it (`{"error":"..."}`, with `leader` when
another watcher leads), 400 for a bad request and 502 when an agent cannot be reached. The API never redirects
to the console's login page, so a monitoring script can rely on the status code.

| Call | Purpose |
| --- | --- |
| `GET /api/v1/clusters` | Every cluster: health and reasons, primary and its host, replicas, lag, last role change |
| `GET /api/v1/hosts` | Every host with the instances of every cluster on it |
| `GET /api/v1/cluster` (`/api/v1/clusters/{cluster}`) | Overview: state, nodes, lag, lease, slots, watchers, `healthReasons`, `roleChange`, `clusterId` |
| `GET /api/v1/role-change` / `POST /api/v1/role-change/acknowledge` | The last failover or switchover / mark it seen (ADMIN) |
| `GET /api/v1/replication` | Replication: walsender rows, each replica's own view, slots, lag |
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

With the default STARTTLS setting the session is encrypted before the credentials are sent, and a server that
does not offer STARTTLS is refused rather than talked to in clear. The server's certificate is checked against
the JVM's trust store; an SMTP server with an internal CA needs that CA added, for example in
`/etc/bfm/watcher.env`:

```
BFM_WATCHER_JAVA_OPTS="-Xmx512m -Djavax.net.ssl.trustStore=/etc/bfm/smtp-ca.p12 -Djavax.net.ssl.trustStoreType=PKCS12 -Djavax.net.ssl.trustStorePassword=..."
```

This affects mail only: the agent calls and the Raft traffic use their own stores from `bfm.agent.tls`.
*Send test mail* on the Notifications page shows the SMTP server's own error when something is wrong.

## Agent (`minipgagent`)

```
minipgagent            # or: java -jar minipgagent.jar /etc/bfm/minipgagent/agent.properties
systemctl enable --now minipgagent@crm   # one more instance on this host: /etc/bfm/minipgagent/crm/
```

Runs as the `postgres` OS user and talks to PostgreSQL through `psql` over the Unix socket (peer
authentication, no stored password). Every setting with its default and what it does:
[docs/agent-config.md](docs/agent-config.md); the file to copy is `packaging/agent.properties.example`.

Every external command is a template and can be overridden (`pg.start.command`, `pg.stop.command`,
`vip.add.command`, `vip.del.command`, `vip.show.command`, `vip.dad.command`, `vip.garp.command`). Templates are
split on whitespace before placeholders are filled; no shell is involved.

### API

All calls are JSON over HTTPS with mutual TLS (optional bearer token as a second check). Every command that
changes the node carries `epoch` and `clusterId`; a bound agent refuses another cluster's commands before it
looks at the epoch.

| Call | Purpose |
| --- | --- |
| `GET /v1/status` | Role, timeline, LSNs, replication, slots, VIP, lease, fence, system identifier, and `identity` (bound cluster, instance, host id, host name) |
| `POST /v1/lease` | Grant or renew the primary lease (`ttlMs`, `fenceOnExpiry`) |
| `POST /v1/vip/acquire` / `release`, `GET /v1/vip/check` | VIP up / down / duplicate check |
| `POST /v1/pg/promote` | Promote; refused unless the VIP is up here |
| `POST /v1/pg/start` / `stop` / `checkpoint` | Lifecycle (`allowPrimary`, `mode`, `releaseVip`) |
| `POST /v1/pg/follow` | Point a replica at a new primary |
| `POST /v1/pg/demote` | Stop, optionally pg_rewind, start as replica (background) |
| `POST /v1/pg/reinit` | Re-initialize with pg_basebackup (background) |
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
BFM_IT_PGBIN=/usr/lib/postgresql/16/bin mvn -pl bfm-common,bfm-agent -Dtest=AgentPostgresIT -Dsurefire.failIfNoSpecifiedTests=false test
BFM_IT_PGBIN=/usr/lib/postgresql/16/bin mvn -pl bfm-core -am -Dtest=WatcherPostgresIT -Dsurefire.failIfNoSpecifiedTests=false test
```

`WatcherPostgresIT` drives three instances through bootstrap, reinit, switchover, crash failover, automatic
rejoin, a failover refused because the VIP cannot come up, and the retry once it can.

## Limits

- Several clusters can share servers (one agent instance per PostgreSQL instance,
  [docs/install.md §10](docs/install.md#10-several-clusters-on-one-host)) and one watcher (group) can run many of
  them; clusters are defined in `watcher.yml` (creating them from the console is the next step of
  [docs/design/multi-cluster.md](docs/design/multi-cluster.md)). One HTTP client serves every cluster's agents,
  with the longest `agent-timeout` of them.
- A primary whose PostgreSQL hangs but keeps its port open is not failed over automatically (replicas still
  reach it); the console shows it and an operator decides.
- Wall clocks of the watchers should be in sync (NTP); Raft observations carry wall-clock times.
- Without self-fencing, a primary that is cut off from everyone but still serves clients on another network is
  only stopped from taking the VIP back; enable self-fencing (with Raft) where that matters.
