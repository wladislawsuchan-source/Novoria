package de.walahi.novosmp.duel;

import de.walahi.novosmp.NovoSMPPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/** Controls countdown freeze, fight start and the running match clock. */
final class DuelCountdownService {
    private final NovoSMPPlugin plugin;
    private final DuelConfig config;
    private final DuelSounds sounds;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    DuelCountdownService(NovoSMPPlugin plugin, DuelConfig config, DuelSounds sounds) {
        this.plugin = plugin;
        this.config = config;
        this.sounds = sounds;
    }

    void start(DuelMatch match, Runnable onStarted, Runnable onTimeout) {
        startFreeze(match);
        runStartCountdown(match, config.countdownSeconds(), onStarted, onTimeout);
    }

    void stop(DuelMatch match) {
        stopFreeze(match);
        if (match.timerTask != null) {
            match.timerTask.cancel();
            match.timerTask = null;
        }
    }

    private void startFreeze(DuelMatch match) {
        stopFreeze(match);
        match.countdownFreezeTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (match.state != DuelMatch.State.COUNTDOWN) {
                stopFreeze(match);
                return;
            }
            enforceAnchor(match, match.request.challenger());
            enforceAnchor(match, match.request.target());
        }, 1L, 1L);
    }

    private void enforceAnchor(DuelMatch match, java.util.UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        Location anchor = match.countdownAnchors.get(playerId);
        if (player == null || !player.isOnline() || anchor == null || anchor.getWorld() == null) return;

        player.setVelocity(new Vector(0D, 0D, 0D));
        player.setFallDistance(0F);

        Location current = player.getLocation();
        boolean moved = current.getWorld() != anchor.getWorld() || current.distanceSquared(anchor) > 0.0001D;
        if (!moved) return;

        Location corrected = anchor.clone();
        corrected.setYaw(current.getYaw());
        corrected.setPitch(current.getPitch());
        teleportAllowed(player, corrected);
    }

    private void stopFreeze(DuelMatch match) {
        if (match.countdownFreezeTask == null) return;
        match.countdownFreezeTask.cancel();
        match.countdownFreezeTask = null;
    }

    private void runStartCountdown(DuelMatch match, int seconds, Runnable onStarted, Runnable onTimeout) {
        if (seconds <= 0) {
            beginRunning(match, onStarted, onTimeout);
            return;
        }

        final int[] remaining = {seconds};
        match.timerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (match.state != DuelMatch.State.COUNTDOWN) return;
            Player one = Bukkit.getPlayer(match.request.challenger());
            Player two = Bukkit.getPlayer(match.request.target());
            if (one == null || two == null) return;

            int value = remaining[0]--;
            if (value <= 0) {
                if (match.timerTask != null) match.timerTask.cancel();
                Component title = miniMessage.deserialize(config.message("match.start-title", "<green><bold>LOS!</bold></green>"));
                one.showTitle(Title.title(title, Component.empty()));
                two.showTitle(Title.title(title, Component.empty()));
                sounds.play(one, "fight-start");
                sounds.play(two, "fight-start");
                beginRunning(match, onStarted, onTimeout);
                return;
            }

            Component title = miniMessage.deserialize(config.message(
                    "match.countdown-title", "<yellow><bold>%seconds%</bold></yellow>",
                    "%seconds%", Integer.toString(value)
            ));
            one.showTitle(Title.title(title, Component.empty()));
            two.showTitle(Title.title(title, Component.empty()));
            sounds.playCountdown(one, value, seconds);
            sounds.playCountdown(two, value, seconds);
        }, 0L, 20L);
    }

    private void beginRunning(DuelMatch match, Runnable onStarted, Runnable onTimeout) {
        stopFreeze(match);
        match.countdownAnchors.clear();
        match.state = DuelMatch.State.RUNNING;
        match.startedAtMillis = System.currentTimeMillis();
        if (match.timerTask != null) match.timerTask.cancel();
        onStarted.run();

        match.timerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!match.running()) return;
            long elapsed = (System.currentTimeMillis() - match.startedAtMillis) / 1000L;
            long remaining = match.request.durationSeconds() - elapsed;
            if (remaining <= 0L) {
                onTimeout.run();
                return;
            }
            if (!config.isTimeAnnouncement(remaining)) return;

            Component action = DuelMessages.actionBar(config, config.message(
                    "match.time-actionbar", "<yellow>Duell endet in <white>%time%</white></yellow>",
                    "%time%", formatTime(remaining)
            ));
            Player one = Bukkit.getPlayer(match.request.challenger());
            Player two = Bukkit.getPlayer(match.request.target());
            if (one != null) one.sendActionBar(action);
            if (two != null) two.sendActionBar(action);
            if (remaining == config.timeWarningSoundSecond()) {
                sounds.play(one, "time-warning");
                sounds.play(two, "time-warning");
            }
        }, 20L, 20L);
    }

    private void teleportAllowed(Player player, Location target) {
        // Countdown corrections are plugin-owned teleports and are therefore marked through the manager registry.
        DuelTeleportRegistry.allow(player.getUniqueId());
        player.teleport(target);
        Bukkit.getScheduler().runTask(plugin, () -> DuelTeleportRegistry.disallow(player.getUniqueId()));
    }

    private String formatTime(long seconds) {
        return String.format(java.util.Locale.GERMANY, "%d:%02d", seconds / 60L, seconds % 60L);
    }
}
