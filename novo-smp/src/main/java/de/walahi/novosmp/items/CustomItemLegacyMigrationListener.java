package de.walahi.novosmp.items;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Kleine Laufzeitmigration für alte Custom-Item-Stacks, deren physisches Material geändert wurde. */
public final class CustomItemLegacyMigrationListener implements Listener {
    private final CustomItemManager customItems;

    public CustomItemLegacyMigrationListener(CustomItemManager customItems) {
        this.customItems = customItems;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        migrateInventory(event.getPlayer().getInventory());
        migrateInventory(event.getPlayer().getEnderChest());
    }


    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (customItems.migrateLegacyWerkzeugfragment(item)) {
            if (event.getHand() == org.bukkit.inventory.EquipmentSlot.HAND) {
                event.getPlayer().getInventory().setItemInMainHand(item);
            } else if (event.getHand() == org.bukkit.inventory.EquipmentSlot.OFF_HAND) {
                event.getPlayer().getInventory().setItemInOffHand(item);
            }
            // Falls die alte Basis ein DEBUG_STICK war, darf genau dieser Klick nicht mehr
            // zur Vanilla-Debug-Stick-Funktion durchgereicht werden.
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        ItemStack stack = event.getItem().getItemStack();
        if (customItems.migrateLegacyWerkzeugfragment(stack)) {
            event.getItem().setItemStack(stack);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        ItemStack current = event.getCurrentItem();
        if (customItems.migrateLegacyWerkzeugfragment(current)) {
            event.setCurrentItem(current);
        }

        ItemStack cursor = event.getCursor();
        if (customItems.migrateLegacyWerkzeugfragment(cursor)) {
            event.getWhoClicked().setItemOnCursor(cursor);
        }
    }

    private void migrateInventory(Inventory inventory) {
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (customItems.migrateLegacyWerkzeugfragment(item)) {
                inventory.setItem(slot, item);
            }
        }
    }
}
