package de.walahi.novosmp.orders;

/** Central permission names for the order system. */
public final class OrderPermissions {
    public static final String USE = "smpcore.order.use";
    public static final String LIMIT_UNLIMITED = "smpcore.order.limit.unlimited";

    private OrderPermissions() {
    }

    public static String limit(int amount) {
        return "smpcore.order.limit." + amount;
    }
}
