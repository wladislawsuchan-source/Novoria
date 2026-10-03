package de.walahi.novosmp.enderchest;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

public final class ExpandableEnderChestHolder implements InventoryHolder {
    private final UUID owner;
    private Inventory inventory;
    ExpandableEnderChestHolder(UUID owner) { this.owner = owner; }
    public UUID owner() { return owner; }
    void inventory(Inventory inventory) { this.inventory = inventory; }
    @Override public Inventory getInventory() { return inventory; }
}
