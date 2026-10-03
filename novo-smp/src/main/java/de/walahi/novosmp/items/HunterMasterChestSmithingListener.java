package de.walahi.novosmp.items;

import de.walahi.novosmp.professions.ProfessionToolService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.inventory.ItemStack;

/** Preserves the Hunter Prestige-V chestplate bonus and its entitlement serial through Netherite smithing. */
public final class HunterMasterChestSmithingListener implements Listener {
    private final CustomItemManager customItems;
    private final ProfessionToolService serialItems;

    public HunterMasterChestSmithingListener(CustomItemManager customItems, ProfessionToolService serialItems) {
        this.customItems = customItems;
        this.serialItems = serialItems;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        ItemStack base = event.getInventory().getInputEquipment();
        ItemStack result = event.getResult();
        if (base == null || result == null) return;
        serialItems.sanitizeRevoked(base);
        if (customItems.preserveHunterMasterChestUpgrade(base, result)) {
            serialItems.transferSerialMetadata(base, result);
            event.setResult(result);
        }
    }
}
