package de.walahi.novosmp.auction;

import org.bukkit.inventory.ItemStack;
import java.time.Instant;
import java.util.UUID;

/** One player-facing auction history entry. */
public record AuctionHistoryEntry(
        long id,
        String eventType,
        UUID listingId,
        ItemStack item,
        UUID counterpartId,
        String counterpartName,
        long price,
        Instant createdAt
) {}
