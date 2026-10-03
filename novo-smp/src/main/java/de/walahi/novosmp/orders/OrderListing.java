package de.walahi.novosmp.orders;

import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.UUID;

public record OrderListing(
        UUID id,
        UUID ownerId,
        String ownerName,
        ItemStack item,
        int requestedAmount,
        int remainingAmount,
        long pricePerItem,
        long escrowRemaining,
        Instant createdAt,
        Instant expiresAt
) { }
