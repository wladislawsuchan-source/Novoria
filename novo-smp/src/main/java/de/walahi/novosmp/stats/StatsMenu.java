package de.walahi.novosmp.stats;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.gui.Gui;
import de.walahi.smpcore.gui.MaterialResolver;
import de.walahi.smpcore.gui.MiniMessageItems;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Personal /stats inventory. Visible entries are defined exclusively in menus.yml. */
final class StatsMenu {
    private final NovoSMPPlugin plugin;
    private final StatsQueryService queries;
    private final StatsFormatter formatter;
    private final MiniMessageItems items = new MiniMessageItems();

    StatsMenu(NovoSMPPlugin plugin, StatsQueryService queries, StatsFormatter formatter) {
        this.plugin = plugin;
        this.queries = queries;
        this.formatter = formatter;
    }

    void open(Player viewer, StatsSnapshot target) {
        UUID uuid = target.uuid();
        List<StatsSnapshot> visibleEntries = queries.visibleEntries();
        int totalPlayers = visibleEntries.size();
        boolean ownStats = viewer.getUniqueId().equals(uuid);
        int rows = Math.max(3, Math.min(6, plugin.configs().menus().getInt("stats.menu.rows", 4)));
        int size = rows * 9;

        String titlePath = ownStats ? "stats.menu.title" : "stats.menu.other-title";
        String fallbackTitle = ownStats
                ? "<dark_gray>Deine Statistiken</dark_gray>"
                : "<dark_gray>Statistiken von <white>%player%</white></dark_gray>";
        String title = plugin.configs().menus().getString(titlePath, fallbackTitle)
                .replace("%player%", formatter.escape(target.name()));
        Gui menu = new Gui(rows, items.component(title));

        ConfigurationSection root = plugin.configs().menus().getConfigurationSection("stats.menu.items");
        List<ConfiguredStatMenuItem> configuredItems = ConfiguredStatMenuItem.load(
                root, size, plugin.getLogger(), "stats.menu.items");
        Set<String> rankedPaths = new HashSet<>();
        for (ConfiguredStatMenuItem configured : configuredItems) {
            if (usesPlaceholder(configured, "%rank%")) {
                rankedPaths.add(StatsQueryService.normalizePath(configured.statPath()));
            }
        }
        Map<String, StatsQueryService.Values> snapshots = new HashMap<>();
        for (ConfiguredStatMenuItem configured : configuredItems) {
            String path = StatsQueryService.normalizePath(configured.statPath());
            StatsQueryService.Values values = snapshots.computeIfAbsent(path, ignored -> queries.values(
                    rankedPaths.contains(path) ? visibleEntries : List.of(), path, target));
            long rawValue = values.value(uuid);
            int rank = usesPlaceholder(configured, "%rank%")
                    ? values.rank(uuid)
                    : 0;
            menu.item(configured.slot(), statItem(configured, rawValue, rank, totalPlayers));
        }

        if (plugin.configs().menus().getBoolean("stats.menu.filler.enabled", false)) {
            Material filler = MaterialResolver.resolve(
                    plugin.configs().menus().getString("stats.menu.filler.material"),
                    Material.GRAY_STAINED_GLASS_PANE);
            menu.filler(filler);
        }
        menu.open(viewer);
    }

    private boolean usesPlaceholder(ConfiguredStatMenuItem configured, String placeholder) {
        if (configured.name() != null && configured.name().contains(placeholder)) return true;
        return configured.lore().stream().anyMatch(line -> line != null && line.contains(placeholder));
    }

    private ItemStack statItem(ConfiguredStatMenuItem configured,
                               long rawValue,
                               int rank,
                               int totalPlayers) {
        String value = configured.format() == ConfiguredStatMenuItem.ValueFormat.PLAYTIME
                ? formatter.playtime(rawValue)
                : formatter.number(rawValue);
        // Keep presentation in menus.yml: Java only exposes the numeric rank.
        String rankText = rank <= 0 ? "" : Integer.toString(rank);
        Map<String, String> placeholders = Map.of(
                "%value%", value,
                "%rank%", rankText,
                "%total%", Integer.toString(totalPlayers)
        );
        List<String> lore = configured.lore();
        return items.item(configured.material(), 1,
                configured.name(), lore, placeholders);
    }
}
