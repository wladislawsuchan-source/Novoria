package de.walahi.novosmp.feature;

import de.walahi.smpcore.SMPCorePlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Persistent, player-controlled night vision without particles. */
public final class NightVisionManager implements Listener {
    private final SMPCorePlugin plugin;
    private final NamespacedKey enabledKey;

    public NightVisionManager(SMPCorePlugin plugin) {
        this.plugin = plugin;
        this.enabledKey = new NamespacedKey(plugin, "night_vision_enabled");
    }

    public boolean toggle(Player player) {
        boolean enabled = !enabled(player);
        player.getPersistentDataContainer().set(enabledKey, PersistentDataType.BYTE, enabled ? (byte) 1 : (byte) 0);
        if (enabled) apply(player);
        else player.removePotionEffect(PotionEffectType.NIGHT_VISION);
        return enabled;
    }

    public boolean enabled(Player player) {
        return player.getPersistentDataContainer().getOrDefault(enabledKey, PersistentDataType.BYTE, (byte) 0) == 1;
    }

    private void restoreNextTick(Player player) {
        if (!enabled(player)) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && enabled(player)) apply(player);
        });
    }

    private void apply(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION,
                PotionEffect.INFINITE_DURATION, 0, false, false, false), true);
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) { restoreNextTick(event.getPlayer()); }
    @EventHandler public void onRespawn(PlayerRespawnEvent event) { restoreNextTick(event.getPlayer()); }
    @EventHandler public void onWorldChange(PlayerChangedWorldEvent event) { restoreNextTick(event.getPlayer()); }
}
