package tech.weinan.play.duckdb;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Resolve Iceberg current-snapshot pointers from Postgres {@code iceberg_tables}
 * (Spark JdbcCatalog), then expose the tables to DuckDB via {@code iceberg_scan}
 * of the exact {@code metadata.json} path.
 */
public final class IcebergTables {

    static final String NAMESPACE = "demo";
    static final String COLLATERAL_POSITION = "collateral_position";
    static final String SECURITIES_LOAN = "securities_loan";
    static final String INVENTORY_BY_ASSET = "inventory_by_asset";
    static final String OPEN_LOANS_DAILY = "open_loans_daily";

    private static boolean icebergObserved;

    private IcebergTables() {
    }

    public static void installExtensions(Connection duckDb) throws SQLException {
        try (Statement statement = duckDb.createStatement()) {
            statement.execute("INSTALL iceberg");
            statement.execute("LOAD iceberg");
            statement.execute("INSTALL httpfs");
            statement.execute("LOAD httpfs");
        }
    }

    public static void configureS3(Connection duckDb) throws SQLException {
        String endpoint = envOr("S3_ENDPOINT", "minio:9000");
        String accessKey = envOr("S3_ACCESS_KEY", "admin");
        String secretKey = envOr("S3_SECRET_KEY", "password");
        String region = envOr("S3_REGION", "us-east-1");

        try (Statement statement = duckDb.createStatement()) {
            statement.execute("""
                    CREATE OR REPLACE SECRET minio (
                      TYPE S3,
                      KEY_ID '%s',
                      SECRET '%s',
                      REGION '%s',
                      ENDPOINT '%s',
                      URL_STYLE 'path',
                      USE_SSL false
                    )
                    """.formatted(
                    escapeLiteral(accessKey),
                    escapeLiteral(secretKey),
                    escapeLiteral(region),
                    escapeLiteral(endpoint)));
        }
    }

    public static Map<String, String> metadataLocations() throws SQLException {
        String url = envOr("PG_JDBC_URL", "jdbc:postgresql://localhost:5432/iceberg");
        String user = envOr("PG_USER", "iceberg");
        String password = envOr("PG_PASSWORD", "iceberg");

        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("PostgreSQL JDBC driver not on classpath", e);
        }

        Map<String, String> locations = new LinkedHashMap<>();
        String sql = """
                SELECT table_name, metadata_location
                FROM iceberg_tables
                WHERE table_namespace = ?
                  AND table_name IN (?, ?, ?, ?)
                """;

        try (Connection postgres = DriverManager.getConnection(url, user, password);
             PreparedStatement statement = postgres.prepareStatement(sql)) {
            statement.setString(1, NAMESPACE);
            statement.setString(2, COLLATERAL_POSITION);
            statement.setString(3, SECURITIES_LOAN);
            statement.setString(4, INVENTORY_BY_ASSET);
            statement.setString(5, OPEN_LOANS_DAILY);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    locations.put(resultSet.getString("table_name"), resultSet.getString("metadata_location"));
                }
            }
        }

        requireLocation(locations, COLLATERAL_POSITION);
        requireLocation(locations, SECURITIES_LOAN);
        requireLocation(locations, INVENTORY_BY_ASSET);
        requireLocation(locations, OPEN_LOANS_DAILY);
        return locations;
    }

    public static void createViews(Connection duckDb) throws SQLException {
        installExtensions(duckDb);
        configureS3(duckDb);
        Map<String, String> locations = metadataLocations();
        createScanView(duckDb, "v_collateral_position", locations.get(COLLATERAL_POSITION));
        createScanView(duckDb, "v_securities_loan", locations.get(SECURITIES_LOAN));
        createScanView(duckDb, "v_inventory_by_asset", locations.get(INVENTORY_BY_ASSET));
        createScanView(duckDb, "v_open_loans_daily", locations.get(OPEN_LOANS_DAILY));
        if (Observe.enabled()) {
            observeIceberg(duckDb, locations);
        }
    }

    /**
     * Catalog pointer, Iceberg snapshots/manifests, and the rows DuckDB will scan.
     * Demo tables are tiny; do not copy this {@code SELECT *} onto a large lake.
     */
    static void observeIceberg(Connection duckDb, Map<String, String> locations) throws SQLException {
        if (icebergObserved) {
            return;
        }
        icebergObserved = true;
        Observe.banner("Iceberg catalog pointers (Postgres iceberg_tables)");
        for (Map.Entry<String, String> entry : locations.entrySet()) {
            IcebergScanTarget target = scanTarget(entry.getValue());
            System.out.println("table=" + entry.getKey()
                    + " metadata_location=" + entry.getValue()
                    + " table_root=" + target.tableRoot()
                    + " version=" + target.version());
        }

        observeIcebergTable(duckDb, COLLATERAL_POSITION, locations.get(COLLATERAL_POSITION), "v_collateral_position");
        observeIcebergTable(duckDb, SECURITIES_LOAN, locations.get(SECURITIES_LOAN), "v_securities_loan");
        observeIcebergTable(duckDb, INVENTORY_BY_ASSET, locations.get(INVENTORY_BY_ASSET), "v_inventory_by_asset");
        observeIcebergTable(duckDb, OPEN_LOANS_DAILY, locations.get(OPEN_LOANS_DAILY), "v_open_loans_daily");
    }

    private static void observeIcebergTable(
            Connection duckDb, String tableName, String metadataLocation, String viewName)
            throws SQLException {
        IcebergScanTarget target = scanTarget(metadataLocation);
        String root = escapeLiteral(target.tableRoot());
        String version = escapeLiteral(target.version());

        Observe.banner("Iceberg snapshots — " + tableName);
        printObserveQuery(duckDb, """
                SELECT *
                FROM iceberg_snapshots('%s', version = '%s')
                """.formatted(root, version));

        Observe.banner("Iceberg manifests / data files — " + tableName);
        printObserveQuery(duckDb, """
                SELECT *
                FROM iceberg_metadata('%s', version = '%s', allow_moved_paths = true)
                """.formatted(root, version));

        Observe.banner("Iceberg column stats per data file — " + tableName);
        printObserveQuery(duckDb, """
                SELECT *
                FROM iceberg_column_stats('%s', version = '%s', allow_moved_paths = true)
                """.formatted(root, version));

        Observe.banner("Rows DuckDB reads from Iceberg — " + viewName);
        printObserveQuery(duckDb, "SELECT * FROM " + viewName);
    }

    private static void printObserveQuery(Connection duckDb, String sql) throws SQLException {
        try {
            DuckDb.printQuery(duckDb, sql);
        } catch (SQLException e) {
            System.out.println("[observe] query failed: " + e.getMessage());
            System.out.println();
        }
    }

    private static void createScanView(Connection duckDb, String viewName, String metadataLocation)
            throws SQLException {
        Objects.requireNonNull(metadataLocation, viewName);
        IcebergScanTarget target = scanTarget(metadataLocation);
        try (Statement statement = duckDb.createStatement()) {
            statement.execute("""
                    CREATE OR REPLACE VIEW %s AS
                    SELECT *
                    FROM iceberg_scan('%s', version = '%s', allow_moved_paths = true)
                    """.formatted(
                    viewName,
                    escapeLiteral(target.tableRoot()),
                    escapeLiteral(target.version())));
        }
        System.out.println("[iceberg] " + viewName + " -> " + metadataLocation);
    }

    /**
     * JdbcCatalog stores the exact metadata.json. DuckDB {@code iceberg_scan} of that
     * file treats the filename as a directory, so we scan the table root and pin
     * {@code version} to the pointer's metadata file stem.
     */
    static IcebergScanTarget scanTarget(String metadataLocation) throws SQLException {
        String marker = "/metadata/";
        int index = metadataLocation.lastIndexOf(marker);
        if (index < 0) {
            throw new SQLException("metadata_location is not an Iceberg metadata path: " + metadataLocation);
        }
        String tableRoot = metadataLocation.substring(0, index);
        String fileName = metadataLocation.substring(index + marker.length());
        String version = fileName
                .replace(".metadata.json.gz", "")
                .replace(".metadata.json", "");
        if (version.isBlank() || version.equals(fileName)) {
            throw new SQLException("Cannot parse Iceberg metadata version from " + metadataLocation);
        }
        return new IcebergScanTarget(tableRoot, version);
    }

    record IcebergScanTarget(String tableRoot, String version) {
    }

    private static void requireLocation(Map<String, String> locations, String tableName) throws SQLException {
        String location = locations.get(tableName);
        if (location == null || location.isBlank()) {
            throw new SQLException(
                    "No metadata_location in iceberg_tables for namespace="
                            + NAMESPACE
                            + " table="
                            + tableName
                            + ". Run ./run.sh seed first.");
        }
    }

    static String envOr(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    static String escapeLiteral(String value) {
        return value.replace("'", "''");
    }
}
