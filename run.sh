#!/usr/bin/env bash
# Helper: build & run with host proxy (Clash/V2Ray on localhost:7890).
set -euo pipefail
cd "$(dirname "$0")"

PROXY_URL="${PROXY_URL:-http://host.docker.internal:7890}"

# Host shell proxy (for any local maven you might run outside Docker)
export http_proxy="${http_proxy:-http://localhost:7890}"
export https_proxy="${https_proxy:-http://localhost:7890}"

# Docker build/run proxy → host proxy port
export HTTP_PROXY="${HTTP_PROXY:-$PROXY_URL}"
export HTTPS_PROXY="${HTTPS_PROXY:-$PROXY_URL}"
export NO_PROXY="${NO_PROXY:-localhost,127.0.0.1,host.docker.internal}"

STAGE="${1:-all}"

mkdir -p data/generated reports

echo "Using HTTP_PROXY=$HTTP_PROXY"
echo "Stage: $STAGE"

docker compose build
docker compose run --rm play-duckdb "$STAGE"
