# MiniPG agent: `agent.properties` reference

Every setting the MiniPG agent (`minipgagent`) reads, with its default. The file is a Java properties file,
one `key=value` per line, `#` for comments; the RPM installs it as `/etc/bfm/minipgagent/agent.properties`,
mode 600, owner `postgres`. The agent reads it once at startup — restart the agent after a change
(`systemctl restart minipgagent`; PostgreSQL keeps running, the unit uses `KillMode=process`).

```bash
minipgagent                    # reads /etc/bfm/minipgagent/agent.properties
minipgagent /path/to/other.properties
```

Everything the agent reads lives in `/etc/bfm/minipgagent/`: this file, the certificates, and `agent.env` for
the environment (`MINIPGAGENT_JAVA_OPTS` for JVM options, and the secrets below). Its state — epoch, fence
flag, lease, operation logs — is in `/var/lib/minipgagent`.

---

## A complete file

`pg-a1`, a DC1 node of the example cluster in [install.md](install.md): PostgreSQL 16 from the PGDG packages,
mutual TLS, VIP `192.168.0.50/24` on `eth0`.

```properties
# ---------------------------------------------------------------- identity and API
# Must equal the node name in the watcher's nodes[] list; the replication slot is derived from it.
node.name=pg-a1
listen.address=0.0.0.0
listen.port=8008

# ---------------------------------------------------------------- mutual TLS
# Only a caller holding a certificate from the BFM CA may call this agent.
tls.enabled=true
tls.keystore=/etc/bfm/minipgagent/pg-a1.p12
tls.keystore.password.file=/etc/bfm/minipgagent/pg-a1.pass
tls.truststore=/etc/bfm/minipgagent/ca.p12
tls.truststore.password.file=/etc/bfm/minipgagent/ca.pass
# Optional second check on top of TLS; required when tls.enabled=false (lab only).
#api.token.file=/etc/bfm/minipgagent/agent.token

# ---------------------------------------------------------------- agent state
# Epoch, fence flag, the primary lease and the logs of background operations. Survives a restart.
state.dir=/var/lib/minipgagent

# ---------------------------------------------------------------- PostgreSQL
pg.bin=/usr/pgsql-16/bin
pg.data=/var/lib/pgsql/16/data
pg.port=5432
pg.socket.dir=/var/run/postgresql
pg.user=postgres
pg.database=postgres
pg.start.timeout.seconds=120
pg.promote.timeout.seconds=60
# Defaults; override only if your site starts PostgreSQL differently.
#pg.start.command={bin}/pg_ctl -D {data} -l {state}/postgres-start.log -w -t {timeout} start
#pg.stop.command={bin}/pg_ctl -D {data} -m {mode} -w -t {timeout} stop
#pg.status.command={bin}/pg_ctl -D {data} status

# ---------------------------------------------------------------- replication, rewind, rebuild
replication.user=replicator
replication.passfile=/var/lib/pgsql/.pgpass
rewind.user=postgres
replication.conninfo.extra=sslmode=prefer
# Rename the old data directory instead of deleting it when a node is re-initialized.
reinit.keep.old=true

# ---------------------------------------------------------------- virtual IP
vip.enabled=true
vip.address=192.168.0.50
vip.prefix=24
vip.interface=eth0
vip.dad.enabled=true
# Defaults; they must match /etc/sudoers.d/minipgagent word for word.
#vip.add.command=sudo -n /usr/sbin/ip addr add {vip}/{prefix} dev {iface}
#vip.del.command=sudo -n /usr/sbin/ip addr del {vip}/{prefix} dev {iface}
#vip.show.command=/usr/sbin/ip -o -4 addr show dev {iface}
#vip.dad.command=sudo -n /usr/sbin/arping -D -q -c 2 -w 3 -I {iface} {vip}
#vip.garp.command=sudo -n /usr/sbin/arping -U -q -c 3 -I {iface} {vip}

# ---------------------------------------------------------------- self-fencing
# What this node does to itself when the primary lease runs out (only when the watcher grants a fencing
# lease, i.e. bfm.cluster.self-fence: true).
fence.action=stop
fence.stop.mode=immediate

# ---------------------------------------------------------------- optional hardware watchdog
watchdog.enabled=false
#watchdog.device=/dev/watchdog
#watchdog.interval.ms=5000

# ---------------------------------------------------------------- timers
command.timeout.seconds=30
probe.interval.ms=1000
```

### The DR node

Same file with its own name, certificate and its own VIP — the DR subnet is another L2 segment, so it cannot
carry the DC1 address:

```properties
node.name=pg-dr1
tls.keystore=/etc/bfm/minipgagent/pg-dr1.p12
tls.keystore.password.file=/etc/bfm/minipgagent/pg-dr1.pass
vip.address=10.20.0.50
vip.interface=eth0
```

Its `/etc/sudoers.d/minipgagent` names `10.20.0.50/24` instead of `192.168.0.50/24`.

### A node without a VIP

Clusters reached through a proxy or a DNS switch instead of a VIP:

```properties
vip.enabled=false
```

The agent then never calls `ip`/`arping`, and promotion is not tied to the VIP — **the "no VIP, no promote"
rule only protects you while a VIP exists.** The sudo rule is not needed.

### The lab (no TLS)

`scripts/local-lab.sh` writes files like this; never use them on a reachable host:

```properties
tls.enabled=false
api.token=lab-token
vip.show.command=/tmp/bfm-lab/lab-fake-ip.sh show {iface} {vip} {prefix}
```

---

## Parameters

Required settings have no default; the agent refuses to start without them.

### Identity and API

| Key | Default | What it does |
| --- | --- | --- |
| `node.name` | **required** | This node's name. Must match `nodes[].name` in `watcher.yml`; the replication slot is derived from it (`pg-a1` → `pg_a1`). |
| `listen.address` | `0.0.0.0` | Address the agent API binds to. |
| `listen.port` | `8008` | Port of the agent API. Must match the port in `agent-url`. |

### Mutual TLS and token

| Key | Default | What it does |
| --- | --- | --- |
| `tls.enabled` | `true` | HTTPS with client-certificate checking. `false` only in a lab, and then `api.token` becomes mandatory. |
| `tls.keystore` | required when TLS is on | PKCS#12 store with this node's certificate and key (from `bfm-make-certs`). |
| `tls.keystore.password` / `.file` | — | Its password, inline or in a file. |
| `tls.truststore` | required when TLS is on | PKCS#12 store with the BFM CA: callers are accepted only if their certificate is signed by it. |
| `tls.truststore.password` / `.file` | — | Its password. |
| `api.token` / `.file` | — | Optional bearer token checked on top of TLS; **required when `tls.enabled=false`**. |

Each secret can be given three ways, and the first one found wins:

1. an environment variable — `MINIPGAGENT_KEYSTORE_PASSWORD`, `MINIPGAGENT_TRUSTSTORE_PASSWORD`, `MINIPGAGENT_API_TOKEN`
   (put them in `/etc/bfm/minipgagent/agent.env`, which the unit reads);
2. the property itself (`tls.keystore.password=…`);
3. the `.file` form (`tls.keystore.password.file=/etc/bfm/minipgagent/pg-a1.pass`).

The `.p12` and `.pass` files live in `/etc/bfm/minipgagent/`, belong to `postgres`, mode 600.

### Agent state

| Key | Default | What it does |
| --- | --- | --- |
| `state.dir` | `/var/lib/minipgagent` | Cluster epoch, fence flag, the persisted primary lease and background-operation logs. Deleting it makes the node forget that it was fenced, so leave it alone. |

### PostgreSQL

| Key | Default | What it does |
| --- | --- | --- |
| `pg.bin` | **required** | Directory with `pg_ctl`, `pg_controldata`, `psql`, `pg_rewind`, `pg_basebackup`. Rocky + PGDG: `/usr/pgsql-16/bin`, Debian: `/usr/lib/postgresql/16/bin`. |
| `pg.data` | **required** | The data directory (`PGDATA`). |
| `pg.port` | `5432` | Port this PostgreSQL listens on. |
| `pg.socket.dir` | `/var/run/postgresql` | Unix socket directory; the agent connects through it with peer authentication, so no password is stored. |
| `pg.user` | `postgres` | Local superuser the agent uses over the socket. |
| `pg.database` | `postgres` | Database it connects to. |
| `pg.start.timeout.seconds` | `120` | Passed to `pg_ctl -t` for start and stop; the agent kills the command 15 s later. Raise it for a database with long crash recovery, and keep the watcher's `bfm.agent.command-timeout` above it. |
| `pg.promote.timeout.seconds` | `60` | `pg_promote(true, N)`: how long the agent waits for the server to leave recovery. |
| `pg.start.command` | `{bin}/pg_ctl -D {data} -l {state}/postgres-start.log -w -t {timeout} start` | How PostgreSQL is started. |
| `pg.stop.command` | `{bin}/pg_ctl -D {data} -m {mode} -w -t {timeout} stop` | How it is stopped. |
| `pg.status.command` | `{bin}/pg_ctl -D {data} status` | How "is it running" is answered. |

### Replication, rewind and rebuild

| Key | Default | What it does |
| --- | --- | --- |
| `replication.user` | `replicator` | Role in `primary_conninfo` and in `pg_basebackup`. Needs `REPLICATION LOGIN`. |
| `replication.passfile` | — | libpq password file (`.pgpass`) for that role and for the rewind role. The agent never writes a password into `postgresql.auto.conf` or onto a command line. |
| `rewind.user` | same as `pg.user` | Role `pg_rewind` connects as, to `dbname=postgres`. Superuser, or a role with the grants `pg_rewind` documents. |
| `replication.conninfo.extra` | `sslmode=prefer` | Appended to every connection string the agent builds (`sslmode=require`, `sslrootcert=…`, `application_name` is already set to the node name). |
| `reinit.keep.old` | `true` | On re-initialize, rename the old data directory to `<data>.bfm-old-<timestamp>` instead of deleting it. Keeps the disk busy but makes a mistake recoverable. |

### Virtual IP

| Key | Default | What it does |
| --- | --- | --- |
| `vip.enabled` | `true` | With `false` the agent manages no address and promotion is not tied to a VIP. |
| `vip.address` | required when enabled | The address that follows the primary **on this node's segment**. A node in another data center has its own. |
| `vip.prefix` | `24` | Prefix length used in `ip addr add`. |
| `vip.interface` | required when enabled | Interface on **this** node (`eth0`, `ens192`, `bond0`…). |
| `vip.dad.enabled` | `true` | Duplicate address detection with `arping -D` before bringing the VIP up: if another host answers, nothing is added and nothing is promoted. |
| `vip.add.command` | `sudo -n /usr/sbin/ip addr add {vip}/{prefix} dev {iface}` | Bring the VIP up. |
| `vip.del.command` | `sudo -n /usr/sbin/ip addr del {vip}/{prefix} dev {iface}` | Take it down. |
| `vip.show.command` | `/usr/sbin/ip -o -4 addr show dev {iface}` | Read the interface's addresses (no sudo needed). |
| `vip.dad.command` | `sudo -n /usr/sbin/arping -D -q -c 2 -w 3 -I {iface} {vip}` | The duplicate check. |
| `vip.garp.command` | `sudo -n /usr/sbin/arping -U -q -c 3 -I {iface} {vip}` | Gratuitous ARP after the VIP comes up, so switches and clients learn the new MAC. |

Whatever you put here must be allowed verbatim in `/etc/sudoers.d/minipgagent`. Sudo matches the full command line:
change the interface in one place and you must change it in the other.

### Self-fencing

| Key | Default | What it does |
| --- | --- | --- |
| `fence.action` | `stop` | `stop`: release the VIP **and** stop PostgreSQL. `vip-only`: release the VIP and leave PostgreSQL running. |
| `fence.stop.mode` | `immediate` | `pg_ctl -m` mode used when fencing: `immediate`, `fast` or `smart`. `immediate` is the point — a fenced primary should be gone before anything else is promoted. |

The agent fences itself only when the lease it holds was granted with fencing, which the watcher does when
`bfm.cluster.self-fence: true`. With the default (`false`) the agent never acts on its own: watchers can be
down for hours and this node keeps serving. Fencing also happens on the watcher's explicit `POST /v1/fence`.

### Hardware watchdog

| Key | Default | What it does |
| --- | --- | --- |
| `watchdog.enabled` | `false` | Arms `/dev/watchdog` while this node is primary. If the agent's JVM hangs or is killed, the kernel resets the machine, which takes the primary down with it. |
| `watchdog.device` | `/dev/watchdog` | The device. Needs a watchdog driver and write access for `postgres`. |
| `watchdog.interval.ms` | `5000` | How often it is petted. Keep it well below the device's own timeout. |

### Timers

| Key | Default | What it does |
| --- | --- | --- |
| `command.timeout.seconds` | `30` | Timeout for the short external commands: `pg_ctl status`, `pg_controldata`, `ip`, `arping`. Start and stop use `pg.start.timeout.seconds` instead; `pg_rewind` and `pg_basebackup` get hours. |
| `probe.interval.ms` | `1000` | How often the agent reads its own state (role, LSNs, replication, VIP). A status call is answered from the last probe, and triggers a fresh one first if that probe is older than three intervals. |

---

## Command templates

`pg.*.command` and `vip.*.command` are templates, not shell lines. They are split on whitespace **first** and
the placeholders are filled afterwards, so a value can never add an argument, and no shell is involved — pipes,
`&&`, `$(…)` and quotes do not work. Put anything more complicated in a script and call that.

| Placeholder | Filled with | Available in |
| --- | --- | --- |
| `{bin}` | `pg.bin` | `pg.*.command` |
| `{data}` | `pg.data` | `pg.*.command` |
| `{port}` | `pg.port` | `pg.*.command` |
| `{state}` | `state.dir` | `pg.*.command` |
| `{node}` | `node.name` | `pg.*.command` |
| `{timeout}` | `pg.start.timeout.seconds` | `pg.start.command`, `pg.stop.command` |
| `{mode}` | `smart` / `fast` / `immediate` | `pg.stop.command` |
| `{vip}` | `vip.address` | `vip.*.command` |
| `{prefix}` | `vip.prefix` | `vip.*.command` |
| `{iface}` | `vip.interface` | `vip.*.command` |

A placeholder that is not filled is an error, so a typo fails loudly instead of running a wrong command.

Example for a site that starts PostgreSQL through a wrapper script:

```properties
pg.start.command=/usr/local/sbin/pgstart.sh {data} {port} {timeout}
```

---

## What the agent refuses at startup

| Message | Cause |
| --- | --- |
| `Missing required setting node.name` (or `pg.bin`, `pg.data`) | The key is absent or empty. |
| `tls.enabled=true needs tls.keystore and tls.truststore` | TLS is on without the stores. |
| `Without TLS an api.token is required` | `tls.enabled=false` and no token: an open agent API is never allowed. |
| `fence.action must be 'stop' or 'vip-only'` | Typo in `fence.action`. |
| `Missing required setting vip.address` / `vip.interface` | `vip.enabled=true` without them. Set `vip.enabled=false` if the cluster has no VIP. |
| `Cannot read tls.keystore.password.file: …` | The `.pass` file is missing or `postgres` cannot read it. |

Check after a change:

```bash
systemctl restart minipgagent && systemctl status minipgagent
journalctl -u minipgagent -n 50
```
