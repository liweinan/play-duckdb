#!/usr/bin/env bash
# Build & run Spark (Iceberg seed via Nomad) + DuckDB (query / report).
#
# Docker Desktop pulls images from the host, so HTTP_PROXY must be 127.0.0.1.
# Build/run containers reach Clash via host.docker.internal (BUILD_HTTP_PROXY).
# GitHub Actions sets CI=true — do not force :7890.
set -euo pipefail
cd "$(dirname "$0")"

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
  export NO_PROXY="${NO_PROXY:-localhost,127.0.0.1,host.docker.internal,postgres,minio,spark,nomad}"
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
WORKERS="${NOMAD_SPARK_WORKERS:-2}"
NOMAD_HTTP="http://127.0.0.1:4646"

echo "HTTP_PROXY=$HTTP_PROXY (docker pull)"
echo "BUILD_HTTP_PROXY=$BUILD_HTTP_PROXY (image build/run)"
echo "Stage: $STAGE"
echo "OBSERVE: ${OBSERVE:-off}"
echo "NOMAD_SPARK_WORKERS: $WORKERS"

docker compose build

JOB_DIR="/opt/nomad/jobs"
nomad_exec() {
  docker compose exec -T nomad nomad "$@"
}

use_host_nomad() {
  JOB_DIR="$(pwd)/nomad/jobs"
  nomad_exec() {
    nomad "$@"
  }
}

nomad_summary_field() {
  local job="$1"
  local group="$2"
  local field="$3"
  python3 - "$NOMAD_HTTP" "$job" "$group" "$field" <<'PY'
import json, sys, urllib.request
base, job, group, field = sys.argv[1:5]
with urllib.request.urlopen(base + "/v1/job/" + job + "/summary") as response:
    payload = json.load(response)
print(payload["Summary"][group][field])
PY
}

wait_nomad_running() {
  local job="$1"
  local group="$2"
  local want="$3"
  local i=0
  while (( i < 90 )); do
    local running
    running="$(nomad_summary_field "$job" "$group" Running || echo 0)"
    if [[ "$running" == "$want" ]]; then
      echo "[nomad] ${job} ${group} running=${running}"
      return 0
    fi
    sleep 2
    i=$((i + 1))
  done
  echo "[nomad] timed out waiting for ${job} ${group} running=${want}" >&2
  nomad_exec job status "$job" || true
  return 1
}

wait_nomad_batch() {
  local job="$1"
  local group="$2"
  local i=0
  while (( i < 180 )); do
    local complete failed
    complete="$(nomad_summary_field "$job" "$group" Complete || echo 0)"
    failed="$(nomad_summary_field "$job" "$group" Failed || echo 0)"
    if [[ "$complete" == "1" ]]; then
      echo "[nomad] ${job} complete"
      nomad_exec alloc logs -job "$job" || true
      return 0
    fi
    sleep 2
    i=$((i + 1))
  done
  echo "[nomad] timed out waiting for ${job} complete" >&2
  nomad_exec job status "$job" || true
  nomad_exec alloc logs -job "$job" || true
  return 1
}

start_nomad_host() {
  use_host_nomad
  export NOMAD_ADDR="${NOMAD_HTTP}"
  mkdir -p /tmp/play-duckdb-nomad
  if curl -sf "${NOMAD_HTTP}/v1/status/leader" >/dev/null; then
    echo "[nomad] host agent already up"
    return 0
  fi
  echo "[nomad] starting host agent -dev"
  nomad agent -dev -bind=0.0.0.0 -data-dir=/tmp/play-duckdb-nomad \
    > /tmp/play-duckdb-nomad-agent.log 2>&1 &
  echo $! > /tmp/play-duckdb-nomad-agent.pid
  local i=0
  while (( i < 60 )); do
    if curl -sf "${NOMAD_HTTP}/v1/status/leader" >/dev/null; then
      echo "[nomad] host agent ready"
      return 0
    fi
    sleep 1
    i=$((i + 1))
  done
  echo "[nomad] host agent failed to become ready" >&2
  cat /tmp/play-duckdb-nomad-agent.log >&2 || true
  return 1
}

start_nomad_compose() {
  mkdir -p /tmp/play-duckdb-nomad
  if ! docker compose up -d --wait postgres minio nomad; then
    echo "[nomad] compose up failed" >&2
    docker compose logs nomad || true
    docker compose ps -a || true
    return 1
  fi
}

run_seed() {
  docker compose up -d --wait postgres minio
  docker compose run --rm create-bucket

  if [[ "${CI:-}" == "true" ]] && command -v nomad >/dev/null 2>&1; then
    start_nomad_host
  else
    start_nomad_compose
  fi

  nomad_exec job run "${JOB_DIR}/spark-master.nomad"
  wait_nomad_running spark-master master 1

  nomad_exec job run -var="workers=${WORKERS}" "${JOB_DIR}/spark-worker.nomad"
  wait_nomad_running spark-worker worker "$WORKERS"
  echo "[nomad] spark-worker running=${WORKERS}"

  nomad_exec job stop -purge spark-etl >/dev/null 2>&1 || true
  nomad_exec job run -var="observe=${OBSERVE:-}" "${JOB_DIR}/spark-etl.nomad"
  wait_nomad_batch spark-etl etl

  if [[ "${OBSERVE:-}" == "1" ]]; then
    echo "======== observe: nomad job status ========"
    nomad_exec job status spark-master
    nomad_exec job status spark-worker
    nomad_exec job status spark-etl
  fi
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
