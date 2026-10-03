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

/** One internal Epic selection after the existing outer EPIC roll. */
public final class FishingEpicPool {
    public record Result(String id, String displayName, AnglerLootFoundation.LootReward reward) { }
    private record Weighted<T>(T value, double weight) { }
    private record Entry(String id, String displayName, double weight, Factory factory) { }
    private record BookCandidate(AnglerLootFoundation.LootReward reward, int minPrestige) { }
    @FunctionalInterface private interface Factory {
        AnglerLootFoundation.LootReward create(Random random, int prestige);
    }

    private final List<Entry> entries;
    private final Map<String, Entry> byId;
    private final Map<String, Double> weights;
    private final boolean ready;
    private final FishingRarePool rare;

    public FishingEpicPool(FileConfiguration config, FishingRarePool rare, Logger logger) {
        this.rare = rare;
        List<String> problems = new ArrayList<>();
        List<Entry> loaded = new ArrayList<>();
        Map<String, Entry> indexed = new LinkedHashMap<>();
        Map<String, Double> configuredWeights = new LinkedHashMap<>();
        double total = 0D;
        for (String id : keys(config, "loot.epic.entries")) {
            String path = "loot.epic.entries." + id;
            double weight = config.getDouble(path + ".weight");
            if (!positive(weight)) {
                problems.add(path + ".weight muss positiv sein");
                continue;
            }
            total += weight;
            configuredWeights.put(id, weight);
            Factory factory = parseFactory(config, path, problems);
            if (factory == null) continue;
            String display = config.getString(path + ".display-name");
            Entry entry = new Entry(id, display == null ? id : display, weight, factory);
            loaded.add(entry);
            indexed.put(id.toLowerCase(Locale.ROOT), entry);
        }
        if (Math.abs(total - 100D) > 0.000001D)
            problems.add("loot.epic.entries hat Gewichtsumme " + total + " statt 100");
        if (loaded.isEmpty()) problems.add("loot.epic.entries enthält keine gültigen Einträge");
        entries = List.copyOf(loaded);
        byId = Map.copyOf(indexed);
        weights = Collections.unmodifiableMap(new LinkedHashMap<>(configuredWeights));
        ready = problems.isEmpty();
        for (String problem : problems) logger.warning("Epic-Pool nicht bereit: " + problem);
    }

    public boolean ready() { return ready; }
    public Map<String, Double> weights() { return weights; }
    public Set<String> entryIds() { return weights.keySet(); }
    public boolean hasEligibleOverlevel(int prestige) { return rare.hasEligibleOverlevel(prestige); }

    public String rollEntryId(Random random, int prestige) { return chooseEntry(random, prestige).id(); }
    public Result roll(Random random, int prestige) { return create(chooseEntry(random, prestige), random, prestige); }

    public Result rollSpecific(String id, Random random, int prestige) {
        if (!ready) throw new IllegalStateException("Epic-Pool ist nicht vollständig konfiguriert");
        Entry entry = byId.get(id.toLowerCase(Locale.ROOT));
        if (entry == null) throw new IllegalArgumentException("Unbekannter Epic-Eintrag: " + id);
        if (entry.id().equals("overlevel_book") && !hasEligibleOverlevel(prestige))
            throw new IllegalStateException("Für dieses Prestige ist kein Overlevel-Buch freigeschaltet");
        return create(entry, random, prestige);
    }

    private Result create(Entry entry, Random random, int prestige) {
        return new Result(entry.id(), entry.displayName(), entry.factory().create(random, prestige));
    }

    private Entry chooseEntry(Random random, int prestige) {
        if (!ready) throw new IllegalStateException("Epic-Pool ist nicht vollständig konfiguriert");
        boolean booksAvailable = hasEligibleOverlevel(prestige);
        double total = entries.stream().filter(entry -> booksAvailable || !entry.id().equals("overlevel_book"))
                .mapToDouble(Entry::weight).sum();
        if (!(total > 0D)) throw new IllegalStateException("Kein Epic-Eintrag für dieses Prestige verfügbar");
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

    private Factory parseFactory(FileConfiguration config, String path, List<String> problems) {
        String configuredType = config.getString(path + ".type");
        String type = configuredType == null ? "" : configuredType.toUpperCase(Locale.ROOT);
        return switch (type) {
            case "BUNDLE" -> parseBundle(config, path, problems);
            case "BOOK_BUNDLE" -> parseBookBundle(config, path, problems);
            case "RANDOM_TRIM_BUNDLE" -> parseTrimBundle(config, path, problems);
            case "EQUIPMENT_BUNDLE" -> parseEquipmentBundle(config, path, problems);
            case "RANDOM_TOOL" -> parseRandomTool(config, path, problems);
            case "CUSTOM_ITEM" -> {
                String id = config.getString(path + ".item-id");
                int amount = config.getInt(path + ".amount");
                if (id == null || id.isBlank() || amount < 1 || amount > 64) {
                    problems.add(path + " benötigt Custom-Item-ID und gültige Menge");
                    yield null;
                }
                yield (random, prestige) -> new AnglerLootFoundation.CustomItemReward(id, amount);
            }
            case "LUMI" -> {
                long amount = config.getLong(path + ".amount");
                if (amount <= 0) { problems.add(path + ".amount muss positiv sein"); yield null; }
                yield (random, prestige) -> new AnglerLootFoundation.CurrencyReward(
                        AnglerLootFoundation.RewardType.LUMI, amount);
            }
            case "OVERLEVEL_BOOK" -> {
                if (!rare.ready()) { problems.add(path + " benötigt den vorhandenen Rare-Overlevel-Generator"); yield null; }
                yield (random, prestige) -> rare.rollSpecific("overlevel_book", random, prestige).reward();
            }
            default -> { problems.add(path + ".type ist unbekannt: " + type); yield null; }
        };
    }

    private Factory parseBundle(FileConfiguration config, String path, List<String> problems) {
        List<AnglerLootFoundation.LootReward> contents = new ArrayList<>();
        for (Map<?, ?> line : config.getMapList(path + ".contents")) {
            Object custom = line.get("item-id");
            int amount = number(line.get("amount"));
            if (custom != null) {
                if (String.valueOf(custom).isBlank() || amount < 1 || amount > 64) {
                    problems.add(path + ".contents hat ungültiges Custom-Item oder Menge"); continue;
                }
                contents.add(new AnglerLootFoundation.CustomItemReward(String.valueOf(custom), amount));
            } else {
                Material material = material(line.get("material"));
                if (material == null || amount < 1 || amount > material.getMaxStackSize()) {
                    problems.add(path + ".contents hat ungültiges Material oder Menge"); continue;
                }
                contents.add(new AnglerLootFoundation.NormalItemReward(new ItemStack(material, amount)));
            }
        }
        if (contents.isEmpty()) { problems.add(path + ".contents ist leer"); return null; }
        List<AnglerLootFoundation.LootReward> frozen = List.copyOf(contents);
        return (random, prestige) -> new AnglerLootFoundation.BundleReward(frozen);
    }

    private Factory parseBookBundle(FileConfiguration config, String path, List<String> problems) {
        int count = config.getInt(path + ".count");
        List<Weighted<BookCandidate>> candidates = new ArrayList<>();
        for (String id : keys(config, path + ".vanilla-books")) {
            String candidatePath = path + ".vanilla-books." + id;
            Enchantment enchantment = enchantment(config.getString(candidatePath + ".enchantment"));
            int level = config.getInt(candidatePath + ".level");
            double weight = config.getDouble(candidatePath + ".weight");
            if (enchantment == null || level < 1 || level > enchantment.getMaxLevel() || !positive(weight)) {
                problems.add(candidatePath + " hat ungültigen Vanilla-Enchant, Level oder Gewicht"); continue;
            }
            candidates.add(new Weighted<>(new BookCandidate(
                    FishingTreasurePool.createVanillaBook(enchantment, level), 0), weight));
        }
        for (String id : keys(config, path + ".custom-books")) {
            String candidatePath = path + ".custom-books." + id;
            String itemId = config.getString(candidatePath + ".item-id");
            int minPrestige = config.getInt(candidatePath + ".min-prestige");
            double weight = config.getDouble(candidatePath + ".weight");
            if (itemId == null || itemId.isBlank() || minPrestige < 1 || minPrestige > 5 || !positive(weight)) {
                problems.add(candidatePath + " hat ungültige Custom-ID, Prestige oder Gewicht"); continue;
            }
            candidates.add(new Weighted<>(new BookCandidate(
                    new AnglerLootFoundation.CustomItemReward(itemId, 1), minPrestige), weight));
        }
        if (count < 1 || count > 64 || candidates.isEmpty()) {
            problems.add(path + " benötigt eine gültige Buchanzahl und Kandidaten"); return null;
        }
        List<Weighted<BookCandidate>> frozen = List.copyOf(candidates);
        return (random, prestige) -> {
            List<Weighted<BookCandidate>> eligible = frozen.stream()
                    .filter(candidate -> candidate.value().minPrestige() <= prestige).toList();
            List<AnglerLootFoundation.LootReward> contents = new ArrayList<>(count);
            for (int index = 0; index < count; index++) contents.add(choose(eligible, random).reward());
            return new AnglerLootFoundation.BundleReward(contents);
        };
    }

    private Factory parseTrimBundle(FileConfiguration config, String path, List<String> problems) {
        int min = config.getInt(path + ".min-count");
        int max = config.getInt(path + ".max-count");
        List<Weighted<Material>> templates = new ArrayList<>();
        for (String id : keys(config, path + ".templates")) {
            Material template = material(id);
            double weight = config.getDouble(path + ".templates." + id);
            if (template == null || !template.name().endsWith("_ARMOR_TRIM_SMITHING_TEMPLATE") || !positive(weight)) {
                problems.add(path + ".templates hat ungültiges Template oder Gewicht: " + id); continue;
            }
            templates.add(new Weighted<>(template, weight));
        }
        if (min < 1 || max < min || max > 64 || templates.isEmpty()) {
            problems.add(path + " hat ungültige Mengen oder Templates"); return null;
        }
        List<Weighted<Material>> frozen = List.copyOf(templates);
        return (random, prestige) -> {
            int count = random.nextInt(min, max + 1);
            List<AnglerLootFoundation.LootReward> contents = new ArrayList<>(count);
            for (int index = 0; index < count; index++)
                contents.add(new AnglerLootFoundation.NormalItemReward(new ItemStack(choose(frozen, random), 1)));
            return new AnglerLootFoundation.BundleReward(contents);
        };
    }

    private Factory parseEquipmentBundle(FileConfiguration config, String path, List<String> problems) {
        List<AnglerLootFoundation.LootReward> items = new ArrayList<>();
        for (Map<?, ?> line : config.getMapList(path + ".items")) {
            ItemStack item = parseTool(line, path + ".items", problems);
            if (item != null) items.add(new AnglerLootFoundation.NormalItemReward(item));
        }
        if (items.isEmpty()) { problems.add(path + ".items ist leer"); return null; }
        List<AnglerLootFoundation.LootReward> frozen = List.copyOf(items);
        return (random, prestige) -> new AnglerLootFoundation.BundleReward(frozen);
    }

    private Factory parseRandomTool(FileConfiguration config, String path, List<String> problems) {
        List<Weighted<AnglerLootFoundation.LootReward>> tools = new ArrayList<>();
        for (Map<?, ?> line : config.getMapList(path + ".tools")) {
            ItemStack item = parseTool(line, path + ".tools", problems);
            double weight = decimal(line.get("weight"));
            if (item == null || !positive(weight)) {
                problems.add(path + ".tools hat ungültiges Tool oder Gewicht"); continue;
            }
            tools.add(new Weighted<>(new AnglerLootFoundation.NormalItemReward(item), weight));
        }
        if (tools.isEmpty()) { problems.add(path + ".tools ist leer"); return null; }
        List<Weighted<AnglerLootFoundation.LootReward>> frozen = List.copyOf(tools);
        return (random, prestige) -> choose(frozen, random);
    }

    static ItemStack parseTool(Map<?, ?> line, String path, List<String> problems) {
        Material material = material(line.get("material"));
        if (material == null || material.getMaxDurability() <= 0 || number(line.get("amount")) != 1) {
            problems.add(path + " braucht ein Werkzeug mit Menge 1"); return null;
        }
        if (!(line.get("enchantments") instanceof Map<?, ?> enchants) || enchants.isEmpty()) {
            problems.add(path + " braucht Vanilla-Enchantments"); return null;
        }
        ItemStack item = new ItemStack(material, 1); // fresh: fully repaired
        for (Map.Entry<?, ?> enchant : enchants.entrySet()) {
            Enchantment resolved = enchantment(String.valueOf(enchant.getKey()));
            int level = number(enchant.getValue());
            if (resolved == null || level < 1 || level > resolved.getMaxLevel()
                    || !resolved.canEnchantItem(item)) {
                problems.add(path + " hat ungültigen oder unpassenden Vanilla-Enchant: " + enchant.getKey());
                continue;
            }
            item.addEnchantment(resolved, level);
        }
        return item;
    }

    private static Enchantment enchantment(String raw) {
        if (raw == null) return null;
        String lowered = raw.toLowerCase(Locale.ROOT);
        NamespacedKey key = NamespacedKey.fromString(lowered.contains(":") ? lowered : "minecraft:" + lowered);
        return key == null || !key.getNamespace().equals("minecraft") ? null : Registry.ENCHANTMENT.get(key);
    }

    private static <T> T choose(List<Weighted<T>> options, Random random) {
        if (options.isEmpty()) throw new IllegalStateException("Keine freigeschaltete Epic-Auswahl");
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
        Set<String> ids = new LinkedHashSet<>();
        ConfigurationSection defaults = config.getDefaults() == null ? null
                : config.getDefaults().getConfigurationSection(path);
        ConfigurationSection physical = config.getConfigurationSection(path);
        if (defaults != null) ids.addAll(defaults.getKeys(false));
        if (physical != null) ids.addAll(physical.getKeys(false));
        return ids;
    }

    private static Material material(Object raw) {
        Material value = raw == null ? null : Material.matchMaterial(String.valueOf(raw));
        return value != null && value.isItem() ? value : null;
    }

    private static int number(Object raw) {
        if (raw instanceof Number number) return number.intValue();
        try { return Integer.parseInt(String.valueOf(raw)); }
        catch (NumberFormatException exception) { return 0; }
    }

    private static double decimal(Object raw) {
        if (raw instanceof Number number) return number.doubleValue();
        try { return Double.parseDouble(String.valueOf(raw)); }
        catch (NumberFormatException exception) { return 0D; }
    }

    private static boolean positive(double value) { return Double.isFinite(value) && value > 0D; }
}
