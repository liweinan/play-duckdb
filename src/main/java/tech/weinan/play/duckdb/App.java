package tech.weinan.play.duckdb;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Locale;

/**
 * Entry point for the self-study pipeline.
 *
 * <p>Stages:
 * <ol>
 *   <li>{@code query}  — DuckDB analytics over Iceberg (pointer from Postgres)</li>
 *   <li>{@code report} — CSV aggregations via Java JDBC</li>
 *   <li>{@code all}    — query → report</li>
 * </ol>
 *
 * <p>{@code seed} is Spark, not this JAR. Use {@code ./run.sh seed}.
 */
public final class App {

    public static void main(String[] args) throws Exception {
        String stage = args.length == 0 ? "all" : args[0].trim().toLowerCase(Locale.ROOT);

        Path reportDir = Path.of(IcebergTables.envOr("REPORT_DIR", "reports"));
        Path sqlDir = Path.of(IcebergTables.envOr("SQL_DIR", "sql"));
        Files.createDirectories(reportDir);

        System.out.println("=== play-duckdb ===");
        System.out.println("REPORT_DIR = " + reportDir.toAbsolutePath());
        System.out.println("STAGE      = " + stage);
        System.out.println();

        switch (stage) {
            case "install-extensions" -> {
                try (Connection connection = DuckDb.openInMemory()) {
                    IcebergTables.installExtensions(connection);
                }
                System.out.println("DuckDB iceberg + httpfs extensions installed.");
            }
            case "seed" -> {
                System.err.println("seed is a Spark job. Use: ./run.sh seed");
                System.exit(1);
            }
            case "query" -> new QueryParquetJob(sqlDir).run();
            case "report" -> new ReportJob(reportDir).run();
            case "all" -> {
                new QueryParquetJob(sqlDir).run();
                new ReportJob(reportDir).run();
            }
            default -> {
                System.err.println("Unknown stage: " + stage);
                System.err.println("Use: all | query | report");
                System.err.println("Seed Iceberg tables with: ./run.sh seed");
                System.exit(1);
            }
        }

        System.out.println();
        System.out.println("Done. Inspect reports under " + reportDir.toAbsolutePath());
    }
}
