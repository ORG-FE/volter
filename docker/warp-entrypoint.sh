#!/bin/bash
set -euo pipefail

WARP_SLEEP="${WARP_SLEEP:-5}"
WARP_GOST_ARGS="${WARP_GOST_ARGS:--L socks5://:1080}"

if [ ! -e /dev/net/tun ]; then
  mkdir -p /dev/net
  mknod /dev/net/tun c 10 200
  chmod 600 /dev/net/tun
fi

mkdir -p /run/dbus
rm -f /run/dbus/pid
dbus-daemon --config-file=/usr/share/dbus-1/system.conf

warp-svc --accept-tos &
sleep "$WARP_SLEEP"

if [ ! -f /var/lib/cloudflare-warp/reg.json ]; then
  warp-cli registration new
  if [ -n "${WARP_LICENSE_KEY:-}" ]; then
    warp-cli registration license "$WARP_LICENSE_KEY"
  fi
fi

warp-cli --accept-tos mode warp
warp-cli --accept-tos connect
warp-cli --accept-tos debug qlog disable || true
sleep "$WARP_SLEEP"

echo "[NAT] Enabling forwarding through CloudflareWARP"
nft add table ip nat 2>/dev/null || true
nft add chain ip nat WARP_NAT '{ type nat hook postrouting priority 100; }' 2>/dev/null || true
nft add rule ip nat WARP_NAT oifname "CloudflareWARP" masquerade 2>/dev/null || true

nft add table ip6 nat 2>/dev/null || true
nft add chain ip6 nat WARP_NAT '{ type nat hook postrouting priority 100; }' 2>/dev/null || true
nft add rule ip6 nat WARP_NAT oifname "CloudflareWARP" masquerade 2>/dev/null || true

ip -4 rule del fwmark 0x100cf lookup 65743 2>/dev/null || true
ip -4 rule add fwmark 0x100cf lookup 65743 priority 29999 2>/dev/null || true

# Traffic created by GOST must be marked before routing decision.
nft add table ip volter_mark 2>/dev/null || true
nft add chain ip volter_mark output '{ type route hook output priority mangle; }' 2>/dev/null || true
nft add rule ip volter_mark output ip daddr 127.0.0.0/8 return 2>/dev/null || true
nft add rule ip volter_mark output ip daddr 10.0.0.0/8 return 2>/dev/null || true
nft add rule ip volter_mark output ip daddr 172.16.0.0/12 return 2>/dev/null || true
nft add rule ip volter_mark output ip daddr 192.168.0.0/16 return 2>/dev/null || true
nft add rule ip volter_mark output ip daddr 162.159.198.2 return 2>/dev/null || true
nft add rule ip volter_mark output meta mark set 0x100cf 2>/dev/null || true
nft add rule inet cloudflare-warp output meta mark 0x100cf accept 2>/dev/null || true

echo "[GOST] Starting proxy: $WARP_GOST_ARGS"
gost $WARP_GOST_ARGS &

report_warp() {
  trace="$(curl -4fsS --max-time 8 https://www.cloudflare.com/cdn-cgi/trace 2>/dev/null || true)"
  ip="$(printf '%s\n' "$trace" | awk -F= '$1=="ip" {print $2}')"
  colo="$(printf '%s\n' "$trace" | awk -F= '$1=="colo" {print $2}')"
  if [ -n "$ip$colo" ]; then
    echo "WARP exit ip=${ip:-unknown} colo=${colo:-unknown}"
  else
    echo "WARP exit info unavailable"
  fi
}

report_warp
echo "WARP initialized"
wait
