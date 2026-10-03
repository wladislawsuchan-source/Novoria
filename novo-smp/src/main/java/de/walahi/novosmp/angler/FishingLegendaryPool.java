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

/** One internal Legendary reward after the unchanged outer LEGENDARY roll. */
public final class FishingLegendaryPool {
    public record Result(String id, String displayName, AnglerLootFoundation.LootReward reward) { }
    private record Weighted<T>(T value, double weight) { }
    private record Entry(String id, String displayName, double weight, Factory factory) { }
    @FunctionalInterface private interface Factory {
        AnglerLootFoundation.LootReward create(Random random, int prestige);
    }

    private final List<Entry> entries;
    private final Map<String, Entry> byId;
    private final Map<String, Double> weights;
    private final int requiredPrestige;
    private final boolean ready;
    private final FishingRarePool rare;

    public FishingLegendaryPool(FileConfiguration config, FishingRarePool rare, Logger logger) {
        this.rare = rare;
        requiredPrestige = config.getInt("loot.legendary.min-prestige");
        List<String> problems = new ArrayList<>();
        if (requiredPrestige < 1 || requiredPrestige > 5)
            problems.add("loot.legendary.min-prestige muss zwischen 1 und 5 liegen");
        List<Entry> loaded = new ArrayList<>();
        Map<String, Entry> indexed = new LinkedHashMap<>();
        Map<String, Double> configuredWeights = new LinkedHashMap<>();
        double total = 0D;
        for (String id : keys(config, "loot.legendary.entries")) {
            String path = "loot.legendary.entries." + id;
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
            problems.add("loot.legendary.entries hat Gewichtsumme " + total + " statt 100");
        if (loaded.isEmpty()) problems.add("loot.legendary.entries enthält keine gültigen Einträge");
        entries = List.copyOf(loaded);
        byId = Map.copyOf(indexed);
        weights = Collections.unmodifiableMap(new LinkedHashMap<>(configuredWeights));
        ready = problems.isEmpty();
        for (String problem : problems) logger.warning("Legendary-Pool nicht bereit: " + problem);
    }

    public boolean ready() { return ready; }
    public int requiredPrestige() { return requiredPrestige; }
    public Map<String, Double> weights() { return weights; }
    public Set<String> entryIds() { return weights.keySet(); }

    public String rollEntryId(Random random, int prestige) { return chooseEntry(random, prestige).id(); }
    public Result roll(Random random, int prestige) { return create(chooseEntry(random, prestige), random, prestige); }

    public Result rollSpecific(String id, Random random, int prestige) {
        checkAvailable(prestige);
        Entry entry = byId.get(id.toLowerCase(Locale.ROOT));
        if (entry == null) throw new IllegalArgumentException("Unbekannter Legendary-Eintrag: " + id);
        return create(entry, random, prestige);
    }

    private Result create(Entry entry, Random random, int prestige) {
        return new Result(entry.id(), entry.displayName(), entry.factory().create(random, prestige));
    }

    private Entry chooseEntry(Random random, int prestige) {
        checkAvailable(prestige);
        double draw = random.nextDouble() * entries.stream().mapToDouble(Entry::weight).sum();
        Entry last = entries.getLast();
        for (Entry entry : entries) {
            draw -= entry.weight();
            if (draw < 0D) return entry;
        }
        return last;
    }

    private void checkAvailable(int prestige) {
        if (!ready) throw new IllegalStateException("Legendary-Pool ist nicht vollständig konfiguriert");
        if (prestige < requiredPrestige)
            throw new IllegalStateException("Legendary-Loot erfordert Angler-Prestige " + requiredPrestige);
    }

    private Factory parseFactory(FileConfiguration config, String path, List<String> problems) {
        String configured = config.getString(path + ".type");
        String type = configured == null ? "" : configured.toUpperCase(Locale.ROOT);
        return switch (type) {
            case "CUSTOM_ITEM" -> {
                String id = config.getString(path + ".item-id");
                int amount = config.getInt(path + ".amount");
                if (id == null || id.isBlank() || amount < 1 || amount > 64) {
                    problems.add(path + " benötigt Custom-Item-ID und gültige Menge");
                    yield null;
                }
                yield (random, prestige) -> new AnglerLootFoundation.CustomItemReward(id, amount);
            }
            case "BUNDLE" -> parseBundle(config, path, problems);
            case "EQUIPMENT_BUNDLE" -> parseEquipmentBundle(config, path, problems);
            case "BOOK_BUNDLE" -> parseBookBundle(config, path, problems);
            case "LUMI" -> {
                long amount = config.getLong(path + ".amount");
                if (amount <= 0) { problems.add(path + ".amount muss positiv sein"); yield null; }
                yield (random, prestige) -> new AnglerLootFoundation.CurrencyReward(
                        AnglerLootFoundation.RewardType.LUMI, amount);
            }
            default -> { problems.add(path + ".type ist unbekannt: " + type); yield null; }
        };
    }

    private Factory parseBundle(FileConfiguration config, String path, List<String> problems) {
        List<AnglerLootFoundation.LootReward> contents = new ArrayList<>();
        for (Map<?, ?> line : config.getMapList(path + ".contents")) {
            int amount = number(line.get("amount"));
            Object custom = line.get("item-id");
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

    private Factory parseEquipmentBundle(FileConfiguration config, String path, List<String> problems) {
        List<AnglerLootFoundation.LootReward> items = new ArrayList<>();
        for (Map<?, ?> line : config.getMapList(path + ".items")) {
            // The same normal-level equipment parser already used by Epic tools.
            ItemStack item = FishingEpicPool.parseTool(line, path + ".items", problems);
            if (item != null) items.add(new AnglerLootFoundation.NormalItemReward(item));
        }
        if (items.isEmpty()) { problems.add(path + ".items ist leer"); return null; }
        List<AnglerLootFoundation.LootReward> frozen = List.copyOf(items);
        return (random, prestige) -> new AnglerLootFoundation.BundleReward(frozen);
    }

    private Factory parseBookBundle(FileConfiguration config, String path, List<String> problems) {
        int overlevelCount = config.getInt(path + ".overlevel-count");
        int vanillaCount = config.getInt(path + ".vanilla-count");
        if (overlevelCount < 0 || vanillaCount < 0 || overlevelCount + vanillaCount < 1
                || overlevelCount + vanillaCount > 64 || (overlevelCount > 0 && !rare.ready())) {
            problems.add(path + " hat ungültige Buchanzahlen oder keinen Overlevel-Generator");
            return null;
        }
        List<Weighted<AnglerLootFoundation.NormalItemReward>> vanilla = new ArrayList<>();
        for (String id : keys(config, path + ".vanilla-books")) {
            String candidatePath = path + ".vanilla-books." + id;
            Enchantment enchantment = enchantment(config.getString(candidatePath + ".enchantment"));
            int level = config.getInt(candidatePath + ".level");
            double weight = config.getDouble(candidatePath + ".weight");
            if (enchantment == null || level < 1 || level > enchantment.getMaxLevel() || !positive(weight)) {
                problems.add(candidatePath + " hat ungültigen Vanilla-Enchant, Level oder Gewicht");
                continue;
            }
            vanilla.add(new Weighted<>(FishingTreasurePool.createVanillaBook(enchantment, level), weight));
        }
        if (vanillaCount > 0 && vanilla.isEmpty()) {
            problems.add(path + ".vanilla-books enthält keine gültigen Kandidaten"); return null;
        }
        List<Weighted<AnglerLootFoundation.NormalItemReward>> frozen = List.copyOf(vanilla);
        return (random, prestige) -> {
            List<AnglerLootFoundation.LootReward> contents = new ArrayList<>(overlevelCount + vanillaCount);
            for (int index = 0; index < overlevelCount; index++)
                contents.add(rare.rollSpecific("overlevel_book", random, prestige).reward());
            for (int index = 0; index < vanillaCount; index++) contents.add(choose(frozen, random));
            return new AnglerLootFoundation.BundleReward(contents);
        };
    }

    private static Enchantment enchantment(String raw) {
        if (raw == null) return null;
        String lowered = raw.toLowerCase(Locale.ROOT);
        NamespacedKey key = NamespacedKey.fromString(lowered.contains(":") ? lowered : "minecraft:" + lowered);
        return key == null || !key.getNamespace().equals("minecraft") ? null : Registry.ENCHANTMENT.get(key);
    }

    private static <T> T choose(List<Weighted<T>> options, Random random) {
        if (options.isEmpty()) throw new IllegalStateException("Keine Vanilla-Buchkandidaten für Legendary");
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

    private static boolean positive(double value) { return Double.isFinite(value) && value > 0D; }
}
