package tech.weinan.play.duckdb;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

/**
 * Stage 1 — Simulate a "vendor extract" that lands as Parquet files.
 *
 * <p>In a real bank reporting stack, a vendor library (often C++) might dump
 * positions / collateral / settlements to Parquet. Here we generate
 * <b>synthetic</b> demo data entirely inside DuckDB, then {@code COPY} to Parquet.
 *
 * <p>Learning goals:
 * <ul>
 *   <li>Create tables and insert sample rows with SQL</li>
 *   <li>Export columnar Parquet (compression, typed columns)</li>
 *   <li>Use Hive-style partitions: {@code as_of_date=YYYY-MM-DD/...}</li>
 * </ul>
 *
 * <p>All entity names are fictional demo labels — not real bank systems.
 */
public final class SeedParquetJob {

    private final Path dataDir;

    public SeedParquetJob(Path dataDir) {
        this.dataDir = dataDir;
    }

    public void run() throws Exception {
        System.out.println("[seed] Writing synthetic Parquet extracts under " + dataDir.toAbsolutePath());

        try (Connection connection = DuckDb.openInMemory();
             Statement statement = connection.createStatement()) {

            // ----- 1) Collateral positions (what we hold / pledge) -----
            statement.execute("""
                    CREATE TABLE collateral_position (
                        position_id   VARCHAR,
                        account_id    VARCHAR,
                        asset_type    VARCHAR,   -- BOND / EQUITY / CASH (demo enums)
                        instrument_id VARCHAR,
                        quantity      DOUBLE,
                        market_value  DOUBLE,
                        currency      VARCHAR,
                        as_of_date    DATE
                    );
                    """);

            statement.execute("""
                    INSERT INTO collateral_position VALUES
                      ('P-1001', 'ACC-A', 'BOND',   'BOND-CN-01', 1000, 1025000, 'CNY', DATE '2026-08-01'),
                      ('P-1002', 'ACC-A', 'EQUITY', 'EQ-HK-88',     500,  420000, 'HKD', DATE '2026-08-01'),
                      ('P-1003', 'ACC-B', 'BOND',   'BOND-US-02',  200,  198000, 'USD', DATE '2026-08-01'),
                      ('P-1004', 'ACC-B', 'CASH',   'CASH-USD',  50000,   50000, 'USD', DATE '2026-08-01'),
                      ('P-2001', 'ACC-A', 'BOND',   'BOND-CN-01', 1200, 1230000, 'CNY', DATE '2026-08-02'),
                      ('P-2002', 'ACC-A', 'EQUITY', 'EQ-HK-88',     480,  400000, 'HKD', DATE '2026-08-02'),
                      ('P-2003', 'ACC-B', 'BOND',   'BOND-US-02',  180,  179000, 'USD', DATE '2026-08-02'),
                      ('P-2004', 'ACC-C', 'EQUITY', 'EQ-US-10',    1000, 1500000, 'USD', DATE '2026-08-02');
                    """);

            // ----- 2) Securities-lending style loans (highly simplified) -----
            statement.execute("""
                    CREATE TABLE securities_loan (
                        loan_id       VARCHAR,
                        lender_acct   VARCHAR,
                        borrower_acct VARCHAR,
                        instrument_id VARCHAR,
                        quantity      DOUBLE,
                        fee_bps       DOUBLE,    -- fee in basis points (demo)
                        status        VARCHAR,   -- OPEN / SETTLED
                        as_of_date    DATE
                    );
                    """);

            statement.execute("""
                    INSERT INTO securities_loan VALUES
                      ('L-1', 'ACC-A', 'ACC-B', 'EQ-HK-88', 100, 35, 'OPEN',    DATE '2026-08-01'),
                      ('L-2', 'ACC-B', 'ACC-C', 'BOND-US-02', 50, 20, 'OPEN',   DATE '2026-08-01'),
                      ('L-3', 'ACC-A', 'ACC-B', 'EQ-HK-88', 120, 35, 'OPEN',    DATE '2026-08-02'),
                      ('L-4', 'ACC-C', 'ACC-A', 'EQ-US-10', 200, 40, 'SETTLED', DATE '2026-08-02');
                    """);

            // ----- 3) Export to Parquet (vendor-extract simulation) -----
            // PARTITION_BY creates directories like:
            //   collateral_position/as_of_date=2026-08-01/data_0.parquet
            String positionRoot = sqlPath(dataDir.resolve("collateral_position"));
            String loanRoot = sqlPath(dataDir.resolve("securities_loan"));

            statement.execute("""
                    COPY collateral_position
                    TO '%s'
                    (FORMAT PARQUET, PARTITION_BY (as_of_date), OVERWRITE_OR_IGNORE);
                    """.formatted(positionRoot));

            statement.execute("""
                    COPY securities_loan
                    TO '%s'
                    (FORMAT PARQUET, PARTITION_BY (as_of_date), OVERWRITE_OR_IGNORE);
                    """.formatted(loanRoot));

            DuckDb.printQuery(connection, """
                    SELECT 'collateral_position' AS dataset, COUNT(*) AS rows FROM collateral_position
                    UNION ALL
                    SELECT 'securities_loan', COUNT(*) FROM securities_loan
                    """);
        }

        System.out.println("[seed] Parquet written. Next: query stage reads these files without re-inserting.");
    }

    /** DuckDB on all platforms accepts forward-slash paths in SQL string literals. */
    private static String sqlPath(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }
}
