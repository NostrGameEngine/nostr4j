#!/bin/sh
set -eu

if [ -n "${NOSTR4jDEMO_CLOUDFLARE_TUNNEL_TOKEN:-}" ]; then
    export TUNNEL_TOKEN="$NOSTR4jDEMO_CLOUDFLARE_TUNNEL_TOKEN"
    unset NOSTR4jDEMO_CLOUDFLARE_TUNNEL_TOKEN
    /usr/local/bin/cloudflared tunnel --no-autoupdate run &
fi

exec java -jar /app/demo-backend.jar
