package de.walahi.novosmp.duel;

import de.walahi.novosmp.NovoSMPPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Configurable, player-local sound feedback for every part of the duel flow. */
public final class DuelSounds {
    private final NovoSMPPlugin plugin;
    private final DuelConfig config;
    private final Set<String> warnedInvalidSounds = new HashSet<>();

    public DuelSounds(NovoSMPPlugin plugin, DuelConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void play(Player player, String key) {
        play(player, key, null);
    }

    public void play(Player player, String key, Float pitchOverride) {
        if (player == null || !player.isOnline() || !enabled()) return;
        String path = "sounds." + key;
        if (!config.raw().getBoolean(path + ".enabled", true)) return;
        Sound sound = resolve(config.raw().getString(path + ".sound", "UI_BUTTON_CLICK"));
        if (sound == null) return;
        float volume = positiveFloat(path + ".volume", 1.0F);
        float pitch = pitchOverride == null ? positiveFloat(path + ".pitch", 1.0F) : clampPitch(pitchOverride);
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    public void playCountdown(Player player, int shownNumber, int totalSeconds) {
        String path = "sounds.countdown-tick";
        float first = positiveFloat(path + ".pitch-first", 0.8F);
        float last = positiveFloat(path + ".pitch-last", 1.2F);
        float progress = totalSeconds <= 1 ? 1.0F
                : Math.max(0.0F, Math.min(1.0F, (totalSeconds - shownNumber) / (float) (totalSeconds - 1)));
        play(player, "countdown-tick", first + (last - first) * progress);
    }

    /** Plays the configurable clap/twinkle sequence only for the winner. */
    public void playVictory(Player player) {
        play(player, "result-winner");
        if (player == null || !player.isOnline() || !enabled()) return;
        String path = "sounds.winner-applause";
        if (!config.raw().getBoolean(path + ".enabled", true)) return;
        Sound sound = resolve(config.raw().getString(path + ".sound", "ENTITY_FIREWORK_ROCKET_TWINKLE"));
        if (sound == null) return;
        float defaultVolume = positiveFloat(path + ".volume", 0.9F);
        List<Map<?, ?>> steps = config.raw().getMapList(path + ".steps");
        if (steps.isEmpty()) {
            steps = List.of(
                    Map.of("delay-ticks", 0, "pitch", 0.9D),
                    Map.of("delay-ticks", 3, "pitch", 1.0D),
                    Map.of("delay-ticks", 6, "pitch", 1.1D),
                    Map.of("delay-ticks", 9, "pitch", 1.2D)
            );
        }
        for (Map<?, ?> step : steps) {
            int delay = Math.max(0, number(step.get("delay-ticks"), 0).intValue());
            float pitch = clampPitch(number(step.get("pitch"), 1.0D).floatValue());
            float volume = Math.max(0.0F, number(step.get("volume"), defaultVolume).floatValue());
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) player.playSound(player.getLocation(), sound, volume, pitch);
            }, delay);
        }
    }

    public boolean enabled() {
        return config.raw().getBoolean("sounds.enabled", true);
    }

    @SuppressWarnings("removal")
    private Sound resolve(String configured) {
        if (configured == null || configured.isBlank() || configured.equalsIgnoreCase("NONE")) return null;
        String raw = configured.trim();

        // Bukkit-Konstanten lassen sich nicht zuverlässig durch einfaches Ersetzen von Unterstrichen
        // in Minecraft-Keys umwandeln (z. B. FIREWORK_ROCKET oder EXPERIENCE_ORB).
        if (raw.indexOf(':') < 0 && raw.indexOf('.') < 0) {
            try {
                return Sound.valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Danach noch als expliziten Namespaced-Key versuchen und sauber warnen.
            }
        }

        NamespacedKey key = raw.indexOf(':') >= 0
                ? NamespacedKey.fromString(raw.toLowerCase(Locale.ROOT))
                : NamespacedKey.minecraft(raw.toLowerCase(Locale.ROOT));
        Sound sound = key == null ? null : Registry.SOUND_EVENT.get(key);
        if (sound == null && warnedInvalidSounds.add(raw.toUpperCase(Locale.ROOT))) {
            plugin.getLogger().warning("Ungültiger Duell-Sound in duels.yml: " + raw);
        }
        return sound;
    }

    private float positiveFloat(String path, float fallback) {
        return Math.max(0.0F, (float) config.raw().getDouble(path, fallback));
    }

    private float clampPitch(float value) {
        return Math.max(0.01F, Math.min(2.0F, value));
    }

    private Number number(Object value, Number fallback) {
        return value instanceof Number number ? number : fallback;
    }
}
