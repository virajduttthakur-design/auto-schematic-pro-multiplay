package dev.autoschematicpro.client;

/** Latest build status reported by the server for this player. Only touched on the client thread. */
public final class ClientBuildState {
    private static String status = "Idle";
    private static int completed;
    private static int total;

    private ClientBuildState() {}

    public static void update(String newStatus, int newCompleted, int newTotal) {
        status = newStatus;
        completed = newCompleted;
        total = newTotal;
    }

    public static void reset() {
        update("Idle", 0, 0);
    }

    public static String status() {
        return status;
    }

    public static int completed() {
        return completed;
    }

    public static int total() {
        return total;
    }
}
