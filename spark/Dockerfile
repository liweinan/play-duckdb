ARG HTTP_PROXY
ARG HTTPS_PROXY
ARG NO_PROXY=localhost,127.0.0.1

FROM apache/spark:3.5.5-java17

ARG HTTP_PROXY
ARG HTTPS_PROXY
ARG NO_PROXY
ARG ICEBERG_VERSION=1.11.0
ARG POSTGRES_JDBC_VERSION=42.7.7

ENV http_proxy=${HTTP_PROXY} \
    https_proxy=${HTTPS_PROXY} \
    HTTP_PROXY=${HTTP_PROXY} \
    HTTPS_PROXY=${HTTPS_PROXY} \
    NO_PROXY=${NO_PROXY} \
    no_proxy=${NO_PROXY} \
    PYSPARK_PYTHON=python3

USER root

RUN set -eux; \
    if ! command -v python3 >/dev/null 2>&1; then \
      apt-get update; \
      apt-get install -y --no-install-recommends python3 curl ca-certificates; \
      ln -sf /usr/bin/python3 /usr/bin/python; \
      rm -rf /var/lib/apt/lists/*; \
    elif ! command -v curl >/dev/null 2>&1; then \
      apt-get update; \
      apt-get install -y --no-install-recommends curl ca-certificates; \
      rm -rf /var/lib/apt/lists/*; \
    fi

RUN set -eux; \
    SPARK_JARS="/opt/spark/jars"; \
    BASE="https://repo1.maven.org/maven2"; \
    curl -fsSL -o "${SPARK_JARS}/iceberg-spark-runtime-3.5_2.12-${ICEBERG_VERSION}.jar" \
      "${BASE}/org/apache/iceberg/iceberg-spark-runtime-3.5_2.12/${ICEBERG_VERSION}/iceberg-spark-runtime-3.5_2.12-${ICEBERG_VERSION}.jar"; \
    curl -fsSL -o "${SPARK_JARS}/iceberg-aws-bundle-${ICEBERG_VERSION}.jar" \
      "${BASE}/org/apache/iceberg/iceberg-aws-bundle/${ICEBERG_VERSION}/iceberg-aws-bundle-${ICEBERG_VERSION}.jar"; \
    curl -fsSL -o "${SPARK_JARS}/postgresql-${POSTGRES_JDBC_VERSION}.jar" \
      "${BASE}/org/postgresql/postgresql/${POSTGRES_JDBC_VERSION}/postgresql-${POSTGRES_JDBC_VERSION}.jar"

COPY conf/spark-defaults.conf /opt/spark/conf/spark-defaults.conf
COPY jobs/seed_iceberg.py /opt/jobs/seed_iceberg.py
COPY jobs/agg_iceberg.py /opt/jobs/agg_iceberg.py
COPY jobs/run_etl.sh /opt/jobs/run_etl.sh

RUN chmod +x /opt/jobs/run_etl.sh \
    && chown -R spark:spark /opt/spark/conf /opt/jobs

USER spark
WORKDIR /opt/jobs

ENTRYPOINT ["/opt/jobs/run_etl.sh"]
