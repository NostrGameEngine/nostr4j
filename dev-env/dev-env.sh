#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
RUNTIME_DIR="$ROOT_DIR/.dev-env-runtime"
CERT_DIR="$RUNTIME_DIR/certs"
OWNER_DIR="$RUNTIME_DIR/owners"
COMPOSE_FILE="$SCRIPT_DIR/compose.yaml"
PORT="${NOSTR4J_DEV_HTTPS_PORT:-8443}"
OWNER_ADDED=false

usage() {
    echo "Usage: $0 {start|stop|status|logs} [--owner NAME] [--force]" >&2
    exit 2
}

ACTION="${1:-}"
shift || true
OWNER="manual"
FORCE=false
while (($#)); do
    case "$1" in
        --owner) OWNER="${2:?Missing owner name}"; shift 2 ;;
        --force) FORCE=true; shift ;;
        *) usage ;;
    esac
done
[[ "$OWNER" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "Invalid owner: $OWNER" >&2; exit 2; }

compose_command() {
    if command -v docker >/dev/null && docker compose version >/dev/null 2>&1; then
        COMPOSE=(docker compose)
    elif command -v podman-compose >/dev/null; then
        COMPOSE=(podman-compose)
    elif command -v podman >/dev/null && podman compose version >/dev/null 2>&1; then
        COMPOSE=(podman compose)
    else
        echo "Docker Compose or Podman Compose is required." >&2
        exit 1
    fi
}

cleanup() {
    local result=$?
    trap - EXIT
    if [[ "$ACTION" == "start" && $result -ne 0 && "$OWNER_ADDED" == "true" ]]; then
        rm -f "$OWNER_DIR/$OWNER"
        if ! compgen -G "$OWNER_DIR/*" >/dev/null; then
            NOSTR4J_DEV_HTTPS_PORT="$PORT" compose down --volumes --remove-orphans >/dev/null 2>&1 || true
        fi
    fi
    rm -rf "$LOCK_DIR"
    exit "$result"
}

compose() {
    "${COMPOSE[@]}" -f "$COMPOSE_FILE" "$@"
}

generate_certificates() {
    [[ -f "$CERT_DIR/ca.crt" && -f "$CERT_DIR/server.crt" && -f "$CERT_DIR/server.key" && -f "$CERT_DIR/truststore.p12" ]] && return
    command -v openssl >/dev/null || { echo "openssl is required." >&2; exit 1; }
    command -v keytool >/dev/null || { echo "keytool (from a JDK) is required." >&2; exit 1; }
    mkdir -p "$CERT_DIR"
    openssl genrsa -out "$CERT_DIR/ca.key" 3072 >/dev/null 2>&1
    openssl req -x509 -new -key "$CERT_DIR/ca.key" -sha256 -days 3650 \
        -subj "/CN=nostr4j development CA" -out "$CERT_DIR/ca.crt"
    openssl genrsa -out "$CERT_DIR/server.key" 2048 >/dev/null 2>&1
    openssl req -new -key "$CERT_DIR/server.key" -subj "/CN=localhost" -out "$CERT_DIR/server.csr"
    openssl x509 -req -in "$CERT_DIR/server.csr" -CA "$CERT_DIR/ca.crt" -CAkey "$CERT_DIR/ca.key" \
        -CAcreateserial -out "$CERT_DIR/server.crt" -days 825 -sha256 \
        -extfile <(printf '%s\n' \
            'subjectAltName=DNS:localhost,DNS:relay1.localhost,DNS:relay2.localhost,DNS:relay3.localhost,DNS:lnbits.localhost,IP:127.0.0.1' \
            'extendedKeyUsage=serverAuth') >/dev/null 2>&1
    rm -f "$CERT_DIR/server.csr" "$CERT_DIR/ca.srl"
    keytool -importcert -noprompt -alias nostr4j-dev-ca -file "$CERT_DIR/ca.crt" \
        -keystore "$CERT_DIR/truststore.p12" -storetype PKCS12 -storepass changeit >/dev/null
}

wait_for_url() {
    local url="$1"
    local header="${2:-}"
    local deadline=$((SECONDS + 240))
    local curl_args=(--silent --show-error --fail --max-time 5 --cacert "$CERT_DIR/ca.crt")
    [[ -z "$header" ]] || curl_args+=(-H "$header")
    until curl "${curl_args[@]}" "$url" >/dev/null 2>&1; do
        if ((SECONDS >= deadline)); then
            echo "Timed out waiting for $url" >&2
            compose ps >&2 || true
            exit 1
        fi
        sleep 1
    done
}

environment_is_ready() {
    [[ -f "$RUNTIME_DIR/test-env.properties" ]] || return 1
    curl --silent --fail --max-time 3 --cacert "$CERT_DIR/ca.crt" \
        "https://lnbits.localhost:$PORT/api/v1/health" >/dev/null || return 1
    local relay
    for relay in relay1 relay2 relay3; do
        curl --silent --fail --max-time 3 --cacert "$CERT_DIR/ca.crt" \
            -H 'Accept: application/nostr+json' "https://$relay.localhost:$PORT" >/dev/null || return 1
    done
}

mkdir -p "$RUNTIME_DIR" "$OWNER_DIR"
compose_command

case "$ACTION" in
    status)
        compose ps
        exit 0
        ;;
    logs)
        compose logs --follow
        exit 0
        ;;
    start|stop) ;;
    *) usage ;;
esac

if [[ "$ACTION" == "start" ]]; then
    command -v curl >/dev/null || { echo "curl is required." >&2; exit 1; }
    command -v python3 >/dev/null || { echo "Python 3 is required." >&2; exit 1; }
fi

LOCK_DIR="$RUNTIME_DIR/lifecycle.lock.d"
while ! mkdir "$LOCK_DIR" 2>/dev/null; do
    if [[ -f "$LOCK_DIR/pid" ]] && ! kill -0 "$(cat "$LOCK_DIR/pid")" 2>/dev/null; then
        rm -rf "$LOCK_DIR"
        continue
    fi
    sleep 0.1
done
echo "$$" > "$LOCK_DIR/pid"
trap cleanup EXIT

case "$ACTION" in
    start)
        [[ -e "$OWNER_DIR/$OWNER" ]] || OWNER_ADDED=true
        touch "$OWNER_DIR/$OWNER"
        generate_certificates
        if environment_is_ready 2>/dev/null; then
            echo "nostr4j development environment is already running (owner: $OWNER)."
            exit 0
        fi
        NOSTR4J_DEV_HTTPS_PORT="$PORT" compose up --build --detach
        wait_for_url "https://lnbits.localhost:$PORT/api/v1/health"
        python3 "$SCRIPT_DIR/bootstrap.py" --port "$PORT" --ca "$CERT_DIR/ca.crt" \
            --output "$RUNTIME_DIR/test-env.properties"
        for relay in relay1 relay2 relay3; do
            wait_for_url "https://$relay.localhost:$PORT" 'Accept: application/nostr+json'
        done
        echo "nostr4j development environment is ready on port $PORT (owner: $OWNER)."
        ;;
    stop)
        if $FORCE; then
            rm -f "$OWNER_DIR"/*
        else
            rm -f "$OWNER_DIR/$OWNER"
        fi
        if compgen -G "$OWNER_DIR/*" >/dev/null; then
            echo "Environment kept running; active owners: $(basename -a "$OWNER_DIR"/* | paste -sd, -)"
            exit 0
        fi
        NOSTR4J_DEV_HTTPS_PORT="$PORT" compose down --volumes --remove-orphans
        rm -f "$RUNTIME_DIR/test-env.properties"
        echo "nostr4j development environment stopped."
        ;;
    *) usage ;;
esac
