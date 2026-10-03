package de.walahi.novosmp.angler;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Logger;

/** One weighted internal roll after the outer TREASURE result; no other category is rolled here. */
public final class FishingTreasurePool {
    public record Result(String id, String displayName, AnglerLootFoundation.LootReward reward) { }
    private record Weighted<T>(T value, double weight) { }
    private record BookSpec(Enchantment enchantment, int level) { }
    private record Entry(String id, String displayName, double weight,
                         Function<Random, AnglerLootFoundation.LootReward> factory) { }

    private final List<Weighted<List<Weighted<BookSpec>>>> bookGroups;
    private final List<Weighted<Entry>> entries;
    private final Map<String, Double> weights;
    private final boolean ready;

    public FishingTreasurePool(FileConfiguration config, Logger logger) {
        List<String> problems = new ArrayList<>();
        bookGroups = parseBookGroups(config, problems);
        List<Weighted<Entry>> loaded = new ArrayList<>();
        Map<String, Double> configuredWeights = new LinkedHashMap<>();
        double total = 0D;
        for (String id : keys(config, "loot.treasure.entries")) {
            String path = "loot.treasure.entries." + id;
            double weight = config.getDouble(path + ".weight");
            if (!positive(weight)) {
                problems.add(path + ".weight muss positiv sein");
                continue;
            }
            total += weight;
            configuredWeights.put(id, weight);
            Function<Random, AnglerLootFoundation.LootReward> factory = parseFactory(config, path, problems);
            if (factory == null) continue;
            String displayName = config.getString(path + ".display-name");
            if (displayName == null) displayName = id;
            loaded.add(new Weighted<>(new Entry(id, displayName, weight, factory), weight));
        }
        if (Math.abs(total - 100D) > 0.000001D)
            problems.add("loot.treasure.entries hat Gewichtsumme " + total + " statt 100");
        if (loaded.isEmpty()) problems.add("loot.treasure.entries enthält keine gültigen Einträge");
        entries = List.copyOf(loaded);
        weights = Collections.unmodifiableMap(new LinkedHashMap<>(configuredWeights));
        ready = problems.isEmpty();
        for (String problem : problems) logger.warning("Treasure-Pool nicht bereit: " + problem);
    }

    public boolean ready() { return ready; }
    public Map<String, Double> weights() { return weights; }

    /** Simulation only: no items, currency or storage operations. */
    public String rollEntryId(Random random) {
        if (!ready) throw new IllegalStateException("Treasure-Pool ist nicht vollständig konfiguriert");
        return choose(entries, random).id();
    }

    public Result roll(Random random) {
        if (!ready) throw new IllegalStateException("Treasure-Pool ist nicht vollständig konfiguriert");
        Entry entry = choose(entries, random);
        return new Result(entry.id(), entry.displayName(), entry.factory().apply(random));
    }

    private List<Weighted<List<Weighted<BookSpec>>>> parseBookGroups(FileConfiguration config, List<String> problems) {
        List<Weighted<List<Weighted<BookSpec>>>> groups = new ArrayList<>();
        double total = 0D;
        for (String groupId : keys(config, "loot.treasure.enchanted-books.groups")) {
            String path = "loot.treasure.enchanted-books.groups." + groupId;
            double weight = config.getDouble(path + ".weight");
            if (!positive(weight)) {
                problems.add(path + ".weight muss positiv sein");
                continue;
            }
            total += weight;
            List<Weighted<BookSpec>> books = new ArrayList<>();
            for (String bookId : keys(config, path + ".books")) {
                String bookPath = path + ".books." + bookId;
                String rawEnchant = config.getString(bookPath + ".enchantment");
                NamespacedKey key = NamespacedKey.fromString(rawEnchant == null ? "" : rawEnchant);
                Enchantment enchantment = key == null ? null : Registry.ENCHANTMENT.get(key);
                int level = config.getInt(bookPath + ".level");
                double bookWeight = config.getDouble(bookPath + ".weight");
                if (enchantment == null || level < 1 || level > enchantment.getMaxLevel()
                        || !positive(bookWeight)) {
                    problems.add(bookPath + " hat ungültigen Vanilla-Enchant, Level oder Gewicht");
                    continue;
                }
                books.add(new Weighted<>(new BookSpec(enchantment, level), bookWeight));
            }
            if (books.isEmpty()) problems.add(path + ".books enthält keine gültigen Bücher");
            else groups.add(new Weighted<>(List.copyOf(books), weight));
        }
        if (Math.abs(total - 100D) > 0.000001D)
            problems.add("loot.treasure.enchanted-books.groups hat Gewichtsumme " + total + " statt 100");
        return List.copyOf(groups);
    }

    private Function<Random, AnglerLootFoundation.LootReward> parseFactory(
            FileConfiguration config, String path, List<String> problems) {
        String rawType = config.getString(path + ".type");
        if (rawType == null) rawType = "";
        return switch (rawType.toUpperCase(Locale.ROOT)) {
            case "NORMAL_ITEM" -> {
                Material material = material(config.getString(path + ".material"));
                int amount = config.getInt(path + ".amount");
                if (material == null || amount <= 0 || amount > material.getMaxStackSize()) {
                    problems.add(path + " benötigt gültiges Item-Material und eine stapelbare Menge");
                    yield null;
                }
                yield random -> new AnglerLootFoundation.NormalItemReward(new ItemStack(material, amount));
            }
            case "ENCHANTED_BOOK" -> {
                if (bookGroups.isEmpty()) problems.add(path + " benötigt gültige Buchgruppen");
                yield this::rollBook;
            }
            case "BUNDLE" -> {
                List<Map<?, ?>> configured = config.getMapList(path + ".contents");
                List<AnglerLootFoundation.LootReward> contents = new ArrayList<>();
                for (Map<?, ?> configuredItem : configured) {
                    Material material = material(String.valueOf(configuredItem.get("material")));
                    int amount = number(configuredItem.get("amount"));
                    if (material == null || amount <= 0 || amount > material.getMaxStackSize()) {
                        problems.add(path + ".contents enthält ungültiges Item oder Menge");
                        continue;
                    }
                    contents.add(new AnglerLootFoundation.NormalItemReward(new ItemStack(material, amount)));
                }
                if (contents.isEmpty()) {
                    problems.add(path + ".contents ist leer");
                    yield null;
                }
                List<AnglerLootFoundation.LootReward> frozen = List.copyOf(contents);
                yield random -> new AnglerLootFoundation.BundleReward(frozen);
            }
            case "RANDOM_TRIM_BUNDLE" -> {
                int count = config.getInt(path + ".count");
                List<Weighted<Material>> templates = new ArrayList<>();
                for (String materialName : keys(config, path + ".templates")) {
                    Material material = material(materialName);
                    double weight = config.getDouble(path + ".templates." + materialName);
                    if (material == null || !material.name().endsWith("_ARMOR_TRIM_SMITHING_TEMPLATE")
                            || !positive(weight)) {
                        problems.add(path + ".templates enthält ungültiges Template oder Gewicht: " + materialName);
                        continue;
                    }
                    templates.add(new Weighted<>(material, weight));
                }
                if (count <= 0 || templates.isEmpty()) {
                    problems.add(path + " benötigt positive count und gültige Templates");
                    yield null;
                }
                List<Weighted<Material>> frozen = List.copyOf(templates);
                yield random -> {
                    List<AnglerLootFoundation.LootReward> contents = new ArrayList<>(count);
                    for (int index = 0; index < count; index++) {
                        Material selected = choose(frozen, random); // with replacement: duplicates are allowed
                        contents.add(new AnglerLootFoundation.NormalItemReward(new ItemStack(selected, 1)));
                    }
                    return new AnglerLootFoundation.BundleReward(contents);
                };
            }
            default -> {
                problems.add(path + ".type ist unbekannt: " + rawType);
                yield null;
            }
        };
    }

    private AnglerLootFoundation.LootReward rollBook(Random random) {
        BookSpec selected = choose(choose(bookGroups, random), random);
        return createVanillaBook(selected.enchantment(), selected.level());
    }

    /** Shared normal-level Vanilla book primitive for Treasure and Epic. */
    static AnglerLootFoundation.NormalItemReward createVanillaBook(Enchantment enchantment, int level) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        if (!(book.getItemMeta() instanceof EnchantmentStorageMeta meta)
                || !meta.addStoredEnchant(enchantment, level, false))
            throw new IllegalStateException("Vanilla-Treasure-Buch konnte nicht erzeugt werden");
        book.setItemMeta(meta);
        return new AnglerLootFoundation.NormalItemReward(book);
    }

    /** Same normal Vanilla-book generator, also used by the Rare book bundle. */
    public AnglerLootFoundation.LootReward rollVanillaBook(Random random) {
        if (!ready) throw new IllegalStateException("Vanilla-Buchgenerator ist nicht vollständig konfiguriert");
        return rollBook(random);
    }

    private static <T> T choose(List<Weighted<T>> options, Random random) {
        if (options.isEmpty()) throw new IllegalStateException("Gewichtete Treasure-Auswahl ist leer");
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
        ConfigurationSection defaults = config.getDefaults() == null
                ? null : config.getDefaults().getConfigurationSection(path);
        ConfigurationSection physical = config.getConfigurationSection(path);
        if (defaults != null) result.addAll(defaults.getKeys(false));
        if (physical != null) result.addAll(physical.getKeys(false));
        return result;
    }

    private static Material material(String raw) {
        Material result = raw == null ? null : Material.matchMaterial(raw);
        return result != null && result.isItem() ? result : null;
    }

    private static int number(Object raw) {
        if (raw instanceof Number number) return number.intValue();
        try { return Integer.parseInt(String.valueOf(raw)); }
        catch (NumberFormatException exception) { return 0; }
    }

    private static boolean positive(double value) { return Double.isFinite(value) && value > 0D; }
}
