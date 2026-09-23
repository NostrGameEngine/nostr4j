#!/usr/bin/env bash
set -euo pipefail

# Legacy entrypoint: delegate to root ./build.sh build
cd "$(dirname "$0")/.."
exec ./build.sh all "${1:-master}"
