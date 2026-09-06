#!/usr/bin/env bash
# Build & run Spark (Iceberg seed) + DuckDB (query / report).
#
# Docker Desktop pulls images from the host, so HTTP_PROXY must be 127.0.0.1.
# Build/run containers reach Clash via host.docker.internal (BUILD_HTTP_PROXY).
set -euo pipefail
cd "$(dirname "$0")"

# Local macOS + Clash. GitHub Actions sets CI=true — do not force :7890.
if [[ "${CI:-}" == "true" ]]; then
  export http_proxy=""
  export https_proxy=""
  export HTTP_PROXY=""
  export HTTPS_PROXY=""
  export BUILD_HTTP_PROXY=""
  export BUILD_HTTPS_PROXY=""
  export NO_PROXY="${NO_PROXY:-*}"
else
  export http_proxy="${http_proxy:-http://127.0.0.1:7890}"
  export https_proxy="${https_proxy:-http://127.0.0.1:7890}"
  export HTTP_PROXY="${HTTP_PROXY:-http://127.0.0.1:7890}"
  export HTTPS_PROXY="${HTTPS_PROXY:-http://127.0.0.1:7890}"
  export BUILD_HTTP_PROXY="${BUILD_HTTP_PROXY:-http://host.docker.internal:7890}"
  export BUILD_HTTPS_PROXY="${BUILD_HTTPS_PROXY:-http://host.docker.internal:7890}"
  export NO_PROXY="${NO_PROXY:-localhost,127.0.0.1,host.docker.internal,postgres,minio,spark}"
fi

STAGE="all"
for arg in "$@"; do
  case "$arg" in
    --observe|-o)
      export OBSERVE=1
      ;;
    all|seed|query|layers|report)
      STAGE="$arg"
      ;;
    *)
      echo "Use: all | seed | query | layers | report  [--observe]" >&2
      exit 1
      ;;
  esac
done

mkdir -p reports

echo "HTTP_PROXY=$HTTP_PROXY (docker pull)"
echo "BUILD_HTTP_PROXY=$BUILD_HTTP_PROXY (image build/run)"
echo "Stage: $STAGE"
echo "OBSERVE: ${OBSERVE:-off}"

docker compose build

run_seed() {
  docker compose run --rm -e OBSERVE="${OBSERVE:-}" spark
}

run_query() {
  docker compose run --rm -e OBSERVE="${OBSERVE:-}" play-duckdb query ${OBSERVE:+--observe}
}

run_layers() {
  docker compose run --rm -e OBSERVE="${OBSERVE:-}" play-duckdb layers ${OBSERVE:+--observe}
}

run_report() {
  docker compose run --rm -e OBSERVE="${OBSERVE:-}" play-duckdb report ${OBSERVE:+--observe}
}

case "$STAGE" in
  seed)
    run_seed
    ;;
  query)
    run_query
    ;;
  layers)
    run_layers
    ;;
  report)
    run_report
    ;;
  all)
    run_seed
    run_query
    run_layers
    run_report
    ;;
  *)
    echo "Use: all | seed | query | layers | report  [--observe]" >&2
    exit 1
    ;;
esac
