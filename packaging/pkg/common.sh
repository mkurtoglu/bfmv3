#!/bin/sh
# Package helper for @PKG@, installed as /usr/share/bfm/pkg/@PKG@.sh. The RPM scriptlets and the Debian maintainer
# scripts call it; it is not meant to be run by hand. POSIX sh: Debian runs maintainer scripts with dash.
#
# The packages own no file under /etc: the settings are made from the examples in /usr/share/bfm/examples when
# they are missing, and are never touched again by an upgrade or a removal. A removal keeps them, a reinstall finds
# them (and asks on Debian, or names them on RPM), and only a Debian purge deletes them.
#
#   users           create the service user and group when missing (Debian; the RPM does it in %pre)
#   seed            create the directories and the missing settings from the examples
#   found           print the settings of this host (not the untouched examples), one per line
#   fresh           move those settings to /etc/bfm/backup/@PKG@-<time>/, then seed
#   notice-kept     say which earlier settings a new installation kept, and how to start fresh
#   notice-removed  say what a removal kept
#   stop            stop and disable the services (a removal)
#   purge           delete the settings and the state (a Debian purge)
#   mark-install / take-install-mark   remember between scripts that this is an installation, not an upgrade

set -u
PKG=@PKG@
EXAMPLES=/usr/share/bfm/examples
BACKUPS=/etc/bfm/backup
MARK=/run/bfm-pkg-$PKG.install

say() {
  echo "$PKG: $*"
}

# Whether two files have the same content (cmp is not on every minimal system; sha256sum is).
same() {
  [ -f "$1" ] && [ -f "$2" ] && [ "$(sha256sum < "$1")" = "$(sha256sum < "$2")" ]
}

# seed FILE EXAMPLE OWNER:GROUP MODE: install the example when the file is missing
seed_file() {
  if [ ! -e "$1" ] && [ -f "$2" ]; then
    install -m "$4" -o "${3%:*}" -g "${3#*:}" "$2" "$1"
  fi
}

# Packages up to 0.1.0-3 owned the settings: upgrading from one moves an edited file to .rpmsave (and deletes an
# untouched one, which seed brings back). Put the edited one back where it was.
restore_rpmsave() {
  for f in "$@"; do
    if [ ! -e "$f" ] && [ -e "$f.rpmsave" ]; then
      mv "$f.rpmsave" "$f"
    fi
  done
}

systemd_running() {
  [ -d /run/systemd/system ] && command -v systemctl >/dev/null 2>&1
}

new_backup_dir() {
  d=$BACKUPS/$PKG-$(date -u +%Y%m%dT%H%M%SZ)
  install -d -m 0700 "$BACKUPS" "$d"
  echo "$d"
}

nologin() {
  for s in /usr/sbin/nologin /sbin/nologin; do
    if [ -x "$s" ]; then
      echo "$s"
      return
    fi
  done
  echo /bin/false
}

common_main() {
  case "${1:-}" in
    users) ensure_user ;;
    seed) seed ;;
    found) found ;;
    fresh)
      d=$(new_backup_dir)
      move_to "$d"
      say "the earlier settings are in $d"
      seed
      ;;
    notice-kept)
      list=$(found)
      if [ -n "$list" ]; then
        say "settings from an earlier installation were found and are kept:"
        echo "$list" | sed 's/^/    /'
        kept_hint
      fi
      ;;
    notice-removed) removed_hint ;;
    stop) stop ;;
    purge) purge ;;
    mark-install) : > "$MARK" ;;
    take-install-mark)
      if [ -e "$MARK" ]; then
        rm -f "$MARK"
        exit 0
      fi
      exit 1
      ;;
    *)
      echo "usage: $0 users|seed|found|fresh|notice-kept|notice-removed|stop|purge" >&2
      exit 64
      ;;
  esac
}
