# Building the packages

*English · [Türkçe](build-packages.tr.md)*

One build makes every package, on Rocky Linux 9 (the build image is Rocky Linux 9.7):

| Package | Targets | Folder |
| --- | --- | --- |
| RPM `bfm-watcher`, `minipgagent` | Rocky / RHEL 8, 9, 10 | `target/packages/el8/`, `el9/`, `el10/` |
| DEB `bfm-watcher`, `minipgagent` | Ubuntu 22.04, 24.04, 26.04 | `target/packages/ubuntu22.04/`, `ubuntu24.04/`, `ubuntu26.04/` |

The packages are `noarch` / `all`: the code is Java 21, so one build serves every architecture. Each package
depends on its distribution's Java 21 runtime (`java-21-headless` on EL, `openjdk-21-jre-headless` on Ubuntu),
and the start scripts pick that runtime even when the host's default `java` is an older one (`BFM_JAVA` in
`/etc/bfm/watcher.env` or `/etc/bfm/minipgagent/agent.env` names another). Installing them is described in
[install-rpm.md](install-rpm.md).

## The build image

`packaging/Containerfile.build`: Rocky Linux 9.7 with Java 21 (`java-21-openjdk-devel`), Maven 3.9 (the
`maven:3.9` module, `maven-openjdk21`), `rpm-build`, `git`, `tar`, `xz` and `gzip`, all from Rocky's own
repositories. The build needs no privileges, no systemd and no container runtime inside the container, so it runs
as an AWS Fargate task as it does under Docker or Podman. `dpkg` is not needed either: a `.deb` is an `ar` archive
of `debian-binary`, `control.tar.xz` and `data.tar.xz`, which `packaging/build.sh` writes as `dpkg-deb` does.

```bash
docker build -f packaging/Containerfile.build -t bfm-build .
```

Behind a proxy that inspects TLS, give its CA certificate to the image build:

```bash
docker build --secret id=extra-ca,src=company-ca.crt -f packaging/Containerfile.build -t bfm-build .
```

Build with the source mounted (the packages land in `target/packages/`):

```bash
docker run --rm -v "$PWD:/src" bfm-build
```

**On AWS Fargate:** push the image to ECR and run a task from it that checks the repository out under `/src`
(or a CI system's Fargate runner that does it), runs `packaging/build.sh`, and copies `target/packages/` to
where the packages go (an S3 bucket, a package repository). The task needs outbound access to the Git server and
to Maven Central (or your Maven mirror); 4 GB of memory is plenty.

## Commands

From the repository's root, in the build image or on any Linux with the same tools:

Every package:

```bash
packaging/build.sh
```

Only the RPMs, or some of them:

```bash
packaging/build.sh --deb none --rpm "el9 el10"
```

Only the DEBs, or some of them:

```bash
packaging/build.sh --rpm none --deb "24.04 26.04"
```

The jars are already built (`mvn package` ran before):

```bash
packaging/build.sh --skip-maven
```

Tests first:

```bash
mvn -B verify && packaging/build.sh --skip-maven
```

`BFM_MAINTAINER="Name <address>"` puts your packaging contact into the packages (DEB maintainer and changelog,
RPM packager); without it they carry `BFM <bfm@localhost>`, which `lintian` flags. `--out DIR` writes somewhere
else than `target/packages`; each run empties its output folder first. The old
`packaging/rpm/build.sh [--dist "el8 el9"]` still works and builds only the RPMs, into `target/rpm/`.

## The result

```
target/packages/el9/bfm-watcher-0.1.0-0.1.202611050713git3aed7ff.el9.noarch.rpm
target/packages/el9/minipgagent-0.1.0-0.1.202611050713git3aed7ff.el9.noarch.rpm
target/packages/el9/bfm-0.1.0-0.1.202611050713git3aed7ff.el9.src.rpm
target/packages/ubuntu24.04/bfm-watcher_0.1.0~snap.202611050713.g3aed7ff-1~ubuntu24.04_all.deb
target/packages/ubuntu24.04/minipgagent_0.1.0~snap.202611050713.g3aed7ff-1~ubuntu24.04_all.deb
...
target/packages/SHA256SUMS
```

- **Versions.** While `pom.xml` has a `-SNAPSHOT` version, a build is versioned by its UTC time and commit
  (`0.1.<time>git<commit>` on RPM, `<version>~snap.<time>.g<commit>-1~ubuntu<release>` on DEB), so every later
  build upgrades an earlier one, also on the same day, and the release (no `-SNAPSHOT`) upgrades all of them.
- **EL8 to EL10.** The RPMs differ only in their `dist` tag; xz payload and SHA-256 digests are read by rpm 4.14
  (EL8) up to rpm 4.19 (EL10).
- **Ubuntu.** The DEBs differ in their version suffix and in where the systemd units go (`/lib/systemd/system`
  on 22.04 and 24.04, `/usr/lib/systemd/system` on 26.04). Ubuntu keeps `arping` in `/usr/bin`, RHEL in
  `/usr/sbin`: the agent and the sudo rule use the one the host has.
- **Check a package** before handing it out:

```bash
rpm -qpi target/packages/el9/bfm-watcher-*.el9.noarch.rpm
```

```bash
dpkg-deb --info target/packages/ubuntu24.04/minipgagent_*_all.deb
```

## Upgrade, removal and reinstallation

The packages own no file under `/etc`. Their settings come from the examples in `/usr/share/bfm/examples/` when
missing, and the packages never touch them after that:

| | RPM (`dnf`) | DEB (`apt`) |
| --- | --- | --- |
| **Installation** | `/etc/bfm/watcher.yml`, `/etc/bfm/minipgagent/agent.properties` and the sudo rule `/etc/sudoers.d/minipgagent` are made from the examples (the rule only if `visudo` accepts it); users `bfm` and `postgres` | the same |
| **Upgrade** | the settings stay as they are; no `.rpmnew` or `.rpmsave`, nothing else left behind; the services are not restarted (a restart would interrupt a running `pg_basebackup`, `pg_rewind` or switchover), the upgrade says so | the same; no `.dpkg-dist` or `.dpkg-old`, and no question about changed settings |
| **Removal** | the services are stopped and disabled (PostgreSQL keeps running); the settings in `/etc/bfm`, the sudo rules and the state in `/var/lib` stay | the same (`apt remove`) |
| **Reinstallation** | finds the earlier settings, uses them and names them; `bfm-watcher setup` asks before replacing them, `minipgagent remove <instance>` then `minipgagent setup` starts an agent fresh | **asks** whether to use the earlier settings (debconf). Yes keeps them; No moves them to `/etc/bfm/backup/<package>-<time>/` and installs the examples. Without a terminal (`DEBIAN_FRONTEND=noninteractive`) the answer is yes; preseed No with `echo "bfm-watcher bfm-watcher/use-existing-config boolean false" \| debconf-set-selections` |
| **Purge** | — | `apt purge` deletes the settings, the state (`secret.key` included) and the backups |

`dnf install` cannot ask a question: RPM scriptlets have no terminal, and an installation that waited for one
would stop Ansible and other automation. On RPM the question is therefore asked by the setup wizards.

Packages up to 0.1.0-3 owned the settings. Upgrading from one puts the settings it would have moved to
`.rpmsave` back in place.

**`dnf remove minipgagent` refuses with "would result in removing protected packages: sudo".** That happens
when `sudo` came onto the host only as the agent's dependency: dnf would remove it with the agent, and RHEL
protects it. Keep it:

```bash
dnf remove --noautoremove minipgagent
```

## In GitLab CI

`.gitlab-ci.yml` runs the tests and builds every package on each push to `main`, on merge requests and on
tags, in the Rocky Linux 9.7 image with the same packages the build image has. The packages are the `packages`
job's artifacts (Build → Pipelines → the pipeline → `packages` → Download artifacts), kept for 30 days; tagged
builds are kept. The project needs a GitLab Runner with the Docker executor that can pull
`rockylinux/rockylinux:9.7`; set `CI_BUILD_IMAGE` to a copy in your own registry otherwise. The real-PostgreSQL
integration tests (`AgentPostgresIT`, `WatcherPostgresIT`) are skipped in CI: they need PostgreSQL programs
(`BFM_IT_PGBIN`).

## Not yet on Ubuntu

The packages install, upgrade and remove cleanly on Ubuntu, and the watcher runs there. The agent, however,
starts PostgreSQL with `pg_ctl -D <data directory>` and expects `postgresql.conf` in that directory, as the PGDG
packages lay it out on RHEL (and on Ubuntu when PostgreSQL is initialized that way). Ubuntu's own PostgreSQL
packages keep the settings in `/etc/postgresql/<version>/<cluster>/` and start every cluster from
`postgresql.service`; `minipgagent setup` does not take such a cluster over yet. Until it does, run the agents of
Ubuntu hosts only on clusters whose settings live in their data directory.
