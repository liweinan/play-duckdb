package tech.weinan.play.duckdb;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Teaching stage — named SQL layers instead of one giant WITH.
 *
 * <p>{@code sql/03_layered.sql} builds semantic views, materializes the reused
 * enrichment as a TEMP TABLE, prints a checksum after each layer, then runs
 * thin report SELECTs. This class only wires Iceberg source views, runs that
 * script, and copies {@code t_open_loans_enriched} to CSV.
 */
public final class LayeredAnalyticsJob {

    private final Path sqlDir;
    private final Path reportDir;

    public LayeredAnalyticsJob(Path sqlDir, Path reportDir) {
        this.sqlDir = sqlDir;
        this.reportDir = reportDir;
    }

    public void run() throws Exception {
        System.out.println("[layers] Named views + one TEMP TABLE (see sql/03_layered.sql)");

        Path layeredSql = sqlDir.resolve("03_layered.sql");
        if (!Files.isRegularFile(layeredSql)) {
            throw new IllegalStateException("Missing " + layeredSql.toAbsolutePath());
        }

        Files.createDirectories(reportDir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path enrichedCsv = reportDir.resolve("report_layered_open_loans_" + stamp + ".csv");

        try (Connection connection = DuckDb.openInMemory();
             Statement statement = connection.createStatement()) {
            IcebergTables.createViews(connection);
            System.out.println("[layers] Executing " + layeredSql.toAbsolutePath());
            DuckDb.runScript(connection, Files.readString(layeredSql));

            statement.execute("""
                    COPY (
                      SELECT *
                      FROM t_open_loans_enriched
                      ORDER BY as_of_date, loan_id
                    ) TO '%s' (HEADER, DELIMITER ',')
                    """.formatted(sqlPath(enrichedCsv)));
        }

        System.out.println("[layers] Wrote " + enrichedCsv.toAbsolutePath());
    }

    private static String sqlPath(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }
}
