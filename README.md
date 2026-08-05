# play-duckdb

Self-study sample for a **Markets-style reporting pipeline**:

```text
Synthetic extract → Parquet (partitioned)
        → DuckDB SQL analytics
        → Java JDBC → CSV reports
```

Inspired by common bank reporting patterns (Parquet + DuckDB + Java).  
**All data is fictional.** No real systems, products, or client data.

中文说明见 [`docs/LEARNING_PATH.md`](docs/LEARNING_PATH.md)。

---

## What you will learn

1. Write / partition **Parquet** with DuckDB `COPY`
2. Query Parquet via `read_parquet(..., hive_partitioning=true)`
3. Hide file paths behind SQL **views** (lightweight logical layer)
4. Drive the engine from **Java JDBC** and export CSV reports
5. Run everything in **Docker** with one command

---

## Quick start (Docker + proxy)

Host proxy (e.g. Clash) on `localhost:7890`:

```bash
cd /Users/weli/works/play-duckdb

export http_proxy=http://localhost:7890
export https_proxy=http://localhost:7890

# Docker needs host.docker.internal to reach the host proxy
chmod +x run.sh
./run.sh all
```

Or manually:

```bash
export HTTP_PROXY=http://host.docker.internal:7890
export HTTPS_PROXY=http://host.docker.internal:7890
docker compose build
docker compose run --rm play-duckdb all
```

### Stages

| Command | Meaning |
|---------|---------|
| `./run.sh all` | seed → query → report |
| `./run.sh seed` | generate Parquet only |
| `./run.sh query` | analytics SQL on Parquet |
| `./run.sh report` | write CSV under `reports/` |

Outputs:

- `data/generated/collateral_position/as_of_date=.../*.parquet`
- `data/generated/securities_loan/as_of_date=.../*.parquet`
- `reports/report_inventory_by_asset_*.csv`
- `reports/report_open_loans_*.csv`

---

## Project layout

```text
play-duckdb/
├── README.md
├── docker-compose.yml
├── Dockerfile
├── run.sh
├── pom.xml
├── sql/
│   ├── 01_concepts.sql      # concepts (reference)
│   └── 02_analytics.sql     # queries run in "query" stage
├── docs/LEARNING_PATH.md
└── src/main/java/tech/weinan/play/duckdb/
    ├── App.java             # CLI entry: all|seed|query|report
    ├── DuckDb.java          # JDBC helpers
    ├── SeedParquetJob.java  # stage 1
    ├── QueryParquetJob.java # stage 2
    └── ReportJob.java       # stage 3
```

---

## Local run (no Docker)

Requires JDK 17+ and Maven.

```bash
export http_proxy=http://localhost:7890 https_proxy=http://localhost:7890
mvn -q -DskipTests package
java -jar target/play-duckdb-1.0.0.jar all
```

---

## Architecture (learning model)

```text
SeedParquetJob  ->  partitioned Parquet on disk
       |
       v
QueryParquetJob ->  read_parquet + VIEW + analytics SQL
       |
       v
ReportJob       ->  aggregations to CSV
```

**Iceberg:** not required to run this demo. Treat Hive-partitioned Parquet + views as step 1; Iceberg is a table-format layer on top of such files (schema evolution, snapshots). See `docs/LEARNING_PATH.md`.

---

## Demo schema (fictional)

**collateral_position** — positions by account / asset type / day  
**securities_loan** — simplified loan records (OPEN / SETTLED)

Use these only as SQL practice data.

---

## Troubleshooting

| Issue | Fix |
|-------|-----|
| Docker daemon not running | Start Docker Desktop, or use local Maven: `mvn -q -DskipTests package && java -jar target/play-duckdb-1.0.0.jar all` |
| Docker cannot pull / Maven timeout | Ensure proxy is up; use `./run.sh` (`host.docker.internal:7890`) |
| `host.docker.internal` fails on Linux | Add `extra_hosts: ["host.docker.internal:host-gateway"]` under the service, or set `HTTP_PROXY` to your LAN IP |
| Empty Parquet dirs | Run `seed` before `query` / `report` |
| Want only SQL exploration | After `seed`, open DuckDB CLI and `read_parquet(...)` the files under `data/generated` |

---

## License

Personal learning sample. Do what you want locally; do not commit real work data.
