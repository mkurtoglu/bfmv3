#!/bin/bash
# Builds the BFM packages from one Rocky Linux 9 host or container (packaging/Containerfile.build; it needs no
# privileges, so it runs on AWS Fargate):
#
#   RPM  Rocky / RHEL 8, 9 and 10    target/packages/el8/ el9/ el10/
#   DEB  Ubuntu 22.04, 24.04, 26.04  target/packages/ubuntu22.04/ ubuntu24.04/ ubuntu26.04/
#
#   packaging/build.sh [--skip-maven] [--rpm "el8 el9 el10"|none] [--deb "22.04 24.04 26.04"|none] [--out DIR]
#
# Needs Java 21 and Maven 3.9 (unless --skip-maven), rpmbuild (for --rpm), and coreutils, tar, xz and gzip. The
# .deb files are written without dpkg: a .deb is an ar archive of debian-binary, control.tar.xz and data.tar.xz,
# put together here as dpkg-deb does. The packages are noarch / all (Java), one build serves every architecture.
#
# A -SNAPSHOT version in pom.xml gives RPM release 0.1.<UTC time>git<commit> and DEB version
# <version>~snap.<UTC time>.g<commit>-1~ubuntu<release>: a later build upgrades an earlier one, also on the same
# day, and the release without -SNAPSHOT upgrades every snapshot of it.
#
# BFM_MAINTAINER="Name <address>" puts your packaging contact into the packages (DEB Maintainer and changelog, RPM
# Packager); without it they say "BFM <bfm@localhost>".
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MAINTAINER="${BFM_MAINTAINER:-BFM <bfm@localhost>}"
RPMS="el8 el9 el10"
DEBS="22.04 24.04 26.04"
MAVEN=1
OUT="$ROOT/target/packages"
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-maven) MAVEN=0 ;;
    --rpm) RPMS=$2; shift ;;
    --deb) DEBS=$2; shift ;;
    --out) OUT=$2; shift ;;
    *) echo "unknown option $1" >&2; exit 64 ;;
  esac
  shift
done
[ "$RPMS" = none ] && RPMS=""
[ "$DEBS" = none ] && DEBS=""
for d in $RPMS; do
  case "$d" in el8|el9|el10) ;; *) echo "RPM targets are el8, el9, el10: $d" >&2; exit 64 ;; esac
done
for v in $DEBS; do
  case "$v" in 22.04|24.04|26.04) ;; *) echo "DEB targets are 22.04, 24.04, 26.04: $v" >&2; exit 64 ;; esac
done

need() {
  command -v "$1" >/dev/null 2>&1 || { echo "missing: $1 ($2)" >&2; exit 69; }
}
cd "$ROOT"
if [ "$MAVEN" = 1 ]; then
  need java "Java 21: dnf install java-21-openjdk-devel"
  need mvn "Maven 3.9: dnf module enable maven:3.9 && dnf install maven-openjdk21"
  major=$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)
  if [ "${major:-0}" -lt 21 ]; then
    echo "Java 21 or newer is needed to build; java is version ${major:-unknown}" >&2
    exit 69
  fi
  mvn -B -q -DskipTests package
fi
[ -n "$RPMS" ] && need rpmbuild "dnf install rpm-build"
for t in tar xz gzip sha256sum md5sum stat; do need "$t" "coreutils, tar, xz, gzip"; done

AGENT_JAR=bfm-agent/target/minipgagent.jar
WATCHER_JAR=bfm-watcher/target/bfm-watcher.jar
[ -f "$AGENT_JAR" ] && [ -f "$WATCHER_JAR" ] || { echo "jars missing; run without --skip-maven" >&2; exit 1; }

# ------------------------------------------------------------------------------------------------- versions

pom_version=$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' pom.xml | head -1)
version=${pom_version%-SNAPSHOT}
commit=$(git rev-parse --short HEAD 2>/dev/null || echo local)
SOURCE_DATE_EPOCH=${SOURCE_DATE_EPOCH:-$(git log -1 --format=%ct 2>/dev/null || date +%s)}
export SOURCE_DATE_EPOCH
if [ "$pom_version" != "$version" ]; then
  stamp=$(date -u +%Y%m%d%H%M)
  rpm_release="0.1.${stamp}git${commit}"
  deb_upstream="${version}~snap.${stamp}.g${commit}"
else
  rpm_release=1
  deb_upstream="$version"
fi

rm -rf "$OUT"
STAGE="$OUT/.stage"
mkdir -p "$STAGE"
trap 'rm -rf "$STAGE" "$OUT/.rpmbuild"' EXIT

# The package helpers: the shared part, then the package's own (packaging/pkg/).
for p in minipgagent bfm-watcher; do
  cat packaging/pkg/common.sh "packaging/pkg/$p.sh" | sed "s/@PKG@/$p/g" > "$STAGE/$p-pkg.sh"
done

# --------------------------------------------------------------------------------------------------- RPM

if [ -n "$RPMS" ]; then
  TOP="$OUT/.rpmbuild"
  mkdir -p "$TOP"/{SOURCES,SPECS,BUILD,RPMS,SRPMS}
  cp "$AGENT_JAR" "$WATCHER_JAR" "$TOP/SOURCES/"
  cp packaging/bin/minipgagent packaging/bin/bfm-watcher packaging/bin/bfm-make-certs "$TOP/SOURCES/"
  cp packaging/minipgagent.service 'packaging/minipgagent@.service' packaging/bfm-watcher.service "$TOP/SOURCES/"
  cp packaging/agent.properties.example packaging/watcher.yml.example packaging/sudoers-minipgagent "$TOP/SOURCES/"
  cp README.md LICENSE NOTICE "$STAGE/minipgagent-pkg.sh" "$STAGE/bfm-watcher-pkg.sh" "$TOP/SOURCES/"
  cp packaging/rpm/bfm.spec "$TOP/SPECS/"
  for d in $RPMS; do
    mkdir -p "$OUT/$d"
    # xz payload and SHA-256 digests: readable by rpm 4.14 on EL8 up to rpm 4.19 on EL10
    rpmbuild -ba "$TOP/SPECS/bfm.spec" \
      --define "_topdir $TOP" \
      --define "dist .$d" \
      --define "bfm_version $version" \
      --define "bfm_release $rpm_release" \
      --define "packager $MAINTAINER" \
      --define "_binary_payload w6.xzdio" \
      --define "_source_payload w6.xzdio" \
      --define "_binary_filedigest_algorithm 8" \
      --define "_source_filedigest_algorithm 8" \
      --define "_build_id_links none" \
      --define "use_source_date_epoch_as_buildtime 1" \
      --quiet
    mv "$TOP"/RPMS/noarch/*.rpm "$TOP"/SRPMS/*.rpm "$OUT/$d/"
  done
fi

# --------------------------------------------------------------------------------------------------- DEB

# ar_add ARCHIVE FILE: one member, the way dpkg-deb writes it (name, mtime, uid 0, gid 0, mode, size; even padding)
ar_add() {
  local size
  size=$(stat -c %s "$2")
  printf '%-16s%-12s%-6s%-6s%-8s%-10s`\n' "$(basename "$2")" "$SOURCE_DATE_EPOCH" 0 0 100644 "$size" >> "$1"
  cat "$2" >> "$1"
  if [ $((size % 2)) -eq 1 ]; then
    printf '\n' >> "$1"
  fi
}

# tar_xz DIR OUT: the directory's content as ./paths, owned by root, sorted, at the commit's time
tar_xz() {
  tar --sort=name --mtime="@$SOURCE_DATE_EPOCH" --owner=0 --group=0 --numeric-owner --format=gnu \
    -C "$1" -cf - . | xz -9 -T1 -c > "$2"
}

# the config script's search for the settings an earlier installation left (it runs before the files are unpacked)
find_snippet() {
  case "$1" in
    bfm-watcher)
      cat <<EOF
if [ -f /etc/bfm/watcher.yml ] && [ "\$(sha256sum < /etc/bfm/watcher.yml | cut -d' ' -f1)" != "$(sha256sum < packaging/watcher.yml.example | cut -d' ' -f1)" ]; then
  add /etc/bfm/watcher.yml
fi
for f in /etc/bfm/watcher.env /etc/bfm/*.p12 /etc/bfm/*.pass; do
  [ -f "\$f" ] && add "\$f"
done
EOF
      ;;
    minipgagent)
      cat <<EOF
if [ -f /etc/bfm/minipgagent/agent.properties ] && [ "\$(sha256sum < /etc/bfm/minipgagent/agent.properties | cut -d' ' -f1)" != "$(sha256sum < packaging/agent.properties.example | cut -d' ' -f1)" ]; then
  add /etc/bfm/minipgagent/agent.properties
fi
for d in /etc/bfm/minipgagent/*/; do
  case "\$d" in
    *.removed-*) continue ;;
  esac
  [ -f "\$d/agent.properties" ] && add "\${d%/}"
done
for f in /etc/sudoers.d/minipgagent-*; do
  [ -f "\$f" ] && add "\$f"
done
EOF
      ;;
  esac
}

# fill PKG FILE: a maintainer script from its template
fill() {
  local pkg=$1 tmpl=$2 note helper
  case "$pkg" in
    minipgagent) note="echo \"minipgagent: restart the agents when the node is idle: systemctl restart 'minipgagent*'\"" ;;
    bfm-watcher) note="echo \"bfm-watcher: restart it when no operation runs: systemctl restart bfm-watcher\"" ;;
  esac
  # postrm keeps the helper's functions: the helper itself is gone when postrm purges
  helper=$(sed -e '1d' -e '$d' "$STAGE/$pkg-pkg.sh")
  # ENVIRON, not awk -v: -v would turn the backslashes of the shell text into escapes
  PKG_NAME="$pkg" NOTE="$note" FIND="$(find_snippet "$pkg")" HELPER="$helper" awk '
    { gsub(/@PKG@/, ENVIRON["PKG_NAME"]) }
    /^@FIND@$/ { print ENVIRON["FIND"]; next }
    /^@HELPER@$/ { print ENVIRON["HELPER"]; next }
    /@UPGRADE_NOTE@/ { i = index($0, "@UPGRADE_NOTE@"); print substr($0, 1, i - 1) ENVIRON["NOTE"]; next }
    { print }
  ' "packaging/deb/$tmpl"
}

build_deb() {
  local pkg=$1 ubuntu=$2
  local debver="${deb_upstream}-1~ubuntu${ubuntu}"
  local work="$STAGE/deb/$pkg-$ubuntu"
  local unitdir=/lib/systemd/system
  local root="$work/root"
  local ctl="$work/control"
  # Ubuntu 26.04 keeps systemd units under /usr/lib, as Debian does since trixie; earlier releases under /lib
  [ "$ubuntu" = 26.04 ] && unitdir=/usr/lib/systemd/system
  mkdir -p "$root/usr/bin" "$root/usr/share/bfm/examples" "$root/usr/share/bfm/pkg" "$root$unitdir" \
    "$root/usr/share/doc/$pkg" "$ctl"

  install -m 0755 "$STAGE/$pkg-pkg.sh" "$root/usr/share/bfm/pkg/$pkg.sh"
  if [ "$pkg" = minipgagent ]; then
    install -m 0644 "$AGENT_JAR" "$root/usr/share/bfm/minipgagent.jar"
    install -m 0755 packaging/bin/minipgagent "$root/usr/bin/minipgagent"
    install -m 0644 packaging/agent.properties.example "$root/usr/share/bfm/examples/agent.properties.example"
    install -m 0644 packaging/sudoers-minipgagent "$root/usr/share/bfm/examples/sudoers-minipgagent.example"
    install -m 0644 packaging/minipgagent.service 'packaging/minipgagent@.service' "$root$unitdir/"
    depends="openjdk-21-jre-headless | java21-runtime-headless, iproute2, iputils-arping, sudo, passwd"
    summary="MiniPG agent: PostgreSQL role changes and the virtual IP on one node"
    long=" Runs as the postgres user next to one PostgreSQL server. It reports the node's
 state to the BFM watchers, brings the virtual IP up and down with sudo ip addr,
 promotes only while the VIP is up on this node, and fences itself when the
 primary lease from the watchers runs out.
 .
 Set it up with minipgagent setup. The settings in /etc/bfm/minipgagent stay
 when the package is removed; purge deletes them."
  else
    install -m 0644 "$WATCHER_JAR" "$root/usr/share/bfm/bfm-watcher.jar"
    install -m 0755 packaging/bin/bfm-watcher packaging/bin/bfm-make-certs "$root/usr/bin/"
    install -m 0644 packaging/watcher.yml.example "$root/usr/share/bfm/examples/watcher.yml.example"
    install -m 0644 packaging/bfm-watcher.service "$root$unitdir/"
    depends="openjdk-21-jre-headless | java21-runtime-headless, openssl, passwd"
    summary="BFM watcher: PostgreSQL failover decisions, web console and REST API"
    long=" Polls the MiniPG agents, keeps the primary's lease, decides failovers and
 runs switchovers, and serves the web console and the REST API. One watcher
 works alone; three form an Apache Ratis (Raft) group.
 .
 Set it up with bfm-watcher setup. The settings in /etc/bfm and the state in
 /var/lib/bfm-watcher stay when the package is removed; purge deletes them."
  fi
  install -m 0644 README.md "$root/usr/share/doc/$pkg/README.md"
  {
    echo "Format: https://www.debian.org/doc/packaging-manuals/copyright-format/1.0/"
    echo "Upstream-Name: bfm"
    echo "Source: https://github.com/mkurtoglu/bfmv3"
    echo
    echo "Files: *"
    echo "Copyright: 2026 Bisoft"
    echo "License: Apache-2.0"
    echo " On Debian and Ubuntu systems, the full text of the Apache License 2.0 is in"
    echo " /usr/share/common-licenses/Apache-2.0."
    echo " ."
    sed -e 's/^$/./' -e 's/^/ /' NOTICE
  } > "$root/usr/share/doc/$pkg/copyright"
  chmod 0644 "$root/usr/share/doc/$pkg/copyright"
  {
    echo "$pkg ($debver) unstable; urgency=medium"
    echo
    echo "  * Built from commit $commit."
    echo
    echo " -- $MAINTAINER  $(LC_ALL=C date -u -R -d "@$SOURCE_DATE_EPOCH")"
  } | gzip -9n > "$root/usr/share/doc/$pkg/changelog.Debian.gz"
  chmod 0644 "$root/usr/share/doc/$pkg/changelog.Debian.gz"
  find "$root" -type d -exec chmod 0755 {} +

  local size
  size=$(du -k -s --apparent-size "$root" | cut -f1)
  cat > "$ctl/control" <<EOF
Package: $pkg
Version: $debver
Architecture: all
Maintainer: $MAINTAINER
Installed-Size: $size
Depends: $depends, debconf (>= 0.5) | debconf-2.0
Section: database
Priority: optional
Homepage: https://github.com/mkurtoglu/bfmv3
Description: $summary
$long
EOF
  for s in config preinst postinst prerm postrm; do
    fill "$pkg" "$s.in" > "$ctl/$s"
    chmod 0755 "$ctl/$s"
  done
  install -m 0644 "packaging/deb/$pkg.templates" "$ctl/templates"
  (cd "$root" && find . -type f | LC_ALL=C sort | sed 's|^\./||' | xargs md5sum) > "$ctl/md5sums"
  chmod 0644 "$ctl/control" "$ctl/md5sums"

  printf '2.0\n' > "$work/debian-binary"
  tar_xz "$ctl" "$work/control.tar.xz"
  tar_xz "$root" "$work/data.tar.xz"
  local deb="$OUT/ubuntu$ubuntu/${pkg}_${debver}_all.deb"
  mkdir -p "$OUT/ubuntu$ubuntu"
  printf '!<arch>\n' > "$deb"
  ar_add "$deb" "$work/debian-binary"
  ar_add "$deb" "$work/control.tar.xz"
  ar_add "$deb" "$work/data.tar.xz"
}

for v in $DEBS; do
  for p in minipgagent bfm-watcher; do
    build_deb "$p" "$v"
  done
done

# ---------------------------------------------------------------------------------------------- the result

(cd "$OUT" && find . -name '*.rpm' -o -name '*.deb' | LC_ALL=C sort | sed 's|^\./||' | xargs -r sha256sum > SHA256SUMS)
for d in $RPMS; do echo "$d:"; ls -1 "$OUT/$d"; done
for v in $DEBS; do echo "ubuntu$v:"; ls -1 "$OUT/ubuntu$v"; done
echo "checksums: $OUT/SHA256SUMS"
