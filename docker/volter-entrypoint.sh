#!/bin/sh
set -eu

WARP_GW="${WARP_GW:-}"
VOLTER_INTERNAL_PORT="${VOLTER_INTERNAL_PORT:-25565}"
VOLTER_PORTS="${LISTEN_PORTS:-$VOLTER_INTERNAL_PORT}"
QUIC_LISTEN_PORT="${QUIC_LISTEN_PORT:-4433}"
CONTROL_PORT="${CONTROL_PORT:-8088}"

detect_public_host() {
  if [ -n "${PUBLIC_HOST:-}" ] && [ "${PUBLIC_HOST:-}" != "auto" ]; then
    printf '%s' "$PUBLIC_HOST"
    return
  fi
  for url in https://api.ipify.org https://checkip.amazonaws.com https://ipv4.icanhazip.com; do
    ip="$(curl -4fsS --max-time 5 "$url" 2>/dev/null | tr -d '[:space:]' || true)"
    if [ -n "$ip" ]; then
      printf '%s' "$ip"
      return
    fi
  done
  printf '%s' "${PUBLIC_HOST_FALLBACK:-}"
}

setup_warp_routes() {
  if [ -z "$WARP_GW" ]; then
    return
  fi

  redsocks -c /etc/redsocks.conf || true
  sleep 1

  iptables -t nat -A OUTPUT -d 127.0.0.0/8 -j RETURN 2>/dev/null || true
  iptables -t nat -A OUTPUT -d "$WARP_GW" -j RETURN 2>/dev/null || true
  iptables -t nat -A OUTPUT -p tcp -j REDIRECT --to-port 12345 2>/dev/null || true

  iptables -t mangle -A OUTPUT -d 127.0.0.0/8 -j RETURN 2>/dev/null || true
  iptables -t mangle -A OUTPUT -d "$WARP_GW" -j RETURN 2>/dev/null || true
  iptables -t mangle -A OUTPUT -p udp --sport "$QUIC_LISTEN_PORT" -j RETURN 2>/dev/null || true
  iptables -t mangle -A OUTPUT -p udp -j MARK --set-mark 1 2>/dev/null || true
  ip route replace default via "$WARP_GW" table 100 2>/dev/null || true
  ip rule add fwmark 1 lookup 100 priority 100 2>/dev/null || true

  echo "WARP routing enabled via $WARP_GW"
}

setup_warp_routes

PUBLIC_HOST_RESOLVED="$(detect_public_host)"
if [ -z "$PUBLIC_HOST_RESOLVED" ]; then
  PUBLIC_HOST_RESOLVED="0.0.0.0"
fi

TOKEN_VALUE="${TOKEN:-}"
CONNECTION_KEY_VALUE="${CONNECTION_KEY:-}"
if [ -z "$TOKEN_VALUE" ] && [ -z "$CONNECTION_KEY_VALUE" ]; then
  echo "TOKEN not set, Volter will generate one. Persist TOKEN in .env for stable clients."
fi

if [ -f /app/config.properties ] && [ "${VOLTER_REGENERATE_CONFIG:-false}" != "true" ]; then
  exec java -cp /app/server.jar dev.c0redev.volter.Launcher
fi

cat > /app/config.properties <<EOF
listenPorts=${VOLTER_PORTS}
udpChannels=${UDP_CHANNELS:-4}
publicHost=${PUBLIC_HOST_RESOLVED}
token=${TOKEN_VALUE}
connectionKey=${CONNECTION_KEY_VALUE}
serverMode=${SERVER_MODE:-both}
quicListenPort=${QUIC_LISTEN_PORT}
quicCertPath=/app/certs/quic.crt
quicKeyPath=/app/certs/quic.key
debug=${DEBUG:-false}
peerRelayEnabled=${PEER_RELAY_ENABLED:-true}
control.panel=${CONTROL_PANEL:-false}
control.listen=0.0.0.0
control.port=${CONTROL_PORT}
control.db=/app/data/volter-control.sqlite
control.public=${CONTROL_PUBLIC:-true}
control.allowRemote=${CONTROL_ALLOW_REMOTE:-true}
EOF

echo "Generated /app/config.properties, publicHost=${PUBLIC_HOST_RESOLVED}, listenPorts=${VOLTER_PORTS}, quic=${QUIC_LISTEN_PORT}"
exec java -cp /app/server.jar dev.c0redev.volter.Launcher
