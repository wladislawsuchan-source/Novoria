package de.walahi.novosmp.economy.worth;

import de.walahi.novosmp.economy.sell.SellManager;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.gui.Gui;
import de.walahi.smpcore.gui.GuiButton;
import de.walahi.smpcore.gui.ItemBuilder;
import de.walahi.smpcore.gui.MaterialResolver;
import de.walahi.smpcore.gui.MiniMessageItems;
import de.walahi.smpcore.gui.PageSlice;
import de.walahi.smpcore.gui.SlotLayout;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class WorthMenu implements Listener {
    private static final List<Integer> DEFAULT_CONTENT_SLOTS = SlotLayout.range(0, 44);

    private final SMPCorePlugin plugin;
    private final SellManager sell;
    private final WorthSignInput signInput;
    private final MiniMessageItems items = new MiniMessageItems();
    private final List<Material> materials;
    private final Map<UUID, Integer> pages = new ConcurrentHashMap<>();
    private final Map<UUID, String> searches = new ConcurrentHashMap<>();
    private final Map<UUID, Filter> filters = new ConcurrentHashMap<>();
    private final Map<UUID, Sort> sorts = new ConcurrentHashMap<>();

    public WorthMenu(SMPCorePlugin plugin, SellManager sell) {
        this.plugin = plugin;
        this.sell = sell;
        this.signInput = new WorthSignInput(plugin, this);
        this.materials = Arrays.stream(Material.values()).filter(this::allowed).toList();
        org.bukkit.Bukkit.getPluginManager().registerEvents(signInput, plugin);
        org.bukkit.Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        pages.remove(id);
        searches.remove(id);
        filters.remove(id);
        sorts.remove(id);
    }

    public void open(Player player) {
        render(player, pages.getOrDefault(player.getUniqueId(), 0));
    }

    void setSearch(Player player, String search) {
        if (search == null || search.isBlank()) searches.remove(player.getUniqueId());
        else searches.put(player.getUniqueId(), search.trim());
        pages.put(player.getUniqueId(), 0);
        render(player, 0);
    }

    private boolean allowed(Material material) {
        return sell.isWorthVisible(material);
    }

    private void render(Player player, int requestedPage) {
        UUID id = player.getUniqueId();
        Filter filter = filters.getOrDefault(id, Filter.ALL);
        Sort sort = sorts.getOrDefault(id, Sort.HIGH);
        String query = searches.getOrDefault(id, "").toLowerCase(Locale.ROOT).replace(' ', '_');

        List<Material> visible = materials.stream()
                .filter(filter::matches)
                .filter(material -> query.isBlank() || material.name().toLowerCase(Locale.ROOT).contains(query))
                .sorted(sort.comparator(sell))
                .toList();

        FileConfiguration config = plugin.configs().menus();
        int rows = 6;
        int inventorySize = rows * 9;
        List<Integer> contentSlots = SlotLayout.configured(
                config, "worth.menu.content-slots", inventorySize, DEFAULT_CONTENT_SLOTS);
        PageSlice<Material> page = PageSlice.of(visible, requestedPage, contentSlots.size());
        pages.put(id, page.page());

        String rawTitle = config.getString("worth.menu.title", "<dark_gray>Worth • %page%/%pages%</dark_gray>")
                .replace("%page%", Integer.toString(page.page() + 1))
                .replace("%pages%", Integer.toString(page.pageCount()));
        Gui gui = new Gui(rows, items.component(rawTitle));
        Material filler = MaterialResolver.resolve(
                config.getString("worth.menu.filler.material"), Material.BLACK_STAINED_GLASS_PANE);
        gui.filler(items.item(filler,
                config.getString("worth.menu.filler.name", " "),
                config.getStringList("worth.menu.filler.lore")));

        for (int index = 0; index < page.entries().size(); index++) {
            gui.item(contentSlots.get(index), worthItem(page.entries().get(index)));
        }

        int previousSlot = SlotLayout.valid(config.getInt("worth.menu.controls.previous.slot", 45), inventorySize, 45);
        int filterSlot = SlotLayout.valid(config.getInt("worth.menu.controls.filter.slot", 48), inventorySize, 48);
        int sortSlot = SlotLayout.valid(config.getInt("worth.menu.controls.sort.slot", 49), inventorySize, 49);
        int searchSlot = SlotLayout.valid(config.getInt("worth.menu.controls.search.slot", 50), inventorySize, 50);
        int nextSlot = SlotLayout.valid(config.getInt("worth.menu.controls.next.slot", 53), inventorySize, 53);

        if (page.hasPrevious()) {
            gui.button(previousSlot, GuiButton.of(controlItem(config, "previous", Material.ARROW,
                            "<yellow>Vorherige Seite</yellow>", List.of()),
                    event -> render(player, page.page() - 1)));
        }

        gui.button(filterSlot, GuiButton.of(controlItem(config, "filter", Material.HOPPER,
                        "<aqua>Filter</aqua>", filterLore(filter)),
                event -> {
                    filters.put(id, filter.next());
                    pages.put(id, 0);
                    render(player, 0);
                }));

        gui.button(sortSlot, GuiButton.of(controlItem(config, "sort", Material.HOPPER_MINECART,
                        "<green>Sortierung</green>", sortLore(sort)),
                event -> {
                    sorts.put(id, sort.next());
                    pages.put(id, 0);
                    render(player, 0);
                }));

        List<Component> searchLore = List.of(Component.text(
                searches.getOrDefault(id, config.getString("worth.menu.controls.search.empty", "Keine Suche")),
                NamedTextColor.GRAY));
        gui.button(searchSlot, GuiButton.of(controlItem(config, "search", Material.OAK_SIGN,
                        "<yellow>Suche</yellow>", searchLore),
                event -> signInput.open(player)));

        if (page.hasNext()) {
            gui.button(nextSlot, GuiButton.of(controlItem(config, "next", Material.ARROW,
                            "<yellow>Nächste Seite</yellow>", List.of()),
                    event -> render(player, page.page() + 1)));
        }

        gui.open(player);
    }

    private ItemStack controlItem(FileConfiguration config, String key, Material fallbackMaterial,
                                  String fallbackName, List<Component> dynamicLore) {
        String base = "worth.menu.controls." + key;
        Material material = MaterialResolver.resolve(config.getString(base + ".material"), fallbackMaterial);
        List<Component> lore = dynamicLore.isEmpty()
                ? items.lore(config.getStringList(base + ".lore"))
                : dynamicLore;
        return ItemBuilder.of(material)
                .name(items.component(config.getString(base + ".name", fallbackName)))
                .lore(lore)
                .build();
    }

    private List<Component> filterLore(Filter active) {
        List<Component> lore = new ArrayList<>();
        for (Filter filter : Filter.values()) {
            boolean selected = filter == active;
            lore.add(Component.text((selected ? "▣ " : "□ ") + filter.label,
                    selected ? NamedTextColor.AQUA : NamedTextColor.GRAY));
        }
        return lore;
    }

    private List<Component> sortLore(Sort active) {
        List<Component> lore = new ArrayList<>();
        for (Sort sort : Sort.values()) {
            boolean selected = sort == active;
            lore.add(Component.text((selected ? "▣ " : "□ ") + sort.label,
                    selected ? NamedTextColor.AQUA : NamedTextColor.GRAY));
        }
        return lore;
    }

    private ItemStack worthItem(Material material) {
        List<Component> lore = new ArrayList<>();
        if (sell.isPotionMaterial(material)) {
            lore.add(Component.text("Preis abhängig von Trankart und Variante", NamedTextColor.GOLD));
        } else {
            lore.add(Component.text(sell.formatUnitPrice(material) + " Coins", NamedTextColor.GOLD));
        }
        if (material == Material.ENCHANTED_BOOK) {
            lore.add(Component.text("+5 Coins pro Stufe", NamedTextColor.GRAY));
        }
        return ItemBuilder.of(material)
                .name(Component.translatable(material.translationKey()).color(NamedTextColor.WHITE))
                .lore(lore)
                .build();
    }

    private enum Filter {
        ALL("Alle") {
            @Override boolean matches(Material material) { return true; }
        },
        BLOCKS("Blöcke") {
            @Override boolean matches(Material material) { return material.isBlock(); }
        },
        TOOLS("Werkzeuge") {
            @Override boolean matches(Material material) { return isTool(material); }
        },
        FOOD("Nahrung") {
            @Override boolean matches(Material material) {
                return material.isEdible()
                        || material == Material.POTION
                        || material == Material.SPLASH_POTION
                        || material == Material.LINGERING_POTION
                        || material == Material.MILK_BUCKET
                        || material == Material.HONEY_BOTTLE;
            }
        },
        COMBAT("Kampf") {
            @Override boolean matches(Material material) { return isCombat(material); }
        },
        BOOKS("Bücher") {
            @Override boolean matches(Material material) { return material.name().contains("BOOK"); }
        },
        INGREDIENTS("Zutaten") {
            @Override boolean matches(Material material) { return isIngredient(material); }
        },
        USEFUL("Nützliches") {
            @Override boolean matches(Material material) {
                if (material == Material.OMINOUS_BOTTLE) return true;
                return !material.isBlock()
                        && !material.isEdible()
                        && !isTool(material)
                        && !isCombat(material)
                        && !material.name().contains("BOOK")
                        && material != Material.POTION
                        && material != Material.SPLASH_POTION
                        && material != Material.LINGERING_POTION
                        && !isIngredient(material);
            }
        };

        private final String label;

        Filter(String label) {
            this.label = label;
        }

        abstract boolean matches(Material material);

        Filter next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private enum Sort {
        HIGH("Wert: hoch → niedrig"),
        LOW("Wert: niedrig → hoch"),
        AZ("A bis Z"),
        ZA("Z bis A");

        private final String label;

        Sort(String label) {
            this.label = label;
        }

        Sort next() {
            return values()[(ordinal() + 1) % values().length];
        }

        Comparator<Material> comparator(SellManager sell) {
            return switch (this) {
                case HIGH -> Comparator.<Material>comparingDouble(sell::unitPrice).reversed().thenComparing(Material::name);
                case LOW -> Comparator.<Material>comparingDouble(sell::unitPrice).thenComparing(Material::name);
                case AZ -> Comparator.comparing(Material::name);
                case ZA -> Comparator.comparing(Material::name).reversed();
            };
        }
    }

    private static boolean isTool(Material material) {
        String name = material.name();
        return name.endsWith("_PICKAXE") || name.endsWith("_AXE") || name.endsWith("_SHOVEL")
                || name.endsWith("_HOE") || material == Material.SHEARS || material == Material.FISHING_ROD
                || material == Material.FLINT_AND_STEEL || material == Material.BRUSH;
    }

    private static boolean isCombat(Material material) {
        String name = material.name();
        return name.endsWith("_SWORD") || name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS") || material == Material.BOW
                || material == Material.CROSSBOW || material == Material.TRIDENT || material == Material.MACE
                || material == Material.SHIELD || material == Material.ARROW
                || material == Material.SPECTRAL_ARROW || material == Material.TIPPED_ARROW;
    }

    private static boolean isIngredient(Material material) {
        String name = material.name();
        return name.endsWith("_INGOT") || name.endsWith("_NUGGET") || name.endsWith("_DUST")
                || name.startsWith("RAW_") || name.endsWith("_DYE") || name.endsWith("_SHARD")
                || name.endsWith("_CRYSTAL") || name.endsWith("_SCRAP") || name.endsWith("_MEMBRANE")
                || material == Material.COAL || material == Material.CHARCOAL || material == Material.DIAMOND
                || material == Material.EMERALD || material == Material.QUARTZ || material == Material.AMETHYST_SHARD
                || material == Material.STICK || material == Material.STRING || material == Material.LEATHER
                || material == Material.FEATHER || material == Material.FLINT || material == Material.CLAY_BALL
                || material == Material.SLIME_BALL || material == Material.MAGMA_CREAM
                || material == Material.BLAZE_ROD || material == Material.BLAZE_POWDER
                || material == Material.ENDER_PEARL || material == Material.ENDER_EYE
                || material == Material.GHAST_TEAR || material == Material.GUNPOWDER
                || material == Material.SPIDER_EYE || material == Material.FERMENTED_SPIDER_EYE
                || material == Material.SUGAR || material == Material.GLISTERING_MELON_SLICE
                || material == Material.RABBIT_FOOT || material == Material.PHANTOM_MEMBRANE
                || material == Material.NETHER_WART || material == Material.REDSTONE
                || material == Material.GLOWSTONE_DUST;
    }
}
