package de.walahi.novosmp.auction;

import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.UUID;

public record AuctionListing(
        UUID id,
        UUID sellerId,
        String sellerName,
        ItemStack item,
        long price,
        AuctionStatus status,
        Instant createdAt,
        Instant expiresAt
) {
    public AuctionListing {
        if (id == null || sellerId == null || item == null || status == null || createdAt == null || expiresAt == null) {
            throw new IllegalArgumentException("AuctionListing enthält null-Werte.");
        }
        if (sellerName == null || sellerName.isBlank()) throw new IllegalArgumentException("sellerName fehlt.");
        if (item.getType().isAir() || item.getAmount() < 1) throw new IllegalArgumentException("Ungültiges Auktions-Item.");
        if (price < 1) throw new IllegalArgumentException("Der Preis muss positiv sein.");
        item = item.clone();
    }
}
