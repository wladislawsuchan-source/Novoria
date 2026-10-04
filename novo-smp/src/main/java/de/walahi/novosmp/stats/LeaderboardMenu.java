package de.walahi.novosmp.stats;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.gui.Gui;
import de.walahi.smpcore.gui.GuiButton;
import de.walahi.smpcore.gui.ItemBuilder;
import de.walahi.smpcore.gui.MaterialResolver;
import de.walahi.smpcore.gui.MiniMessageItems;
import de.walahi.smpcore.gui.PageSlice;
import de.walahi.smpcore.gui.SlotLayout;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Main leaderboard selector and paginated category lists. Categories come only from menus.yml. */
final class LeaderboardMenu {
    private final NovoSMPPlugin plugin;
    private final StatsCache cache;
    private final StatsQueryService queries;
    private final StatsFormatter formatter;
    private final LeaderboardProfileCache profiles;
    private final MiniMessageItems items = new MiniMessageItems();
    private final Set<String> warnedOverallSettings = new HashSet<>();

    LeaderboardMenu(NovoSMPPlugin plugin, StatsCache cache,
                    StatsQueryService queries, StatsFormatter formatter) {
        this.plugin = plugin;
        this.cache = cache;
        this.queries = queries;
        this.formatter = formatter;
        this.profiles = plugin.leaderboardProfiles();
    }

    void open(Player viewer) {
        int rows = Math.max(3, Math.min(6,
                plugin.configs().menus().getInt("leaderboards.menu.rows", 4)));
        int size = rows * 9;
        Gui menu = new Gui(rows, items.component(plugin.configs().menus().getString(
                "leaderboards.menu.title", "<dark_gray>Bestenlisten</dark_gray>")));

        ConfigurationSection root = plugin.configs().menus().getConfigurationSection("leaderboards.menu.items");
        for (ConfiguredStatMenuItem category : ConfiguredStatMenuItem.load(
                root, size, plugin.getLogger(), "leaderboards.menu.items")) {
            if (isOverall(category) && !plugin.configs().menus().getBoolean("leaderboards.overall.enabled", true))
                continue;
            menu.button(category.slot(), GuiButton.of(categoryItem(category), event -> {
                playSound(viewer, "leaderboards.list.sounds.open",
                        Sound.BLOCK_CHEST_OPEN, 0.7f, 1.2f);
                openPage(viewer, category, 0);
            }));
        }

        if (plugin.configs().menus().getBoolean("leaderboards.menu.filler.enabled", false)) {
            menu.filler(MaterialResolver.resolve(
                    plugin.configs().menus().getString("leaderboards.menu.filler.material"),
                    Material.GRAY_STAINED_GLASS_PANE));
        }
        menu.open(viewer);
    }

    private void openPage(Player viewer, ConfiguredStatMenuItem category, int requestedPage) {
        List<StatsSnapshot> entries = queries.visibleEntries();
        StatsSnapshot ownEntry = cache.snapshot(viewer.getUniqueId(), viewer.getName());
        Board board = isOverall(category) ? overallBoard(entries) : normalBoard(category, entries, ownEntry);
        entries = board.entries();

        int rows = Math.max(2, Math.min(6,
                plugin.configs().menus().getInt("leaderboards.list.rows", 6)));
        int size = rows * 9;
        List<Integer> fallbackSlots = SlotLayout.range(0, Math.max(0, size - 10));
        List<Integer> contentSlots = SlotLayout.configured(
                plugin.configs().menus(), "leaderboards.list.content-slots", size, fallbackSlots);
        PageSlice<StatsSnapshot> page = PageSlice.of(entries, requestedPage, contentSlots.size());

        String title = plugin.configs().menus().getString(
                        "leaderboards.list.title",
                        "<dark_gray>%category% <gray>(Seite %page%/%pages%)</gray>")
                .replace("%category%", category.name())
                .replace("%page%", Integer.toString(page.page() + 1))
                .replace("%pages%", Integer.toString(page.pageCount()));
        Gui gui = new Gui(rows, items.component(title));

        List<PendingHead> pendingHeads = new ArrayList<>();
        int globalStart = page.page() * contentSlots.size();
        for (int index = 0; index < page.entries().size(); index++) {
            int slot = contentSlots.get(index);
            StatsSnapshot entry = page.entries().get(index);
            int rank = globalStart + index + 1;
            String value = board.value(entry.uuid());
            gui.item(slot, playerHead(category, entry, rank, value, board.categories()));
            pendingHeads.add(new PendingHead(slot, entry, rank, false, 0, value, board.categories()));
        }

        int ownRank = 0;
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).uuid().equals(viewer.getUniqueId())) {
                ownRank = index + 1;
                ownEntry = entries.get(index);
                break;
            }
        }

        int ownSlot = navigationSlot("leaderboards.list.own-head.slot", 49, size);
        String ownValue = board.value(ownEntry.uuid());
        gui.item(ownSlot, ownHead(category, ownEntry, ownRank, entries.size(), ownValue, board.categories()));
        pendingHeads.add(new PendingHead(ownSlot, ownEntry, ownRank, true, entries.size(), ownValue,
                board.categories()));

        int backSlot = navigationSlot("leaderboards.list.back.slot", 45, size);
        gui.button(backSlot, GuiButton.of(simpleItem(
                "leaderboards.list.back", Material.BARRIER, "<red>Zurück</red>"), event -> {
            playSound(viewer, "leaderboards.list.sounds.back", Sound.UI_BUTTON_CLICK, 1.0f, 0.8f);
            open(viewer);
        }));

        if (page.hasPrevious()) {
            int previousSlot = navigationSlot("leaderboards.list.previous.slot", 48, size);
            gui.button(previousSlot, GuiButton.of(simpleItem(
                    "leaderboards.list.previous", Material.ARROW, "<yellow>Vorherige Seite</yellow>"), event -> {
                playSound(viewer, "leaderboards.list.sounds.page", Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
                openPage(viewer, category, page.page() - 1);
            }));
        }
        if (page.hasNext()) {
            int nextSlot = navigationSlot("leaderboards.list.next.slot", 53, size);
            gui.button(nextSlot, GuiButton.of(simpleItem(
                    "leaderboards.list.next", Material.ARROW, "<yellow>Nächste Seite</yellow>"), event -> {
                playSound(viewer, "leaderboards.list.sounds.page", Sound.UI_BUTTON_CLICK, 1.0f, 1.2f);
                openPage(viewer, category, page.page() + 1);
            }));
        }
        Inventory inventory = gui.createInventory();
        viewer.openInventory(inventory);
        refreshPendingHeads(viewer, inventory, category, pendingHeads);
    }

    private Board normalBoard(ConfiguredStatMenuItem category, List<StatsSnapshot> entries,
                              StatsSnapshot ownEntry) {
        StatsQueryService.Values values = queries.values(entries, category.statPath(), ownEntry);
        if (plugin.configs().menus().getBoolean("leaderboards.list.hide-zero-values", false))
            entries.removeIf(entry -> values.value(entry.uuid()) <= 0L);
        values.sortLeaderboardEntries(entries);
        Map<UUID, String> formatted = new HashMap<>();
        for (StatsSnapshot entry : entries)
            formatted.put(entry.uuid(), value(category, values.value(entry.uuid())));
        formatted.put(ownEntry.uuid(), value(category, values.value(ownEntry.uuid())));
        return new Board(entries, formatted, 0);
    }

    private Board overallBoard(List<StatsSnapshot> visible) {
        List<ConfiguredStatMenuItem> included = overallCategories();
        List<StatsQueryService.Values> values = new ArrayList<>(included.size());
        for (ConfiguredStatMenuItem category : included)
            values.add(queries.values(visible, category.statPath(), null));
        List<OverallRanking.Entry> ranked = OverallRanking.calculate(visible, values);
        List<StatsSnapshot> sorted = new ArrayList<>(ranked.size());
        Map<UUID, String> formatted = new HashMap<>(ranked.size());
        for (OverallRanking.Entry entry : ranked) {
            sorted.add(entry.player());
            formatted.put(entry.player().uuid(), formatter.averageRank(entry.rankSum(), entry.categories()));
        }
        return new Board(sorted, formatted, included.size());
    }

    private List<ConfiguredStatMenuItem> overallCategories() {
        ConfigurationSection root = plugin.configs().menus().getConfigurationSection("leaderboards.menu.items");
        if (root == null) return List.of();
        int rows = Math.max(3, Math.min(6, plugin.configs().menus().getInt("leaderboards.menu.rows", 4)));
        Map<String, ConfiguredStatMenuItem> available = new HashMap<>();
        for (ConfiguredStatMenuItem item : ConfiguredStatMenuItem.load(
                root, rows * 9, plugin.getLogger(), "leaderboards.menu.items"))
            if (!isOverall(item)) available.put(item.key(), item);

        List<ConfiguredStatMenuItem> included = new ArrayList<>();
        Set<String> usedPaths = new HashSet<>();
        for (String key : plugin.configs().menus().getStringList("leaderboards.overall.included")) {
            ConfiguredStatMenuItem item = available.get(key);
            if (item == null) {
                warnOverall("Kategorie '" + key + "' fehlt, ist deaktiviert oder ungültig.");
                continue;
            }
            String path = StatsQueryService.normalizePath(item.statPath());
            if (!StatsQueryService.supportsPath(path)) {
                warnOverall("Kategorie '" + key + "' hat einen unbekannten Stat-Pfad: " + path);
                continue;
            }
            if (!usedPaths.add(path)) {
                warnOverall("Kategorie '" + key + "' nutzt einen bereits gewerteten Stat-Pfad: " + path);
                continue;
            }
            included.add(item);
        }
        if (included.isEmpty()) warnOverall("Keine gültigen Kategorien konfiguriert.");
        return included;
    }

    private void warnOverall(String message) {
        if (warnedOverallSettings.add(message))
            plugin.getLogger().warning("leaderboards.overall: " + message);
    }

    private boolean isOverall(ConfiguredStatMenuItem category) {
        return category.key().equals("overall");
    }

    private void refreshPendingHeads(Player viewer, Inventory inventory, ConfiguredStatMenuItem category,
                                     List<PendingHead> pendingHeads) {
        for (PendingHead pending : pendingHeads) {
            if (profiles.cached(pending.entry().uuid()) != null) continue;
            profiles.resolve(pending.entry().uuid(), pending.entry().name(), profile -> {
                if (!viewer.isOnline()) return;
                if (viewer.getOpenInventory().getTopInventory() != inventory) return;

                ItemStack refreshed = pending.own()
                        ? ownHead(category, pending.entry(), pending.rank(), pending.total(), pending.value(),
                                pending.categories())
                        : playerHead(category, pending.entry(), pending.rank(), pending.value(),
                                pending.categories());
                inventory.setItem(pending.slot(), refreshed);
            });
        }
    }

    private ItemStack categoryItem(ConfiguredStatMenuItem category) {
        List<String> lore = category.lore();
        return items.item(category.material(), category.name(), lore);
    }

    private ItemStack playerHead(ConfiguredStatMenuItem category, StatsSnapshot entry, int rank,
                                 String value, int categories) {
        Map<String, String> placeholders = Map.of(
                "%rank%", Integer.toString(rank),
                "%player%", formatter.escape(entry.name()),
                "%value%", value,
                "%average%", value,
                "%category%", category.key(),
                "%categories%", Integer.toString(categories)
        );
        String base = isOverall(category) ? "leaderboards.overall.player-head" : "leaderboards.list.player-head";
        List<String> lore = plugin.configs().menus().getStringList(base + ".lore");
        if (lore.isEmpty()) lore = List.of("<gray>Wert: <yellow>%value%");
        ItemStack head = playerHead(entry);
        Component name = items.component(plugin.configs().menus().getString(
                base + ".name", "<yellow>#%rank% <white>%player%</white>"), placeholders);
        ItemStack result = ItemBuilder.from(head)
                .name(name)
                .lore(items.lore(lore, placeholders))
                .editMeta(meta -> meta.itemName(name))
                .build();
        hideSkullProfileTooltip(result);
        return result;
    }

    private ItemStack ownHead(ConfiguredStatMenuItem category, StatsSnapshot entry, int rank, int total,
                              String value, int categories) {
        Map<String, String> placeholders = Map.of(
                "%rank%", rank <= 0 ? "-" : "#" + rank,
                "%player%", formatter.escape(entry.name()),
                "%value%", value,
                "%average%", value,
                "%total%", Integer.toString(total),
                "%category%", category.key(),
                "%categories%", Integer.toString(categories)
        );
        String base = isOverall(category) ? "leaderboards.overall.own-head" : "leaderboards.list.own-head";
        List<String> lore = plugin.configs().menus().getStringList(base + ".lore");
        if (lore.isEmpty()) {
            lore = List.of("<gray>Wert: <yellow>%value%", "<gray>Platz: <green>%rank%</green>");
        }
        ItemStack head = playerHead(entry);
        Component name = items.component(plugin.configs().menus().getString(
                base + ".name", "<gold>Deine Statistik</gold>"), placeholders);
        ItemStack result = ItemBuilder.from(head)
                .name(name)
                .lore(items.lore(lore, placeholders))
                .editMeta(meta -> meta.itemName(name))
                .build();
        hideSkullProfileTooltip(result);
        return result;
    }

    private String value(ConfiguredStatMenuItem category, long rawValue) {
        return category.format() == ConfiguredStatMenuItem.ValueFormat.PLAYTIME
                ? formatter.playtime(rawValue)
                : formatter.number(rawValue);
    }

    private ItemStack playerHead(StatsSnapshot entry) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        org.bukkit.profile.PlayerProfile profile = profiles.cached(entry.uuid());
        if (profile != null) meta.setOwnerProfile(profile);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack simpleItem(String base, Material fallbackMaterial, String fallbackName) {
        Material material = MaterialResolver.resolve(
                plugin.configs().menus().getString(base + ".material"), fallbackMaterial);
        return items.item(material,
                plugin.configs().menus().getString(base + ".name", fallbackName), List.of());
    }

    private int navigationSlot(String path, int fallback, int inventorySize) {
        int safeFallback = fallback < inventorySize ? fallback : Math.max(0, inventorySize - 1);
        return SlotLayout.valid(plugin.configs().menus().getInt(path, safeFallback), inventorySize, safeFallback);
    }

    private void playSound(Player player, String path, Sound fallback,
                           float fallbackVolume, float fallbackPitch) {
        if (!plugin.configs().menus().getBoolean(path + ".enabled", true)) return;
        String configured = plugin.configs().menus().getString(path + ".sound", fallback.name());
        try {
            Sound sound = Sound.valueOf(configured.toUpperCase(Locale.ROOT));
            float volume = (float) plugin.configs().menus().getDouble(path + ".volume", fallbackVolume);
            float pitch = (float) plugin.configs().menus().getDouble(path + ".pitch", fallbackPitch);
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Ungültiger Leaderboard-Sound bei " + path + ": " + configured);
        }
    }

    private record Board(List<StatsSnapshot> entries, Map<UUID, String> formatted, int categories) {
        String value(UUID playerId) { return formatted.getOrDefault(playerId, "-"); }
    }

    private record PendingHead(int slot, StatsSnapshot entry, int rank, boolean own, int total,
                               String value, int categories) { }

    private void hideSkullProfileTooltip(ItemStack head) {
        head.setData(
                DataComponentTypes.TOOLTIP_DISPLAY,
                TooltipDisplay.tooltipDisplay()
                        .addHiddenComponents(DataComponentTypes.PROFILE)
                        .build()
        );
    }
}
