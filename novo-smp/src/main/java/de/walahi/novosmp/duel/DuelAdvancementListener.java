package de.walahi.novosmp.duel;

import com.destroystokyo.paper.event.player.PlayerAdvancementCriterionGrantEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Prevents temporary duel kits and duel actions from granting persistent
 * vanilla advancement progress on the SMP.
 */
final class DuelAdvancementListener implements Listener {
    private final DuelManager manager;

    DuelAdvancementListener(DuelManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAdvancementCriterionGrant(PlayerAdvancementCriterionGrantEvent event) {
        if (manager.inDuel(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
