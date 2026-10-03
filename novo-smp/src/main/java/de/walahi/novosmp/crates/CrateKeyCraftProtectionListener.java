package de.walahi.novosmp.crates;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;

/** Verhindert, dass echte Crate-Keys als normale Crafting-Zutaten verbraucht werden. */
public final class CrateKeyCraftProtectionListener implements Listener {
    private final KeyManager keys;

    public CrateKeyCraftProtectionListener(KeyManager keys) {
        this.keys = keys;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (!containsCrateKey(event.getInventory().getMatrix())) return;
        event.getInventory().setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!containsCrateKey(event.getInventory().getMatrix())) return;
        event.setCancelled(true);
        event.getInventory().setResult(null);
    }

    private boolean containsCrateKey(ItemStack[] matrix) {
        if (matrix == null) return false;
        for (ItemStack item : matrix) {
            if (keys.isAnyKey(item)) return true;
        }
        return false;
    }
}
