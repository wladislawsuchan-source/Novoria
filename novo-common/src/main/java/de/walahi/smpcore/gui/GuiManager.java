package de.walahi.smpcore.gui;

import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;

/** Verwaltet sämtliche SMPCore-GUIs zentral und sperrt deren Inhalte vollständig. */
public final class GuiManager implements Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void protectClickEarly(InventoryClickEvent event) {
        if (!isManagedGui(event.getView().getTopInventory())) return;
        deny(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof GuiHolder holder)) return;

        // Nicht nur canceln, sondern das Bukkit-Ergebnis ausdrücklich verweigern.
        // Damit sind auch Shift-Klick, Hotbar-Tausch, Doppelklick, Drop und Creative-Klick gesperrt.
        deny(event);
        if (event.getClick().isKeyboardClick() || event.getClick().isShiftClick()
                || event.getClick().isCreativeAction()
                || event.getSlotType() == InventoryType.SlotType.OUTSIDE) return;

        int rawSlot = event.getRawSlot();
        if (rawSlot >= 0 && rawSlot < top.getSize()) holder.gui().click(rawSlot, event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void protectDragEarly(InventoryDragEvent event) {
        if (!isManagedGui(event.getView().getTopInventory())) return;
        deny(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (!isManagedGui(event.getView().getTopInventory())) return;
        deny(event);
    }

    private boolean isManagedGui(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof GuiHolder;
    }

    private void deny(org.bukkit.event.inventory.InventoryInteractEvent event) {
        event.setCancelled(true);
        event.setResult(Event.Result.DENY);
    }
}
