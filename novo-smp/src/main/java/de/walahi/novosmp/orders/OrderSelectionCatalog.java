package de.walahi.novosmp.orders;

import de.walahi.novosmp.trade.TradeItemPolicy;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.OminousBottleMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Builds and filters the immutable catalogue used by the order item selection menu. */
final class OrderSelectionCatalog {
    private final List<Entry> entries;

    OrderSelectionCatalog(TradeItemPolicy tradePolicy) {
        this.entries = build(tradePolicy);
    }

    List<Entry> visible(Filter filter, Sort sort, String query) {
        String normalized = query == null ? "" : query.trim();
        return entries.stream()
                .filter(filter::matches)
                .filter(entry -> matchesSearch(entry, normalized))
                .sorted(sort.comparator())
                .toList();
    }

    private boolean matchesSearch(Entry entry, String query) {
        if (query.isBlank()) return true;
        String needle = query.toLowerCase(Locale.ROOT).replace(' ', '_');
        String materialName = entry.stack().getType().name().toLowerCase(Locale.ROOT);
        String readable = entry.displayName().toLowerCase(Locale.ROOT).replace(' ', '_');
        return materialName.contains(needle) || readable.contains(needle);
    }

    private List<Entry> build(TradeItemPolicy tradePolicy) {
        List<Entry> result = new ArrayList<>();
        for (Material material : Material.values()) {
            if (!tradePolicy.isOrderMaterialAllowed(material)) continue;
            if (material == Material.POTION || material == Material.SPLASH_POTION
                    || material == Material.LINGERING_POTION || material == Material.TIPPED_ARROW
                    || material == Material.ENCHANTED_BOOK) {
                continue;
            }
            ItemStack stack = new ItemStack(material, 1);
            if (material == Material.OMINOUS_BOTTLE) setOminousBottleLevelOne(stack);
            result.add(new Entry(stack, OrderMenuItems.readableName(material)));
        }

        addPotionVariants(result, Material.POTION, "Trank");
        addPotionVariants(result, Material.SPLASH_POTION, "Wurftrank");
        addPotionVariants(result, Material.LINGERING_POTION, "Verweiltrank");

        for (Enchantment enchantment : Registry.ENCHANTMENT) {
            ItemStack book = new ItemStack(Material.ENCHANTED_BOOK, 1);
            if (book.getItemMeta() instanceof EnchantmentStorageMeta meta) {
                meta.addStoredEnchant(enchantment, 1, true);
                book.setItemMeta(meta);
                result.add(new Entry(book,
                        "Verzaubertes Buch: " + readableKey(enchantment.getKey().getKey())));
            }
        }
        result.sort(Comparator.comparing(Entry::displayName, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    private void addPotionVariants(List<Entry> result, Material material, String prefix) {
        for (PotionType type : PotionType.values()) {
            ItemStack potion = new ItemStack(material, 1);
            if (!(potion.getItemMeta() instanceof PotionMeta meta)) continue;
            try {
                meta.setBasePotionType(type);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            potion.setItemMeta(meta);
            result.add(new Entry(potion, prefix + ": " + readableKey(type.name())));
        }
    }

    private void setOminousBottleLevelOne(ItemStack bottle) {
        if (bottle.getItemMeta() instanceof OminousBottleMeta meta) {
            meta.setAmplifier(0);
            bottle.setItemMeta(meta);
        }
    }

    private static String readableKey(String key) {
        String[] words = key.toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    record Entry(ItemStack stack, String displayName) {
        Entry {
            stack = stack.clone();
            stack.setAmount(1);
        }
    }

    enum Filter {
        ALL("Alle") {
            @Override boolean matches(Entry entry) { return true; }
        },
        BLOCKS("Blöcke") {
            @Override boolean matches(Entry entry) { return entry.stack().getType().isBlock(); }
        },
        TOOLS("Werkzeuge") {
            @Override boolean matches(Entry entry) { return isTool(entry.stack().getType()); }
        },
        FOOD("Nahrung") {
            @Override boolean matches(Entry entry) {
                Material material = entry.stack().getType();
                return material.isEdible()
                        || material == Material.POTION
                        || material == Material.SPLASH_POTION
                        || material == Material.LINGERING_POTION
                        || material == Material.MILK_BUCKET
                        || material == Material.HONEY_BOTTLE;
            }
        },
        COMBAT("Kampf") {
            @Override boolean matches(Entry entry) { return isCombat(entry.stack().getType()); }
        },
        BOOKS("Bücher") {
            @Override boolean matches(Entry entry) { return entry.stack().getType().name().contains("BOOK"); }
        },
        INGREDIENTS("Zutaten") {
            @Override boolean matches(Entry entry) { return isIngredient(entry.stack().getType()); }
        },
        USEFUL("Nützliches") {
            @Override boolean matches(Entry entry) {
                Material material = entry.stack().getType();
                if (material == Material.OMINOUS_BOTTLE) return true;
                return !material.isBlock() && !material.isEdible() && !isTool(material)
                        && !isCombat(material) && !material.name().contains("BOOK")
                        && material != Material.POTION && material != Material.SPLASH_POTION
                        && material != Material.LINGERING_POTION && !isIngredient(material);
            }
        };

        private final String displayName;

        Filter(String displayName) {
            this.displayName = displayName;
        }

        abstract boolean matches(Entry entry);

        String displayName() {
            return displayName;
        }

        Filter next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    enum Sort {
        A_TO_Z("A bis Z", Comparator.comparing(Entry::displayName, String.CASE_INSENSITIVE_ORDER)),
        Z_TO_A("Z bis A", Comparator.comparing(Entry::displayName, String.CASE_INSENSITIVE_ORDER).reversed()),
        BLOCKS_FIRST("Blöcke zuerst", Comparator.<Entry, Boolean>comparing(entry -> entry.stack().getType().isBlock())
                .reversed().thenComparing(Entry::displayName, String.CASE_INSENSITIVE_ORDER));

        private final String displayName;
        private final Comparator<Entry> comparator;

        Sort(String displayName, Comparator<Entry> comparator) {
            this.displayName = displayName;
            this.comparator = comparator;
        }

        String displayName() {
            return displayName;
        }

        Comparator<Entry> comparator() {
            return comparator;
        }

        Sort next() {
            return values()[(ordinal() + 1) % values().length];
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
                || material == Material.SHIELD || material == Material.ARROW || material == Material.SPECTRAL_ARROW;
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
