package de.walahi.novosmp.professions;

import de.walahi.smpcore.SMPCorePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Compact profession XP and level feedback without chat spam. */
final class ProfessionFeedback {
    private final SMPCorePlugin plugin;
    private final ProfessionConfig config;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, PendingProgress> pendingProgress = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> pendingTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> levelDisplayUntil = new ConcurrentHashMap<>();

    ProfessionFeedback(SMPCorePlugin plugin, ProfessionConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    void showProgress(Player player, ProfessionProgress progress) {
        UUID playerId = player.getUniqueId();
        if (System.currentTimeMillis() < levelDisplayUntil.getOrDefault(playerId, 0L)) return;
        pendingProgress.put(playerId, PendingProgress.from(config, progress));
        pendingTasks.computeIfAbsent(playerId, ignored -> Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pendingTasks.remove(playerId);
            PendingProgress latest = pendingProgress.remove(playerId);
            if (latest == null || !player.isOnline()) return;
            player.sendActionBar(progressComponent(latest));
        }, Math.max(1L, plugin.configs().professions().getLong("feedback.xp-actionbar.delay-ticks", 2L))));
    }

    void showLevelUp(Player player, ProfessionProgress progress, int oldLevel, int newLevel, int milestone) {
        UUID playerId = player.getUniqueId();
        clearPending(playerId);
        long holdMillis = Math.max(500L, plugin.configs().professions()
                .getLong("feedback.level-up-hold-millis", 1800L));
        levelDisplayUntil.put(playerId, System.currentTimeMillis() + holdMillis);
        String path = milestone > 0 ? "feedback.milestone-actionbar" : "feedback.level-up-actionbar";
        String fallback = milestone > 0
                ? "<gold><bold>MEILENSTEIN!</bold></gold> <gray>%profession% Level <yellow>%level%</yellow> • Abgabe in /berufe</gray>"
                : "<green><bold>LEVELAUFSTIEG!</bold></green> <gray>%profession% <yellow>%old%</yellow> → <yellow>%level%</yellow></gray>";
        String raw = config.string(path, fallback)
                .replace("%profession%", config.professionDisplayName(progress.professionId()))
                .replace("%old%", Integer.toString(oldLevel))
                .replace("%level%", Integer.toString(newLevel));
        player.sendActionBar(miniMessage.deserialize(raw));
        playLevelSound(player, milestone > 0);
    }

    void clear(UUID playerId) {
        clearPending(playerId);
        levelDisplayUntil.remove(playerId);
    }

    void suppressProgress(UUID playerId, String professionId) {
        PendingProgress pending = pendingProgress.get(playerId);
        if (pending != null && pending.professionId().equals(professionId)) clearPending(playerId);
    }

    void stop() {
        pendingTasks.values().forEach(BukkitTask::cancel);
        pendingTasks.clear();
        pendingProgress.clear();
        levelDisplayUntil.clear();
    }

    private Component progressComponent(PendingProgress progress) {
        int segments = Math.max(5, Math.min(20,
                plugin.configs().professions().getInt("feedback.xp-actionbar.bar-segments", 10)));
        int filled = (int) Math.round(progress.ratio * segments);
        String bar = "<green>" + "▰".repeat(Math.max(0, filled)) + "</green>"
                + "<dark_gray>" + "▱".repeat(Math.max(0, segments - filled)) + "</dark_gray>";
        String raw = config.string("feedback.xp-actionbar.format",
                        "%profession% <dark_gray>•</dark_gray> <yellow>Level %level%</yellow> "
                                + "<dark_gray>•</dark_gray> %bar% <aqua>%current%/%needed% XP</aqua>")
                .replace("%profession%", config.professionDisplayName(progress.professionId))
                .replace("%level%", Integer.toString(progress.level))
                .replace("%bar%", bar)
                .replace("%current%", format(progress.currentWithinLevel))
                .replace("%needed%", format(progress.neededForLevel));
        return miniMessage.deserialize(raw);
    }

    private void playLevelSound(Player player, boolean milestone) {
        String base = milestone ? "feedback.sounds.milestone" : "feedback.sounds.level-up";
        String configured = config.string(base + ".sound", "ENTITY_PLAYER_LEVELUP");
        Sound sound;
        try {
            sound = Sound.valueOf(configured.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            sound = Sound.ENTITY_PLAYER_LEVELUP;
        }
        float volume = (float) plugin.configs().professions().getDouble(base + ".volume", 1.0D);
        float pitch = (float) plugin.configs().professions().getDouble(base + ".pitch", milestone ? 1.25D : 1.1D);
        player.playSound(player.getLocation(), sound, Math.max(0F, volume), Math.max(0.01F, pitch));
    }

    private void clearPending(UUID playerId) {
        pendingProgress.remove(playerId);
        BukkitTask task = pendingTasks.remove(playerId);
        if (task != null) task.cancel();
    }

    private String format(double value) {
        return de.walahi.smpcore.gui.MenuFormat.integer(Math.max(0L, Math.round(value)));
    }

    private record PendingProgress(String professionId, int level, double currentWithinLevel,
                                   double neededForLevel, double ratio) {
        static PendingProgress from(ProfessionConfig config, ProfessionProgress progress) {
            double previous = config.xpThreshold(progress.professionId(), progress.prestige(), progress.level());
            double next = progress.level() >= config.maxLevel()
                    ? config.totalXp(progress.professionId(), progress.prestige())
                    : config.xpThreshold(progress.professionId(), progress.prestige(), progress.level() + 1);
            double current = Math.max(0D, progress.xp() - previous);
            double needed = Math.max(1D, next - previous);
            return new PendingProgress(progress.professionId(), progress.level(), current, needed,
                    Math.max(0D, Math.min(1D, current / needed)));
        }
    }
}
