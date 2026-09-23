#!/usr/bin/env bash
set -euo pipefail

# -----------------------------------------------------------------------------
# Nostr4J Documentation Build Script (MkDocs & Podman)
# -----------------------------------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

MKDOCS_IMAGE="docker.io/squidfunk/mkdocs-material:latest"
JAVA_IMAGE="docker.io/library/eclipse-temurin:25-jdk"

# Detect whether podman can mount the current working directory directly.
# (On macOS AppleHV VMs, only paths under /Users and /private are shared by default).
can_direct_mount() {
    podman run --rm -v "$SCRIPT_DIR:/mnt:ro" alpine true >/dev/null 2>&1
}

# Run MkDocs command inside Podman
run_mkdocs() {
    local cmd=("$@")
    if can_direct_mount; then
        podman run --rm -it -v "$SCRIPT_DIR:/docs:z" "$MKDOCS_IMAGE" "${cmd[@]}"
    else
        # Staging directory under /private/tmp which is shared with AppleHV
        local STAGE_DIR="/private/tmp/nostr4j-mkdocs-stage"
        mkdir -p "$STAGE_DIR"
        rsync -a --delete \
            --exclude='.git' \
            --exclude='.cache' \
            --exclude='site-demos/build' \
            --exclude='_site' \
            --exclude='/nostr4j*' \
            --exclude='.gradle' \
            "$SCRIPT_DIR/" "$STAGE_DIR/"
        
        podman run --rm -v "$STAGE_DIR:/docs" "$MKDOCS_IMAGE" "${cmd[@]}"
        
        # Sync back output directory if build was run
        if [[ -d "$STAGE_DIR/_site" ]]; then
            mkdir -p "$SCRIPT_DIR/_site"
            rsync -a --delete "$STAGE_DIR/_site/" "$SCRIPT_DIR/_site/"
        fi
    fi
}

cmd_serve() {
    echo "==> Starting MkDocs preview server with live reload on http://localhost:8000..."
    if can_direct_mount; then
        podman run --rm -it -p 8000:8000 -v "$SCRIPT_DIR:/docs:z" "$MKDOCS_IMAGE" serve -a 0.0.0.0:8000
    else
        local STAGE_DIR="/private/tmp/nostr4j-mkdocs-stage"
        mkdir -p "$STAGE_DIR"
        rsync -a --delete \
            --exclude='.git' \
            --exclude='.cache' \
            --exclude='site-demos/build' \
            --exclude='_site' \
            --exclude='/nostr4j*' \
            --exclude='.gradle' \
            "$SCRIPT_DIR/" "$STAGE_DIR/"

        # Run background rsync synchronizer so file edits sync into the container volume for live reload
        (
            while true; do
                sleep 1
                rsync -a --update \
                    --exclude='.git' \
                    --exclude='.cache' \
                    --exclude='site-demos/build' \
                    --exclude='_site' \
                    --exclude='/nostr4j*' \
                    --exclude='.gradle' \
                    "$SCRIPT_DIR/docs/" "$STAGE_DIR/docs/" 2>/dev/null || true
                rsync -a --update \
                    "$SCRIPT_DIR/theme/" "$STAGE_DIR/theme/" 2>/dev/null || true
                rsync -a --update \
                    "$SCRIPT_DIR/mkdocs.yml" "$STAGE_DIR/mkdocs.yml" 2>/dev/null || true
            done
        ) &
        SYNC_PID=$!
        trap 'kill "$SYNC_PID" 2>/dev/null || true' EXIT INT TERM

        podman run --rm -it -p 8000:8000 -v "$STAGE_DIR:/docs" "$MKDOCS_IMAGE" serve -a 0.0.0.0:8000
    fi
}

cmd_build() {
    echo "==> Building static documentation site with MkDocs..."
    run_mkdocs build -d _site
    echo "==> Validating internal links and assets..."
    if command -v node >/dev/null 2>&1; then
        node scripts/check-site.mjs _site /nostr4j
    else
        podman run --rm -v "$SCRIPT_DIR/_site:/site:ro" -v "$SCRIPT_DIR/scripts:/scripts:ro" \
            docker.io/library/node:20-alpine node /scripts/check-site.mjs /site /nostr4j
    fi
    echo "==> Build complete: _site/"
}

cmd_demos() {
    local REF="${1:-master}"
    echo "==> Compiling browser demos and Javadoc against ref: $REF..."

    local CACHE_DIR
    if can_direct_mount; then
        CACHE_DIR="$SCRIPT_DIR/.cache"
    else
        CACHE_DIR="/private/tmp/nostr4j-build-cache"
    fi
    mkdir -p "$CACHE_DIR"

    # 1. Prepare master / ref checkout
    local MASTER_DIR="$CACHE_DIR/master"
    if [[ ! -d "$MASTER_DIR/.git" ]]; then
        echo "--> Cloning nostr4j ($REF) into $MASTER_DIR..."
        git clone --filter=blob:none -b "$REF" "$SCRIPT_DIR" "$MASTER_DIR" 2>/dev/null || \
        git clone -b "$REF" "https://github.com/NostrGameEngine/nostr4j.git" "$MASTER_DIR"
    else
        echo "--> Updating $MASTER_DIR to ref $REF..."
        (
            cd "$MASTER_DIR"
            git fetch origin "$REF" 2>/dev/null || git fetch "$SCRIPT_DIR" "$REF"
            git checkout "$REF"
            git pull --ff-only 2>/dev/null || true
        )
    fi

    # 2. Prepare nge-platforms dependency
    local PLATFORMS_DIR="$CACHE_DIR/platforms"
    if [[ ! -d "$PLATFORMS_DIR/.git" ]]; then
        echo "--> Cloning nge-platforms into $PLATFORMS_DIR..."
        git clone --depth 1 https://github.com/NostrGameEngine/nge-platforms.git "$PLATFORMS_DIR"
    else
        echo "--> Updating nge-platforms in $PLATFORMS_DIR..."
        (cd "$PLATFORMS_DIR" && git pull --ff-only 2>/dev/null || true)
    fi

    # 3. Overlay site-demos into library checkout
    echo "--> Overlaying site-demos into library checkout..."
    rm -rf "$MASTER_DIR/site-demos"
    cp -R "$SCRIPT_DIR/site-demos" "$MASTER_DIR/site-demos"

    (
        cd "$MASTER_DIR"
        if ! grep -q 'site-demos' settings.gradle; then
            printf '\ninclude("site-demos")\n' >> settings.gradle
        fi
    )

    # 4. Compile with Podman using JDK 25
    echo "--> Running Gradle compilation inside $JAVA_IMAGE container..."
    podman run --rm -v "$CACHE_DIR:/workspace" -w /workspace "$JAVA_IMAGE" sh -c "
        set -e
        echo '--> Publishing platform modules to container Maven cache...'
        cd /workspace/platforms && ./gradlew :nge-platform-common:publishToMavenLocal :nge-platform-jvm:publishToMavenLocal :nge-platform-teavm:publishToMavenLocal --no-daemon
        echo '--> Compiling TeaVM demos and Javadoc from $REF...'
        cd /workspace/master && ./gradlew :site-demos:buildAllDemos :site-demos:jvmSmokeTest :nostr4j:javadoc --no-daemon
    "

    # 5. Stage outputs to docs/
    echo "--> Staging compiled browser demos to docs/assets/demos/..."
    for demo in quickstart relay nwc rtc game; do
        mkdir -p "$SCRIPT_DIR/docs/assets/demos/$demo"
        cp -a "$MASTER_DIR/site-demos/build/teavm/$demo/." "$SCRIPT_DIR/docs/assets/demos/$demo/"
    done

    echo "--> Staging Javadoc to docs/api/..."
    mkdir -p "$SCRIPT_DIR/docs/api"
    cp -a "$MASTER_DIR/dist/javadoc/nostr4j/." "$SCRIPT_DIR/docs/api/"

    echo "==> Demo and API reference build complete!"
}

cmd_all() {
    cmd_demos "${1:-master}"
    cmd_build
}

cmd_check() {
    if [[ ! -d "$SCRIPT_DIR/_site" ]]; then
        echo "Error: _site/ does not exist. Run './build.sh build' first." >&2
        exit 1
    fi
    node scripts/check-site.mjs _site /nostr4j
}

cmd_clean() {
    echo "==> Cleaning build artifacts and caches..."
    rm -rf _site/ _lan-preview/ .cache/ site-demos/build/
    rm -rf /private/tmp/nostr4j-mkdocs-stage /private/tmp/nostr4j-build-cache 2>/dev/null || true
    echo "==> Clean complete."
}

usage() {
    cat << EOF
Usage: ./build.sh [command] [options]

Commands:
  serve         Start MkDocs development server with live reload on http://localhost:8000
  build         Build static documentation site with MkDocs and validate links
  demos [REF]   Compile browser TeaVM demos and Javadoc against ref (default: master)
  all [REF]     Compile demos and build static documentation site
  check         Run internal link and asset validator on _site/
  clean         Clean generated site and build caches

All builds run in Podman containers without requiring host-level toolchains.
EOF
}

case "${1:-usage}" in
    serve) cmd_serve ;;
    build) cmd_build ;;
    demos) cmd_demos "${2:-master}" ;;
    all)   cmd_all "${2:-master}" ;;
    check) cmd_check ;;
    clean) cmd_clean ;;
    *)     usage ;;
esac
