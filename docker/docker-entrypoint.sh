#!/bin/sh
set -e

if [ -f /app/config.properties ]; then
  exec java -cp /app/server.jar dev.c0redev.volter.Launcher
fi

if [ -z "$TOKEN" ] && [ -z "$CONNECTION_KEY" ]; then
  echo "ERROR: TOKEN or CONNECTION_KEY must be set"
  exit 1
fi

LISTEN_PORTS="${LISTEN_PORTS:-25565}"
SERVER_MODE="${SERVER_MODE:-both}"
QUIC_LISTEN_PORT="${QUIC_LISTEN_PORT:-4433}"
DEBUG="${DEBUG:-false}"
CONTROL_PANEL="${CONTROL_PANEL:-false}"
CONTROL_PORT="${CONTROL_PORT:-8088}"

cat > /app/config.properties <<EOF
listenPorts=${LISTEN_PORTS}
udpChannels=4
publicHost=${PUBLIC_HOST}
token=${TOKEN}
connectionKey=${CONNECTION_KEY}
serverMode=${SERVER_MODE}
quicListenPort=${QUIC_LISTEN_PORT}
quicCertPath=/app/certs/quic.crt
quicKeyPath=/app/certs/quic.key
debug=${DEBUG}
peerRelayEnabled=true
control.panel=${CONTROL_PANEL}
control.listen=0.0.0.0
control.port=${CONTROL_PORT}
control.db=/app/data/volter-control.sqlite
control.public=true
control.allowRemote=true
EOF

echo "Generated /app/config.properties from environment"
exec java -cp /app/server.jar dev.c0redev.volter.Launcher
