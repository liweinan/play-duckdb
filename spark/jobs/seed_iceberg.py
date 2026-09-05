"""Write the play-duckdb sample tables as Iceberg via Spark JdbcCatalog."""

from pyspark.sql import SparkSession

POSITION = "demo.demo.collateral_position"
LOAN = "demo.demo.securities_loan"


def main() -> None:
    spark = SparkSession.builder.appName("play-duckdb-seed-iceberg").getOrCreate()

    spark.sql("CREATE NAMESPACE IF NOT EXISTS demo.demo")
    spark.sql("DROP TABLE IF EXISTS demo.collateral_position")
    spark.sql("DROP TABLE IF EXISTS demo.securities_loan")
    spark.sql(f"DROP TABLE IF EXISTS {POSITION}")
    spark.sql(f"DROP TABLE IF EXISTS {LOAN}")

    spark.sql(
        f"""
        CREATE TABLE {POSITION} (
            position_id   STRING,
            account_id    STRING,
            asset_type    STRING,
            instrument_id STRING,
            quantity      DOUBLE,
            market_value  DOUBLE,
            currency      STRING,
            as_of_date    DATE
        )
        USING iceberg
        PARTITIONED BY (as_of_date)
        """
    )

    spark.sql(
        f"""
        CREATE TABLE {LOAN} (
            loan_id       STRING,
            lender_acct   STRING,
            borrower_acct STRING,
            instrument_id STRING,
            quantity      DOUBLE,
            fee_bps       DOUBLE,
            status        STRING,
            as_of_date    DATE
        )
        USING iceberg
        PARTITIONED BY (as_of_date)
        """
    )

    spark.sql(
        f"""
        INSERT INTO {POSITION} VALUES
          ('P-1001', 'ACC-A', 'BOND',   'BOND-CN-01', 1000, 1025000, 'CNY', DATE '2026-08-01'),
          ('P-1002', 'ACC-A', 'EQUITY', 'EQ-HK-88',     500,  420000, 'HKD', DATE '2026-08-01'),
          ('P-1003', 'ACC-B', 'BOND',   'BOND-US-02',  200,  198000, 'USD', DATE '2026-08-01'),
          ('P-1004', 'ACC-B', 'CASH',   'CASH-USD',  50000,   50000, 'USD', DATE '2026-08-01'),
          ('P-2001', 'ACC-A', 'BOND',   'BOND-CN-01', 1200, 1230000, 'CNY', DATE '2026-08-02'),
          ('P-2002', 'ACC-A', 'EQUITY', 'EQ-HK-88',     480,  400000, 'HKD', DATE '2026-08-02'),
          ('P-2003', 'ACC-B', 'BOND',   'BOND-US-02',  180,  179000, 'USD', DATE '2026-08-02'),
          ('P-2004', 'ACC-C', 'EQUITY', 'EQ-US-10',    1000, 1500000, 'USD', DATE '2026-08-02')
        """
    )

    spark.sql(
        f"""
        INSERT INTO {LOAN} VALUES
          ('L-1', 'ACC-A', 'ACC-B', 'EQ-HK-88', 100, 35, 'OPEN',    DATE '2026-08-01'),
          ('L-2', 'ACC-B', 'ACC-C', 'BOND-US-02', 50, 20, 'OPEN',   DATE '2026-08-01'),
          ('L-3', 'ACC-A', 'ACC-B', 'EQ-HK-88', 120, 35, 'OPEN',    DATE '2026-08-02'),
          ('L-4', 'ACC-C', 'ACC-A', 'EQ-US-10', 200, 40, 'SETTLED', DATE '2026-08-02')
        """
    )

    print("[seed] Iceberg tables written via Spark JdbcCatalog")
    spark.sql(
        f"""
        SELECT 'collateral_position' AS dataset, COUNT(*) AS rows
        FROM {POSITION}
        UNION ALL
        SELECT 'securities_loan', COUNT(*)
        FROM {LOAN}
        """
    ).show(truncate=False)

    spark.stop()


if __name__ == "__main__":
    main()
