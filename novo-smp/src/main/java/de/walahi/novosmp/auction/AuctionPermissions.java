package de.walahi.novosmp.auction;

/** Stable permission names for the auction house. */
public final class AuctionPermissions {
    public static final String USE = "smpcore.ah.use";
    public static final String SELL = "smpcore.ah.sell";
    public static final String LIMIT_UNLIMITED = "smpcore.ah.limit.unlimited";

    private AuctionPermissions() {
    }

    public static String listingLimit(int limit) {
        return "smpcore.ah.limit." + limit;
    }
}
