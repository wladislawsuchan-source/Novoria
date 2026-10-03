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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Main leaderboard selector and paginated category lists. Categories come only from menus.yml. */
final class LeaderboardMenu {
    private final NovoSMPPlugin plugin;
    private final StatsCache cache;
    private final StatsQueryService queries;
    private final StatsFormatter formatter;
    private final LeaderboardProfileCache profiles;
    private final MiniMessageItems items = new MiniMessageItems();

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
        StatsQueryService.Values values = queries.values(entries, category.statPath(), ownEntry);
        if (plugin.configs().menus().getBoolean("leaderboards.list.hide-zero-values", false)) {
            entries.removeIf(entry -> values.value(entry.uuid()) <= 0L);
        }
        entries.sort(Comparator
                .comparingLong((StatsSnapshot entry) -> values.value(entry.uuid()))
                .reversed()
                .thenComparing(Comparator.comparingLong(StatsSnapshot::lastSeen).reversed())
                .thenComparing(StatsSnapshot::name, String.CASE_INSENSITIVE_ORDER));

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
            long rawValue = values.value(entry.uuid());
            gui.item(slot, playerHead(category, entry, rank, rawValue));
            pendingHeads.add(new PendingHead(slot, entry, rank, false, 0, rawValue));
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
        long ownValue = values.value(ownEntry.uuid());
        gui.item(ownSlot, ownHead(category, ownEntry, ownRank, entries.size(), ownValue));
        pendingHeads.add(new PendingHead(ownSlot, ownEntry, ownRank, true, entries.size(), ownValue));

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

    private void refreshPendingHeads(Player viewer, Inventory inventory, ConfiguredStatMenuItem category,
                                     List<PendingHead> pendingHeads) {
        for (PendingHead pending : pendingHeads) {
            if (profiles.cached(pending.entry().uuid()) != null) continue;
            profiles.resolve(pending.entry().uuid(), pending.entry().name(), profile -> {
                if (!viewer.isOnline()) return;
                if (viewer.getOpenInventory().getTopInventory() != inventory) return;

                ItemStack refreshed = pending.own()
                        ? ownHead(category, pending.entry(), pending.rank(), pending.total(), pending.value())
                        : playerHead(category, pending.entry(), pending.rank(), pending.value());
                inventory.setItem(pending.slot(), refreshed);
            });
        }
    }

    private ItemStack categoryItem(ConfiguredStatMenuItem category) {
        List<String> lore = category.lore();
        return items.item(category.material(), category.name(), lore);
    }

    private ItemStack playerHead(ConfiguredStatMenuItem category, StatsSnapshot entry, int rank, long rawValue) {
        String value = value(category, rawValue);
        Map<String, String> placeholders = Map.of(
                "%rank%", Integer.toString(rank),
                "%player%", formatter.escape(entry.name()),
                "%value%", value,
                "%category%", category.key()
        );
        String base = "leaderboards.list.player-head";
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
                              long rawValue) {
        String value = value(category, rawValue);
        Map<String, String> placeholders = Map.of(
                "%rank%", rank <= 0 ? "-" : "#" + rank,
                "%player%", formatter.escape(entry.name()),
                "%value%", value,
                "%total%", Integer.toString(total),
                "%category%", category.key()
        );
        String base = "leaderboards.list.own-head";
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

    private record PendingHead(int slot, StatsSnapshot entry, int rank, boolean own, int total,
                               long value) { }

    private void hideSkullProfileTooltip(ItemStack head) {
        head.setData(
                DataComponentTypes.TOOLTIP_DISPLAY,
                TooltipDisplay.tooltipDisplay()
                        .addHiddenComponents(DataComponentTypes.PROFILE)
                        .build()
        );
    }
}
