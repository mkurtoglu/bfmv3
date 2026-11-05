#!/bin/bash
# Kept for scripts that call it: builds only the RPMs, into target/rpm/<dist>/. packaging/build.sh builds the RPMs
# and the DEBs (docs/build-packages.md).
#
#   packaging/rpm/build.sh [--skip-maven] [--dist "el8 el9 el10"]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
args=()
dists="el8 el9 el10"
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-maven) args+=(--skip-maven) ;;
    --dist) dists=$2; shift ;;
    *) echo "unknown option $1" >&2; exit 64 ;;
  esac
  shift
done
exec "$ROOT/packaging/build.sh" "${args[@]}" --rpm "$dists" --deb none --out "$ROOT/target/rpm"
