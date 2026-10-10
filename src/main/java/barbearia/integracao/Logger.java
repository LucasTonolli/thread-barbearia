package barbearia.integracao;

/**
 * Logger simples e thread-safe para a simulacao.
 * O timestamp e relativo ao inicio da execucao para facilitar a auditoria.
 */
public final class Logger {

    private static long startNanos = System.nanoTime();
    private static volatile boolean enabled = true;

    private Logger() {
    }

    public static synchronized void reset() {
        startNanos = System.nanoTime();
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static synchronized void log(String category, String message) {
        if (!enabled) {
            return;
        }

        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        long minutes = elapsedMillis / 60_000L;
        long seconds = (elapsedMillis % 60_000L) / 1_000L;
        long millis = elapsedMillis % 1_000L;

        System.out.printf(
                "[%02d:%02d.%03d] [%s] %s: %s%n",
                minutes,
                seconds,
                millis,
                Thread.currentThread().getName(),
                category,
                message
        );
    }

    public static void log(String category, String message, int occupancy, int maxCapacity) {
        log(category, message + " (Lotacao: " + occupancy + "/" + maxCapacity + ").");
    }
}
