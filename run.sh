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

nomad_exec() {
  docker compose exec -T nomad nomad "$@"
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
    if [[ "$failed" != "0" ]]; then
      echo "[nomad] ${job} failed=${failed}" >&2
      nomad_exec job status "$job" || true
      nomad_exec alloc logs -job "$job" || true
      return 1
    fi
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

run_seed() {
  docker compose up -d --wait postgres minio nomad
  docker compose run --rm create-bucket

  nomad_exec job run /opt/nomad/jobs/spark-master.nomad
  wait_nomad_running spark-master master 1

  nomad_exec job run -var="workers=${WORKERS}" /opt/nomad/jobs/spark-worker.nomad
  wait_nomad_running spark-worker worker "$WORKERS"
  echo "[nomad] spark-worker running=${WORKERS}"

  nomad_exec job stop -purge spark-etl >/dev/null 2>&1 || true
  nomad_exec job run -var="observe=${OBSERVE:-}" /opt/nomad/jobs/spark-etl.nomad
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
