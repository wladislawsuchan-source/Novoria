package de.walahi.novosmp.feature;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.stats.StatsAccess;
import de.walahi.novosmp.lumi.LumiRepository;
import de.walahi.smpcore.services.EconomyService;
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

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Verwaltet ausschließlich das SMP-Sidebar-Scoreboard. */
public final class SmpScoreboardManager {
    private static final String OBJECTIVE_NAME = "novosmp_sidebar";
    private static final String PROFILE = "smp-scoreboard";
    private static final NumberFormat NUMBER_FORMAT = NumberFormat.getIntegerInstance(Locale.GERMANY);
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
    private final StatsAccess stats;
    private final EconomyService economy;
    private final LumiRepository lumis;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, Scoreboard> activeBoards = new HashMap<>();
    private BukkitTask updateTask;

    public SmpScoreboardManager(SMPCorePlugin plugin, StatsAccess stats,
                                EconomyService economy, LumiRepository lumis) {
        this.plugin = plugin;
        this.stats = stats;
        this.economy = economy;
        this.lumis = lumis;
        NUMBER_FORMAT.setGroupingUsed(true);
        NUMBER_FORMAT.setMaximumFractionDigits(0);
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
                if (board != null && player.getScoreboard() == board) player.setScoreboard(manager.getMainScoreboard());
            }
        }
        activeBoards.clear();
    }

    public void refreshNow(Player player) { updatePlayer(player); }

    public void refreshNow() { updateAll(); }

    private void updateAll() {
        for (Player player : Bukkit.getOnlinePlayers()) updatePlayer(player);
        activeBoards.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
    }

    private void updatePlayer(Player player) {
        // NovoSMP läuft ausschließlich auf dem SMP-Backend. Das Scoreboard soll daher
        // auch am SMP-Spawn und in allen weiteren geladenen SMP-Welten sichtbar sein.
        if (!plugin.configs().scoreboards().getBoolean(PROFILE + ".enabled", true)) {
            remove(player);
            return;
        }
        List<String> configured = new ArrayList<>(plugin.configs().scoreboards().getStringList(PROFILE + ".lines"));
        if (configured.stream().noneMatch(line -> line.contains("%lumis%"))) {
            int coinLine = -1;
            for (int i = 0; i < configured.size(); i++) {
                if (configured.get(i).contains("%coins%")) { coinLine = i; break; }
            }
            configured.add(Math.min(configured.size(), coinLine + 1),
                    "<light_purple> ✦ <white>Lumis: <yellow>%lumis%</yellow>");
        }
        List<String> lines = new ArrayList<>(configured.subList(0, Math.min(15, configured.size())));
        Scoreboard board = activeBoards.get(player.getUniqueId());
        if (board == null) {
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) return;
            board = manager.getNewScoreboard();
            activeBoards.put(player.getUniqueId(), board);
        }
        String titleTemplate = plugin.configs().scoreboards().getString(
                PROFILE + ".title", "<gold><bold>SURVIVAL</bold></gold>");
        boolean needsCoins = titleTemplate != null && titleTemplate.contains("%coins%");
        boolean needsLumis = titleTemplate != null && titleTemplate.contains("%lumis%");
        for (String line : lines) {
            if (line == null) continue;
            needsCoins |= line.contains("%coins%");
            needsLumis |= line.contains("%lumis%");
        }
        UUID playerId = player.getUniqueId();
        String coins = needsCoins ? NUMBER_FORMAT.format(
                economy == null || !economy.available() ? 0L : economy.balanceReadFirst(playerId)) : "";
        String lumiBalance = needsLumis ? NUMBER_FORMAT.format(
                lumis == null ? 0L : lumis.balanceReadFirst(playerId)) : "";
        Component title = parse(replacePlaceholders(titleTemplate, player, coins, lumiBalance));
        Objective objective = board.getObjective(OBJECTIVE_NAME);
        if (objective == null) {
            objective = board.registerNewObjective(OBJECTIVE_NAME, Criteria.DUMMY, title);
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            objective.numberFormat(io.papermc.paper.scoreboard.numbers.NumberFormat.blank());
        } else objective.displayName(title);

        for (int i = 0; i < UNIQUE_ENTRIES.length; i++) {
            String teamName = "line_" + i;
            Team team = board.getTeam(teamName);
            if (i < lines.size()) {
                if (team == null) team = board.registerNewTeam(teamName);
                String entry = UNIQUE_ENTRIES[i];
                if (!team.hasEntry(entry)) team.addEntry(entry);
                team.prefix(parse(replacePlaceholders(lines.get(i), player, coins, lumiBalance)));
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

    private String replacePlaceholders(String input, Player viewer, String coins, String lumis) {
        int totalOnline = Bukkit.getOnlinePlayers().size();
        int hubOnline = 0;
        int smpOnline = totalOnline;
        if (plugin.getNetworkManager() != null) {
            totalOnline = plugin.getNetworkManager().totalOnline(totalOnline);
            hubOnline = plugin.getNetworkManager().hubOnline(hubOnline);
            smpOnline = plugin.getNetworkManager().smpOnline(smpOnline);
        }
        totalOnline = plugin.visibleOnlineCount(viewer, totalOnline, ignored -> true);
        hubOnline = plugin.visibleOnlineCount(viewer, hubOnline, p -> !plugin.isSmpGameplayWorld(p.getWorld()));
        smpOnline = plugin.visibleOnlineCount(viewer, smpOnline, p -> plugin.isSmpGameplayWorld(p.getWorld()));
        long kills = stats == null ? 0L : stats.getStat(viewer.getUniqueId(), "kills");
        long deaths = stats == null ? 0L : stats.getStat(viewer.getUniqueId(), "deaths");
        String playtime = stats == null ? "0m" : stats.getFormattedPlaytime(viewer.getUniqueId());
        return (input == null ? "" : input)
                .replace("%online%", Integer.toString(totalOnline))
                .replace("%hub_online%", Integer.toString(hubOnline))
                .replace("%smp_online%", Integer.toString(smpOnline))
                .replace("%player%", viewer.getName())
                .replace("%kills%", Long.toString(kills))
                .replace("%deaths%", Long.toString(deaths))
                .replace("%playtime%", playtime)
                .replace("%coins%", coins)
                .replace("%lumis%", lumis);
    }

    private Component parse(String input) { return miniMessage.deserialize(input == null ? "" : input); }
}
