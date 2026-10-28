#!/bin/bash
# Lab stand-in for "ip addr" and "arping": who holds a VIP is a file per interface under ./net next to this
# script. The agent's vip.*.command templates call it instead of sudo ip / arping, so failover can be tried on one
# machine without root. Never use it on real servers.
#
#   lab-fake-ip.sh show|add|del|dad|garp <iface> <vip> [prefix]
#
# net/<vip>@<iface>   the VIP is up on that interface
# net/fail-add@<iface> "ip addr add" fails there (to try "no VIP, no promote")
# net/down@<iface>    that host is powered off: its VIP no longer answers ARP
set -u
NET="$(cd "$(dirname "$0")" && pwd)/net"
mkdir -p "$NET"
cmd=${1:?command}
iface=${2:?interface}
vip=${3:?address}
prefix=${4:-24}

case "$cmd" in
  show)
    echo "1: lo    inet 127.0.0.1/8 scope host lo"
    if [ -f "$NET/$vip@$iface" ]; then
      echo "2: $iface    inet $vip/$prefix scope global secondary $iface"
    fi
    ;;
  add)
    if [ -f "$NET/fail-add@$iface" ]; then
      echo "RTNETLINK answers: Operation not permitted" >&2
      exit 2
    fi
    touch "$NET/$vip@$iface"
    ;;
  del)
    rm -f "$NET/$vip@$iface"
    ;;
  dad)
    for f in "$NET/$vip@"*; do
      [ -e "$f" ] || continue
      holder=${f##*@}
      [ "$holder" = "$iface" ] && continue
      [ -f "$NET/down@$holder" ] && continue
      exit 1
    done
    ;;
  garp)
    ;;
  *)
    echo "unknown command $cmd" >&2
    exit 64
    ;;
esac
exit 0
