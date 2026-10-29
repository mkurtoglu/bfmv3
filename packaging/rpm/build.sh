#!/bin/bash
# Builds the bfm-agent and bfm-watcher RPMs for Rocky / RHEL 8 and 9.
#
#   packaging/rpm/build.sh [--skip-maven] [--dist "el8 el9"]
#
# Needs Java 21, Maven and rpmbuild (package "rpm-build" on Rocky, "rpm" on Debian/Ubuntu). The packages are
# noarch, so they can be built on any Linux; the result lands in target/rpm/<dist>/. A snapshot version gets a
# release like 0.1.20261028git7ac2264 so that later builds upgrade earlier ones.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DISTS="el8 el9"
MAVEN=1
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-maven) MAVEN=0 ;;
    --dist) DISTS=$2; shift ;;
    *) echo "unknown option $1" >&2; exit 64 ;;
  esac
  shift
done

cd "$ROOT"
if [ "$MAVEN" = 1 ]; then
  mvn -B -q -DskipTests package
fi
AGENT_JAR=bfm-agent/target/bfm-agent.jar
WATCHER_JAR=bfm-watcher/target/bfm-watcher.jar
[ -f "$AGENT_JAR" ] && [ -f "$WATCHER_JAR" ] || { echo "jars missing; run without --skip-maven" >&2; exit 1; }

pom_version=$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' pom.xml | head -1)
version=${pom_version%-SNAPSHOT}
if [ "$pom_version" != "$version" ]; then
  release="0.1.$(date -u +%Y%m%d)git$(git rev-parse --short HEAD 2>/dev/null || echo local)"
else
  release=1
fi

TOP="$ROOT/target/rpm"
rm -rf "$TOP"
mkdir -p "$TOP"/{SOURCES,SPECS,BUILD,RPMS,SRPMS}
cp "$AGENT_JAR" "$WATCHER_JAR" "$TOP/SOURCES/"
cp packaging/bin/bfm-agent packaging/bin/bfm-watcher packaging/bin/bfm-make-certs "$TOP/SOURCES/"
cp packaging/bfm-agent.service packaging/bfm-watcher.service "$TOP/SOURCES/"
cp packaging/agent.properties.example packaging/watcher.yml.example packaging/sudoers-bfm "$TOP/SOURCES/"
cp README.md "$TOP/SOURCES/"
cp packaging/rpm/bfm.spec "$TOP/SPECS/"

for d in $DISTS; do
  out="$TOP/$d"
  mkdir -p "$out"
  # xz payload and sha256 digests: readable by rpm 4.14 on EL8 as well as EL9
  rpmbuild -ba "$TOP/SPECS/bfm.spec" \
    --define "_topdir $TOP" \
    --define "dist .$d" \
    --define "bfm_version $version" \
    --define "bfm_release $release" \
    --define "_binary_payload w6.xzdio" \
    --define "_source_payload w6.xzdio" \
    --define "_build_id_links none" \
    --quiet
  mv "$TOP"/RPMS/noarch/*.rpm "$out/"
  mv "$TOP"/SRPMS/*.rpm "$out/"
  echo "$d:"
  ls -1 "$out"
done
