# pgVIP: PostgreSQL VIP failover manager

Automatic failover for PostgreSQL streaming replication clusters, with a virtual IP that follows the primary.
An **agent** runs next to every PostgreSQL server; one or three **watchers** decide. Three watchers form an
Apache Ratis (Raft) group; Raft is optional and a single watcher works on its own.

> Status: work in progress. The agent is complete and tested against real PostgreSQL 16. The watcher core,
> the Ratis state store and the Vaadin web UI are being written.

## Safety rules

- **No VIP, no promote.** The VIP is brought up on the new primary with `sudo ip addr add` first. If the
  address still answers from another host (`arping -D`) or the command fails, the replica is not promoted.
  The agent enforces this itself: `/v1/pg/promote` refuses while the VIP is not on the local interface.
- **A node never starts as primary on its own.** Without `standby.signal`, the agent starts PostgreSQL only
  when the watcher explicitly allows it. Disable the PostgreSQL systemd unit and let the agent start it.
- **Epochs.** Every role change runs under a new cluster epoch; agents refuse commands from older epochs.
- **Lease and self-fencing.** The watcher renews a lease on the primary. With `selfFence` on, a primary that
  stops getting renewals releases the VIP and stops PostgreSQL before the watcher promotes anyone.
- **Confirmation before failover.** In standalone mode the watcher asks every reachable replica whether it
  still sees the primary; in Raft mode a majority of watchers must also agree.

## Modules

| Module | What it is | Dependencies |
| --- | --- | --- |
| `pgvip-common` | Agent API model, JSON codec, LSN and TLS helpers | JDK only |
| `pgvip-agent` | Agent: status probe, start/stop/promote/follow/demote/rewind/reinit, VIP, lease, mTLS API | JDK only |
| `pgvip-core` | Watcher logic (in progress) | JDK only |
| `pgvip-raft` | Raft state store on Apache Ratis (next) | Ratis 3.3 |
| `pgvip-watcher` | Spring Boot 4 + Vaadin 25 web UI and REST API (next) | Spring, Vaadin |

## Build

Java 21 and Maven 3.9:

```
mvn package
```

The agent jar is `pgvip-agent/target/pgvip-agent.jar`.

The agent integration test drives two real PostgreSQL instances through pg_basebackup, a clean switchover,
a crash failover and pg_rewind. It needs PostgreSQL binaries and a non-root user:

```
PGVIP_IT_PGBIN=/usr/lib/postgresql/16/bin mvn -pl pgvip-common,pgvip-agent -Dtest=AgentPostgresIT -Dsurefire.failIfNoSpecifiedTests=false test
```

## Agent

```
java -jar pgvip-agent.jar /etc/pgvip/agent.properties
```

Runs as the `postgres` OS user and talks to PostgreSQL through `psql` over the Unix socket (peer
authentication, no stored password). Main settings:

```properties
node.name=pg-a1
listen.port=8008
tls.enabled=true
tls.keystore=/etc/pgvip/pg-a1.p12
tls.keystore.password.file=/etc/pgvip/keystore.pass
tls.truststore=/etc/pgvip/ca.p12
tls.truststore.password.file=/etc/pgvip/truststore.pass
state.dir=/var/lib/pgvip
pg.bin=/usr/pgsql-16/bin
pg.data=/var/lib/pgsql/16/data
pg.port=5432
pg.socket.dir=/var/run/postgresql
replication.user=replicator
replication.passfile=/var/lib/pgsql/.pgpass
vip.address=192.168.0.50
vip.prefix=24
vip.interface=eth0
fence.action=stop
```

Every external command is a template and can be overridden (`pg.start.command`, `pg.stop.command`,
`vip.add.command`, `vip.del.command`, `vip.show.command`, `vip.dad.command`, `vip.garp.command`). Templates are
split on whitespace before placeholders are filled; no shell is involved.

Sudo rule for the VIP (`/etc/sudoers.d/pgvip`), adjust address and interface:

```
postgres ALL=(root) NOPASSWD: /usr/sbin/ip addr add 192.168.0.50/24 dev eth0, \
                              /usr/sbin/ip addr del 192.168.0.50/24 dev eth0, \
                              /usr/sbin/arping -D -q -c 2 -w 3 -I eth0 192.168.0.50, \
                              /usr/sbin/arping -U -q -c 3 -I eth0 192.168.0.50
```

`pg_rewind` needs `wal_log_hints = on` or data checksums.

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
