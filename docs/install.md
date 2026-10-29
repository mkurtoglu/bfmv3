# Installing BFM

This guide takes a three-node PostgreSQL cluster from "streaming replication works" to "BFM runs it": the VIP
follows the primary, a lost primary is failed over automatically, and operators work from the console.

It is written for Rocky Linux / RHEL 8 and 9 with the RPMs from `packaging/rpm/build.sh`. Other distributions
work the same way; see [Other distributions](#other-distributions) for the file layout.

Example cluster used throughout:

| Node | Role at install | Address | DC | What runs there |
| --- | --- | --- | --- | --- |
| `pg-a1` | primary | 10.0.0.11 | DC1 | PostgreSQL 16, `minipgagent` |
| `pg-a2` | replica | 10.0.0.12 | DC1 | PostgreSQL 16, `minipgagent` |
| `pg-dr1` | replica | 10.20.0.11 | DR | PostgreSQL 16, `minipgagent` |
| `w1` | — | 10.0.9.1 | DC1 | `bfm-watcher` (console on 8443) |
| VIP | — | 192.168.0.50/24 in DC1, 10.20.0.50/24 in DR | | moves with the primary |

Replace every address, interface and name with your own. Nothing below needs an internet connection on the
database servers.

---

## 1. Before you start

- [ ] **PostgreSQL 13 or newer** on every node, same major version, streaming replication already working
      (one primary, the replicas streaming from it). BFM does not set replication up for you on the first
      node; it keeps it running and rebuilds replicas afterwards.
- [ ] **Java 21** available: `dnf install java-21-openjdk-headless` (Rocky 8.9+ / 9.3+). `dnf` pulls it in
      with the packages.
- [ ] **The VIP is free** and lives on the same L2 segment as the nodes that may hold it. A node in another
      data center needs its own VIP (`vip.address` per node); the VIP never moves between data centers on its
      own.
- [ ] **Clocks in sync** (chrony). Leases and Raft observations carry wall-clock times.
- [ ] **Ports open** between the hosts (table below).
- [ ] **Node names decided.** The same name goes into `agent.properties` (`node.name`), `watcher.yml`
      (`nodes[].name`) and the certificate, and the replication slot is derived from it (`pg-a1` → `pg_a1`).
      Keep them short and lowercase: `pg-a1`, `pg-a2`, `pg-dr1`.
- [ ] A host to generate certificates on (any of them; keep the CA key off the servers afterwards).

| From | To | Port | What for |
| --- | --- | --- | --- |
| watcher(s) | every node | 8008/tcp | agent API (mutual TLS) |
| operators | watcher | 8443/tcp | console and REST API |
| watcher | watcher | 9870/tcp | Raft, only with three watchers |
| nodes | nodes | 5432/tcp | replication, `pg_rewind`, `pg_basebackup`, peer checks |

```bash
firewall-cmd --permanent --add-port=8008/tcp && firewall-cmd --reload     # on each database node
firewall-cmd --permanent --add-port=8443/tcp && firewall-cmd --reload     # on the watcher
```

---

## 2. Prepare PostgreSQL

On **every** node, as `postgres`.

### 2.1 Settings

`pg_rewind` is how a returning old primary rejoins without a full copy. It needs one of these:

```sql
ALTER SYSTEM SET wal_log_hints = on;     -- needs a restart
```

or a data directory created with `initdb --data-checksums`. Check what you have:

```bash
psql -c "SHOW wal_log_hints" -c "SHOW data_checksums"
```

The rest are the usual replication settings; defaults are fine on PostgreSQL 13+:

```
wal_level = replica
max_wal_senders = 10          # at least (nodes - 1) + 2
max_replication_slots = 10    # BFM creates one physical slot per replica
hot_standby = on
listen_addresses = '*'        # or the node address and the VIP
```

`full_page_writes` must stay `on` (the default) for `pg_rewind`.

### 2.2 Roles

A replication role used by `pg_basebackup` and by the replicas, and the rewind role (`postgres` by default):

```sql
CREATE ROLE replicator WITH REPLICATION LOGIN PASSWORD 'choose-a-password';
```

`pg_rewind` connects as `rewind.user` (default `postgres`) to database `postgres`. A superuser works; to avoid
one, create a role with the [grants `pg_rewind` documents](https://www.postgresql.org/docs/current/app-pgrewind.html)
and set `rewind.user` to it.

### 2.3 `pg_hba.conf`

Every node must accept, from every other node, a replication connection as `replicator` and a normal connection
as the rewind role:

```
# TYPE  DATABASE     USER        ADDRESS         METHOD
host    replication  replicator  10.0.0.11/32    scram-sha-256
host    replication  replicator  10.0.0.12/32    scram-sha-256
host    replication  replicator  10.20.0.11/32   scram-sha-256
host    postgres     postgres    10.0.0.11/32    scram-sha-256
host    postgres     postgres    10.0.0.12/32    scram-sha-256
host    postgres     postgres    10.20.0.11/32   scram-sha-256
local   all          postgres                    peer
```

The last line is how the agent talks to its own PostgreSQL: `psql` over the Unix socket, peer authentication,
no password stored anywhere.

### 2.4 `.pgpass`

The agent never puts a password on a command line or in `postgresql.auto.conf`; it points libpq at a password
file. On every node, owned by `postgres`, mode 600:

```bash
cat >> /var/lib/pgsql/.pgpass <<'EOF'
*:*:replication:replicator:the-replicator-password
*:*:postgres:postgres:the-postgres-password
EOF
chown postgres:postgres /var/lib/pgsql/.pgpass
chmod 600 /var/lib/pgsql/.pgpass
```

The fields are `host:port:database:user:password`; `replication` in the database field is the replication
connection the replicas and `pg_basebackup` make, the second line is the one `pg_rewind` uses.

### 2.5 Hand PostgreSQL over to the agent

The agent starts and stops PostgreSQL, and **a node must never start as a primary by itself**. Disable the
PostgreSQL unit on every node:

```bash
systemctl disable postgresql-16          # do not stop it now; the agent takes over a running server
```

Leave the running server alone — installing the agent does not restart it.

---

## 3. Certificates

Agents only answer callers holding a certificate from the BFM CA, and the watcher only talks to agents that
present one. Generate everything once, on one machine:

```bash
bfm-make-certs /etc/bfm/certs \
    pg-a1=10.0.0.11 \
    pg-a2=10.0.0.12 \
    pg-dr1=10.20.0.11 \
    w1=10.0.9.1,bfm.example.com
```

This writes `ca.crt`, `ca.key`, `ca.p12` + `ca.pass`, and `NAME.p12` + `NAME.pass` per name.

- The addresses become the certificate's SAN and **must match the `agent-url` the watcher uses**. An agent
  reached as `https://10.0.0.11:8008` needs `10.0.0.11` in its certificate.
- Add the DNS name operators type in the address bar to the watcher's certificate (`bfm.example.com` above),
  so browsers accept the console.
- Copy `NAME.p12`, `NAME.pass`, `ca.p12` and `ca.pass` to the matching host, owned by the service user,
  mode 600: a database node reads them from `/etc/bfm/minipgagent/`, the watcher from `/etc/bfm/`.
- **Keep `ca.key` offline.** It is only needed to issue more certificates. Certificates are valid 825 days;
  the CA 10 years.
- Those directories are created by the packages, so copy the certificates after §4 and §5, or create the
  directories yourself.

```bash
scp /etc/bfm/certs/{pg-a1.p12,pg-a1.pass,ca.p12,ca.pass} root@10.0.0.11:/etc/bfm/minipgagent/
ssh root@10.0.0.11 'cd /etc/bfm/minipgagent && chown postgres:postgres *.p12 *.pass && chmod 600 *.p12 *.pass'
```

Operators' browsers trust the console once `ca.crt` is in their trust store.

---

## 4. Install the agent on every database node

The agent is packaged as **`minipgagent`**:

```bash
dnf install ./minipgagent-0.1.0-*.el9.noarch.rpm
```

| Path | What it is |
| --- | --- |
| `/etc/bfm/minipgagent/` | everything the agent reads: settings, certificates, `agent.env`; mode 750, group `postgres` |
| `/etc/bfm/minipgagent/agent.properties` | settings, mode 600, owner `postgres` |
| `/etc/sudoers.d/minipgagent` | the one sudo rule the agent needs |
| `/usr/share/bfm/minipgagent.jar`, `/usr/bin/minipgagent` | the agent |
| `/var/lib/minipgagent` | epoch, fence flag, lease, operation logs |
| `minipgagent.service` | runs as `postgres`, `KillMode=process` so PostgreSQL survives an agent restart |

### 4.1 `/etc/bfm/minipgagent/agent.properties`

Every setting with its default and what it does: **[agent-config.md](agent-config.md)**; the file to copy is
`packaging/agent.properties.example`. What you must set on each node:

```properties
node.name=pg-a1                       # same name as in watcher.yml
listen.address=0.0.0.0
listen.port=8008

tls.enabled=true
tls.keystore=/etc/bfm/minipgagent/pg-a1.p12
tls.keystore.password.file=/etc/bfm/minipgagent/pg-a1.pass
tls.truststore=/etc/bfm/minipgagent/ca.p12
tls.truststore.password.file=/etc/bfm/minipgagent/ca.pass

pg.bin=/usr/pgsql-16/bin              # where pg_ctl, pg_rewind, pg_basebackup live
pg.data=/var/lib/pgsql/16/data
pg.port=5432
pg.socket.dir=/var/run/postgresql

replication.user=replicator
replication.passfile=/var/lib/pgsql/.pgpass
rewind.user=postgres
replication.conninfo.extra=sslmode=prefer

vip.enabled=true
vip.address=192.168.0.50              # the DR node gets 10.20.0.50
vip.prefix=24
vip.interface=eth0                    # the interface on THIS node
```

Every external command is a template (`pg.start.command`, `vip.add.command`, …) and can be overridden if your
site does things differently. Templates are split on whitespace and filled in; no shell is involved.

### 4.2 The sudo rule

The agent runs as `postgres` and may change exactly one address on exactly one interface. Edit
`/etc/sudoers.d/minipgagent` on each node to match that node's VIP and interface:

```
postgres ALL=(root) NOPASSWD: /usr/sbin/ip addr add 192.168.0.50/24 dev eth0, \
                              /usr/sbin/ip addr del 192.168.0.50/24 dev eth0, \
                              /usr/sbin/arping -D -q -c 2 -w 3 -I eth0 192.168.0.50, \
                              /usr/sbin/arping -U -q -c 3 -I eth0 192.168.0.50
```

```bash
visudo -cf /etc/sudoers.d/minipgagent        # must print "parsed OK"
sudo -u postgres sudo -n -l          # shows exactly these four commands for postgres
sudo -u postgres sudo -n /usr/sbin/arping -D -q -c 2 -w 3 -I eth0 192.168.0.50; echo "exit $?"
```

The last line is the duplicate-address check the agent runs before it brings the VIP up, and it changes
nothing: on a node that does not hold the VIP, exit 0 means nobody else answers for that address. Do not add
the VIP by hand to test the rule — two hosts answering for one address is exactly what BFM exists to prevent.
The real test is the switchover in §7.

If this rule is wrong, failover to that node refuses to promote — by design.

### 4.3 Start it

```bash
systemctl enable --now minipgagent
systemctl status minipgagent
journalctl -u minipgagent -f
```

Check the agent from the watcher host once its certificate is in place:

```bash
curl --cert-type P12 --cert /etc/bfm/w1.p12:"$(cat /etc/bfm/w1.pass)" \
     --cacert /etc/bfm/certs/ca.crt https://10.0.0.11:8008/v1/status
```

It answers with the node's role, timeline, LSNs, replication, slots, VIP and lease. A `curl` built without
PKCS#12 support cannot do this; the watcher's Overview page shows the same thing once it is running.

---

## 5. Install the watcher

On one host (or three, see [Raft](#9-three-watchers-raft)):

```bash
dnf install ./bfm-watcher-0.1.0-*.el9.noarch.rpm
```

| Path | What it is |
| --- | --- |
| `/etc/bfm/watcher.yml` | settings, mode 640, group `bfm` |
| `/var/lib/bfm-watcher` | cluster state, events, Raft log, **`secret.key`** |
| `/usr/bin/bfm-watcher`, `/usr/bin/bfm-make-certs` | the watcher and the certificate helper |

### 5.1 Users

```bash
bfm-watcher hash-password                       # asks for the password twice, shows nothing
echo 'the-admin-password' | bfm-watcher hash-password     # or from a pipe, in a script
```

It prints `{bcrypt}$2a$10$…`; paste that whole string, prefix included, into `bfm.users[].password-hash`.
Passwords shorter than 8 characters are refused. `ADMIN` operates (switchover, rejoin, reinit, settings),
`VIEWER` only looks. If you start the watcher with no users configured, it generates a one-time admin password
and writes it to the journal — fine for the first look, not for production.

### 5.2 `/etc/bfm/watcher.yml`

Full list with defaults: `packaging/watcher.yml.example`.

```yaml
server:
  port: 8443
  ssl:
    key-store: file:/etc/bfm/w1.p12
    key-store-type: PKCS12
    key-store-password: ${BFM_KEYSTORE_PASSWORD}

bfm:
  watcher-id: w1
  data-dir: /var/lib/bfm-watcher
  time-zone: Europe/Istanbul

  cluster:
    name: prod-main            # operators type this to confirm a switchover
    scope: DC_LOCAL            # automatic failover stays in the primary's data center
    self-fence: false          # true with Raft
    timing:
      poll-interval: 2s
      failure-threshold: 3     # 3 failed polls ≈ 6 s before the primary counts as lost
      max-lag: 16MB            # replicas further behind are not promoted automatically
    nodes:
      - name: pg-a1
        agent-url: https://10.0.0.11:8008
        pg-host: 10.0.0.11
        dc: DC1
        priority: 100
      - name: pg-a2
        agent-url: https://10.0.0.12:8008
        pg-host: 10.0.0.12
        dc: DC1
      - name: pg-dr1
        agent-url: https://10.20.0.11:8008
        pg-host: 10.20.0.11
        dc: DR

  agent:
    tls:
      enabled: true
      keystore: /etc/bfm/w1.p12
      keystore-password-file: /etc/bfm/w1.pass
      truststore: /etc/bfm/ca.p12
      truststore-password-file: /etc/bfm/ca.pass

  users:
    - name: admin
      password-hash: "{bcrypt}$2a$10$...from hash-password..."
      role: ADMIN
```

`pg-host` is the address replicas stream from and peer checks use — the node's own address, **never the VIP**.

The key store password goes in `/etc/bfm/watcher.env` (mode 640, group `bfm`), not in the YAML:

```
BFM_KEYSTORE_PASSWORD=...contents of w1.pass...
BFM_WATCHER_JAVA_OPTS="-Xmx512m"
```

### 5.3 Start it

```bash
systemctl enable --now bfm-watcher
journalctl -u bfm-watcher -f
```

Open `https://10.0.9.1:8443` (or the DNS name in the certificate) and log in.

### 5.4 Back up `secret.key`

`/var/lib/bfm-watcher/secret.key` encrypts the stored SMTP password. Back it up; with three watchers, copy the
first watcher's key to the others before they start, so all of them can read the same settings.

---

## 6. First start

The watcher never guesses a primary:

| Situation | What to do |
| --- | --- |
| One node already runs as primary | Nothing. The watcher records it ("… runs as primary; recorded it as the cluster primary") and starts leasing it. |
| No node runs as primary | On the Overview page, **Start as primary** on the node that should be it, then **Re-initialize** the others (`pg_basebackup`). |
| Two nodes run as primary | BFM records neither and does nothing: it never guesses. Stop the wrong one, then **Rejoin** or **Re-initialize** it from the console. |

Replicas that already stream keep streaming; BFM only adds a replication slot per replica on the primary the
first time it runs an operation (failover, switchover, start-as-primary or a repair). Until then the cluster
relies on whatever `wal_keep_size` or slots you already had.

---

## 7. Check that it works

Before you trust it, in a maintenance window:

1. **Overview** shows one primary, the VIP on it, every agent *Online*, every replica *Streaming*.
2. **Replication** shows the same from both sides: a walsender row per replica on the primary, and each
   replica reporting itself streaming from the primary's address, "Behind the primary" near 0, slots *Active*.
3. **Switchover** to `pg-a2`: pre-checks pass, type the cluster name, watch the steps. Clients on the VIP see
   a short interruption; the old primary comes back as a replica. Switch back.
4. **Crash test:** power off the primary (or `systemctl stop minipgagent; pg_ctl stop -m immediate`). After
   `failure-threshold` polls the watcher confirms with the replicas, raises the epoch, brings the VIP up on
   the best replica and promotes it. Bring the node back: it rejoins as a replica (`pg_rewind`).
5. **The safety rule:** break the sudo rule on the replica that would be chosen (with `scope: DC_LOCAL` and
   this example cluster, `pg-a2`: change the interface name in `/etc/sudoers.d/minipgagent`), then crash the primary.
   The failover refuses to promote it — *"VIP could not be brought up on pg-a2 … stays a replica (no VIP, no
   promote)"* — and retries every `failover-retry`. Put the rule back and watch the next attempt succeed.

Every one of these is in the event log with who did what, and in **Operations** with its steps.

---

## 8. Mail notifications

On the **Notifications** page: SMTP server, recipient groups (all events / critical only / role changes),
thresholds and per-event rules. The password is encrypted with `secret.key` and never shown again. Only the
leading watcher sends mail. *Send test mail* reports the SMTP server's own error if something is wrong.

With the default STARTTLS setting the session is encrypted before credentials are sent, and a server that does
not offer STARTTLS is refused. An SMTP server with an internal CA needs that CA in the JVM trust store, in
`/etc/bfm/watcher.env`:

```
BFM_WATCHER_JAVA_OPTS="-Xmx512m -Djavax.net.ssl.trustStore=/etc/bfm/smtp-ca.p12 -Djavax.net.ssl.trustStoreType=PKCS12 -Djavax.net.ssl.trustStorePassword=..."
```

This affects mail only; agent and Raft traffic use their own stores.

---

## 9. Three watchers (Raft)

One watcher is a single point of decision: if it is down, nothing fails over. Three watchers form an Apache
Ratis group, and only the Raft leader acts.

1. Install the watcher on three hosts, with a certificate each (`w1`, `w2`, `w3`).
2. Use the **same `watcher.yml` on all three**, changing only `watcher-id`.
3. Copy `secret.key` from the first watcher to the other two before they start.
4. Enable Raft and recommend self-fencing:

   ```yaml
   bfm:
     cluster:
       self-fence: true
     raft:
       enabled: true
       tls: true
       peers:
         - { id: w1, host: 10.0.9.1, port: 9870, url: https://10.0.9.1:8443 }
         - { id: w2, host: 10.0.9.2, port: 9870, url: https://10.0.9.2:8443 }
         - { id: w3, host: 10.0.9.3, port: 9870, url: https://10.0.9.3:8443 }
   ```

Followers poll, publish what they see, show the console read-only and link to the leader through `url`. With
`self-fence: true` a primary that stops getting lease renewals releases the VIP and stops PostgreSQL by itself,
which is what makes a split brain impossible when the watchers cannot reach it.

---

## 10. Day-2

**Upgrades.** `dnf upgrade ./minipgagent-*.rpm ./bfm-watcher-*.rpm` replaces the jars, keeps `/etc/bfm/*` (`%config(noreplace)`) and
**does not restart the services** — a restart would interrupt a running `pg_basebackup`, `pg_rewind` or
switchover. Restart them when the cluster is idle:

```bash
systemctl restart minipgagent          # one node at a time; PostgreSQL keeps running
systemctl restart bfm-watcher        # when no operation is running
```

**Back up:** `/etc/bfm/` (settings and certificates), `/var/lib/bfm-watcher/secret.key`, and the CA from
`bfm-make-certs` — `ca.key` offline.

**If every watcher is down.** The cluster keeps serving: agents never touch PostgreSQL or the VIP on their
own (unless `self-fence: true`, where the primary stops itself after `lease-ttl`). What stops is repair: a
node that reboots meanwhile is not started by anyone. `pg.autostart.replica=true` in `agent.properties` makes a
rebooted *replica* start itself; a primary, a fenced node or a half-rewritten data directory is still left for
the watcher.

**Planned maintenance.** Turn *Maintenance mode* on in the console: the watcher keeps watching and alerting but
starts no automatic failover. Turn it off afterwards.

**Adding or replacing a node.** Issue its certificate (`bfm-make-certs /etc/bfm/certs pg-a3=10.0.0.13`, the CA
key is needed for this), install and configure the agent, add the `nodes[]` entry to `watcher.yml` on every
watcher and restart them — `watcher.yml` is read at startup — then **Re-initialize** the new node from the
console to build it from the primary. Removing a node is the same in reverse: take it out of `watcher.yml`,
restart the watchers, then uninstall its agent.

**Removing a node from automatic failover:** `no-failover: true` in its `nodes[]` entry — it is never promoted
automatically, manual switchover still works.

**Uninstall.** `dnf remove minipgagent` stops and disables the agent; `KillMode=process` leaves PostgreSQL
running. Re-enable the PostgreSQL unit (`systemctl enable postgresql-16`) if you are going back to running it
yourself. `/etc/bfm` and `/var/lib/minipgagent*` are kept.

---

## 11. Troubleshooting

| What you see | What it means | What to do |
| --- | --- | --- |
| Agent *Offline* in the console | TLS, firewall or the agent is down | `journalctl -u minipgagent`; check that the certificate's SAN matches `agent-url`; `curl` the agent from the watcher host |
| `refusing to start without standby.signal: this would start a primary` | The agent was asked to start a node that has no `standby.signal` | Intentional. Use *Start as primary* if it really should be the primary, or *Re-initialize* to rebuild it as a replica |
| `VIP 192.168.0.50 is not up on this node; refusing to promote` | "No VIP, no promote" | Check the sudo rule and the interface name on that node; the Operations log says why the VIP could not come up |
| `ip addr add failed: … Operation not permitted` | The sudo rule does not match the command | `visudo -cf /etc/sudoers.d/minipgagent`; the address, prefix and interface must match `agent.properties` exactly |
| Failover blocked: *"still reach pg-a1"* | Replicas can still reach the primary, so it is not down from their side | Expected for a watcher-side network problem. Investigate the watcher's path to the primary |
| Failover blocked: *"N MB behind the primary, limit 16 MB"* | No replica is close enough | Raise `max-lag` knowingly, or switch over manually once a replica catches up |
| Failover blocked: *"in data center DR"* | `scope: DC_LOCAL` | Promote in the other DC with a manual switchover (a deliberate decision) |
| `stale epoch N (agent is at M)` | Another watcher steered this cluster, or an old watcher came back | Make sure only one watcher (group) manages this cluster; the console shows an epoch conflict and pauses automatic actions |
| `pg_rewind failed` | No `wal_log_hints` / checksums, or the rewind role cannot connect | Check §2.1 and §2.3; turn on *Auto reinit* to let BFM rebuild with `pg_basebackup` instead |
| Browser warns about the certificate | The CA is not trusted, or the name is not in the SAN | Add `ca.crt` to the operators' trust store; reissue the watcher certificate with the DNS name |
| `node is fenced: …` | BFM stopped this node to keep it from acting as a second primary | Read the reason in the event log, then **Rejoin** (pg_rewind) or **Re-initialize** it from the console |
| `busy with …` from an agent | A background operation (`pg_basebackup`, `pg_rewind`) is still running there | Watch it on the Operations page; it can take hours on a large database (`operation-timeout`) |
| API answers 302 to the login page | You are calling the console, not the API | Use `/api/v1/...` with HTTP Basic; the API never redirects |

---

## Other distributions

The RPMs only place files; nothing in BFM is Rocky-specific.

```
/usr/share/bfm/minipgagent.jar          bfm-agent/target/minipgagent.jar
/usr/share/bfm/bfm-watcher.jar          bfm-watcher/target/bfm-watcher.jar
/usr/bin/minipgagent                    packaging/bin/minipgagent
/usr/bin/bfm-watcher                    packaging/bin/bfm-watcher
/usr/bin/bfm-make-certs                 packaging/bin/bfm-make-certs
/etc/systemd/system/minipgagent.service packaging/minipgagent.service
/etc/systemd/system/bfm-watcher.service packaging/bfm-watcher.service
/etc/bfm/minipgagent/agent.properties   packaging/agent.properties.example   (0600 postgres)
/etc/bfm/watcher.yml                    packaging/watcher.yml.example        (0640 root:bfm)
/etc/sudoers.d/minipgagent              packaging/sudoers-minipgagent        (0440 root)
/var/lib/minipgagent                    agent state   (0700 postgres)
/var/lib/bfm-watcher                    watcher state (0700 bfm)
```

Create the `bfm` system user for the watcher (`groupadd -r bfm && useradd -r -N -g bfm -d /var/lib/bfm-watcher -s /sbin/nologin bfm`),
adjust `pg.bin` and `pg.data` for your layout (Debian: `/usr/lib/postgresql/16/bin`,
`/var/lib/postgresql/16/main`), then follow this guide from §3.

To try everything on one machine first, without root and without touching a real VIP, use
`scripts/local-lab.sh` — see the README.
