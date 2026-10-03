package de.walahi.novohub.feature;

import de.walahi.smpcore.SMPCorePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Verwaltet ausschließlich das Hub-Sidebar-Scoreboard. */
public final class HubScoreboardManager {

    private static final String OBJECTIVE_NAME = "novohub_sidebar";
    private static final String PROFILE = "hub-scoreboard";
    private static final String[] UNIQUE_ENTRIES = {
            ChatColor.BLACK.toString(), ChatColor.DARK_BLUE.toString(),
            ChatColor.DARK_GREEN.toString(), ChatColor.DARK_AQUA.toString(),
            ChatColor.DARK_RED.toString(), ChatColor.DARK_PURPLE.toString(),
            ChatColor.GOLD.toString(), ChatColor.GRAY.toString(),
            ChatColor.DARK_GRAY.toString(), ChatColor.BLUE.toString(),
            ChatColor.GREEN.toString(), ChatColor.AQUA.toString(),
            ChatColor.RED.toString(), ChatColor.LIGHT_PURPLE.toString(),
            ChatColor.YELLOW.toString()
    };

    private final SMPCorePlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, Scoreboard> activeBoards = new HashMap<>();
    private BukkitTask updateTask;

    public HubScoreboardManager(SMPCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        long interval = Math.max(1L, plugin.configs().scoreboards().getLong(PROFILE + ".update-ticks", 20L));
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateAll, 1L, interval);
    }

    public void stop() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                Scoreboard board = activeBoards.get(player.getUniqueId());
                if (board != null && player.getScoreboard() == board) {
                    player.setScoreboard(manager.getMainScoreboard());
                }
            }
        }
        activeBoards.clear();
    }

    public void refreshNow(Player player) {
        updatePlayer(player);
    }

    public void refreshNow() {
        updateAll();
    }

    private void updateAll() {
        for (Player player : Bukkit.getOnlinePlayers()) updatePlayer(player);
        activeBoards.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
    }

    private void updatePlayer(Player player) {
        String hubWorld = plugin.configs().server().getString("world", "world");
        if (!player.getWorld().getName().equalsIgnoreCase(hubWorld)
                || !plugin.configs().scoreboards().getBoolean(PROFILE + ".enabled", true)) {
            remove(player);
            return;
        }

        List<String> configured = plugin.configs().scoreboards().getStringList(PROFILE + ".lines");
        // Alte gemeinsame scoreboards.yml konnte noch SMP-Werte im Hub-Profil enthalten.
        // Diese Zeilen werden unabhängig von bestehenden Dateien zuverlässig ignoriert.
        List<String> hubOnly = configured.stream()
                .filter(line -> !containsSmpOnlyPlaceholder(line))
                .toList();
        List<String> lines = new ArrayList<>(hubOnly.subList(0, Math.min(15, hubOnly.size())));
        Scoreboard board = activeBoards.get(player.getUniqueId());
        if (board == null) {
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) return;
            board = manager.getNewScoreboard();
            activeBoards.put(player.getUniqueId(), board);
        }

        Component title = parse(replacePlaceholders(plugin.configs().scoreboards().getString(
                PROFILE + ".title", "<gold><bold>LOBBY</bold></gold>"), player));
        Objective objective = board.getObjective(OBJECTIVE_NAME);
        if (objective == null) {
            objective = board.registerNewObjective(OBJECTIVE_NAME, Criteria.DUMMY, title);
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            objective.numberFormat(io.papermc.paper.scoreboard.numbers.NumberFormat.blank());
        } else {
            objective.displayName(title);
        }

        for (int i = 0; i < UNIQUE_ENTRIES.length; i++) {
            String teamName = "line_" + i;
            Team team = board.getTeam(teamName);
            if (i < lines.size()) {
                if (team == null) team = board.registerNewTeam(teamName);
                String entry = UNIQUE_ENTRIES[i];
                if (!team.hasEntry(entry)) team.addEntry(entry);
                team.prefix(parse(replacePlaceholders(lines.get(i), player)));
                objective.getScore(entry).setScore(lines.size() - i);
            } else if (team != null) {
                for (String entry : Set.copyOf(team.getEntries())) {
                    board.resetScores(entry);
                    team.removeEntry(entry);
                }
                team.unregister();
            }
        }
        if (player.getScoreboard() != board) player.setScoreboard(board);
    }

    private void remove(Player player) {
        Scoreboard board = activeBoards.remove(player.getUniqueId());
        if (board == null || player.getScoreboard() != board) return;
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager != null) player.setScoreboard(manager.getMainScoreboard());
    }


    private boolean containsSmpOnlyPlaceholder(String line) {
        if (line == null) return false;
        return line.contains("%coins%")
                || line.contains("%lumis%")
                || line.contains("%kills%")
                || line.contains("%deaths%")
                || line.contains("%playtime%");
    }

    private String replacePlaceholders(String input, Player viewer) {
        int totalOnline = Bukkit.getOnlinePlayers().size();
        int hubOnline = totalOnline;
        int smpOnline = 0;
        if (plugin.getNetworkManager() != null) {
            totalOnline = plugin.getNetworkManager().totalOnline(totalOnline);
            hubOnline = plugin.getNetworkManager().hubOnline(hubOnline);
            smpOnline = plugin.getNetworkManager().smpOnline(smpOnline);
        }
        totalOnline = plugin.visibleOnlineCount(viewer, totalOnline, ignored -> true);
        hubOnline = plugin.visibleOnlineCount(viewer, hubOnline, p -> !plugin.isSmpGameplayWorld(p.getWorld()));
        smpOnline = plugin.visibleOnlineCount(viewer, smpOnline, p -> plugin.isSmpGameplayWorld(p.getWorld()));
        return (input == null ? "" : input)
                .replace("%online%", Integer.toString(totalOnline))
                .replace("%hub_online%", Integer.toString(hubOnline))
                .replace("%smp_online%", Integer.toString(smpOnline))
                .replace("%player%", viewer.getName());
    }

    private Component parse(String input) {
        return miniMessage.deserialize(input == null ? "" : input);
    }
}
