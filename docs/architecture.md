# BFM architecture

*English · [Türkçe](architecture.tr.md)*

How BFM is built and why: the parts, what each one knows, how they agree on who the primary is, and which
mechanism stops which failure. For installing it see [install-rpm.md](install-rpm.md), for every setting
[agent-config.md](agent-config.md) and [install.md](install.md), for the console [user-guide.md](user-guide.md),
and for the decisions behind several clusters per server [design/multi-cluster.md](design/multi-cluster.md).

---

## 1. What BFM does, and what it does not

BFM keeps a PostgreSQL streaming replication cluster writable when its primary fails, and moves a virtual IP
(VIP) to whichever node is primary, so clients keep one address. It also runs the planned role changes
(switchover), brings failed nodes back as replicas (`pg_rewind`, `pg_basebackup`), raises alerts and mails, and
shows all of it in a web console and a REST API.

It does not proxy or pool connections, take backups, or manage logical replication. PostgreSQL's own streaming
replication carries the data; BFM decides roles and moves the VIP.

## 2. The parts

```
                     operators, scripts
                           │ HTTPS 8443 (console, REST API)
              ┌────────────┴────────────┐
              │  bfm-watcher  (1 or 3)  │◄──── Raft 9870 ────► other watchers
              │  decisions, console,    │      (shared state, leader election)
              │  API, mail, CA          │
              └──┬──────────┬────────┬──┘
   HTTPS 8008,   │          │        │      poll every 2 s, lease, commands
   mutual TLS    ▼          ▼        ▼
          ┌──────────┐ ┌──────────┐ ┌──────────┐
          │ agent    │ │ agent    │ │ agent    │   minipgagent@<instance>, user postgres
          │ pg-a1    │ │ pg-a2    │ │ pg-dr1   │   one per PostgreSQL instance
          ├──────────┤ ├──────────┤ ├──────────┤
          │PostgreSQL│►│PostgreSQL│ │PostgreSQL│   streaming replication 5432
          │ primary  │ │ replica  │ │ replica  │
          └────┬─────┘ └──────────┘ └──────────┘
               │ VIP (sudo ip addr add, GARP)
            clients
```

| Part | Runs where | Knows | Decides |
| --- | --- | --- | --- |
| **MiniPG agent** (`minipgagent`, module `bfm-agent`) | next to every PostgreSQL instance, as `postgres` | its own PostgreSQL (through `psql` over the Unix socket), its VIP, its epoch, lease, fence flag and cluster binding | nothing about the cluster; it carries out commands and enforces local rules (no promote without the VIP, refuse old epochs and other clusters, fence when the lease runs out) |
| **Watcher** (`bfm-watcher`, modules `bfm-core`, `bfm-raft`, `bfm-watcher`) | on its own host(s), as `bfm` | every node's last status, the shared cluster state, the other watchers' observations | failover, repairs, alerts; runs operator commands |
| **Shared state** (`LocalStateStore` or `RaftStateStore`) | inside the watcher(s) | per cluster: epoch, primary, switches, running operation, last role change, settings, event log | a compare-and-set either wins or loses, once for the whole group |
| **Console and REST API** | inside the watcher | the views of the state and the nodes | nothing; it asks the engine, which refuses on a watcher that does not lead |

The watcher never logs in to PostgreSQL and never runs a command on a database host by itself: everything goes
through an agent's API, so every rule an agent enforces holds whoever asks.

## 3. The watcher

```
 bfm-watcher (Spring Boot)
 ├── ClusterHub ─────────────── one engine per cluster
 │    └── Watcher (bfm-core)    poll loop, decisions, operations, alerts
 │         ├── Poller           GET /v1/status on every agent, in parallel
 │         ├── role executor    failover / switchover / start as primary — one at a time
 │         ├── node executor    rejoin / re-initialize — per node, beside a role change
 │         └── ScopedStateStore the cluster's part of the shared state and its event log
 ├── StateStore                 LocalStateStore (one watcher) or RaftStateStore (Apache Ratis)
 ├── NotificationService        mail rules, throttling, SMTP (only the leader sends)
 ├── EnrollmentService, WatcherCa, CaBootstrap   the agents' CA and one-time tokens
 ├── ConsoleService, FleetService                views for the console and the API
 ├── ui/ (Vaadin 25)            pages, wizards, languages (i18n/)
 └── api/ (Spring MVC)          REST API, HTTP Basic, JSON errors
```

**One engine per cluster.** `ClusterHub` builds a `Watcher` for every cluster, from `watcher.yml`
(`bfm.clusters`) and from the clusters made in the console (kept in the shared state's registry). Each engine has
its own poll loop, threads, operations, alerts and event log, so a failover in one cluster never waits for
another. One HTTP client with the agents' TLS stores serves all of them.

**The poll loop** runs every `poll-interval` (2 s). On every watcher, leader or not:

1. Ask every agent for `/v1/status` (role, timeline, LSNs, replication, slots, VIP, lease, fence, system
   identifier, identity). An agent that does not answer within `agent-timeout` counts as unreachable.
2. Check identity: the agent is bound to this cluster, its certificate names this node, its database has the
   cluster's system identifier. A node that fails is *Wrong identity* and is left alone from here on.
3. Publish this watcher's observation (what it sees of the primary) into the shared state; in Raft mode the
   leader counts these for the quorum check.
4. Alerts: lag, inactive slots, nodes down or back, watchers lost; mail through the rules (only the leader
   sends mail).

Only on the **leading** watcher, then:

5. Renew the primary's lease (after confirming leadership with a majority in Raft mode).
6. Classify the primary: `OK`, `AGENT_UNREACHABLE`, `PG_DOWN`, `FENCED` or `NOT_PRIMARY`. After
   `failure-threshold` (3) bad polls in a row it counts as lost.
7. Act, in this order: restart a primary whose PostgreSQL is down but whose agent answers, once
   (`restart-primary-first`); otherwise start an automatic failover if every gate is open (section 6); fence
   a node that runs as primary although the state names another; remove a VIP from a node that is not the
   primary; re-point or rejoin replicas that are stopped, not streaming or on an old timeline (auto rejoin,
   `pg_rewind`; `pg_basebackup` with auto re-initialize).

**Operations** are objects with a plan of steps (`OperationProgress`), shown live in the console and kept in the
history. Role changes hold the cluster state (`operation` in the state) while they run; before each step that
cannot be undone they check that this watcher still leads and still holds the cluster. If the watcher dies in
the middle, the next leader sees an operation no thread runs any more, releases the cluster with a new epoch and
logs it.

## 4. The agent

```
 minipgagent@<instance>  (plain JDK, ~200 KB)
 ├── AgentHttpServer    HTTPS, client certificate required, tls.allowed.clients, cluster binding, epoch check
 ├── AgentService       the loops and the commands
 │    ├── probe         every 1 s: PgProbe (one psql statement) + pg_controldata when stopped
 │    ├── lease tick    every 0.5 s: fence when a fencing lease has run out
 │    ├── watchdog      optional /dev/watchdog, armed while primary
 │    └── DAD self-test every 60 s on its own thread (vip.dad.probe)
 ├── pg/   PgProbe, PgController (pg_ctl start/stop/promote, pg_rewind, pg_basebackup), Psql
 ├── vip/  VipManager: sudo ip addr add/del, arping -D (duplicate check), arping -U (GARP)
 ├── state/ EpochStore, FenceState, LeaseGuard (persisted), ClusterBinding, InterruptedOperation,
 │          ExclusiveLock (one agent per state directory), Watchdog
 └── setup/ minipgagent setup / remove: discovery, enrollment, readiness checks, sudo rule, systemd
```

The agent is deliberately small and has no dependency beyond the JDK. Its state directory survives restarts:
the highest epoch it has seen, whether it is fenced, the lease with its wall-clock deadline (an agent restarted
while cut off still fences on time), the cluster it belongs to, and the mark of a `pg_rewind` or
`pg_basebackup` that did not finish (such a data directory is never started until it is re-initialized).

Every external command is a template (`pg.start.command`, `vip.add.command`, …) split on whitespace before the
placeholders are filled; no shell is involved. The sudo rule allows exactly the VIP commands for one address on
one interface, and the self-test against one neighbour.

## 5. Shared state and who acts

Per cluster the shared state holds one small document — `epoch`, `primary`, the switches (`autoFailover`,
`maintenance`, `mailEnabled`, `autoReinit`), the running `operation`, who changed it and when — plus the last role
change, the mail settings, the observations of each watcher, and the event log.

**Every change is a compare-and-set** on that document's version. Two watchers that try to fail over at once
cannot both win: the second one's write fails and it stops. With one watcher the store is a local file
(`LocalStateStore`); with three it is an Apache Ratis group (`RaftStateStore`): writes go through the Raft log, so
a watcher without a majority cannot change anything, and reads are linearizable through the leader.

**The acting watcher** is the Raft leader (or the only watcher). Followers poll, publish what they see and show
the console read-only with a link to the leader; operator commands on a follower are refused with the leader's
name.

**Epochs.** Every role change first raises the cluster epoch (in the compare-and-set) and announces it to every
agent it can reach. Agents remember the highest epoch and refuse commands from older ones — fencing included — so
a watcher that lost the lead in the middle of a failover cannot steer or stop a node any more. An agent that
reports an epoch newer than the state means another watcher steered the cluster; automatic actions then pause
until an operator acts.

**Lease.** The leading watcher grants the primary a lease (`lease-ttl`, 30 s) on every poll. With self-fencing
on, the lease is a fencing lease: a primary that stops getting renewals releases its VIP and stops PostgreSQL when
it runs out, and a failover waits until any lease the old primary could still hold has run out (TTL + margin)
before promoting. In Raft mode the leader confirms its leadership with a majority before every renewal, so a
leader cut off from the others cannot keep a primary alive.

## 6. Automatic failover

The gates, in order. Any closed gate means no failover, an event saying why, and another look at the next poll.

| Gate | Passes when |
| --- | --- |
| Primary lost | `failure-threshold` polls in a row with the primary not `OK`; a restart in place was tried first if its agent answered |
| Armed | auto failover on, maintenance off, no operation running, the retry wait after a failed attempt over |
| Replicas agree | every reachable replica is asked (`/v1/peer-check`) whether it still reaches the primary's PostgreSQL port or streams from it; one "yes" blocks |
| Watchers agree (Raft) | a majority of watchers saw the primary gone within `observation-fresh` |
| A candidate exists | a replica whose agent answers, in recovery, not fenced or busy, on the primary's last timeline, not `no-failover`, in the old primary's data center (scope `DC_LOCAL`), at most `max-lag` behind the newest WAL position seen on the primary; the best by received WAL, then priority, then name |

Then the `FailoverOperation`:

```
 confirm ─► choose ─► epoch ─► fence old ─► lease wait ─► VIP up ─► promote ─► record ─► re-point replicas
           (gates     (CAS +   (if its      (self-fence:  (arping -D, (only if   (state:   (follow, slots)
            again)     announce) agent       TTL+margin)   ip addr add, VIP here)  primary,
                                 answers)                  GARP)                   epoch)
```

If the VIP cannot be brought up on the candidate, nothing is promoted: the failover fails, the cluster is released,
that node is avoided for a while and the next attempt waits `failover-retry`. If the promote ends without a clear
answer, the watcher waits to see what the node became; it never moves the VIP away or restarts the old primary
while that is unknown. After a successful failover the mail and the console's role change card report the data
loss check: whether the new primary had received the last WAL position seen on the old one, or how far behind it
was.

When the old primary comes back it runs as primary with a newer node already recorded: it is fenced (VIP released,
PostgreSQL stopped) and rejoined as a replica of the new primary with `pg_rewind`.

## 7. Planned operations

**Switchover** (operator, after pre-checks and the cluster's name typed):

```
 pause failover + epoch ─► CHECKPOINT ─► clean stop of the primary, VIP removed ─► target replays the final LSN
   ─► VIP free? (arping -D) ─► VIP up on the target ─► promote ─► record ─► old primary starts as replica
   ─► other replicas follow ─► resume failover
```

The clean stop writes a shutdown checkpoint; the target is promoted only after it has replayed it, so nothing
written before the stop is lost. If the VIP cannot be brought up on the target, the target stays a replica and the
old primary starts again as primary with its VIP.

**Node repairs** run beside role changes and do not pause failover: *Rejoin* (stop, `pg_rewind`, start as a
replica), *Re-initialize* (`pg_basebackup` into a fresh directory, the old one kept aside until the copy is done),
*Restart replica*. **Start as primary** is for a cluster with no running primary.

**Adding a cluster** in the console: a draft with nodes, one-time enrollment tokens, `minipgagent setup` on each
host (certificate, settings, sudo rule, systemd), checks across the nodes, then *Adopt* (record the running
primary) or *Build* (start one node as primary and re-initialize the others from it).

## 8. Which mechanism stops which failure

| Failure | Mechanism |
| --- | --- |
| Two primaries after a failover (split brain) | epochs; fencing of a returning or rogue primary; replica and watcher confirmation before failing over; with self-fencing, the fencing lease and the lease wait |
| Two hosts holding the VIP | no VIP, no promote; `arping -D` before bringing it up, and its self-test against a live neighbour (on networks that hide duplicates the VIP stays down); a VIP found on a non-primary is removed |
| A superseded watcher acting | compare-and-set on the state; epochs checked by the agents; leadership confirmed before every lease renewal and before each irreversible step |
| Promoting a replica that lost data | candidate rules (timeline, lag limit, received WAL first); switchover waits for the final LSN; data loss check reported |
| Commanding the wrong node or cluster | agents bound to one cluster id; the watcher checks each certificate names its node; the system identifier check; *Wrong identity* nodes are left alone |
| Another database host commanding an agent | mutual TLS with the BFM CA, and `tls.allowed.clients` limited to the watchers' certificates |
| A half-rewritten data directory starting | the interrupted-operation mark in the agent's state; such a node is only re-initialized |
| A node starting as primary by itself | the agent starts PostgreSQL only as a replica (`standby.signal`) unless the watcher allows it; the PostgreSQL unit is disabled |
| An unknown outcome treated as failure | long command timeouts; promote results that are unclear are watched, not retried elsewhere |

## 9. Timing

| Setting | Default | Effect |
| --- | --- | --- |
| `poll-interval` | 2 s | how often every agent is asked |
| `failure-threshold` | 3 polls | the primary counts as lost after about 6 s |
| `agent-timeout` | 3 s | one status call |
| `lease-ttl` / `lease-margin` | 30 s / 5 s | how long a primary lives without renewals (self-fencing), and the extra wait before promoting |
| `observation-fresh` | 10 s | how old another watcher's observation may be for the quorum |
| `max-lag` | 16 MB | replicas further behind are not promoted automatically |
| `failover-retry` | 60 s | wait after a failed or blocked failover (not after one a replica's confirmation stopped: that is looked at again at the next poll) |
| `switchover-catchup` | 60 s | how long a switchover waits for the target to replay the final LSN |
| agent `probe.interval.ms` | 1 s | how fresh the agent's status is |

Measured on three PostgreSQL 16 servers in a cloud network (no VIP, no self-fencing): the failover itself — epoch,
promote, record, re-point — takes about 10 s, and detection takes `failure-threshold` polls, about 6–15 s (an
unreachable agent costs its `agent-timeout` per poll). What decides the rest is the replica confirmation. When the
primary's host is cut off from the network rather than down, no TCP reset reaches the replicas: their WAL receivers
keep reporting the stream until PostgreSQL's `wal_receiver_timeout` ends it, and while one does the failover waits.
A failover stopped only by that confirmation has done nothing yet (no epoch raised), so the watcher looks again at
the next poll and keeps no record of the empty attempt; any other failed or blocked attempt waits `failover-retry`.
`minipgagent setup` proposes `wal_receiver_timeout = 10s` (the default is 60 s). A network cut then took 37 s in all;
with the default 60 s and the old 60 s retry wait it took 62–105 s. With self-fencing, the lease wait adds up to
`lease-ttl` + `lease-margin` counted from the last renewal; that is the price of ruling out two primaries when the
old one is only cut off, not dead.

The returning old primary of those tests was fenced within 3 s of answering again and rejoined with `pg_rewind` in
about 35 s, with no data lost (the last WAL position seen on the old primary had reached the new one).

With three watchers and self-fencing, on the same servers (the VIP checked with `ip addr` on every node, sampled
every second; this network does not carry it to clients):

- **The leader stops**: another watcher led 4 s later; the cluster stayed healthy, the lease renewed by the new leader.
- **The primary is cut off from the network**: it stopped itself 29 s later when its lease ran out; the watchers
  waited for that lease before the new primary took the VIP, 9 s after; the failover finished 43 s after the cut. The
  VIP was never on two nodes. The old primary, back on the network, rejoined with `pg_rewind`; no data lost.
- **Two of the three watchers stop**: no watcher can act without a majority, so the lease was not renewed and the
  primary stopped itself 33 s later — no writes, but no second primary either. When the watchers came back, the leader
  found it fenced with its agent answering and restarted it in place; healthy 49 s after the watchers were started.

## 10. Security

- **Agents** speak HTTPS only with a client certificate signed by the BFM CA; `tls.allowed.clients` narrows that
  to the watchers' certificates. Each command carries the cluster id and the epoch.
- **The CA** lives in the shared state, its key encrypted with `secret.key` (AES-GCM). `bfm-watcher setup` creates
  it, or an offline CA from `bfm-make-certs` is used instead. Database hosts enroll with one-time tokens that are
  valid for 30 minutes and one node; the enrollment command pins the console's certificate fingerprint, so the
  token goes nowhere else.
- **Console and API**: HTTPS, users with bcrypt hashes in `watcher.yml`, two roles (ADMIN operates, VIEWER reads).
  Every operator action is written to the event log with the user name. The API answers JSON with status codes
  and never redirects to the login page.
- **Secrets at rest**: the SMTP password and the CA key are encrypted with `secret.key`; key store passwords are in
  their own files under `/etc/bfm`, not in `watcher.yml`. The replication role is created with a SCRAM verifier;
  the password itself appears on no command line and in no log.
- **Root on database hosts**: the agent runs as `postgres`; its sudo rule allows `ip addr add/del` of exactly its
  VIP on exactly its interface, `arping` for that VIP and for the self-test neighbour, nothing else. `pg_rewind`
  runs as the replication role with the function grants it needs, not as a superuser.

## 11. Deployment shapes

| Shape | Use it when | Notes |
| --- | --- | --- |
| One watcher | a lab, or a site that accepts no automatic failover while the watcher is down | replica confirmation still guards every failover |
| Three watchers (Raft) with self-fencing | production | a majority must agree; a cut-off primary stops itself; the leader moves within seconds |
| A DR data center | replicas far away | scope `DC_LOCAL` keeps automatic failover in the primary's data center; a DR replica becomes primary only by an operator's decision; each data center has its own VIP |
| Several clusters per watcher group | many clusters | one engine per cluster, one console with Fleet and Hosts pages |
| Several clusters per server | shared database hosts | one agent instance per PostgreSQL instance (`minipgagent@<instance>`), own port, state and sudo rule; two nodes of one cluster on one host are refused |

## 12. Console and languages

The console (Vaadin 25, server-side Java) reads everything through `ConsoleService` and `FleetService` and changes
nothing directly; every action goes through the engine like an API call. It speaks English, Turkish and Arabic
(right to left). The views' own texts come from `i18n/messages*.properties`; texts made by the engine (health,
events, operation steps, checks, errors) stay English in the state, the API and the mails, and are translated for
the screen with the catalogs `i18n/runtime-tr.json` and `runtime-ar.json` (templates such as `{node} is down`;
a text the catalog does not know is shown in English). So two operators in different languages see the same
facts, and logs and mails stay searchable.

## 13. Where things are in the code

| Module | Packages | Look here for |
| --- | --- | --- |
| `bfm-common` | `model`, `json`, `tls` | the agent API's records (`NodeStatus`, `ApiResult`), the JSON codec, LSN arithmetic, keytool and TLS helpers |
| `bfm-agent` | `http`, `pg`, `vip`, `state`, `setup`, `exec` | `AgentService` (loops, commands), `PgProbe`, `VipManager`, `LeaseGuard`, `SetupCommand`, `Readiness` |
| `bfm-core` | `decision`, `ops`, `state`, `monitor`, `notify`, `event`, `agent`, `config` | `Watcher` (the poll loop), `CandidateSelector`, `PrimaryHealth`, `FailoverOperation`, `SwitchoverOperation`, `NodeRepairOperation`, `ClusterState`, `StateStore` |
| `bfm-raft` | — | `RaftStateStore`, the Ratis state machine |
| `bfm-watcher` | `service`, `ui`, `api`, `i18n`, `security`, `setup`, `mail`, `config` | `ClusterHub`, `ConsoleService`, `EnrollmentService`, `WatcherCa`, the Vaadin pages, `WatcherSetup` |

Tests: `mvn verify` runs the engine against an in-memory cluster that follows the agent's rules
(`bfm-core` `WatcherTest`), three in-process Raft members, the REST API and the console texts;
`AgentPostgresIT` and `WatcherPostgresIT` use real PostgreSQL (see the README).
