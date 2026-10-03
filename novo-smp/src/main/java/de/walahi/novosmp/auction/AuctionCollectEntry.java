package de.walahi.novosmp.auction;

import org.bukkit.inventory.ItemStack;

import java.time.Instant;

public record AuctionCollectEntry(long id, ItemStack item, String reason, Instant createdAt) {}
