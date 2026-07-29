#!/bin/bash
#
# Install, remove, or inspect the macOS system-wide DNS blocker.
#
#   sudo ./safeworld-dnsd.sh install     build, install, load, and point DNS here
#   sudo ./safeworld-dnsd.sh uninstall   restore DNS and remove everything
#        ./safeworld-dnsd.sh status      what is running and what DNS is set (no root)
#
# Why a root daemon at all: a GUI app in /Applications runs as the user and cannot bind port 53.
#
# **Undo is the important part.** `uninstall` restores DNS first, then removes the daemon, and is
# safe to run at any point — including if install failed halfway. If anything ever goes wrong and
# this script is unavailable, one command fixes it:
#
#     sudo networksetup -setdnsservers Wi-Fi empty
#
set -uo pipefail

PREFIX="/Library/Application Support/SafeWorld"
PLIST="/Library/LaunchDaemons/com.safeworld.dnsd.plist"
LABEL="com.safeworld.dnsd"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE="$HERE/../../ios/SafeWorldCore"
BACKUP="$PREFIX/dns-restore.txt"

need_root() {
  if [ "$(id -u)" -ne 0 ]; then
    echo "This needs root: sudo $0 $1" >&2
    exit 1
  fi
}

services() {
  networksetup -listallnetworkservices 2>/dev/null | tail -n +2
}

# The resolver actually in use, which on DHCP is not visible through networksetup.
current_resolver() {
  scutil --dns 2>/dev/null | awk '/nameserver\[0\]/ {print $3; exit}'
}

status() {
  echo "daemon:"
  if launchctl list 2>/dev/null | grep -q "$LABEL"; then
    launchctl list | grep "$LABEL" | sed 's/^/  /'
  else
    echo "  not loaded"
  fi
  echo "port 53:"
  lsof -iUDP:53 -P 2>/dev/null | tail -n +2 | sed 's/^/  /' || echo "  (need root to inspect)"
  echo "DNS per service:"
  services | while read -r svc; do
    printf "  %-24s %s\n" "$svc" "$(networksetup -getdnsservers "$svc" 2>/dev/null | tr '\n' ' ')"
  done
  echo "resolver in use: $(current_resolver)"
}

install_daemon() {
  need_root install

  echo "==> building the daemon"
  # Built as the invoking user: swift build in a root-owned .build directory would leave files
  # the user can no longer touch.
  sudo -u "${SUDO_USER:-$(whoami)}" swift build -c release --package-path "$CORE" --product safeworld-dnsd || {
    echo "build failed" >&2; exit 1;
  }
  BIN="$(sudo -u "${SUDO_USER:-$(whoami)}" swift build -c release --package-path "$CORE" --show-bin-path)/safeworld-dnsd"

  echo "==> installing to $PREFIX"
  mkdir -p "$PREFIX/filters"
  cp "$BIN" "$PREFIX/safeworld-dnsd"
  chmod 755 "$PREFIX/safeworld-dnsd"
  cp "$CORE/Sources/SafeWorldCore/Resources/"*.filter "$PREFIX/filters/" 2>/dev/null || {
    echo "no filters found — run 'npm run build:ios' first" >&2; exit 1;
  }
  echo "    $(ls "$PREFIX/filters" | wc -l | tr -d ' ') filters, $(du -sh "$PREFIX/filters" | cut -f1)"

  # Saved before anything changes, so a failure part-way through is still recoverable.
  UPSTREAM="$(current_resolver)"
  [ -z "$UPSTREAM" ] && UPSTREAM="1.1.1.1"
  echo "$UPSTREAM" > "$BACKUP"

  echo "==> loading the daemon"
  cp "$HERE/com.safeworld.dnsd.plist" "$PLIST"
  chown root:wheel "$PLIST"
  chmod 644 "$PLIST"
  launchctl unload "$PLIST" 2>/dev/null
  launchctl load "$PLIST" || { echo "launchctl load failed" >&2; exit 1; }

  sleep 2
  echo "==> checking it answers before touching your DNS"
  if ! dig +short +time=3 +tries=1 @127.0.0.1 wikipedia.org >/dev/null 2>&1; then
    echo "daemon is not answering on port 53 — see /var/log/safeworld-dnsd.log" >&2
    echo "nothing was changed; removing the daemon again" >&2
    launchctl unload "$PLIST" 2>/dev/null
    rm -f "$PLIST"
    exit 1
  fi

  echo "==> pointing DNS at 127.0.0.1 (keeping $UPSTREAM as fallback)"
  # The real resolver stays as the secondary. macOS only falls back on timeout, and NXDOMAIN is a
  # valid answer rather than a failure — so blocking still works, but if the daemon dies the
  # machine keeps resolving instead of losing DNS entirely.
  services | while read -r svc; do
    networksetup -setdnsservers "$svc" 127.0.0.1 "$UPSTREAM" 2>/dev/null
  done
  dscacheutil -flushcache 2>/dev/null
  killall -HUP mDNSResponder 2>/dev/null

  echo
  echo "Done. Verify with:"
  echo "  dig +short bet365.com      # expect nothing (NXDOMAIN)"
  echo "  dig +short wikipedia.org   # expect an address"
  echo
  echo "Undo with: sudo $0 uninstall"
}

uninstall_daemon() {
  need_root uninstall

  # DNS first, always. Removing the daemon while the system still points at it would leave a
  # window with no resolver at all.
  echo "==> restoring DNS"
  services | while read -r svc; do
    networksetup -setdnsservers "$svc" empty 2>/dev/null
  done
  dscacheutil -flushcache 2>/dev/null
  killall -HUP mDNSResponder 2>/dev/null

  echo "==> removing the daemon"
  launchctl unload "$PLIST" 2>/dev/null
  rm -f "$PLIST"
  rm -rf "$PREFIX"

  echo "Done. DNS is back to DHCP."
}

case "${1:-status}" in
  install) install_daemon ;;
  uninstall) uninstall_daemon ;;
  status) status ;;
  *) echo "usage: $0 {install|uninstall|status}" >&2; exit 1 ;;
esac
