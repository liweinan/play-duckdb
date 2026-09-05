package tech.weinan.play.duckdb;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;

/**
 * Stage 2 — Query Iceberg with DuckDB.
 *
 * <p>Java reads {@code iceberg_tables.metadata_location} from Postgres (Spark
 * JdbcCatalog pointer), then DuckDB {@code iceberg_scan}s that metadata file
 * on MinIO. Views hide the S3 path from analytics SQL.
 */
public final class QueryParquetJob {

    private final Path sqlDir;

    public QueryParquetJob(Path sqlDir) {
        this.sqlDir = sqlDir;
    }

    public void run() throws Exception {
        System.out.println("[query] Scanning Iceberg with DuckDB...");

        try (Connection connection = DuckDb.openInMemory()) {
            IcebergTables.createViews(connection);

            Path demoSql = sqlDir.resolve("02_analytics.sql");
            if (Files.isRegularFile(demoSql)) {
                System.out.println("[query] Executing " + demoSql.toAbsolutePath());
                DuckDb.runScript(connection, Files.readString(demoSql));
            } else {
                DuckDb.printQuery(connection, """
                        SELECT as_of_date, asset_type, ROUND(SUM(market_value), 2) AS total_mv
                        FROM v_collateral_position
                        GROUP BY 1, 2
                        ORDER BY 1, 2
                        """);

                DuckDb.printQuery(connection, """
                        SELECT l.as_of_date, l.loan_id, l.instrument_id, l.quantity, l.status,
                               p.market_value AS related_position_mv
                        FROM v_securities_loan l
                        LEFT JOIN v_collateral_position p
                          ON l.instrument_id = p.instrument_id
                         AND l.as_of_date = p.as_of_date
                         AND l.lender_acct = p.account_id
                        ORDER BY l.as_of_date, l.loan_id
                        """);
            }
        }

        System.out.println("[query] Done. Views hid Iceberg metadata paths from the query author.");
    }
}
