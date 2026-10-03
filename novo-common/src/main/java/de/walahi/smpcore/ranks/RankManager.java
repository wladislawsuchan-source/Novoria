package de.walahi.smpcore.ranks;

import de.walahi.smpcore.SMPCorePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verwaltet Rangauflösung, sichtbare Tab-Namen und die feste Reihenfolge.
 * LuckPerms ist die einzige Quelle für die Gruppenzugehörigkeit. Darstellung,
 * Farben und Reihenfolge bleiben vollständig in ranks.yml konfigurierbar.
 */
public final class RankManager {
    private static final Pattern HEX_COLOR = Pattern.compile("<#([0-9a-fA-F]{6})>");
    private static final String MANAGED_TEAM_PREFIX = "sc";

    private final SMPCorePlugin plugin;
    private final LuckPerms luckPerms;
    private final List<RankDefinition> ranks = new ArrayList<>();
    private EventSubscription<UserDataRecalculateEvent> luckPermsSubscription;
    private Function<Player, Component> tabSuffixProvider = player -> Component.empty();

    public RankManager(SMPCorePlugin plugin) {
        this.plugin = plugin;
        this.luckPerms = LuckPermsProvider.get();
        subscribeToLuckPerms();
        reload();
    }

    private void subscribeToLuckPerms() {
        luckPermsSubscription = luckPerms.getEventBus().subscribe(
                plugin,
                UserDataRecalculateEvent.class,
                event -> refreshPlayer(event.getUser().getUniqueId())
        );
    }

    private void refreshPlayer(UUID uniqueId) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(uniqueId);
            if (player != null && player.isOnline()) {
                applyAll();
                if (plugin.isSmpServer() && plugin.getStaffCommands() != null) {
                    plugin.getStaffCommands().refreshDndVisibilityFor(player);
                }
            }
        });
    }

    public void reload() {
        plugin.configs().ranksFile().reload();
        YamlConfiguration yaml = plugin.configs().ranksFile().yaml();
        ranks.clear();
        ConfigurationSection section = yaml.getConfigurationSection("ranks");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection rank = section.getConfigurationSection(key);
                if (rank == null || !rank.getBoolean("enabled", true)) continue;

                String legacyFormat = rank.getString("tab-format", "");
                String legacyColor = extractColor(legacyFormat, "#AAAAAA");
                String prefix = rank.getString("prefix", rank.getString("display", key.toUpperCase(Locale.ROOT)));
                boolean defaultShowPrefix = !prefix.isBlank() && !key.equalsIgnoreCase("player");

                String prefixColor = normalizeColor(rank.getString("prefix-color", legacyColor), legacyColor);
                String nameColor = normalizeColor(rank.getString("name-color", legacyColor), legacyColor);

                if (key.equalsIgnoreCase("developer")) {
                    if (prefixColor.equalsIgnoreCase("#AAAAAA")) prefixColor = "#55FFFF";
                    if (nameColor.equalsIgnoreCase("#AAAAAA")) nameColor = "#55FFFF";
                }

                ranks.add(new RankDefinition(
                        key,
                        rank.getInt("priority", 999),
                        resolveConfiguredGroup(rank, key),
                        prefix,
                        prefixColor,
                        nameColor,
                        rank.getBoolean("italic", false),
                        rank.getBoolean("show-prefix", defaultShowPrefix)
                ));
            }
        }
        ranks.sort(Comparator.comparingInt(RankDefinition::priority).thenComparing(RankDefinition::key));
        applyAll();
    }

    /**
     * Unterstützt beim ersten Start noch alte permission:-Dateien, ohne diese
     * Permissions weiter auszuwerten. Daraus wird lediglich der Gruppenname
     * abgeleitet, damit bestehende ranks.yml optisch unverändert weiterläuft.
     */
    private String resolveConfiguredGroup(ConfigurationSection rank, String key) {
        String configured = rank.getString("group", "").trim().toLowerCase(Locale.ROOT);
        if (!configured.isBlank()) return configured;

        String legacyPermission = rank.getString("permission", "").trim().toLowerCase(Locale.ROOT);
        if (legacyPermission.startsWith("smpcore.rank.")) {
            return legacyPermission.substring("smpcore.rank.".length());
        }

        if (key.equalsIgnoreCase("player")) return "default";
        return key.replace("-", "").toLowerCase(Locale.ROOT);
    }

    public RankDefinition resolve(Player player) {
        return resolve(player.getUniqueId());
    }

    public RankDefinition resolve(UUID playerId) {
        User user = luckPerms.getUserManager().getUser(playerId);
        if (user == null) return fallbackPlayerRank();

        return resolve(user);
    }

    /** Moderation must load persisted groups instead of treating an unloaded user as a player. */
    public Optional<RankDefinition> resolveForModeration(UUID playerId) {
        try {
            User user = luckPerms.getUserManager().getUser(playerId);
            if (user == null) user = luckPerms.getUserManager().loadUser(playerId).join();
            if (user == null) throw new IllegalStateException("LuckPerms hat keinen User geliefert");
            return Optional.of(resolve(user));
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Moderationsrang für " + playerId + " konnte nicht geladen werden; "
                    + "Hierarchieaktion wird abgelehnt: " + exception.getMessage());
            return Optional.empty();
        }
    }

    private RankDefinition resolve(User user) {

        Set<String> inheritedGroups = inheritedGroups(user);

        for (RankDefinition rank : ranks) {
            if (inheritedGroups.contains(rank.group())) return rank;
        }
        return fallbackPlayerRank();
    }

    /** Checks effective LuckPerms inheritance instead of relying on the visually highest rank. */
    public boolean hasGroup(Player player, String groupName) {
        if (player == null || groupName == null || groupName.isBlank()) return false;
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        return user != null && inheritedGroups(user).contains(groupName.trim().toLowerCase(Locale.ROOT));
    }

    /** Resolves effective groups for an offline UUID when clan leadership is transferred. */
    public boolean hasGroup(UUID playerId, String groupName) {
        if (playerId == null || groupName == null || groupName.isBlank()) return false;
        try {
            User user = luckPerms.getUserManager().getUser(playerId);
            if (user == null) user = luckPerms.getUserManager().loadUser(playerId).join();
            return user != null && inheritedGroups(user).contains(groupName.trim().toLowerCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("LuckPerms-Gruppe für " + playerId + " konnte nicht geladen werden: " + exception.getMessage());
            return false;
        }
    }

    /** Premium+ is cumulative and therefore includes every normal Premium benefit. */
    public boolean hasPremium(Player player) {
        return hasGroup(player, "premium") || hasGroup(player, "premiumplus");
    }

    public boolean hasPremiumPlus(Player player) {
        return hasGroup(player, "premiumplus");
    }

    public boolean hasPremium(UUID playerId) {
        return hasGroup(playerId, "premium") || hasGroup(playerId, "premiumplus");
    }

    public boolean hasPremiumPlus(UUID playerId) {
        return hasGroup(playerId, "premiumplus");
    }

    private Set<String> inheritedGroups(User user) {
        Set<String> inheritedGroups = new HashSet<>();
        for (Group group : user.getInheritedGroups(user.getQueryOptions())) {
            inheritedGroups.add(group.getName().toLowerCase(Locale.ROOT));
        }
        return inheritedGroups;
    }

    public RankDefinition fallbackPlayerRank() {
        for (RankDefinition rank : ranks) {
            if (rank.key().equalsIgnoreCase("player") || rank.group().equalsIgnoreCase("default")) return rank;
        }
        return new RankDefinition("player", 999, "default", "", "#AAAAAA", "#AAAAAA", false, false);
    }

    public Component tabName(Player player) {
        return tabName(resolve(player), player.getName());
    }

    /** Adds a server-specific suffix behind the player name without replacing rank formatting. */
    public void tabSuffixProvider(Function<Player, Component> provider) {
        tabSuffixProvider = provider == null ? player -> Component.empty() : provider;
        applyAll();
    }

    /** Returns only the player name in the configured rank name color. */
    public Component coloredName(Player player) {
        return coloredName(player.getUniqueId(), player.getName());
    }

    /** Returns a cached/offline player's name in the configured rank color. */
    public Component coloredName(UUID playerId, String playerName) {
        RankDefinition rank = resolve(playerId);
        return Component.text(playerName)
                .color(color(rank.nameColor()))
                .decoration(TextDecoration.BOLD, false)
                .decoration(TextDecoration.ITALIC, false);
    }

    /** Returns arbitrary text in the player's configured rank name color. */
    public Component coloredText(Player player, String text) {
        RankDefinition rank = resolve(player);
        return Component.text(text)
                .color(color(rank.nameColor()))
                .decoration(TextDecoration.BOLD, false)
                .decoration(TextDecoration.ITALIC, false);
    }

    public Component tabName(RankDefinition rank, String playerName) {
        String separator = plugin.configs().scoreboards().getString("tablist.prefix-style.separator", " ");
        String mode = plugin.configs().scoreboards().getString("tablist.prefix-style.mode", "compact").toLowerCase(Locale.ROOT);
        boolean globalBold = plugin.configs().scoreboards().getBoolean("tablist.prefix-style.bold", true);

        Component result = Component.empty();
        if (rank.showPrefix() && !rank.prefix().isBlank()) {
            String visiblePrefix = switch (mode) {
                case "normal" -> rank.prefix();
                case "mini", "abbreviated" -> abbreviate(rank.prefix());
                case "compact", "smallcaps" -> toSmallCaps(rank.prefix());
                default -> toSmallCaps(rank.prefix());
            };

            Component prefix = Component.text(visiblePrefix)
                    .color(color(rank.prefixColor()))
                    .decoration(TextDecoration.BOLD, globalBold)
                    .decoration(TextDecoration.ITALIC, rank.italic());
            result = result.append(prefix).append(Component.text(separator));
        }

        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null && plugin.getBuildModeManager() != null && plugin.getBuildModeManager().isActive(online)
                && plugin.getCommandConfiguration().getBoolean("build-mode.tag.enabled", true)
                && plugin.getCommandConfiguration().getBoolean("build-mode.tag.show-in-tab", true)) {
            String format = plugin.getCommandConfiguration().getString("build-mode.tag.format", "<gray>🛠</gray> ");
            result = result.append(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(format));
        }
        if (online != null && plugin.getAfkManager() != null && plugin.getAfkManager().isAfk(online)
                && plugin.configs().main().getBoolean("afk.tab.enabled", true)) {
            String afkFormat = plugin.configs().main().getString("afk.tab.format", "<gray>(ᴀꜰᴋ)</gray> ");
            result = result.append(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(afkFormat));
        }
        boolean privatelyHidden = online != null && plugin.getStaffCommands() != null
                && plugin.getStaffCommands().isHiddenFromPlayers(online);
        if (privatelyHidden && plugin.configs().main().getBoolean("vanish.visuals.tab.enabled", true)) {
            TextColor faded = color(plugin.configs().main().getString("vanish.visuals.tab.name-color", "#777777"));
            return result.color(faded)
                    .decoration(TextDecoration.BOLD, false)
                    .decoration(TextDecoration.ITALIC, plugin.configs().main().getBoolean("vanish.visuals.tab.italic", true))
                    .append(Component.text(playerName).color(faded)
                            .decoration(TextDecoration.BOLD, false)
                            .decoration(TextDecoration.ITALIC, plugin.configs().main().getBoolean("vanish.visuals.tab.italic", true)))
                    .append(tabSuffixProvider.apply(online));
        }
        Component name = Component.text(playerName)
                .color(color(rank.nameColor()))
                .decoration(TextDecoration.BOLD, false)
                .decoration(TextDecoration.ITALIC, false);
        return result.append(name).append(online == null ? Component.empty() : tabSuffixProvider.apply(online));
    }

    private TextColor color(String hex) {
        TextColor parsed = TextColor.fromHexString(hex);
        return parsed == null ? TextColor.color(0xAAAAAA) : parsed;
    }

    private String abbreviate(String display) {
        return switch (display.toUpperCase(Locale.ROOT)) {
            case "DEVELOPER" -> "DEV";
            case "SR.MODERATOR" -> "SR.MOD";
            case "MODERATOR" -> "MOD";
            case "SUPPORTER" -> "SUP";
            case "PREMIUM+" -> "PREM+";
            case "PREMIUM" -> "PREM";
            default -> display;
        };
    }

    private String toSmallCaps(String input) {
        String normal = "abcdefghijklmnopqrstuvwxyz";
        String small =  "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀѕᴛᴜᴠᴡхʏᴢ";
        StringBuilder out = new StringBuilder(input.length());
        for (char raw : input.toLowerCase(Locale.ROOT).toCharArray()) {
            int index = normal.indexOf(raw);
            out.append(index >= 0 ? small.charAt(index) : raw);
        }
        return out.toString();
    }

    public List<RankDefinition> getRanks() {
        return List.copyOf(ranks);
    }

    /** Niedrige priority = weiter oben; innerhalb desselben Rangs alphabetisch. */
    public void applyAll() {
        List<Player> sorted = new ArrayList<>(Bukkit.getOnlinePlayers());
        sorted.sort(Comparator
                .comparingInt((Player player) -> resolve(player).priority())
                .thenComparing(Player::getName, String.CASE_INSENSITIVE_ORDER));

        Set<Scoreboard> scoreboards = new LinkedHashSet<>();
        if (Bukkit.getScoreboardManager() != null) {
            scoreboards.add(Bukkit.getScoreboardManager().getMainScoreboard());
        }
        for (Player online : Bukkit.getOnlinePlayers()) scoreboards.add(online.getScoreboard());

        for (Player player : sorted) {
            RankDefinition rank = resolve(player);
            applyTeams(player, rank, scoreboards);
            // listPlayer can refresh client-side TAB entries without a reliably readable
            // client-side name/order state. Keep these writes as the existing repair path.
            player.playerListName(tabName(player));
            try {
                // Bei Paper steht der höhere List-Order-Wert weiter oben. Die ranks.yml
                // bleibt trotzdem wie bisher aufgebaut: OWNER=1, ADMIN=2, ... PLAYER=999.
                player.setPlayerListOrder(listOrder(rank.priority()));
            } catch (NoSuchMethodError ignored) {
                // Scoreboard-Teamname bleibt als Fallback aktiv.
            }
        }
    }

    public void apply(Player player) {
        applyAll();
    }

    private void applyTeams(Player player, RankDefinition rank, Set<Scoreboard> scoreboards) {
        for (Scoreboard scoreboard : scoreboards) {
            applyTeam(player, rank, scoreboard);
        }
    }

    /** Restores one player's normal rank/name-tag team on one specific viewer scoreboard. */
    public void restoreEntry(Player player, Scoreboard scoreboard) {
        if (player == null || scoreboard == null) return;
        applyTeam(player, resolve(player), scoreboard);
    }

    private void applyTeam(Player player, RankDefinition rank, Scoreboard scoreboard) {
        String targetTeamName = teamName(rank);
        for (Team team : new ArrayList<>(scoreboard.getTeams())) {
            if (isManagedTeam(team.getName()) && team.hasEntry(player.getName())
                    && !team.getName().equals(targetTeamName)) {
                team.removeEntry(player.getName());
            }
        }
        Team target = scoreboard.getTeam(targetTeamName);
        if (target == null) target = scoreboard.registerNewTeam(targetTeamName);

        // Nametag über dem Skin: gleicher Rang-Prefix wie in der Tablist,
        // der eigentliche Spielername bleibt unabhängig vom Rang immer grau.
        Component namePrefix = nameTagPrefix(rank);
        if (plugin.getStaffCommands() != null && plugin.getStaffCommands().isVanished(player)
                && plugin.configs().main().getBoolean("vanish.visuals.nametag.enabled", true)) {
            String marker = plugin.configs().main().getString("vanish.visuals.nametag.marker", "<red><bold>V</bold></red> ");
            namePrefix = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(marker).append(namePrefix);
        }
        if (plugin.getBuildModeManager() != null && plugin.getBuildModeManager().isActive(player)
                && plugin.getCommandConfiguration().getBoolean("build-mode.tag.enabled", true)
                && plugin.getCommandConfiguration().getBoolean("build-mode.tag.show-in-nametag", true)) {
            String format = plugin.getCommandConfiguration().getString("build-mode.tag.format", "<gray>🛠</gray> ");
            namePrefix = namePrefix.append(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(format));
        }
        if (!namePrefix.equals(target.prefix())) target.prefix(namePrefix);
        if (!Component.empty().equals(target.suffix())) target.suffix(Component.empty());
        applyTeamColor(target, NamedTextColor.GRAY);
        if (!target.hasEntry(player.getName())) target.addEntry(player.getName());
    }

    static void applyTeamColor(Team team, NamedTextColor desiredColor) {
        if (!team.hasColor() || !desiredColor.equals(team.color())) team.color(desiredColor);
    }

    private Component nameTagPrefix(RankDefinition rank) {
        if (!rank.showPrefix() || rank.prefix().isBlank()) return Component.empty();

        String separator = plugin.configs().scoreboards().getString("tablist.prefix-style.separator", " ");
        String mode = plugin.configs().scoreboards().getString("tablist.prefix-style.mode", "compact").toLowerCase(Locale.ROOT);
        boolean globalBold = plugin.configs().scoreboards().getBoolean("tablist.prefix-style.bold", true);

        String visiblePrefix = switch (mode) {
            case "normal" -> rank.prefix();
            case "mini", "abbreviated" -> abbreviate(rank.prefix());
            case "compact", "smallcaps" -> toSmallCaps(rank.prefix());
            default -> toSmallCaps(rank.prefix());
        };

        Component base = Component.text(visiblePrefix)
                .color(color(rank.prefixColor()))
                .decoration(TextDecoration.BOLD, globalBold)
                .decoration(TextDecoration.ITALIC, rank.italic())
                .append(Component.text(separator));
        return base;
    }

    private int listOrder(int priority) {
        return Math.max(0, 1000 - clampPriority(priority));
    }

    private boolean isManagedTeam(String name) {
        return name.startsWith(MANAGED_TEAM_PREFIX)
                || name.startsWith("smp")
                || name.startsWith("smp_rank_")
                || name.startsWith("r");
    }

    public String teamName(RankDefinition rank) {
        String safe = rank.key().replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
        if (safe.length() > 10) safe = safe.substring(0, 10);
        return MANAGED_TEAM_PREFIX + String.format("%03d", clampPriority(rank.priority())) + safe;
    }

    private int clampPriority(int priority) {
        return Math.max(0, Math.min(999, priority));
    }

    private String extractColor(String format, String fallback) {
        Matcher matcher = HEX_COLOR.matcher(format == null ? "" : format);
        return matcher.find() ? "#" + matcher.group(1).toUpperCase(Locale.ROOT) : fallback;
    }

    private String normalizeColor(String color, String fallback) {
        String value = color == null ? fallback : color.trim();
        if (value.equalsIgnoreCase("aqua")) return "#55FFFF";
        if (value.equalsIgnoreCase("dark_green")) return "#00AA00";
        if (value.equalsIgnoreCase("green")) return "#55FF55";
        if (value.equalsIgnoreCase("gray") || value.equalsIgnoreCase("grey")) return "#AAAAAA";
        if (!value.startsWith("#")) value = "#" + value;
        if (!value.matches("#[0-9a-fA-F]{6}")) return fallback;
        return value.toUpperCase(Locale.ROOT);
    }

    public record RankDefinition(
            String key,
            int priority,
            String group,
            String prefix,
            String prefixColor,
            String nameColor,
            boolean italic,
            boolean showPrefix
    ) {}
}
