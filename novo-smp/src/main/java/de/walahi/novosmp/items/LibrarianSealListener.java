package de.walahi.novosmp.items;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.messages.MessageChannel;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Rerolls an unused librarian through Paper's own Vanilla offer generation. */
public final class LibrarianSealListener implements Listener {
    private final SMPCorePlugin plugin;
    private final CustomItemManager customItems;
    private final Map<UUID, Integer> lastSealClickTick = new HashMap<>();

    public LibrarianSealListener(SMPCorePlugin plugin, CustomItemManager customItems) {
        this.plugin = plugin;
        this.customItems = customItems;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager villager)) return;
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItem(event.getHand());
        if (!customItems.is(held, "bibliothekarsiegel")) {
            // A main-hand trade must not swallow the subsequent off-hand seal click.
            if (event.getHand() == EquipmentSlot.HAND
                    && customItems.is(player.getInventory().getItemInOffHand(), "bibliothekarsiegel")) {
                event.setCancelled(true);
            }
            return;
        }

        event.setCancelled(true);
        int tick = Bukkit.getCurrentTick();
        UUID playerId = player.getUniqueId();
        if (Integer.valueOf(tick).equals(lastSealClickTick.put(playerId, tick))) return;

        if (villager.getProfession() != Villager.Profession.LIBRARIAN) {
            plugin.messages().sendConfigured(player, plugin.configs().items(),
                    "custom-items.messages.librarian-seal-wrong-profession", MessageChannel.NOVORIA,
                    "<red>Das Bibliothekarsiegel funktioniert nur bei Bibliothekaren.</red>");
            return;
        }
        if (hasTraded(villager)) {
            plugin.messages().sendConfigured(player, plugin.configs().items(),
                    "custom-items.messages.librarian-seal-already-traded", MessageChannel.NOVORIA,
                    "<red>Dieser Bibliothekar kann nicht mehr neu gewürfelt werden.</red>");
            return;
        }

        villager.resetOffers();
        player.openMerchant(villager, false);
    }

    /** Recipe uses catch recent trades; XP and level remain after restocks and restarts. */
    public static boolean hasTraded(Villager villager) {
        return villager.getVillagerExperience() > 0 || villager.getVillagerLevel() > 1
                || villager.getRecipes().stream().anyMatch(recipe -> recipe.getUses() > 0);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastSealClickTick.remove(event.getPlayer().getUniqueId());
    }
}
