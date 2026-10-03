package de.walahi.smpcore.sounds;

import de.walahi.smpcore.SMPCorePlugin;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.Locale;

public final class SoundManager {
    private final SMPCorePlugin plugin;
    public SoundManager(SMPCorePlugin plugin) { this.plugin = plugin; }
    public void play(Player player, String path) {
        String full = path.startsWith("sounds.") ? path : "sounds." + path;
        if (!plugin.configs().sounds().getBoolean("sounds.enabled", true)) return;
        if (!plugin.configs().sounds().getBoolean(full + ".enabled", true)) return;
        String name = plugin.configs().sounds().getString(full + ".name", "");
        if (name == null || name.isBlank()) return;
        try {
            Sound sound = Sound.valueOf(name.toUpperCase(Locale.ROOT));
            float volume = (float) plugin.configs().sounds().getDouble(full + ".volume", 1.0D);
            float pitch = (float) plugin.configs().sounds().getDouble(full + ".pitch", 1.0D);
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Ungültiger Sound in sounds.yml: " + name + " (" + full + ")");
        }
    }
}
