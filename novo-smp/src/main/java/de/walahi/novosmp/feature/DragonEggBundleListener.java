package de.walahi.novosmp.feature;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.messages.MessageChannel;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

/** Cancels vanilla bundle insertion before either item stack is modified. */
public final class DragonEggBundleListener implements Listener {
    private final SMPCorePlugin plugin;

    public DragonEggBundleListener(SMPCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBundleInsert(InventoryClickEvent event) {
        // Paper distinguishes both directions and full/partial insertions. This
        // also covers dyed bundles without inspecting any bundle contents.
        ItemStack inserted = switch (event.getAction()) {
            case PLACE_ALL_INTO_BUNDLE, PLACE_SOME_INTO_BUNDLE -> event.getCursor();
            case PICKUP_ALL_INTO_BUNDLE, PICKUP_SOME_INTO_BUNDLE -> event.getCurrentItem();
            default -> null;
        };
        if (inserted == null || inserted.getType() != Material.DRAGON_EGG) return;

        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player player) {
            plugin.messages().send(player, MessageChannel.SMP,
                    "<red>Das Drachenei kann nicht in einem Bundle verstaut werden.</red>");
        }
    }
}
