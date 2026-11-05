
# ------------------------------------------------------------------------------------------------- minipgagent

CONF=/etc/bfm/minipgagent
STATE=/var/lib/minipgagent
SUDOERS=/etc/sudoers.d/minipgagent

ensure_user() {
  # PostgreSQL's packages normally made it already: uid 26 on RHEL, a system user on Debian
  home=/var/lib/pgsql
  [ -f /etc/debian_version ] && home=/var/lib/postgresql
  if ! getent group postgres >/dev/null; then
    groupadd -r -g 26 postgres 2>/dev/null || groupadd -r postgres
  fi
  if ! getent passwd postgres >/dev/null; then
    useradd -r -N -g postgres -u 26 -d "$home" -s /bin/bash -c "PostgreSQL Server" postgres 2>/dev/null \
      || useradd -r -N -g postgres -d "$home" -s /bin/bash -c "PostgreSQL Server" postgres
  fi
}

tool() {
  for d in /usr/sbin /usr/bin /sbin /bin; do
    if [ -x "$d/$1" ]; then
      echo "$d/$1"
      return
    fi
  done
  echo "/usr/sbin/$1"
}

# The example sudo rule with this host's paths (Debian and Ubuntu keep arping in /usr/bin): sudo matches the path.
sudoers_example() {
  sed -e "s|/usr/sbin/arping|$(tool arping)|g" -e "s|/usr/sbin/ip |$(tool ip) |g" "$EXAMPLES/sudoers-minipgagent.example"
}

seed() {
  restore_rpmsave "$CONF/agent.properties" "$SUDOERS"
  install -d -m 0755 /etc/bfm
  install -d -m 0750 -o root -g postgres "$CONF"
  install -d -m 0700 -o postgres -g postgres "$STATE"
  seed_file "$CONF/agent.properties" "$EXAMPLES/agent.properties.example" postgres:postgres 0600
  if [ ! -e "$SUDOERS" ] && [ -d /etc/sudoers.d ]; then
    tmp=$(mktemp)
    sudoers_example > "$tmp"
    # a broken rule would lock everyone out of sudo: install it only when visudo accepts it
    if ! command -v visudo >/dev/null 2>&1 || visudo -cf "$tmp" >/dev/null 2>&1; then
      install -m 0440 -o root -g root "$tmp" "$SUDOERS"
    fi
    rm -f "$tmp"
  fi
}

found() {
  if [ -f "$CONF/agent.properties" ] && ! same "$CONF/agent.properties" "$EXAMPLES/agent.properties.example"; then
    echo "$CONF/agent.properties"
  fi
  for d in "$CONF"/*/; do
    case "$d" in
      *.removed-*) continue ;;
    esac
    [ -f "$d/agent.properties" ] && echo "${d%/}"
  done
  if [ -f "$SUDOERS" ]; then
    tmp=$(mktemp)
    sudoers_example > "$tmp"
    same "$SUDOERS" "$tmp" || echo "$SUDOERS"
    rm -f "$tmp"
  fi
  for f in "$SUDOERS"-*; do
    [ -f "$f" ] && echo "$f"
  done
  return 0
}

move_to() {
  install -d -m 0700 "$1/minipgagent" "$1/sudoers.d"
  for f in "$CONF"/* "$CONF"/.[!.]*; do
    [ -e "$f" ] && mv "$f" "$1/minipgagent/"
  done
  for f in "$SUDOERS" "$SUDOERS"-*; do
    [ -e "$f" ] && mv "$f" "$1/sudoers.d/"
  done
  return 0
}

instances() {
  for d in "$CONF"/*/; do
    case "$d" in
      *.removed-*) continue ;;
    esac
    [ -f "$d/agent.properties" ] && basename "$d"
  done
  return 0
}

kept_hint() {
  names=$(instances | tr '\n' ' ')
  if [ -n "$names" ]; then
    for n in $names; do
      echo "    start it again: systemctl enable --now minipgagent@$n"
    done
    echo "    or start fresh: minipgagent remove <instance>, then minipgagent setup"
  else
    echo "    start it again: systemctl enable --now minipgagent"
  fi
}

removed_hint() {
  say "the agents are stopped; PostgreSQL keeps running. Settings kept in $CONF and $SUDOERS*, state in $STATE:"
  say "a reinstall finds them (Debian: purge deletes them)."
}

stop() {
  if systemd_running; then
    systemctl --no-reload disable --now minipgagent.service >/dev/null 2>&1 || :
    for u in $(systemctl list-units --all --plain --no-legend 'minipgagent@*.service' 2>/dev/null \
        | awk '{print $1}'); do
      systemctl --no-reload disable --now "$u" >/dev/null 2>&1 || :
    done
    # instances that are enabled but not loaded
    for n in $(instances); do
      systemctl --no-reload disable "minipgagent@$n.service" >/dev/null 2>&1 || :
    done
  fi
}

purge() {
  rm -rf "$CONF" "$STATE" "$BACKUPS"/minipgagent-*
  rm -f "$SUDOERS" "$SUDOERS"-*
  rmdir "$BACKUPS" /etc/bfm 2>/dev/null || :
}

common_main "$@"
