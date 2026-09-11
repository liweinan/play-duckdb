package tech.weinan.play.duckdb;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Stage 3 — Emit CSV reports from Java + DuckDB over Spark Iceberg marts.
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
                      SELECT as_of_date,
                             asset_type,
                             position_count,
                             total_market_value,
                             currency
                      FROM v_inventory_by_asset
                      ORDER BY asset_type, currency
                    ) TO '%s' (HEADER, DELIMITER ',');
                    """.formatted(sqlPath(inventoryCsv)));

            Path loanCsv = reportDir.resolve("report_open_loans_" + stamp + ".csv");
            statement.execute("""
                    COPY (
                      SELECT as_of_date,
                             open_loan_count,
                             total_qty,
                             avg_fee_bps
                      FROM v_open_loans_daily
                      ORDER BY as_of_date
                    ) TO '%s' (HEADER, DELIMITER ',');
                    """.formatted(sqlPath(loanCsv)));

            System.out.println("[report] JDBC preview of inventory report:");
            try (ResultSet rs = statement.executeQuery("""
                    SELECT asset_type, currency, total_market_value AS mv
                    FROM v_inventory_by_asset
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
        Observe.banner("report rows — inventory by asset (Spark mart)");
        DuckDb.printQuery(connection, """
                SELECT as_of_date, asset_type, position_count, total_market_value, currency
                FROM v_inventory_by_asset
                ORDER BY asset_type, currency
                """);
        Observe.banner("report rows — open loans by date (Spark mart)");
        DuckDb.printQuery(connection, """
                SELECT as_of_date, open_loan_count, total_qty, avg_fee_bps
                FROM v_open_loans_daily
                ORDER BY as_of_date
                """);
    }

    private static String sqlPath(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }
}
