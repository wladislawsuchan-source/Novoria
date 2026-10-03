package de.walahi.novosmp.angler;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.logging.Logger;

/** One internal Rare entry after the existing outer RARE roll. */
public final class FishingRarePool {
    public record Result(String id, String displayName, AnglerLootFoundation.LootReward reward) { }
    private record Weighted<T>(T value, double weight) { }
    private record Entry(String id, String displayName, double weight, Factory factory) { }
    @FunctionalInterface private interface Factory {
        AnglerLootFoundation.LootReward create(Random random, int prestige);
    }

    private final List<Entry> entries;
    private final Map<String, Entry> byId;
    private final Map<String, Double> weights;
    private final List<Weighted<AnglerLootFoundation.OverlevelBook>> overlevelBooks;
    private final boolean ready;

    public FishingRarePool(FileConfiguration config, FishingTreasurePool treasure,
                           Map<String, AnglerLootFoundation.OverlevelBook> availableBooks, Logger logger) {
        List<String> problems = new ArrayList<>();
        List<Weighted<AnglerLootFoundation.OverlevelBook>> books = new ArrayList<>();
        for (AnglerLootFoundation.OverlevelBook book : availableBooks.values()) {
            String path = "loot.overlevel-books." + book.id() + ".weight";
            double weight = config.get(path) == null ? 1D : config.getDouble(path);
            if (!positive(weight)) problems.add(path + " muss positiv sein");
            else books.add(new Weighted<>(book, weight));
        }
        overlevelBooks = List.copyOf(books);

        List<Entry> loaded = new ArrayList<>();
        Map<String, Entry> indexed = new LinkedHashMap<>();
        Map<String, Double> configuredWeights = new LinkedHashMap<>();
        double total = 0D;
        for (String id : keys(config, "loot.rare.entries")) {
            String path = "loot.rare.entries." + id;
            double weight = config.getDouble(path + ".weight");
            if (!positive(weight)) {
                problems.add(path + ".weight muss positiv sein");
                continue;
            }
            total += weight;
            configuredWeights.put(id, weight);
            Factory factory = parseFactory(config, path, treasure, problems);
            if (factory == null) continue;
            String display = config.getString(path + ".display-name");
            Entry entry = new Entry(id, display == null ? id : display, weight, factory);
            loaded.add(entry);
            indexed.put(id.toLowerCase(Locale.ROOT), entry);
        }
        if (Math.abs(total - 100D) > 0.000001D)
            problems.add("loot.rare.entries hat Gewichtsumme " + total + " statt 100");
        if (loaded.isEmpty()) problems.add("loot.rare.entries enthält keine gültigen Einträge");
        entries = List.copyOf(loaded);
        byId = Map.copyOf(indexed);
        weights = Collections.unmodifiableMap(new LinkedHashMap<>(configuredWeights));
        ready = problems.isEmpty();
        for (String problem : problems) logger.warning("Rare-Pool nicht bereit: " + problem);
    }

    public boolean ready() { return ready; }
    public Map<String, Double> weights() { return weights; }
    public Set<String> entryIds() { return weights.keySet(); }
    public boolean hasEligibleOverlevel(int prestige) {
        return overlevelBooks.stream().anyMatch(book -> book.value().minPrestige() <= prestige);
    }

    /** Simulation uses the exact entry-selection method used by a real reward. */
    public String rollEntryId(Random random, int prestige) { return chooseEntry(random, prestige).id(); }

    public Result roll(Random random, int prestige) {
        return create(chooseEntry(random, prestige), random, prestige);
    }

    public Result rollSpecific(String id, Random random, int prestige) {
        if (!ready) throw new IllegalStateException("Rare-Pool ist nicht vollständig konfiguriert");
        Entry entry = byId.get(id.toLowerCase(Locale.ROOT));
        if (entry == null) throw new IllegalArgumentException("Unbekannter Rare-Eintrag: " + id);
        if (entry.id().equals("overlevel_book") && !hasEligibleOverlevel(prestige))
            throw new IllegalStateException("Für dieses Prestige ist kein Overlevel-Buch freigeschaltet");
        return create(entry, random, prestige);
    }

    private Result create(Entry entry, Random random, int prestige) {
        return new Result(entry.id(), entry.displayName(), entry.factory().create(random, prestige));
    }

    private Entry chooseEntry(Random random, int prestige) {
        if (!ready) throw new IllegalStateException("Rare-Pool ist nicht vollständig konfiguriert");
        boolean booksAvailable = hasEligibleOverlevel(prestige);
        double total = 0D;
        for (Entry entry : entries)
            if (booksAvailable || !entry.id().equals("overlevel_book")) total += entry.weight();
        if (!(total > 0D)) throw new IllegalStateException("Kein Rare-Eintrag für dieses Prestige verfügbar");
        double draw = random.nextDouble() * total;
        Entry last = null;
        for (Entry entry : entries) {
            if (!booksAvailable && entry.id().equals("overlevel_book")) continue;
            last = entry;
            draw -= entry.weight();
            if (draw < 0D) return entry;
        }
        return last;
    }

    private Factory parseFactory(FileConfiguration config, String path, FishingTreasurePool treasure,
                                 List<String> problems) {
        String type = config.getString(path + ".type");
        if (type == null) {
            problems.add(path + ".type fehlt");
            return null;
        }
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "BUNDLE" -> parseBundle(config, path, problems);
            case "VANILLA_BOOK_BUNDLE" -> {
                int count = config.getInt(path + ".count");
                if (count < 1 || count > 64 || !treasure.ready()) {
                    problems.add(path + " braucht gültige Buchanzahl und den normalen Treasure-Buchgenerator");
                    yield null;
                }
                yield (random, prestige) -> {
                    List<AnglerLootFoundation.LootReward> contents = new ArrayList<>(count);
                    for (int index = 0; index < count; index++)
                        contents.add(treasure.rollVanillaBook(random));
                    return new AnglerLootFoundation.BundleReward(contents);
                };
            }
            case "CUSTOM_ITEM" -> {
                String itemId = config.getString(path + ".item-id");
                int amount = config.getInt(path + ".amount");
                if (itemId == null || itemId.isBlank() || amount < 1 || amount > 64) {
                    problems.add(path + " hat ungültige Custom-Item-ID oder Menge");
                    yield null;
                }
                yield (random, prestige) -> new AnglerLootFoundation.CustomItemReward(itemId, amount);
            }
            case "UNIQUE_TRIM_BUNDLE" -> parseTrimBundle(config, path, problems);
            case "EQUIPMENT_BUNDLE" -> parseEquipmentBundle(config, path, problems);
            case "OVERLEVEL_BOOK" -> (random, prestige) -> {
                List<Weighted<AnglerLootFoundation.OverlevelBook>> eligible = overlevelBooks.stream()
                        .filter(book -> book.value().minPrestige() <= prestige).toList();
                AnglerLootFoundation.OverlevelBook selected = choose(eligible, random);
                return new AnglerLootFoundation.OverlevelBookReward(selected.id(), prestige);
            };
            default -> {
                problems.add(path + ".type ist unbekannt: " + type);
                yield null;
            }
        };
    }

    private Factory parseBundle(FileConfiguration config, String path, List<String> problems) {
        List<AnglerLootFoundation.LootReward> contents = new ArrayList<>();
        List<Map<?, ?>> configured = config.getMapList(path + ".contents");
        for (Map<?, ?> line : configured) {
            Material material = material(line.get("material"));
            int amount = number(line.get("amount"));
            if (material == null || amount < 1 || amount > material.getMaxStackSize()) {
                problems.add(path + ".contents enthält ungültiges Material oder Menge");
                continue;
            }
            contents.add(new AnglerLootFoundation.NormalItemReward(new ItemStack(material, amount)));
        }
        if (contents.isEmpty()) {
            problems.add(path + ".contents ist leer");
            return null;
        }
        List<AnglerLootFoundation.LootReward> frozen = List.copyOf(contents);
        return (random, prestige) -> new AnglerLootFoundation.BundleReward(frozen);
    }

    private Factory parseTrimBundle(FileConfiguration config, String path, List<String> problems) {
        int minTypes = config.getInt(path + ".min-types");
        int maxTypes = config.getInt(path + ".max-types");
        int minAmount = config.getInt(path + ".min-amount-per-type");
        int maxAmount = config.getInt(path + ".max-amount-per-type");
        List<Weighted<Material>> templates = new ArrayList<>();
        for (String id : keys(config, path + ".templates")) {
            Material material = material(id);
            double weight = config.getDouble(path + ".templates." + id);
            if (material == null || !material.name().endsWith("_ARMOR_TRIM_SMITHING_TEMPLATE")
                    || !positive(weight)) {
                problems.add(path + ".templates enthält ungültiges Template oder Gewicht: " + id);
                continue;
            }
            templates.add(new Weighted<>(material, weight));
        }
        if (minTypes < 1 || maxTypes < minTypes || maxTypes > templates.size()
                || minAmount < 1 || maxAmount < minAmount || maxAmount > Material.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE.getMaxStackSize()) {
            problems.add(path + " hat ungültige Typ- oder Mengengrenzen");
            return null;
        }
        List<Weighted<Material>> frozen = List.copyOf(templates);
        return (random, prestige) -> {
            int count = random.nextInt(minTypes, maxTypes + 1);
            List<Weighted<Material>> remaining = new ArrayList<>(frozen);
            List<AnglerLootFoundation.LootReward> contents = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                Material selected = choose(remaining, random);
                remaining.removeIf(template -> template.value() == selected);
                int amount = random.nextInt(minAmount, maxAmount + 1);
                contents.add(new AnglerLootFoundation.NormalItemReward(new ItemStack(selected, amount)));
            }
            return new AnglerLootFoundation.BundleReward(contents);
        };
    }

    private Factory parseEquipmentBundle(FileConfiguration config, String path, List<String> problems) {
        List<AnglerLootFoundation.LootReward> contents = new ArrayList<>();
        List<Map<?, ?>> configured = config.getMapList(path + ".items");
        for (Map<?, ?> line : configured) {
            Material material = material(line.get("material"));
            int amount = number(line.get("amount"));
            if (material == null || material.getMaxDurability() <= 0 || amount != 1) {
                problems.add(path + ".items braucht gültiges Werkzeug mit Menge 1");
                continue;
            }
            ItemStack item = new ItemStack(material, amount); // freshly created: fully repaired
            if (!(line.get("enchantments") instanceof Map<?, ?> enchants) || enchants.isEmpty()) {
                problems.add(path + ".items braucht Vanilla-Enchantments");
                continue;
            }
            for (Map.Entry<?, ?> enchant : enchants.entrySet()) {
                String raw = String.valueOf(enchant.getKey()).toLowerCase(Locale.ROOT);
                NamespacedKey key = NamespacedKey.fromString(raw.contains(":") ? raw : "minecraft:" + raw);
                Enchantment resolved = key == null || !key.getNamespace().equals("minecraft")
                        ? null : Registry.ENCHANTMENT.get(key);
                int level = number(enchant.getValue());
                if (resolved == null || key.getKey().equals("mending") || level < 1
                        || level > resolved.getMaxLevel() || !resolved.canEnchantItem(item)) {
                    problems.add(path + ".items hat ungültigen oder unerlaubten Vanilla-Enchant: " + raw);
                    continue;
                }
                item.addEnchantment(resolved, level);
            }
            contents.add(new AnglerLootFoundation.NormalItemReward(item));
        }
        if (contents.isEmpty()) {
            problems.add(path + ".items ist leer");
            return null;
        }
        List<AnglerLootFoundation.LootReward> frozen = List.copyOf(contents);
        return (random, prestige) -> new AnglerLootFoundation.BundleReward(frozen);
    }

    private static <T> T choose(List<Weighted<T>> options, Random random) {
        if (options.isEmpty()) throw new IllegalStateException("Keine freigeschaltete gewichtete Rare-Auswahl");
        double total = options.stream().mapToDouble(Weighted::weight).sum();
        double draw = random.nextDouble() * total;
        T last = options.getLast().value();
        for (Weighted<T> option : options) {
            draw -= option.weight();
            if (draw < 0D) return option.value();
        }
        return last;
    }

    private static Set<String> keys(FileConfiguration config, String path) {
        Set<String> result = new LinkedHashSet<>();
        ConfigurationSection defaults = config.getDefaults() == null ? null
                : config.getDefaults().getConfigurationSection(path);
        ConfigurationSection physical = config.getConfigurationSection(path);
        if (defaults != null) result.addAll(defaults.getKeys(false));
        if (physical != null) result.addAll(physical.getKeys(false));
        return result;
    }

    private static Material material(Object raw) {
        Material value = raw == null ? null : Material.matchMaterial(String.valueOf(raw));
        return value != null && value.isItem() ? value : null;
    }

    private static int number(Object raw) {
        if (raw instanceof Number value) return value.intValue();
        try { return Integer.parseInt(String.valueOf(raw)); }
        catch (NumberFormatException exception) { return 0; }
    }

    private static boolean positive(double value) { return Double.isFinite(value) && value > 0D; }
}
