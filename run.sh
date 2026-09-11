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

nomad_log() {
  echo "[nomad $(date -u +%H:%M:%S)] $*"
}

nomad_summary() {
  local job="$1"
  local group="$2"
  python3 - "$NOMAD_HTTP" "$job" "$group" <<'PY'
import json, sys, urllib.request
base, job, group = sys.argv[1:4]
try:
    with urllib.request.urlopen(base + "/v1/job/" + job + "/summary", timeout=5) as response:
        payload = json.load(response)
    row = payload["Summary"][group]
except Exception as exc:
    print("error=1 queued=0 starting=0 running=0 failed=0 complete=0 lost=0 msg=%s" % exc)
    raise SystemExit(0)
print(
    "error=0 queued=%(Queued)s starting=%(Starting)s running=%(Running)s "
    "failed=%(Failed)s complete=%(Complete)s lost=%(Lost)s" % row
)
PY
}

nomad_dump_job() {
  local job="$1"
  nomad_log "---- job status ${job} ----"
  nomad_exec job status "$job" || true
  nomad_log "---- alloc status ${job} ----"
  nomad_exec alloc status -job "$job" || true
  nomad_log "---- alloc logs ${job} ----"
  nomad_exec alloc logs -job "$job" || true
  nomad_exec alloc logs -stderr -job "$job" || true
}

wait_nomad_running() {
  local job="$1"
  local group="$2"
  local want="$3"
  local i=0
  nomad_log "waiting for ${job}/${group} running=${want}"
  while (( i < 90 )); do
    local summary running
    summary="$(nomad_summary "$job" "$group")"
    running="$(echo "$summary" | sed -n 's/.*running=\([0-9]*\).*/\1/p')"
    running="${running:-0}"
    nomad_log "${job} ${summary}"
    if [[ "$running" == "$want" ]]; then
      nomad_log "${job}/${group} running=${running} (ready)"
      return 0
    fi
    if (( i > 0 && i % 8 == 0 )); then
      nomad_dump_job "$job"
    fi
    sleep 2
    i=$((i + 1))
  done
  nomad_log "timed out waiting for ${job}/${group} running=${want}" >&2
  nomad_dump_job "$job"
  return 1
}

wait_nomad_batch() {
  local job="$1"
  local group="$2"
  local i=0
  local idle_failed=0
  nomad_log "waiting for batch ${job}/${group} complete"
  while (( i < 180 )); do
    local summary running starting failed complete
    summary="$(nomad_summary "$job" "$group")"
    running="$(echo "$summary" | sed -n 's/.*running=\([0-9]*\).*/\1/p')"
    starting="$(echo "$summary" | sed -n 's/.*starting=\([0-9]*\).*/\1/p')"
    failed="$(echo "$summary" | sed -n 's/.*failed=\([0-9]*\).*/\1/p')"
    complete="$(echo "$summary" | sed -n 's/.*complete=\([0-9]*\).*/\1/p')"
    running="${running:-0}"
    starting="${starting:-0}"
    failed="${failed:-0}"
    complete="${complete:-0}"
    nomad_log "${job} ${summary}"
    if [[ "$complete" == "1" ]]; then
      nomad_log "${job} complete"
      nomad_dump_job "$job"
      return 0
    fi
    if (( i > 0 && i % 5 == 0 )); then
      nomad_log "---- live alloc logs ${job} ----"
      nomad_exec alloc logs -job "$job" || true
    fi
    if [[ "$failed" != "0" && "$running" == "0" && "$starting" == "0" && "$complete" == "0" ]]; then
      idle_failed=$((idle_failed + 1))
      if (( idle_failed >= 4 )); then
        nomad_log "${job} failed and is idle (no reschedule)" >&2
        nomad_dump_job "$job"
        return 1
      fi
    else
      idle_failed=0
    fi
    sleep 2
    i=$((i + 1))
  done
  nomad_log "timed out waiting for ${job} complete" >&2
  nomad_dump_job "$job"
  return 1
}

start_nomad_host() {
  use_host_nomad
  export NOMAD_ADDR="${NOMAD_HTTP}"
  mkdir -p /tmp/play-duckdb-nomad
  if curl -sf "${NOMAD_HTTP}/v1/status/leader" >/dev/null; then
    nomad_log "host agent already up"
    return 0
  fi
  nomad_log "starting host agent -dev"
  nomad agent -dev -bind=0.0.0.0 -data-dir=/tmp/play-duckdb-nomad \
    > /tmp/play-duckdb-nomad-agent.log 2>&1 &
  echo $! > /tmp/play-duckdb-nomad-agent.pid
  local i=0
  while (( i < 60 )); do
    if curl -sf "${NOMAD_HTTP}/v1/status/leader" >/dev/null; then
      nomad_log "host agent ready"
      return 0
    fi
    if (( i % 5 == 0 )); then
      nomad_log "host agent still starting (${i}s)"
    fi
    sleep 1
    i=$((i + 1))
  done
  nomad_log "host agent failed to become ready" >&2
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
  nomad_log "up postgres + minio"
  docker compose up -d --wait postgres minio
  nomad_log "create-bucket"
  docker compose run --rm create-bucket

  if [[ "${CI:-}" == "true" ]] && command -v nomad >/dev/null 2>&1; then
    nomad_log "mode=host-agent"
    start_nomad_host
  else
    nomad_log "mode=compose-agent"
    start_nomad_compose
  fi

  nomad_log "submit spark-master from ${JOB_DIR}"
  nomad_exec job run "${JOB_DIR}/spark-master.nomad"
  wait_nomad_running spark-master master 1

  nomad_log "submit spark-worker count=${WORKERS}"
  nomad_exec job run -var="workers=${WORKERS}" "${JOB_DIR}/spark-worker.nomad"
  wait_nomad_running spark-worker worker "$WORKERS"
  nomad_log "spark-worker running=${WORKERS}"

  nomad_log "submit spark-etl"
  nomad_exec job stop -purge spark-etl >/dev/null 2>&1 || true
  nomad_exec job run -var="observe=${OBSERVE:-}" "${JOB_DIR}/spark-etl.nomad"
  wait_nomad_batch spark-etl etl
  nomad_log "seed finished"

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
