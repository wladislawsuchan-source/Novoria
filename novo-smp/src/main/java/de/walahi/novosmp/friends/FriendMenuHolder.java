package de.walahi.novosmp.friends;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import java.util.UUID;

/** Stable menu identity; click handling no longer depends on visible inventory titles. */
public final class FriendMenuHolder implements InventoryHolder {
    private final FriendMenuType type;
    private final UUID subject;
    private final int page;
    private Inventory inventory;

    public FriendMenuHolder(FriendMenuType type, UUID subject, int page) {
        this.type = type;
        this.subject = subject;
        this.page = page;
    }

    public FriendMenuType type() { return type; }
    public UUID subject() { return subject; }
    public int page() { return page; }

    public void bind(Inventory inventory) {
        if (this.inventory != null) throw new IllegalStateException("Inventory ist bereits gebunden.");
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        if (inventory == null) throw new IllegalStateException("Inventory wurde noch nicht gebunden.");
        return inventory;
    }
}
