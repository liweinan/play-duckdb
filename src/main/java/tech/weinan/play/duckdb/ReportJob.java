package tech.weinan.play.duckdb;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Stage 3 — Emit simple "report-like" artifacts from Java + DuckDB.
 *
 * <p>In production, reports may feed internal control packs or regulatory filings.
 * Here we only write local CSV files with <b>synthetic</b> aggregations so you can
 * practice the engineering pattern: SQL in DuckDB → consume ResultSet in Java → export.
 */
public final class ReportJob {

    private final Path dataDir;
    private final Path reportDir;

    public ReportJob(Path dataDir, Path reportDir) {
        this.dataDir = dataDir;
        this.reportDir = reportDir;
    }

    public void run() throws Exception {
        System.out.println("[report] Generating CSV reports under " + reportDir.toAbsolutePath());
        Files.createDirectories(reportDir);

        String positionGlob = sqlPath(dataDir.resolve("collateral_position/**/*.parquet"));
        String loanGlob = sqlPath(dataDir.resolve("securities_loan/**/*.parquet"));
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

        try (Connection connection = DuckDb.openInMemory();
             Statement statement = connection.createStatement()) {

            statement.execute("""
                    CREATE OR REPLACE VIEW v_collateral_position AS
                    SELECT * FROM read_parquet('%s', hive_partitioning=true);
                    """.formatted(positionGlob));

            statement.execute("""
                    CREATE OR REPLACE VIEW v_securities_loan AS
                    SELECT * FROM read_parquet('%s', hive_partitioning=true);
                    """.formatted(loanGlob));

            // Report A: collateral inventory by asset type (latest as_of_date only)
            Path inventoryCsv = reportDir.resolve("report_inventory_by_asset_" + stamp + ".csv");
            statement.execute("""
                    COPY (
                      WITH latest AS (
                        SELECT MAX(as_of_date) AS d FROM v_collateral_position
                      )
                      SELECT p.as_of_date,
                             p.asset_type,
                             COUNT(*) AS position_count,
                             ROUND(SUM(p.market_value), 2) AS total_market_value,
                             p.currency
                      FROM v_collateral_position p, latest
                      WHERE p.as_of_date = latest.d
                      GROUP BY p.as_of_date, p.asset_type, p.currency
                      ORDER BY p.asset_type, p.currency
                    ) TO '%s' (HEADER, DELIMITER ',');
                    """.formatted(sqlPath(inventoryCsv)));

            // Report B: open loan exposure summary
            Path loanCsv = reportDir.resolve("report_open_loans_" + stamp + ".csv");
            statement.execute("""
                    COPY (
                      SELECT as_of_date,
                             COUNT(*) AS open_loan_count,
                             ROUND(SUM(quantity), 2) AS total_qty,
                             ROUND(AVG(fee_bps), 2) AS avg_fee_bps
                      FROM v_securities_loan
                      WHERE status = 'OPEN'
                      GROUP BY as_of_date
                      ORDER BY as_of_date
                    ) TO '%s' (HEADER, DELIMITER ',');
                    """.formatted(sqlPath(loanCsv)));

            // Also print a small preview via JDBC ResultSet (how app code usually consumes rows)
            System.out.println("[report] JDBC preview of inventory report:");
            try (ResultSet rs = statement.executeQuery("""
                    SELECT asset_type, currency, ROUND(SUM(market_value), 2) AS mv
                    FROM v_collateral_position
                    WHERE as_of_date = (SELECT MAX(as_of_date) FROM v_collateral_position)
                    GROUP BY asset_type, currency
                    ORDER BY asset_type
                    """)) {
                while (rs.next()) {
                    System.out.printf(
                            "  %-8s %-3s  mv=%s%n",
                            rs.getString("asset_type"),
                            rs.getString("currency"),
                            rs.getString("mv"));
                }
            }

            System.out.println("[report] Wrote:");
            System.out.println("  - " + inventoryCsv.toAbsolutePath());
            System.out.println("  - " + loanCsv.toAbsolutePath());
        }
    }

    private static String sqlPath(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }
}
