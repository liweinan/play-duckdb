package tech.weinan.play.duckdb;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

/**
 * Stage 2 — Query Parquet directly with DuckDB (no need to load into a warehouse first).
 *
 * <p>Key idea: DuckDB can push predicates into the Parquet scan
 * ({@code read_parquet(..., hive_partitioning=true)}), which is why it is popular
 * for reporting / EUD-style analysis over columnar extracts.
 *
 * <p>We also create <b>views</b> as a lightweight "logical layer":
 * downstream Java code queries view names, not raw file paths.
 * (This is a teaching stand-in for catalog/isolation concepts; see docs/LEARNING_PATH.md
 * for how Apache Iceberg relates.)
 */
public final class QueryParquetJob {

    private final Path dataDir;
    private final Path sqlDir;

    public QueryParquetJob(Path dataDir, Path sqlDir) {
        this.dataDir = dataDir;
        this.sqlDir = sqlDir;
    }

    public void run() throws Exception {
        System.out.println("[query] Scanning Parquet with DuckDB...");

        String positionGlob = sqlPath(dataDir.resolve("collateral_position/**/*.parquet"));
        String loanGlob = sqlPath(dataDir.resolve("securities_loan/**/*.parquet"));

        try (Connection connection = DuckDb.openInMemory();
             Statement statement = connection.createStatement()) {

            // hive_partitioning=true: infer as_of_date from directory names
            statement.execute("""
                    CREATE OR REPLACE VIEW v_collateral_position AS
                    SELECT *
                    FROM read_parquet('%s', hive_partitioning=true);
                    """.formatted(positionGlob));

            statement.execute("""
                    CREATE OR REPLACE VIEW v_securities_loan AS
                    SELECT *
                    FROM read_parquet('%s', hive_partitioning=true);
                    """.formatted(loanGlob));

            // Optional: run the curated SQL notebook if present
            Path demoSql = sqlDir.resolve("02_analytics.sql");
            if (Files.isRegularFile(demoSql)) {
                System.out.println("[query] Executing " + demoSql.toAbsolutePath());
                String script = Files.readString(demoSql)
                        // Allow the SQL file to stay path-agnostic
                        .replace("${POSITION_GLOB}", positionGlob)
                        .replace("${LOAN_GLOB}", loanGlob);
                // File may contain SELECT demos — execute statement by statement and print SELECTs
                for (String part : script.split(";")) {
                    // Strip full-line SQL comments so "-- demo" above a SELECT is not skipped
                    String sql = stripLineComments(part).trim();
                    if (sql.isEmpty()) {
                        continue;
                    }
                    String lower = sql.toLowerCase();
                    if (lower.startsWith("select") || lower.startsWith("with")) {
                        DuckDb.printQuery(connection, sql);
                    } else {
                        statement.execute(sql);
                    }
                }
            } else {
                // Fallback demos if sql file missing
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

        System.out.println("[query] Done. Views hid file paths from the query author.");
    }

    private static String sqlPath(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    /** Remove lines that are empty or start with `--` (simple SQL comment style). */
    private static String stripLineComments(String sql) {
        StringBuilder builder = new StringBuilder();
        for (String line : sql.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            builder.append(line).append('\n');
        }
        return builder.toString();
    }
}
