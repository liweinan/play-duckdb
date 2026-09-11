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
  )
fi

echo "[etl] spark-submit --master ${MASTER} seed_iceberg.py"
"${SUBMIT[@]}" /opt/jobs/seed_iceberg.py

echo "[etl] spark-submit --master ${MASTER} agg_iceberg.py"
"${SUBMIT[@]}" /opt/jobs/agg_iceberg.py

echo "[etl] facts + marts written"
