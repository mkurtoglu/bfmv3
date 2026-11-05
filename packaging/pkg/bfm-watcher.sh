
# ------------------------------------------------------------------------------------------------- bfm-watcher

ETC=/etc/bfm
YML=$ETC/watcher.yml
STATE=/var/lib/bfm-watcher

ensure_user() {
  if ! getent group bfm >/dev/null; then
    groupadd -r bfm
  fi
  if ! getent passwd bfm >/dev/null; then
    useradd -r -N -g bfm -d "$STATE" -s "$(nologin)" -c "BFM watcher" bfm
  fi
}

seed() {
  restore_rpmsave "$YML"
  install -d -m 0755 "$ETC"
  install -d -m 0700 -o bfm -g bfm "$STATE"
  seed_file "$YML" "$EXAMPLES/watcher.yml.example" root:bfm 0640
}

# What bfm-watcher setup writes next to watcher.yml: the console's key store, the CA, their passwords.
own_files() {
  for f in "$ETC/watcher.env" "$ETC"/*.p12 "$ETC"/*.pass; do
    [ -f "$f" ] && echo "$f"
  done
  return 0
}

found() {
  if [ -f "$YML" ] && ! same "$YML" "$EXAMPLES/watcher.yml.example"; then
    echo "$YML"
  fi
  own_files
}

move_to() {
  install -d -m 0700 "$1/bfm"
  for f in "$YML" $(own_files); do
    [ -e "$f" ] && mv "$f" "$1/bfm/"
  done
  return 0
}

kept_hint() {
  echo "    start it again: systemctl enable --now bfm-watcher"
  echo "    or start fresh: bfm-watcher setup (it asks before replacing them)"
  if [ -f "$STATE/secret.key" ]; then
    echo "    the state in $STATE is kept too, secret.key included"
  fi
}

removed_hint() {
  say "the watcher is stopped. Settings kept in $ETC, state in $STATE (secret.key included):"
  say "a reinstall finds them (Debian: purge deletes them)."
}

stop() {
  if systemd_running; then
    systemctl --no-reload disable --now bfm-watcher.service >/dev/null 2>&1 || :
  fi
}

purge() {
  for f in "$YML" $(own_files); do
    rm -f "$f"
  done
  rm -rf "$STATE" "$BACKUPS"/bfm-watcher-*
  rmdir "$BACKUPS" "$ETC" 2>/dev/null || :
}

common_main "$@"
