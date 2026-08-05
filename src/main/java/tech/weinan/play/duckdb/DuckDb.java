package tech.weinan.play.duckdb;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Tiny helper around DuckDB's JDBC driver.
 *
 * <p>Why DuckDB (for this learning project)?
 * <ul>
 *   <li>Embedded — no cluster / no broker to start</li>
 *   <li>First-class Parquet — {@code read_parquet()} / {@code COPY ... TO} </li>
 *   <li>SQL analytics (aggregations, window functions) close to what reporting teams use</li>
 * </ul>
 *
 * <p>Connection URL:
 * <ul>
 *   <li>{@code jdbc:duckdb:} — in-memory database (lost when process exits)</li>
 *   <li>{@code jdbc:duckdb:/tmp/demo.duckdb} — file-backed database</li>
 * </ul>
 */
public final class DuckDb {

    private DuckDb() {
    }

    /** Open an in-memory DuckDB connection. */
    public static Connection openInMemory() throws SQLException {
        // Driver auto-registers via SPI in modern JDKs; Class.forName is optional insurance.
        try {
            Class.forName("org.duckdb.DuckDBDriver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("DuckDB JDBC driver not on classpath", e);
        }
        return DriverManager.getConnection("jdbc:duckdb:");
    }

    /** Execute a SQL script that may contain multiple statements separated by ';'. */
    public static void executeScript(Connection connection, String sql) throws SQLException {
        // DuckDB JDBC accepts multi-statement scripts on a single Statement in many cases,
        // but splitting keeps error messages easier to map to a statement.
        String[] parts = sql.split(";");
        try (Statement statement = connection.createStatement()) {
            for (String part : parts) {
                String trimmed = part.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                    continue;
                }
                // Skip pure comment blocks
                if (trimmed.lines().allMatch(line -> line.trim().isEmpty() || line.trim().startsWith("--"))) {
                    continue;
                }
                statement.execute(trimmed);
            }
        }
    }

    /** Pretty-print a query result to stdout (for interactive learning). */
    public static void printQuery(Connection connection, String sql) throws SQLException {
        System.out.println("--- SQL ---");
        System.out.println(sql.strip());
        System.out.println("--- rows ---");
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            ResultSetMetaData meta = resultSet.getMetaData();
            int columnCount = meta.getColumnCount();

            // Header
            StringBuilder header = new StringBuilder();
            for (int i = 1; i <= columnCount; i++) {
                if (i > 1) {
                    header.append(" | ");
                }
                header.append(meta.getColumnLabel(i));
            }
            System.out.println(header);

            int rowCount = 0;
            while (resultSet.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= columnCount; i++) {
                    if (i > 1) {
                        row.append(" | ");
                    }
                    row.append(resultSet.getString(i));
                }
                System.out.println(row);
                rowCount++;
            }
            System.out.println("(" + rowCount + " rows)");
            System.out.println();
        }
    }
}
