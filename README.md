# play-duckdb

Self-study sample for a **Spark → Iceberg → DuckDB** reporting pipeline:

```text
Nomad (docker driver)
  spark-master x1 + spark-worker xN + batch spark-etl
        |
Spark (JdbcCatalog) writes facts then marts
        |
        +-- pointer -->  PostgreSQL (iceberg_tables.metadata_location)
        +-- files ---->  MinIO (s3://warehouse/)
                              |
Java reads metadata_location  |
        |                     |
        v                     v
DuckDB iceberg_scan(exact metadata.json) → SQL / thin report COPY → CSV
```

All data is fictional. No real systems, products, or client data.

中文说明见 [`docs/LEARNING_PATH.md`](docs/LEARNING_PATH.md)。

---

## What you will learn

1. **Nomad** schedules Spark Standalone (1 master + N workers) and the ETL batch
2. Spark writes Iceberg **facts** then **marts** through **JdbcCatalog** (Postgres holds the current-snapshot pointer)
3. Object files (metadata + Parquet) live on **MinIO**
4. DuckDB scans the **exact** `metadata.json` from that pointer — not the table directory
5. Drive the engine from **Java JDBC**; reports `COPY` Spark marts
6. Split teaching analytics into **named VIEW / TEMP TABLE layers** (`sql/03_layered.sql`)
7. Turn on **`--observe`** for Iceberg snapshots/files, Nomad job status, and layer row dumps
8. Run the stack with **Docker Compose** (no Vagrant — Nomad is a binary + Docker socket)

---

## Quick start (Docker + proxy)

Host proxy (e.g. Clash) on `localhost:7890`. `./run.sh` uses `127.0.0.1:7890` for image pulls and `host.docker.internal:7890` inside containers.

```bash
chmod +x run.sh
./run.sh all
```

Or manually:

```bash
export HTTP_PROXY=http://127.0.0.1:7890
export HTTPS_PROXY=http://127.0.0.1:7890
export BUILD_HTTP_PROXY=http://host.docker.internal:7890
export BUILD_HTTPS_PROXY=http://host.docker.internal:7890
docker compose build
./run.sh seed
docker compose run --rm play-duckdb query
docker compose run --rm play-duckdb report
```

### Stages

| Command | Meaning |
|---------|---------|
| `./run.sh all` | seed → query → layers → report |
| `./run.sh seed` | Nomad starts Spark workers, ETL writes Iceberg facts + marts |
| `./run.sh query` | DuckDB analytics SQL |
| `./run.sh layers` | named VIEW / TEMP TABLE teaching script |
| `./run.sh report` | write CSV under `reports/` |
| `./run.sh all --observe` | same stages, plus Iceberg / layer dumps |
| `OBSERVE=1 ./run.sh layers` | same as `--observe` |

Ports:

- Postgres `5432` (db/user/password: `iceberg`)
- MinIO API `9000`, console `http://localhost:9001` (`admin` / `password`)
- Nomad HTTP `4646`

Outputs:

- `reports/report_inventory_by_asset_*.csv`
- `reports/report_open_loans_*.csv`
- `reports/report_layered_open_loans_*.csv`（`layers` 阶段）

Inspect the catalog pointer:

```bash
docker compose exec postgres psql -U iceberg -d iceberg -c \
  'SELECT table_namespace, table_name, metadata_location FROM iceberg_tables;'
```

---

## Project layout

```text
play-duckdb/
├── README.md
├── docker-compose.yml
├── Dockerfile
├── run.sh
├── pom.xml
├── nomad/
│   ├── nomad.hcl
│   └── jobs/{spark-master,spark-worker,spark-etl}.nomad
├── spark/
│   ├── Dockerfile
│   ├── conf/spark-defaults.conf
│   └── jobs/{seed_iceberg,agg_iceberg,run_etl}.*
├── sql/
│   ├── 01_concepts.sql
│   ├── 02_analytics.sql
│   └── 03_layered.sql
├── docs/LEARNING_PATH.md
└── src/main/java/tech/weinan/play/duckdb/
    ├── App.java
    ├── DuckDb.java
    ├── IcebergTables.java
    ├── Observe.java
    ├── QueryParquetJob.java
    ├── LayeredAnalyticsJob.java
    └── ReportJob.java
```

---

## Local Java query (infra already up)

Requires JDK 17+, Maven, and `docker compose up -d postgres minio` plus a completed `./run.sh seed`.

```bash
export PG_JDBC_URL=jdbc:postgresql://localhost:5432/iceberg
export PG_USER=iceberg
export PG_PASSWORD=iceberg
export S3_ENDPOINT=localhost:9000
mvn -q -DskipTests package
java -jar target/play-duckdb-1.0.0.jar query
java -jar target/play-duckdb-1.0.0.jar layers --observe
```

`java -jar ... seed` is not supported. Seed is Spark: `./run.sh seed`.

---

## Architecture

```text
Nomad  ->  spark-master + spark-worker x2
       ->  spark-etl: seed_iceberg.py then agg_iceberg.py
              |
              v
QueryParquetJob      ->  facts + Spark marts + analytics SQL
       |
       v
LayeredAnalyticsJob  ->  named layers on facts (03_layered.sql) + checksum + CSV
       |
       v
ReportJob            ->  thin COPY of Spark marts to CSV
```

---

## Demo schema (fictional)

**collateral_position** — positions by account / asset type / day  
**securities_loan** — simplified loan records (OPEN / SETTLED)  
**inventory_by_asset** / **open_loans_daily** — Spark marts; DuckDB reports read these

Use these only as SQL practice data.

---

## Troubleshooting

| Issue | Fix |
|-------|-----|
| Docker daemon not running | Start Docker Desktop |
| Docker cannot pull / Maven timeout | Ensure proxy is up; use `./run.sh` (`host.docker.internal:7890`) |
| `host.docker.internal` fails on Linux | compose already sets `extra_hosts: host.docker.internal:host-gateway` |
| DuckDB: no metadata_location | Run `./run.sh seed` first (needs Nomad + Spark marts) |
| Want a Linux VM cluster | Not this repo. kubeadm needs Vagrant; Nomad does not |
| Empty reports | Run `seed` before `query` / `report` |

---

## License

Personal learning sample. Do what you want locally; do not commit real work data.
