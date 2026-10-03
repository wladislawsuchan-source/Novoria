package de.walahi.novosmp.orders;

import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.UUID;

/** One safely stored item delivery waiting for the order owner. */
public record OrderCollectEntry(long id, UUID ownerId, UUID orderId, ItemStack item, String reason, Instant createdAt) {
}
