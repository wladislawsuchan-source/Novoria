package de.walahi.smpcore.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

final class GuiHolder implements InventoryHolder {
    private final Gui gui;
    private Inventory inventory;

    GuiHolder(Gui gui) {
        this.gui = gui;
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    Gui gui() {
        return gui;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
