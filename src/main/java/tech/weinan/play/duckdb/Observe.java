package tech.weinan.play.duckdb;

/**
 * Optional observability for local demos.
 *
 * <p>Enable with {@code --observe} / {@code -o}, or {@code OBSERVE=1} (also
 * {@code true} / {@code yes} / {@code on}). Off by default so normal runs stay
 * at checksums only.
 */
public final class Observe {

    private static boolean enabledFromCli;

    private Observe() {
    }

    public static void enableFromCli() {
        enabledFromCli = true;
    }

    public static boolean enabled() {
        if (enabledFromCli) {
            return true;
        }
        String value = IcebergTables.envOr("OBSERVE", "");
        return value.equals("1")
                || value.equalsIgnoreCase("true")
                || value.equalsIgnoreCase("yes")
                || value.equalsIgnoreCase("on");
    }

    public static void banner(String title) {
        if (!enabled()) {
            return;
        }
        System.out.println();
        System.out.println("======== observe: " + title + " ========");
    }
}
