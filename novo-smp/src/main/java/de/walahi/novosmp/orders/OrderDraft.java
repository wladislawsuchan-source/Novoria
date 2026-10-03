package de.walahi.novosmp.orders;

import org.bukkit.inventory.ItemStack;

/** Mutable per-player creation draft, owned exclusively by OrderMenuState. */
final class OrderDraft {
    private final ItemStack item;
    private int amount;
    private long pricePerItem;

    OrderDraft(ItemStack item) {
        this.item = item.clone();
        this.item.setAmount(1);
    }

    ItemStack item() {
        return item;
    }

    int amount() {
        return amount;
    }

    void amount(int amount) {
        this.amount = amount;
    }

    long pricePerItem() {
        return pricePerItem;
    }

    void pricePerItem(long pricePerItem) {
        this.pricePerItem = pricePerItem;
    }

    boolean complete(long minimumPrice) {
        return amount > 0 && pricePerItem >= minimumPrice;
    }
}
