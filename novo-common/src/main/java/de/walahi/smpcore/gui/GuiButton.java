package de.walahi.smpcore.gui;

import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.function.Consumer;

public record GuiButton(ItemStack item, Consumer<InventoryClickEvent> action) {
    public GuiButton {
        Objects.requireNonNull(item, "item");
        item = item.clone();
    }

    public static GuiButton of(ItemStack item, Consumer<InventoryClickEvent> action) {
        return new GuiButton(item, action);
    }
}
