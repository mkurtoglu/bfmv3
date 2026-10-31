# Design: several clusters per server, and the installation wizards

Status: **proposal**, for review before implementation.

BFM today manages one PostgreSQL cluster per watcher, and its packages install one agent per server. This design
lets one server carry several PostgreSQL clusters, lets one watcher (or one Raft group of three) run all of them
from a single console, and adds two wizards that take the hand-editing out of installation: one on each
database server, one in the console.

---

## 1. Summary

| Today | After |
| --- | --- |
| One `minipgagent` per server, fixed paths | One agent **instance** per PostgreSQL instance: `minipgagent@<instance>`, its own directory, port, VIP and sudo rule |
| One cluster per watcher process | One watcher (group) runs **many clusters**; each keeps its own failover loop, epoch, lease and operations |
| Nothing ties an agent to a cluster | Every agent is **bound** to one cluster id, one node name and one PostgreSQL system identifier; anything else is refused |
| Certificates made by hand with `bfm-make-certs` | **Enrollment**: a one-time token from the console, the node wizard does the rest (offline certificates stay possible) |
| `agent.properties`, `watcher.yml`, sudoers and pg_hba edited by hand | `minipgagent setup` on the server, **Add cluster** in the console, `bfm-watcher setup` for the first start |
| The console shows one cluster | A **Fleet** page, a **Hosts** page and a cluster switcher; every cluster page as today |

Three phases deliver this, each useful on its own (§12). The first one is small and closes a safety gap that opens
the moment anyone runs two clusters on one server today (§3).

---

## 2. Words

- **Host**: one server (physical or VM), identified by its `/etc/machine-id`.
- **Instance**: one PostgreSQL data directory with its own port, on one host. A host can carry several.
- **Agent instance**: the `minipgagent` process that manages exactly one instance.
- **Cluster**: a primary and its replicas, spread over hosts. Has a name, a generated **cluster id** (UUID) and,
  once it has run, a PostgreSQL **system identifier** that all its data directories share.
- **Node**: one instance's place in one cluster (`pg-a1`).

```
 host db01                          host db02                          host db03 (DR)
 ┌────────────────────────────┐     ┌────────────────────────────┐     ┌──────────────────────────┐
 │ minipgagent@erp   :8008    │     │ minipgagent@erp   :8008    │     │ minipgagent@erp  :8008   │
 │   PG 5432  erp  PRIMARY    │     │   PG 5432  erp  replica    │     │   PG 5432  erp  replica  │
 │ minipgagent@crm   :8009    │     │ minipgagent@crm   :8009    │     └──────────────────────────┘
 │   PG 5433  crm  replica    │     │   PG 5433  crm  PRIMARY    │
 └────────────────────────────┘     └────────────────────────────┘
                 ▲                                ▲
                 └──────── bfm-watcher (1 or 3): clusters erp and crm, one console ──────┘
```

---

## 3. Safety first: what goes wrong today with two clusters on a host

Running two agents on one host already works (the lab does it). What is missing is anything that stops one
cluster's watcher from steering the other cluster's instance:

1. **A wrong port is enough.** If cluster `erp` lists `https://db01:8009` (crm's agent) instead of `:8008`, the
   watcher talks to crm's instance as if it were erp's: it can fence it, point it at erp's primary, or promote it.
   Epochs do not help: they are plain numbers per agent, not tied to a cluster. The watcher never compares
   PostgreSQL system identifiers today.
2. **Two agents can share a state directory.** Nothing locks `state.dir`; a copied `agent.properties` with the
   default `/var/lib/minipgagent` makes two agents share one epoch, fence flag and lease.
3. **The hardware watchdog is per host.** Two agents arming `/dev/watchdog` conflict, and its reset takes down every
   instance on the host.
4. **A host failure is several failovers at once.** If db01 holds the primaries of three clusters, losing db01 fails
   over three clusters at the same moment, and nobody was told that risk existed.
5. **Two nodes of the same cluster on one host give no HA**, and nothing says so.

The design answers each:

| Risk | Guard |
| --- | --- |
| Wrong agent for a node | **Binding**: the agent stores `cluster id + node name` (state dir, file `binding`) and refuses any command whose `clusterId` differs, with 409 *"this agent belongs to cluster crm as pg-b1"*. Every command that changes the node already carries `epoch`; it carries `clusterId` too. |
| Wrong data under a right agent | **System identifier guard**: the watcher records the cluster's system identifier at bootstrap. A node whose PostgreSQL reports another one is marked *foreign data directory*: never promoted, never rewound, never followed; only **Re-initialize** (which replaces it with the cluster's data) is offered. |
| Wrong certificate | The watcher checks that the agent's certificate names the node it expects (CN = node name, as `bfm-make-certs` and enrollment issue them). A port mix-up fails the TLS identity check before any command is sent. |
| Shared state directory | The agent takes an exclusive `flock` on `state.dir/lock` at start and refuses to run if it is held: *"state directory /var/lib/minipgagent/erp is in use by PID 4711"*. |
| Watchdog | A host-wide lock (`/run/minipgagent/watchdog.lock`). The second agent with `watchdog.enabled=true` refuses to start. The wizard turns the watchdog off when a host carries more than one instance. |
| Primaries piled on one host | The agent reports its `hostId` and hostname; the console shows primaries per host and warns above a threshold (§7.3). An advisory **Balance** suggests switchovers; nothing moves on its own. |
| Two nodes of one cluster on one host | The wizards refuse it unless *Allow co-location (lab only)* is ticked; the console marks such a cluster *No host redundancy*. |
| Many rebuilds hitting one host's disks | Re-initialize and rejoin are queued per host (default: one at a time per host), visible in Operations. |

An agent installed the old way has no binding. The first watcher that sends it a command with a cluster id binds
it (trust on first use), and the console says so in the event log. The wizards always bind explicitly.

---

## 4. Goals and non-goals

Goals
- Several PostgreSQL instances per host, each with its own agent instance, port, VIP, sudo rule and lifecycle.
- One watcher process (or one Raft group of three) running many clusters, with one console and one API.
- Installation without hand-editing: discovery of PostgreSQL on the host, checks with exact fixes, enrollment,
  certificates, sudoers, systemd, and a guided cluster creation in the console with live checks.
- Every existing single-cluster installation keeps working unchanged after an upgrade.

Non-goals (this design)
- Installing PostgreSQL itself, or creating the first data directory. The wizards work with instances that exist.
- Changing PostgreSQL settings or restarting PostgreSQL without an operator's explicit yes on that step.
- Per-cluster user permissions: designed for (§8.4), delivered later.
- Moving a primary automatically to balance hosts.

---

## 5. Agent: one instance per PostgreSQL instance

### 5.1 One process per instance, not one per host

| | One agent process per instance (**chosen**) | One agent per host serving several instances |
| --- | --- | --- |
| Isolation | A crash, hang or upgrade touches one cluster | One JVM down = every cluster on the host blind |
| Leases, epochs, fencing | Independent by construction | Must be partitioned inside one process |
| Ports, certificates | One port per instance (8008, 8009, …) | One port |
| Code change | Small: it already works this way | Large rewrite of the agent |

### 5.2 Layout

| What | Path |
| --- | --- |
| Settings | `/etc/bfm/minipgagent/<instance>/agent.properties` |
| Environment | `/etc/bfm/minipgagent/<instance>/agent.env` |
| Certificates | `/etc/bfm/minipgagent/<instance>/{node.p12,node.pass,ca.p12,ca.pass}` |
| State | `/var/lib/minipgagent/<instance>/` (epoch, fence, lease, binding, lock, interrupted-operation mark) |
| Sudo rule | `/etc/sudoers.d/minipgagent-<instance>` (that instance's VIP and interface only) |
| Service | `minipgagent@<instance>.service` (template unit) |

`<instance>` is `[a-z0-9-]{1,32}`, by default the cluster name. The current single layout
(`/etc/bfm/minipgagent/agent.properties`, `minipgagent.service`, `/var/lib/minipgagent`) stays valid as the
*default instance*; `minipgagent setup --adopt-default <name>` moves it into the new layout when wanted.

The template unit:

```ini
[Unit]
Description=MiniPG agent %i
After=network-online.target
Wants=network-online.target

[Service]
User=postgres
Group=postgres
EnvironmentFile=-/etc/bfm/minipgagent/%i/agent.env
ExecStart=/usr/bin/minipgagent /etc/bfm/minipgagent/%i/agent.properties
Restart=always
RestartSec=2
KillMode=process
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
```

### 5.3 Agent changes

- `cluster.id` in `agent.properties` (written by the wizard) and the binding check on every command (§3).
- `state.dir` lock; host-wide watchdog lock.
- `/v1/status` adds `clusterId`, `instance`, `hostId` (`/etc/machine-id`), `hostname`, data directory size and free
  space on its filesystem (for re-initialize planning), and the PostgreSQL system identifier it already reports.
- `/v1/checks` (new, read only): the readiness checks of §9.2 step 5, so the console wizard can show them live.
- Nothing else in the API changes; old watchers that send no `clusterId` keep working against unbound agents.

---

## 6. Watcher: many clusters in one process

### 6.1 Structure

```
 bfm-watcher
 ├── ClusterRegistry      which clusters exist: from watcher.yml (file-managed) and from the store (wizard-managed)
 ├── ClusterEngine erp    = today's Watcher: poll loop, failover decisions, operations, lease clock, events
 ├── ClusterEngine crm    ...
 ├── Shared               users, SMTP server, Raft group and leadership, enrollment/CA, host index
 └── Console + REST API   fleet, hosts, and every cluster page under /c/<cluster>/
```

- **Engines** are today's `Watcher` class, one per cluster, each with its own loop thread and operation executors.
  A failover in `erp` and a re-initialize in `crm` run side by side; a stuck operation in one cluster never blocks
  another.
- **State** stays in one store (local file or the Raft group). Each engine sees a *scoped* view whose keys are
  prefixed `clusters/<cluster-id>/` (`cluster`, `role-change`, `primary-position`, `observation/…`, events,
  notification rules). The engine code does not change: it already takes a `StateStore`.
- **Leadership** is one for all clusters: the Raft leader runs every engine, followers poll and observe every
  cluster, exactly as today for one. No per-cluster leader election.
- **Registry entries** carry: id, name, environment, nodes, timing, scope, self-fence, VIPs, and whether the
  file or the console owns it. File-managed clusters are read-only in the console (*"defined in watcher.yml"*).
- **Adding a cluster** starts its engine at once; no restart. **Removing** one is a *detach* (§9.5), never a plain
  delete: an engine that stops renewing a fencing lease would make that primary stop itself.

### 6.2 Configuration

```yaml
bfm:
  clusters:                       # new: a list; bfm.cluster (one) is still accepted
    - name: erp
      environment: production
      nodes: [ ... ]
    - name: crm
      environment: production
      nodes: [ ... ]
  console:
    co-location: block            # block | warn: two nodes of one cluster on one host
    primaries-per-host-warning: 2 # warn when a host holds more primaries than this
  rebuilds-per-host: 1            # concurrent re-initialize / rejoin per host
```

Clusters created in the console are not written back to `watcher.yml`; they live in the store and are shown with
an export button (YAML) for whoever wants them under configuration management.

### 6.3 Scale

Polling cost is nodes ÷ poll interval: 30 clusters × 3 nodes at 2 s is 45 agent calls per second, well within one
process. The console and the API compute a cluster's read model only when it is looked at; the fleet page reads a
small cached summary per cluster that each engine refreshes once per poll.

### 6.4 Upgrade from one cluster

On the first start of the new version, when the store has the old single-cluster keys and no registry:

1. create a registry entry for `bfm.cluster` with a new cluster id;
2. copy `cluster`, `role-change`, `primary-position`, observations, events and notification settings under
   `clusters/<id>/`; keep the old keys untouched (a downgrade still finds them);
3. write `migrated-to-registry` with the version; the next start skips this.

With Raft, the leader runs the migration once; followers see it replicated. The console's single-cluster URLs
(`/`, `/replication`, …) redirect to `/c/<name>/…` while there is exactly one cluster, so bookmarks keep working.

---

## 7. Console

### 7.1 Navigation

```
 ┌──────────────────────────────────────────────────────────────────────────────────────────┐
 │ ≡  BFM   [ erp ▾ ] PRODUCTION ● Healthy          Fleet: 11 healthy · 1 degraded   ◐  ⇥   │
 ├──────────────┬───────────────────────────────────────────────────────────────────────────┤
 │ Fleet        │                                                                           │
 │ Hosts        │   (cluster pages as today: Overview, Replication, Switchover,             │
 │ ── erp ──    │    Operations, Events, Notifications — all under /c/erp/...)              │
 │ Overview     │                                                                           │
 │ Replication  │                                                                           │
 │ Switchover   │                                                                           │
 │ Operations   │                                                                           │
 │ Events       │                                                                           │
 │ Notifications│                                                                           │
 │ ── Settings ─│                                                                           │
 │ Add cluster  │                                                                           │
 │ Mail server  │                                                                           │
 └──────────────┴───────────────────────────────────────────────────────────────────────────┘
```

- The **cluster switcher** lists clusters worst first, each with its health dot and environment; type to filter.
- URLs: `/fleet`, `/hosts`, `/clusters/new`, `/c/<cluster>/overview|replication|switchover|operations|events|notifications`.
- With a single cluster the switcher and the Fleet page stay hidden: the console looks as it does today.

### 7.2 Fleet

```
 Fleet                                                    [ All environments ▾ ] [ Problems first ▾ ]
 ┌────────┬────────────┬──────────────┬───────────────────┬──────────┬─────────┬────────────────────┐
 │ Cluster│ Env        │ State        │ Primary (host)    │ Replicas │ Max lag │ Last role change   │
 ├────────┼────────────┼──────────────┼───────────────────┼──────────┼─────────┼────────────────────┤
 │ crm    │ PRODUCTION │ ● Degraded   │ pg-b2 (db02)      │ 1/2      │ 12 MB   │ failover 09:12 ⚑   │
 │ erp    │ PRODUCTION │ ● Healthy    │ pg-a1 (db01)      │ 2/2      │ 0 B     │ —                  │
 │ bi     │ TEST       │ ● Healthy    │ pg-c1 (db04)      │ 1/1      │ 0 B     │ switchover Tue     │
 └────────┴────────────┴──────────────┴───────────────────┴──────────┴─────────┴────────────────────┘
  ⚑ not acknowledged yet
```

### 7.3 Hosts

```
 Hosts
 db01  (machine-id 3f2a…)                                          ⚠ 2 primaries on this host
   erp   pg-a1  PRIMARY   PG 5432  agent :8008  VIP 192.168.0.50
   crm   pg-b1  replica   PG 5433  agent :8009  VIP 192.168.0.60
   → A crash of db01 fails over erp and crm at once.  [ Suggest balance ]
 db02
   erp   pg-a2  replica   PG 5432  agent :8008
   crm   pg-b2  PRIMARY   PG 5433  agent :8009
```

*Suggest balance* lists the switchovers that would leave at most the configured number of primaries per host; each
one is started by hand on its own Switchover page, with its own checks and confirmation.

### 7.4 API

| Call | Purpose |
| --- | --- |
| `GET /api/v1/clusters` | Every cluster with its health, primary, environment |
| `/api/v1/clusters/{name}/…` | Every call that exists today, for that cluster |
| `GET /api/v1/hosts` | Hosts and their instances |
| `POST /api/v1/clusters` / `DELETE /api/v1/clusters/{name}` | Create (what the wizard does) / detach |
| `POST /api/v1/clusters/{name}/enrollments` | One-time enrollment token for a node |
| `/api/v1/cluster`, `/api/v1/replication`, … (today's) | Still answer while there is exactly one cluster; with more, 409 and the list of names |

---

## 8. Shared settings

### 8.1 Mail
The SMTP server stays global. Recipients and event rules are per cluster and start as a copy of a chosen cluster's
(or the defaults). Subjects already carry the cluster name.

### 8.2 Certificates and the CA
Two ways, chosen at watcher setup:

- **Enrollment (recommended)**: the watcher holds the BFM CA. Its key is stored encrypted with `secret.key` (and
  replicated with Raft, so any leader can sign). The node wizard sends a CSR with the token and gets back its
  certificate; nobody copies `.p12` files around.
- **Offline**: as today, `bfm-make-certs` on a separate machine; the node wizard takes a certificate bundle path
  instead of a token. The watcher then never holds a CA key.

### 8.3 Enrollment tokens
- 128 random bits, shown once, stored only as a hash.
- Valid 30 minutes, usable once, bound to cluster id + node name (+ expected host, when given).
- The command the console prints pins the watcher's certificate fingerprint, so the node trusts the right watcher
  even with a self-signed console certificate:
  `sudo minipgagent setup --enroll https://bfm.example.com:8443 --token 7Q2…Kx --fingerprint SHA256:4b:9e:…`
- Every issue, use and expiry is written to the event log.

### 8.4 Permissions (later)
Users keep their global role. The registry and the API are shaped so a user can later be limited to some clusters
(`users[].clusters: [erp, crm]`) without changing URLs.

---

## 9. The installation wizards

Three entry points, one per moment:

| When | Where | Command / page |
| --- | --- | --- |
| First start of a watcher | watcher host, terminal | `bfm-watcher setup` |
| Creating a cluster, adding a node | console | **Add cluster**, **Add node** |
| Preparing each PostgreSQL instance | database host, terminal | `minipgagent setup` |

All of them are in Java (no Python, no extra tools): the terminal wizards are a subcommand of the jars already
installed; the console wizards are Vaadin views.

### 9.1 `bfm-watcher setup` (once per watcher)

1. Watcher id, data directory, time zone, console port.
2. First admin user (password asked twice, hashed).
3. TLS for the console: generate a CA and a server certificate (names and IPs asked, defaults from the host), or
   point to an existing PKCS#12 store.
4. CA mode for agents: enrollment (CA kept by the watcher) or offline.
5. Raft: alone, or one of three (peer addresses; the first watcher generates `secret.key` and the CA; the others
   are given an enrollment-like join token to fetch them over TLS).
6. Writes `watcher.yml` and `watcher.env`, enables the service, prints the console URL and the certificate
   fingerprint to give to browsers.

A watcher with no cluster opens the console on **Add cluster**.

### 9.2 `minipgagent setup` (on each database host)

Interactive in a terminal, or non-interactive with `--answers file` for Ansible; `--dry-run` prints every change
without making it. It is safe to run again: what is already right is left alone.

**Step 1 – Discover**

```
 MiniPG agent setup                                              host db01 · machine-id 3f2a…
 PostgreSQL on this host
   #  version  data directory               port  state     role     managed by
   1  16.4     /var/lib/pgsql/16/data       5432  running   primary  minipgagent@erp
   2  16.4     /var/lib/pgsql/16/crm        5433  running   replica  —
   3  15.8     /var/lib/pgsql/15/data       5434  stopped   ?        —
 Choose the instance to set up [2]:
```

Found from running postmasters, `postmaster.pid` files, the PGDG and Debian default locations, and
`pg_lsclusters` where it exists. An instance already managed by an agent instance is shown, not offered again.

**Step 2 – Join a cluster**

```
 Enrollment token (from the console, Add cluster / Add node), or "offline" for a certificate bundle:
 > 7Q2…Kx
 Watcher https://bfm.example.com:8443  fingerprint SHA256:4b:9e:…  ✔ matches
 This instance becomes node pg-b1 of cluster crm (PRODUCTION), data center DC1.
```

**Step 3 – Network**

```
 Agent port [8009]  (8008 is used by minipgagent@erp)
 VIP for crm in DC1: 192.168.0.60/24
   Interface: [1] eth0 192.168.0.11/24   [2] eth1 10.1.0.11/24        > 1
   ✔ 192.168.0.60 is in eth0's subnet   ✔ nobody answers for it (arping -D)
```

**Step 4 – PostgreSQL readiness** (also served as `/v1/checks` for the console)

```
 ✔ version 16.4, same as the other nodes of crm
 ✔ system identifier 7301…  same as crm's primary
 ✖ wal_log_hints is off and data checksums are off — pg_rewind cannot work
     fix: ALTER SYSTEM SET wal_log_hints = on;   (takes effect after a restart)
 ✔ max_wal_senders 10, max_replication_slots 10
 ✖ pg_hba.conf has no replication entry for 192.168.0.12 (pg-b2) and 10.20.0.11 (pg-b3)
     fix: add 2 lines (shown below) and reload
 ✔ role replicator exists, can log in, has REPLICATION
 ! .pgpass has no line for replicator — will be written (0600, owner postgres)
 ✔ 210 GB free next to a 48 GB data directory (enough for a re-initialize)
 Apply the fixes now? Each one is shown again before it is made.  [y/N]
```

The wizard never restarts PostgreSQL by itself. A setting that needs a restart is written and reported:
*"restart crm on db01 in a maintenance window, then run setup again"*. Changes to `pg_hba.conf` are followed by a
reload, which drops no connections.

**Step 5 – Review and apply**

```
 This will:
   write   /etc/bfm/minipgagent/crm/agent.properties      (node pg-b1, cluster crm, port 8009)
   write   /etc/bfm/minipgagent/crm/node.p12 + ca.p12     (signed by the watcher's CA)
   write   /etc/sudoers.d/minipgagent-crm                 (192.168.0.60/24 on eth0; checked with visudo)
   write   /var/lib/pgsql/.pgpass                         (1 line added)
   disable postgresql-16-crm.service                      (the agent starts PostgreSQL from now on)
   enable  minipgagent@crm.service and start it
   watchdog: off (this host runs 2 instances)
 Proceed? [y/N]
```

**Step 6 – Verify**: waits until the agent answers and the watcher reports it online, then prints the next step
(*"back in the console, crm shows pg-b1 enrolled"*).

Undo: `minipgagent remove crm` stops and disables the instance, removes its sudo rule and settings (kept as a
`.bak`), and offers to re-enable the PostgreSQL unit. PostgreSQL keeps running and its data is not touched.

### 9.3 Console: **Add cluster**

Six steps, with a draft saved after each, so an installation spread over a day can be resumed.

```
 Add cluster    ① Basics  ② Nodes  ③ Enroll  ④ Checks  ⑤ Start  ⑥ Review
```

1. **Basics**: name (unique), environment, failover scope, self-fencing (recommended with Raft), timings under
   *Advanced*, mail rules copied from another cluster or the defaults.
2. **Nodes**: one row per node: name, data center, priority, never promote automatically; one VIP per data center;
   the replication role name. Expected host is optional (filled in by enrollment otherwise).
3. **Enroll**: one command per node, with a copy button. Status per node updates live:

   ```
   pg-b1  ● enrolled   db01 · PG 16.4 · port 5433 · agent :8009 online
   pg-b2  ● waiting    command copied 2 min ago                      [ New token ]
   pg-b3  ● enrolled   db03 · PG 16.4 · port 5432 · agent :8008 online
   ```

4. **Checks**, live, per node and across nodes:

   | Check | Blocks creation |
   | --- | --- |
   | Same major version everywhere | yes |
   | One system identifier for all data directories (or none yet) | yes |
   | At most one node runs as primary | yes |
   | No two nodes on one host (unless lab co-location is allowed) | yes / warn |
   | VIP free, sudo rule works (dry run), VIP in the interface's subnet | yes |
   | `wal_log_hints` or checksums | warn (rejoin falls back to re-initialize) |
   | Replicas stream from the primary; slots possible | warn |
   | Clock difference under 2 s between agents and watcher | warn |
   | Free space for a re-initialize | warn |
   | Primaries per host after creation within the configured limit | warn |

5. **Start**: *Adopt* when one node already runs as primary (the watcher records it and creates the slots), or
   *Build* when none does (choose the primary: Start as primary, then Re-initialize the others, with live
   progress).
6. **Review**: the whole cluster on one screen; typing the cluster name creates it. The engine starts and the
   console opens the new cluster's Overview.

### 9.4 Console: **Add node**
Steps 2–5 for one node in an existing cluster, ending with how it joins: follow (already a streaming replica),
rejoin (pg_rewind), or re-initialize.

### 9.5 Remove a node, detach a cluster
**Detach** is ordered so that nothing stops on its own:
1. pause automatic failover for the cluster;
2. grant the primary a final lease *without* fencing, so it never self-fences when renewals stop;
3. clear the binding on every agent of the cluster;
4. stop the engine and remove the registry entry (the events stay, read-only, for the record).

PostgreSQL keeps running on every node; the agents keep running unbound until `minipgagent remove` is run on
each host. Typed confirmation with the cluster name; written to the event log.

---

## 10. Packaging

- `minipgagent` RPM: adds `minipgagent@.service`, keeps `minipgagent.service` for the default instance, creates
  `/var/lib/minipgagent` with one directory per instance on demand. `minipgagent setup` and `minipgagent remove`
  are subcommands of the existing launcher.
- `bfm-watcher` RPM: unchanged files; `bfm-watcher setup` subcommand.
- No new runtime dependency: the terminal wizards use plain Java console I/O (works over SSH, no curses).

---

## 11. Testing

- Unit: registry and scoped store, migration from one cluster (and the downgrade path), binding refusals,
  system identifier guard, certificate name check, state directory lock, enrollment tokens (expiry, reuse, wrong
  node), detach order, discovery parsers (`postmaster.pid`, `pg_lsclusters` output, PGDG layouts).
- Lab: two clusters on the same three machines (six instances), a "host crash" taking down two primaries at once,
  a deliberately wrong agent port (must be refused), co-location warnings, enrollment end to end.
- Console: Playwright runs through Add cluster, including resuming a draft and a failing check.
- Real PostgreSQL: the existing `AgentPostgresIT` and `WatcherPostgresIT`, plus one for two clusters side by side.

---

## 12. Phases

| Phase | Delivers | Size | Useful on its own because |
| --- | --- | --- | --- |
| **1. Host safety** | template unit and instance layout, state lock, watchdog host lock, binding with cluster id, system identifier guard, certificate name check, host id in status, docs for "several clusters per host" with one watcher per cluster | S–M | Several clusters per host become safe today, with one watcher per cluster |
| **2. Multi-cluster watcher** | registry, engines, scoped store, migration, Fleet and Hosts pages, switcher, `/api/v1/clusters/…` | L | One console for every cluster |
| **3. Terminal wizards** | `minipgagent setup` / `remove`, `bfm-watcher setup`, enrollment and the CA | M | No hand-edited files on the servers |
| **4. Console wizards** | Add cluster, Add node, Detach, drafts, live checks, balance suggestions | M–L | Clusters created and grown from the console |

Phase 1 should come first whatever is decided about the rest: the gap in §3 exists as soon as two clusters share a
host.

---

## 13. Decisions needed

| # | Question | Proposal |
| --- | --- | --- |
| 1 | Does the watcher hold the agents' CA (enrollment), or only the offline flow? | Enrollment by default, offline always available |
| 2 | Two nodes of one cluster on one host | Blocked; *lab only* override |
| 3 | One agent process per instance, or one per host | Per instance (§5.1) |
| 4 | May the wizards change PostgreSQL settings and `pg_hba.conf`? | Yes, each change shown and confirmed; never a restart |
| 5 | One watcher process for all clusters, or one per cluster | One (a `bfm-watcher@.service` per cluster stays possible for strict separation) |
| 6 | Per-cluster permissions in this work or later | Later; the shapes are ready (§8.4) |
| 7 | Primaries-per-host warning threshold | 2 |
