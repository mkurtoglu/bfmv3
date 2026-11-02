#!/bin/bash
# Builds the installation guide (English and Turkish), the user guide and the installation reference as single HTML files, screenshots and style inside, so they
# open anywhere without a network connection.
#
#   scripts/docs-html.sh [OUT_DIR]      default: target/docs-html
#
# Needs pandoc 2.19 or newer. Links to other Markdown files go to GitHub, on the branch checked out
# (BFM_DOCS_BASE overrides the address).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mkdir -p "${1:-$ROOT/target/docs-html}" && cd "${1:-$ROOT/target/docs-html}" && pwd)"
cd "$ROOT/docs"
VERSION="$(git -C "$ROOT" describe --always --dirty 2>/dev/null || echo unknown)"
BRANCH="$(git -C "$ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo main)"
export BFM_DOCS_BASE="${BFM_DOCS_BASE:-https://github.com/mkurtoglu/bfmv3/blob/$BRANCH/}"

build() {  # source title [language]
  local src=$1 title=$2 lang=${3:-en}
  pandoc "$src" \
    --from gfm+implicit_figures --to html5 \
    --standalone --embed-resources \
    --template html/template.html --css html/guide.css \
    --lua-filter html/links.lua \
    --metadata pagetitle="$title" \
    --metadata lang="$lang" \
    --metadata footer="BFM · built from $VERSION on $(date -u +%Y-%m-%d)" \
    --output "$OUT/${src%.md}.html"
  echo "$OUT/${src%.md}.html"
}

build install-rpm.md "BFM installation (RPM)"
build install-rpm.tr.md "BFM kurulumu (RPM)" tr
build user-guide.md "BFM user guide"
build install.md "BFM installation reference"
