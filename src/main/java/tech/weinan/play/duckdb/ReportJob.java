package tech.weinan.play.duckdb;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Stage 3 — Emit CSV reports from Java + DuckDB over Iceberg views.
 */
public final class ReportJob {

    private final Path reportDir;

    public ReportJob(Path reportDir) {
        this.reportDir = reportDir;
    }

    public void run() throws Exception {
        System.out.println("[report] Generating CSV reports under " + reportDir.toAbsolutePath());
        Files.createDirectories(reportDir);

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

        try (Connection connection = DuckDb.openInMemory();
             Statement statement = connection.createStatement()) {

            IcebergTables.createViews(connection);
            observeReports(connection);

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

    private static void observeReports(Connection connection) throws Exception {
        if (!Observe.enabled()) {
            return;
        }
        Observe.banner("report rows — inventory by asset (latest as_of)");
        DuckDb.printQuery(connection, """
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
                """);
        Observe.banner("report rows — open loans by date");
        DuckDb.printQuery(connection, """
                SELECT as_of_date,
                       COUNT(*) AS open_loan_count,
                       ROUND(SUM(quantity), 2) AS total_qty,
                       ROUND(AVG(fee_bps), 2) AS avg_fee_bps
                FROM v_securities_loan
                WHERE status = 'OPEN'
                GROUP BY as_of_date
                ORDER BY as_of_date
                """);
    }

    private static String sqlPath(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }
}
