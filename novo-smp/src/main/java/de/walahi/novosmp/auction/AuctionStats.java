package de.walahi.novosmp.auction;

/** Aggregated auction-house statistics for one player. */
public record AuctionStats(
        long coinsEarned,
        long coinsSpent,
        long itemsSold,
        long itemsBought,
        long listingsCreated
) {
    public static AuctionStats empty() { return new AuctionStats(0, 0, 0, 0, 0); }
}
