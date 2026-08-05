package tech.weinan.play.duckdb;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Entry point for the self-study pipeline.
 *
 * <p>Stages mirror a simplified "Reporting layer" learning path:
 * <ol>
 *   <li>{@code seed}   — simulate a vendor extract by writing Parquet files</li>
 *   <li>{@code query}  — scan Parquet with DuckDB (SQL analytics)</li>
 *   <li>{@code report} — produce CSV "regulatory-style" aggregations via Java</li>
 *   <li>{@code all}    — run seed → query → report</li>
 * </ol>
 *
 * <p>Usage:
 * <pre>
 *   java -jar app.jar [all|seed|query|report]
 * </pre>
 */
public final class App {

    public static void main(String[] args) throws Exception {
        String stage = args.length == 0 ? "all" : args[0].trim().toLowerCase(Locale.ROOT);

        // Configurable paths (Docker sets these; local defaults work for IDE runs)
        Path dataDir = Path.of(envOr("DATA_DIR", "data/generated"));
        Path reportDir = Path.of(envOr("REPORT_DIR", "reports"));
        Path sqlDir = Path.of(envOr("SQL_DIR", "sql"));

        Files.createDirectories(dataDir);
        Files.createDirectories(reportDir);

        System.out.println("=== play-duckdb ===");
        System.out.println("DATA_DIR   = " + dataDir.toAbsolutePath());
        System.out.println("REPORT_DIR = " + reportDir.toAbsolutePath());
        System.out.println("STAGE      = " + stage);
        System.out.println();

        switch (stage) {
            case "seed" -> new SeedParquetJob(dataDir).run();
            case "query" -> new QueryParquetJob(dataDir, sqlDir).run();
            case "report" -> new ReportJob(dataDir, reportDir).run();
            case "all" -> {
                new SeedParquetJob(dataDir).run();
                new QueryParquetJob(dataDir, sqlDir).run();
                new ReportJob(dataDir, reportDir).run();
            }
            default -> {
                System.err.println("Unknown stage: " + stage);
                System.err.println("Use: all | seed | query | report");
                System.exit(1);
            }
        }

        System.out.println();
        System.out.println("Done. Inspect Parquet under " + dataDir.toAbsolutePath());
        System.out.println("Inspect reports under " + reportDir.toAbsolutePath());
    }

    private static String envOr(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}
