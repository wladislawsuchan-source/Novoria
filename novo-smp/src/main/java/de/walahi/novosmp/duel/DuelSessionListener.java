package de.walahi.novosmp.duel;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/** Handles disconnect cleanup and disconnect-as-loss rules. */
final class DuelSessionListener implements Listener {
    private final DuelManager manager;

    DuelSessionListener(DuelManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        manager.removePendingState(player.getUniqueId());

        DuelMatch match = manager.match(player.getUniqueId());
        if (match == null) return;
        if (match.state == DuelMatch.State.ENDING) {
            manager.restoreEndingPlayer(match, player);
            return;
        }

        manager.finishOnDisconnect(match, player);
    }
}
