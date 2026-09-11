#!/usr/bin/env bash
# Seed facts then write Spark marts. SPARK_MASTER defaults to local[2]
# (compose fallback). Nomad ETL sets spark://spark-master:7077.
set -euo pipefail

MASTER="${SPARK_MASTER:-local[2]}"
DRIVER_HOST="${SPARK_DRIVER_HOST:-}"
SUBMIT=(/opt/spark/bin/spark-submit --master "${MASTER}")

if [[ -n "${DRIVER_HOST}" ]]; then
  SUBMIT+=(
    --conf "spark.driver.host=${DRIVER_HOST}"
    --conf "spark.driver.bindAddress=0.0.0.0"
    --conf "spark.driver.memory=512m"
    --conf "spark.executor.memory=256m"
    --conf "spark.executor.memoryOverhead=64m"
    --conf "spark.executor.cores=1"
    --conf "spark.cores.max=2"
  )
fi

echo "[etl $(date -u +%H:%M:%S)] SPARK_MASTER=${MASTER} SPARK_DRIVER_HOST=${DRIVER_HOST:-} OBSERVE=${OBSERVE:-off}"
echo "[etl $(date -u +%H:%M:%S)] spark-submit seed_iceberg.py"
"${SUBMIT[@]}" /opt/jobs/seed_iceberg.py
echo "[etl $(date -u +%H:%M:%S)] seed_iceberg.py exit=0"

echo "[etl $(date -u +%H:%M:%S)] spark-submit agg_iceberg.py"
"${SUBMIT[@]}" /opt/jobs/agg_iceberg.py
echo "[etl $(date -u +%H:%M:%S)] agg_iceberg.py exit=0"
echo "[etl $(date -u +%H:%M:%S)] facts + marts written"
