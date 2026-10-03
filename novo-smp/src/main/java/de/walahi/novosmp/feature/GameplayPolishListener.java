package de.walahi.novosmp.feature;

import de.walahi.smpcore.SMPCorePlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Small gameplay polish features that are configurable without extra plugins. */
public final class GameplayPolishListener implements Listener {
    private final SMPCorePlugin plugin;

    public GameplayPolishListener(SMPCorePlugin plugin) {
        this.plugin = plugin;
        removeLegacyCollisionTeams();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChatMention(AsyncChatEvent event) {
        if (!plugin.configs().main().getBoolean("gameplay.chat-mention.enabled", true)) return;

        Player sender = event.getPlayer();
        String plainMessage = PlainTextComponentSerializer.plainText().serialize(event.message());
        Set<Player> mentioned = event.viewers().stream()
                .filter(Player.class::isInstance)
                .map(Player.class::cast)
                .filter(player -> !player.equals(sender))
                .filter(player -> containsExactName(plainMessage, player.getName()))
                .collect(java.util.stream.Collectors.toSet());

        if (mentioned.isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin, () -> mentioned.forEach(this::playMentionSound));
    }

    private boolean containsExactName(String message, String playerName) {
        Pattern pattern = Pattern.compile(
                "(?i)(?<![A-Za-z0-9_])" + Pattern.quote(playerName) + "(?![A-Za-z0-9_])"
        );
        return pattern.matcher(message).find();
    }

    /** Removes the old collision-only team from already loaded scoreboards after an update. */
    private void removeLegacyCollisionTeams() {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) return;
        Set<Scoreboard> scoreboards = new HashSet<>();
        scoreboards.add(manager.getMainScoreboard());
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            scoreboards.add(viewer.getScoreboard());
        }
        for (Scoreboard scoreboard : scoreboards) {
            Team legacy = scoreboard.getTeam("smp_nocollision");
            if (legacy != null) legacy.unregister();
            // Old NovoSMP versions also wrote NEVER onto their rank/anonymity teams.
            // Reset that persisted setting once; no recurring collision rule remains.
            for (Team team : scoreboard.getTeams()) {
                String name = team.getName();
                if ((name.matches("sc[0-9]{3}[a-z0-9]*") || name.equals("nv_anon"))
                        && team.getOption(Team.Option.COLLISION_RULE) == Team.OptionStatus.NEVER) {
                    team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.ALWAYS);
                }
            }
        }
    }

    private void playMentionSound(Player player) {
        playConfiguredSound(
                player,
                "sounds.chat-mention",
                Sound.BLOCK_NOTE_BLOCK_PLING,
                0.8f,
                1.6f
        );
    }

    private void playConfiguredSound(Player player, String path, Sound fallback, float defaultVolume, float defaultPitch) {
        if (!plugin.configs().sounds().getBoolean(path + ".enabled", true)) return;
        String configured = plugin.configs().sounds().getString(path + ".name", fallback.name());
        try {
            Sound sound = Sound.valueOf(configured.toUpperCase(Locale.ROOT));
            float volume = (float) plugin.configs().sounds().getDouble(path + ".volume", defaultVolume);
            float pitch = (float) plugin.configs().sounds().getDouble(path + ".pitch", defaultPitch);
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Ungültiger Sound in " + path + ": " + configured);
        }
    }
}
