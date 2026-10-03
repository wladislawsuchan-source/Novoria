package de.walahi.novosmp.auction;

import de.walahi.smpcore.SMPCorePlugin;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.Locale;

/** Plays backwards-compatible auction sounds from the global config.yml. */
final class AuctionSoundService {
    private final SMPCorePlugin plugin;
    private final AuctionSettings settings;

    AuctionSoundService(SMPCorePlugin plugin, AuctionSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    void play(Player player, String key) {
        if (!settings.soundsEnabled()) return;
        String configured = settings.soundName(key);
        if (configured == null || configured.isBlank()) return;
        try {
            Sound sound = Sound.valueOf(configured.toUpperCase(Locale.ROOT));
            float volume = settings.soundVolume(key);
            float pitch = settings.soundPitch(key);
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Ungültiger AH-Sound: " + configured);
        }
    }
}
