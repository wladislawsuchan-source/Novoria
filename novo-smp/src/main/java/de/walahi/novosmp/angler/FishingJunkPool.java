package de.walahi.novosmp.angler;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.logging.Logger;

/** One configurable internal JUNK roll after the existing outer category roll. */
public final class FishingJunkPool {
    public record Result(String id, String displayName, AnglerLootFoundation.LootReward reward) { }
    private record Entry(String id, String displayName, double weight, Material material,
                         int minAmount, int maxAmount, int minDurability, int maxDurability,
                         boolean damaged) { }

    private final List<Entry> entries;
    private final Map<String, Double> weights;
    private final boolean ready;

    public FishingJunkPool(FileConfiguration config, Logger logger) {
        List<String> problems = new ArrayList<>();
        List<Entry> loaded = new ArrayList<>();
        Map<String, Double> configuredWeights = new LinkedHashMap<>();
        double total = 0D;
        Set<String> ids = new LinkedHashSet<>();
        ConfigurationSection defaults = config.getDefaults() == null ? null
                : config.getDefaults().getConfigurationSection("loot.junk.entries");
        ConfigurationSection physical = config.getConfigurationSection("loot.junk.entries");
        if (defaults != null) ids.addAll(defaults.getKeys(false));
        if (physical != null) ids.addAll(physical.getKeys(false));
        for (String id : ids) {
            String path = "loot.junk.entries." + id;
            double weight = config.getDouble(path + ".weight");
            if (!Double.isFinite(weight) || weight <= 0D) {
                problems.add(path + ".weight muss positiv sein");
                continue;
            }
            total += weight;
            configuredWeights.put(id, weight);
            if (!"NORMAL_ITEM".equalsIgnoreCase(config.getString(path + ".type"))) {
                problems.add(path + ".type muss NORMAL_ITEM sein");
                continue;
            }
            String materialName = config.getString(path + ".material");
            Material material = materialName == null ? null : Material.matchMaterial(materialName);
            if (material == null || !material.isItem()) {
                problems.add(path + ".material ist kein gültiges Item");
                continue;
            }
            boolean damaged = config.get(path + ".min-durability-percent") != null
                    || config.get(path + ".max-durability-percent") != null;
            int fixedAmount = config.getInt(path + ".amount");
            int minAmount = config.get(path + ".min-amount") == null
                    ? fixedAmount : config.getInt(path + ".min-amount");
            int maxAmount = config.get(path + ".max-amount") == null
                    ? fixedAmount : config.getInt(path + ".max-amount");
            int minDurability = config.getInt(path + ".min-durability-percent");
            int maxDurability = config.getInt(path + ".max-durability-percent");
            if (minAmount < 1 || maxAmount < minAmount || maxAmount > material.getMaxStackSize()
                    || (damaged && (minDurability < 1 || maxDurability > 100
                    || maxDurability < minDurability || material.getMaxDurability() <= 0))) {
                problems.add(path + " hat ungültige Mengen- oder Haltbarkeitsgrenzen");
                continue;
            }
            String displayName = config.getString(path + ".display-name");
            loaded.add(new Entry(id, displayName == null ? id : displayName, weight,
                    material, minAmount, maxAmount, minDurability, maxDurability, damaged));
        }
        if (Math.abs(total - 100D) > 0.000001D)
            problems.add("loot.junk.entries hat Gewichtsumme " + total + " statt 100");
        if (loaded.isEmpty()) problems.add("loot.junk.entries enthält keine gültigen Einträge");
        entries = List.copyOf(loaded);
        weights = Collections.unmodifiableMap(new LinkedHashMap<>(configuredWeights));
        ready = problems.isEmpty();
        for (String problem : problems) logger.warning("Junk-Pool nicht bereit: " + problem);
    }

    public boolean ready() { return ready; }
    public Map<String, Double> weights() { return weights; }

    /** Uses the same weighted entry selection as the real roll; constructs no reward. */
    public String rollEntryId(Random random) { return choose(random).id(); }

    public Result roll(Random random) {
        Entry entry = choose(random);
        int amount = entry.minAmount() == entry.maxAmount() ? entry.minAmount()
                : random.nextInt(entry.minAmount(), entry.maxAmount() + 1);
        ItemStack item = new ItemStack(entry.material(), amount);
        if (entry.damaged()) {
            int remainingPercent = random.nextInt(entry.minDurability(), entry.maxDurability() + 1);
            int maximum = entry.material().getMaxDurability();
            int remaining = Math.max(1, Math.round(maximum * remainingPercent / 100F));
            ItemMeta meta = item.getItemMeta();
            if (!(meta instanceof Damageable damageable))
                throw new IllegalStateException("Junk-Item hat keine Haltbarkeit: " + entry.material());
            damageable.setDamage(maximum - remaining);
            item.setItemMeta(meta);
        }
        return new Result(entry.id(), entry.displayName(), new AnglerLootFoundation.NormalItemReward(item));
    }

    private Entry choose(Random random) {
        if (!ready) throw new IllegalStateException("Junk-Pool ist nicht vollständig konfiguriert");
        double draw = random.nextDouble() * 100D;
        Entry last = entries.getLast();
        for (Entry entry : entries) {
            draw -= entry.weight();
            if (draw < 0D) return entry;
        }
        return last;
    }
}
