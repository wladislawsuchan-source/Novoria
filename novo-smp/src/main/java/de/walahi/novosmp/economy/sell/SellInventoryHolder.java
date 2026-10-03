package de.walahi.novosmp.economy.sell;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

final class SellInventoryHolder implements InventoryHolder {
    private final UUID owner;
    private Inventory inventory;
    private boolean completed;

    SellInventoryHolder(UUID owner) { this.owner = owner; }
    UUID owner() { return owner; }
    boolean completed() { return completed; }
    void completed(boolean completed) { this.completed = completed; }
    void inventory(Inventory inventory) { this.inventory = inventory; }

    @Override public @NotNull Inventory getInventory() { return inventory; }
}
