"""Write Iceberg marts from the seeded fact tables (same grain as ReportJob)."""

import os

from pyspark.sql import SparkSession

POSITION = "demo.demo.collateral_position"
LOAN = "demo.demo.securities_loan"
INVENTORY = "demo.demo.inventory_by_asset"
OPEN_LOANS = "demo.demo.open_loans_daily"


def main() -> None:
    spark = SparkSession.builder.appName("play-duckdb-agg-iceberg").getOrCreate()
    print("[spark] executor_count=%s" % len(spark.sparkContext.getExecutorMemoryStatus()))
    if observe_enabled():
        print("[spark] executors=%s" % spark.sparkContext.getExecutorMemoryStatus())

    spark.sql("DROP TABLE IF EXISTS demo.inventory_by_asset")
    spark.sql("DROP TABLE IF EXISTS demo.open_loans_daily")
    spark.sql(f"DROP TABLE IF EXISTS {INVENTORY}")
    spark.sql(f"DROP TABLE IF EXISTS {OPEN_LOANS}")

    spark.sql(
        f"""
        CREATE TABLE {INVENTORY}
        USING iceberg AS
        WITH latest AS (
          SELECT MAX(as_of_date) AS d FROM {POSITION}
        )
        SELECT p.as_of_date,
               p.asset_type,
               COUNT(*) AS position_count,
               ROUND(SUM(p.market_value), 2) AS total_market_value,
               p.currency
        FROM {POSITION} p, latest
        WHERE p.as_of_date = latest.d
        GROUP BY p.as_of_date, p.asset_type, p.currency
        """
    )

    spark.sql(
        f"""
        CREATE TABLE {OPEN_LOANS}
        USING iceberg AS
        SELECT as_of_date,
               COUNT(*) AS open_loan_count,
               ROUND(SUM(quantity), 2) AS total_qty,
               ROUND(AVG(fee_bps), 2) AS avg_fee_bps
        FROM {LOAN}
        WHERE status = 'OPEN'
        GROUP BY as_of_date
        """
    )

    print("[agg] Iceberg marts written via Spark JdbcCatalog")
    spark.sql(
        f"""
        SELECT 'inventory_by_asset' AS dataset, COUNT(*) AS rows FROM {INVENTORY}
        UNION ALL
        SELECT 'open_loans_daily', COUNT(*) FROM {OPEN_LOANS}
        """
    ).show(truncate=False)

    if observe_enabled():
        print()
        print("======== observe: Spark mart — inventory_by_asset ========")
        spark.sql(f"SELECT * FROM {INVENTORY} ORDER BY asset_type, currency").show(truncate=False)
        print("======== observe: Spark mart — open_loans_daily ========")
        spark.sql(f"SELECT * FROM {OPEN_LOANS} ORDER BY as_of_date").show(truncate=False)

    spark.stop()


def observe_enabled() -> bool:
    value = os.environ.get("OBSERVE", "")
    return value.lower() in {"1", "true", "yes", "on"}


if __name__ == "__main__":
    main()
