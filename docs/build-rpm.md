# Building the RPM packages

*English · [Türkçe](build-rpm.tr.md)*

`packaging/rpm/build.sh` builds the two packages, `bfm-watcher` and `minipgagent`, for Rocky Linux / RHEL 8 and 9.
The packages are `noarch` (Java), so one build host makes both the `el8` and the `el9` packages; it can be any
Linux. Installing them is described in [install-rpm.md](install-rpm.md).

## What the build host needs

| Tool | Version | Rocky / RHEL 9 | Debian / Ubuntu |
| --- | --- | --- | --- |
| Java (JDK) | 21 | `dnf install java-21-openjdk-devel` | `apt install openjdk-21-jdk-headless` |
| Maven | 3.9 | [apache-maven-3.9.x-bin.tar.gz](https://maven.apache.org/download.cgi), unpacked and on `PATH` | the same |
| rpmbuild | any | `dnf install rpm-build` | `apt install rpm` |
| git | any | `dnf install git` | `apt install git` |

Maven downloads the project's dependencies from Maven Central on the first build (about 300 MB under `~/.m2`);
behind a proxy, set it in `~/.m2/settings.xml`.

## Commands

From the repository's root:

```bash
git clone https://gitlab.bisoft.com.tr/bfm/bfmv3.git && cd bfmv3
```

Both distributions:

```bash
packaging/rpm/build.sh
```

Only EL9:

```bash
packaging/rpm/build.sh --dist el9
```

Only EL8:

```bash
packaging/rpm/build.sh --dist el8
```

Each run starts by clearing `target/rpm`: to keep both, build them in one run (the first command).

With the jars already built (`mvn package` ran before), skip Maven:

```bash
packaging/rpm/build.sh --skip-maven
```

The script compiles with `mvn -DskipTests package`. To run the tests first:

```bash
mvn -B verify && packaging/rpm/build.sh --skip-maven
```

## The result

```
target/rpm/el8/bfm-watcher-0.1.0-0.1.202611030915git767f1d4.el8.noarch.rpm
target/rpm/el8/minipgagent-0.1.0-0.1.202611030915git767f1d4.el8.noarch.rpm
target/rpm/el8/bfm-0.1.0-0.1.202611030915git767f1d4.el8.src.rpm
target/rpm/el9/bfm-watcher-0.1.0-0.1.202611030915git767f1d4.el9.noarch.rpm
target/rpm/el9/minipgagent-0.1.0-0.1.202611030915git767f1d4.el9.noarch.rpm
target/rpm/el9/bfm-0.1.0-0.1.202611030915git767f1d4.el9.src.rpm
```

- **Version and release.** While `pom.xml` has a `-SNAPSHOT` version, the release is
  `0.1.<UTC date and time>git<commit>`, so every later build upgrades an earlier one with `dnf upgrade`, also on
  the same day. A release version (no `-SNAPSHOT`) gets release `1`.
- **EL8 and EL9.** The packages differ only in their `dist` tag; both use an xz payload and SHA-256 digests,
  which rpm 4.14 on EL8 reads.
- **Check a package** before handing it out:

```bash
rpm -qpi target/rpm/el9/bfm-watcher-*.el9.noarch.rpm
```

```bash
rpm -qpl target/rpm/el9/minipgagent-*.el9.noarch.rpm
```

## In GitLab CI

`.gitlab-ci.yml` runs the tests and builds the packages on every push to `main`, on merge requests and on tags.
The RPMs are the `rpm` job's artifacts (Build → Pipelines → the pipeline → `rpm` → Download artifacts), kept for
30 days; tagged builds are kept. The jobs run in the `maven:3.9.9-eclipse-temurin-21` image (Java 21 and Maven
3.9 on Ubuntu; `rpm` is added in the job), so the project needs a GitLab Runner with the Docker executor that can
pull it — set `CI_BUILD_IMAGE` to a copy in your own registry otherwise.

The Maven download cache is kept between pipelines (`.m2/repository`). The real-PostgreSQL integration tests
(`AgentPostgresIT`, `WatcherPostgresIT`) are skipped in CI: they need PostgreSQL programs (`BFM_IT_PGBIN`).
