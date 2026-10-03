package de.walahi.novosmp.enchants;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Iterator;

/** Keeps only the actual Seelenbindung drops on ordinary player deaths. */
public final class SoulboundDeathListener implements Listener {
    private final CustomEnchantmentService enchantments;

    public SoulboundDeathListener(CustomEnchantmentService enchantments) {
        this.enchantments = enchantments;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (event.getKeepInventory() || event.getEntity().hasMetadata("novosmp-combat-dummy")) return;

        Iterator<ItemStack> drops = event.getDrops().iterator();
        while (drops.hasNext()) {
            ItemStack item = drops.next();
            if (enchantments.level(item, "seelenbindung") < 1) continue;
            drops.remove();
            event.getItemsToKeep().add(item);
        }
    }
}
