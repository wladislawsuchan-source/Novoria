package de.walahi.novosmp.angler;

import de.walahi.novosmp.items.CustomItemManager;
import de.walahi.novosmp.lumi.LumiRepository;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.BundleContents;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Item/currency primitives for later Angler loot tables; no pool contents are selected here. */
public final class AnglerLootFoundation {
    public enum RewardType { NORMAL_ITEM, CUSTOM_ITEM, OVERLEVEL_BOOK, BUNDLE, LUMI }
    public sealed interface LootReward permits NormalItemReward, CustomItemReward,
            OverlevelBookReward, BundleReward, CurrencyReward {
        RewardType type();
    }
    public record NormalItemReward(ItemStack item) implements LootReward {
        public NormalItemReward {
            if (item == null || item.getType().isAir() || item.getAmount() <= 0)
                throw new IllegalArgumentException("Normales Loot-Item ist ungültig");
            item = item.clone();
        }
        @Override public ItemStack item() { return item.clone(); }
        @Override public RewardType type() { return RewardType.NORMAL_ITEM; }
    }
    public record CustomItemReward(String id, int amount) implements LootReward {
        public CustomItemReward {
            if (id == null || id.isBlank() || amount <= 0)
                throw new IllegalArgumentException("Custom-Item-Loot braucht ID und positive Anzahl");
        }
        @Override public RewardType type() { return RewardType.CUSTOM_ITEM; }
    }
    public record OverlevelBookReward(String id, int anglerPrestige) implements LootReward {
        public OverlevelBookReward {
            if (id == null || id.isBlank() || anglerPrestige < 0 || anglerPrestige > 5)
                throw new IllegalArgumentException("Overlevel-Buch-Loot braucht ID und gültiges Prestige");
        }
        @Override public RewardType type() { return RewardType.OVERLEVEL_BOOK; }
    }
    public record BundleReward(List<LootReward> contents) implements LootReward {
        public BundleReward {
            if (contents == null || contents.isEmpty() || contents.stream().anyMatch(Objects::isNull))
                throw new IllegalArgumentException("Bundle-Loot braucht gültige Inhalte");
            contents = List.copyOf(contents);
        }
        @Override public RewardType type() { return RewardType.BUNDLE; }
    }
    public record CurrencyReward(RewardType type, long amount) implements LootReward {
        public CurrencyReward {
            if (type != RewardType.LUMI || amount <= 0)
                throw new IllegalArgumentException("Lumi-Loot braucht eine positive Menge");
        }
    }
    public record DeliveryResult(RewardType type, boolean success, long lumisCredited,
                                 int storedItems, int droppedItems) { }
    public record OverlevelBook(String id, NamespacedKey enchantment, int level, int minPrestige) { }

    private final CustomItemManager customItems;
    private final LumiRepository lumis;
    private final Logger logger;
    private Map<String, OverlevelBook> overlevelBooks = Map.of();

    public AnglerLootFoundation(CustomItemManager customItems, LumiRepository lumis,
                                FileConfiguration anglerConfig, Logger logger) {
        this.customItems = Objects.requireNonNull(customItems);
        this.lumis = Objects.requireNonNull(lumis);
        this.logger = Objects.requireNonNull(logger);
        reload(anglerConfig);
    }

    public void reload(FileConfiguration anglerConfig) {
        Map<String, OverlevelBook> configured = parseOverlevelBooks(anglerConfig,
                reason -> logger.warning("Angler-Overlevel-Buch übersprungen: " + reason));
        Map<String, OverlevelBook> valid = new LinkedHashMap<>();
        for (OverlevelBook book : configured.values()) {
            if (Registry.ENCHANTMENT.get(book.enchantment()) == null) {
                logger.warning("Angler-Overlevel-Buch übersprungen: " + book.id()
                        + " verwendet unbekannten Enchant " + book.enchantment());
            } else valid.put(book.id(), book);
        }
        overlevelBooks = Map.copyOf(valid);
    }

    public static Map<String, OverlevelBook> parseOverlevelBooks(FileConfiguration anglerConfig) {
        return parseOverlevelBooks(anglerConfig, reason -> { });
    }

    private static Map<String, OverlevelBook> parseOverlevelBooks(FileConfiguration anglerConfig,
                                                                   Consumer<String> report) {
        Map<String, OverlevelBook> parsed = new LinkedHashMap<>();
        ConfigurationSection physical = anglerConfig.getConfigurationSection("loot.overlevel-books");
        ConfigurationSection defaults = anglerConfig.getDefaults() == null ? null
                : anglerConfig.getDefaults().getConfigurationSection("loot.overlevel-books");
        Set<String> ids = new LinkedHashSet<>();
        if (defaults != null) ids.addAll(defaults.getKeys(false));
        if (physical != null) ids.addAll(physical.getKeys(false));
        for (String rawId : ids) {
            ConfigurationSection section = physical == null ? null : physical.getConfigurationSection(rawId);
            if (section == null && defaults != null) section = defaults.getConfigurationSection(rawId);
            if (section == null) {
                report.accept(rawId + " hat keinen gültigen YAML-Abschnitt");
                continue;
            }
            NamespacedKey key = NamespacedKey.fromString(section.getString("enchantment", ""));
            int level = section.getInt("level", 0);
            int prestige = section.getInt("min-prestige", -1);
            if (key == null || level < 1 || prestige < 0 || prestige > 5) {
                report.accept(rawId + " hat ungültigen Enchant, Level oder min-prestige");
                continue;
            }
            String id = rawId.toLowerCase(Locale.ROOT);
            parsed.put(id, new OverlevelBook(id, key, level, prestige));
        }
        return Map.copyOf(parsed);
    }

    public Map<String, OverlevelBook> overlevelBooks() { return overlevelBooks; }

    /** Uses the existing custom_item_id path, including the existing Magnet I book. */
    public ItemStack createCustom(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            logger.warning("Angler-Loot: Custom-Item-ID fehlt");
            return null;
        }
        ItemStack item = customItems.create(itemId, 1);
        if (item == null) logger.warning("Angler-Loot: unbekannte Custom-Item-ID '" + itemId + "'");
        return item;
    }

    /** Vanilla stored enchantment above its normal cap, not a new custom enchantment. */
    public ItemStack createOverlevelBook(String id, int anglerPrestige) {
        if (id == null) {
            logger.warning("Angler-Loot: Overlevel-Buch-ID fehlt");
            return null;
        }
        OverlevelBook definition = overlevelBooks.get(id.toLowerCase(Locale.ROOT));
        if (definition == null) {
            logger.warning("Angler-Loot: unbekannte Overlevel-Buch-ID '" + id + "'");
            return null;
        }
        if (anglerPrestige < definition.minPrestige()) {
            logger.warning("Angler-Loot: '" + id + "' erfordert Prestige " + definition.minPrestige());
            return null;
        }
        ItemStack book = createOverlevelBook(definition);
        if (book == null) logger.warning("Angler-Loot: Enchant für Overlevel-Buch '" + id + "' ist nicht verfügbar");
        return book;
    }

    static ItemStack createOverlevelBook(OverlevelBook definition) {
        if (definition == null) return null;
        Enchantment enchantment = Registry.ENCHANTMENT.get(definition.enchantment());
        if (enchantment == null) return null;
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK, 1);
        if (!(book.getItemMeta() instanceof EnchantmentStorageMeta meta)) return null;
        if (!meta.addStoredEnchant(enchantment, definition.level(), true)) return null;
        book.setItemMeta(meta);
        return book;
    }

    /** Direct account credit; no item or Fanglager operation is involved. */
    public boolean credit(Player player, CurrencyReward reward) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(reward, "reward");
        try {
            boolean credited = lumis.add(player.getUniqueId(), reward.amount());
            if (!credited) logger.warning("Angler-Lumi-Gutschrift für " + player.getUniqueId()
                    + " wurde abgelehnt (" + reward.amount() + ")");
            return credited;
        } catch (RuntimeException exception) {
            logger.log(Level.SEVERE, "Angler-Lumi-Gutschrift für " + player.getUniqueId()
                    + " fehlgeschlagen (" + reward.amount() + ")", exception);
            return false;
        }
    }

    /** Converts only physical rewards. A LUMI reward cannot enter a bundle or Fanglager. */
    public ItemStack createPhysicalReward(LootReward reward) {
        Objects.requireNonNull(reward, "reward");
        if (reward instanceof NormalItemReward normal) return normal.item();
        if (reward instanceof CustomItemReward custom) {
            ItemStack item = createCustom(custom.id());
            if (item == null) throw new IllegalArgumentException("Unbekannte Custom-Item-ID: " + custom.id());
            if (custom.amount() > item.getMaxStackSize())
                throw new IllegalArgumentException("Custom-Item-Anzahl über Stacklimit: " + custom.id());
            item.setAmount(custom.amount());
            return item;
        }
        if (reward instanceof OverlevelBookReward overlevel) {
            ItemStack item = createOverlevelBook(overlevel.id(), overlevel.anglerPrestige());
            if (item == null) throw new IllegalArgumentException("Overlevel-Buch nicht erzeugbar: " + overlevel.id());
            return item;
        }
        if (reward instanceof BundleReward bundle) {
            List<ItemStack> contents = new ArrayList<>(bundle.contents().size());
            for (LootReward part : bundle.contents()) contents.add(createPhysicalReward(part));
            return createBundle(contents);
        }
        throw new IllegalArgumentException("Lumis sind Währung und dürfen nicht als Item ausgegeben werden");
    }

    /** Future pool entry point: physical rewards use the existing Fanglager, Lumis bypass it. */
    public DeliveryResult deliver(Player player, LootReward reward, AnglerFeature angler) {
        RewardType type = reward == null ? null : reward.type();
        try {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(reward, "reward");
            if (reward instanceof CurrencyReward currency) {
                if (!credit(player, currency)) throw new IllegalStateException("Lumi-Gutschrift fehlgeschlagen");
                return new DeliveryResult(type, true, currency.amount(), 0, 0);
            }
            Objects.requireNonNull(angler, "angler");
            ItemStack item = createPhysicalReward(reward);
            ItemStack overflow = angler.storeCatch(player, item);
            int dropped = overflow == null ? 0 : overflow.getAmount();
            if (overflow != null) player.getWorld().dropItemNaturally(player.getLocation(), overflow);
            return new DeliveryResult(type, true, 0L, item.getAmount() - dropped, dropped);
        } catch (RuntimeException exception) {
            logger.log(Level.SEVERE, "Angler-Loot konnte nicht ausgegeben werden (Typ: " + type + ")", exception);
            return new DeliveryResult(type, false, 0L, 0, 0);
        }
    }

    /** Pure repair primitive; does not choose an interaction or consume a Reparaturkern. */
    public static ItemStack repairedDurabilityCopy(ItemStack target) {
        if (target == null || target.getType().isAir()) return null;
        ItemStack result = target.clone();
        ItemMeta meta = result.getItemMeta();
        if (!(meta instanceof Damageable damageable) || damageable.getDamage() <= 0) return null;
        damageable.setDamage(0);
        result.setItemMeta(meta);
        return result;
    }

    /** Native bundle with its normal client behaviour. Deliberately no capacity precheck. */
    public static ItemStack createBundle(List<ItemStack> contents) {
        Objects.requireNonNull(contents, "contents");
        List<ItemStack> copies = new ArrayList<>(contents.size());
        for (ItemStack item : contents) {
            if (item == null || item.getType().isAir() || item.getAmount() <= 0)
                throw new IllegalArgumentException("Bundle-Inhalt muss ein gültiger ItemStack sein");
            copies.add(item.clone());
        }
        ItemStack bundle = new ItemStack(Material.BUNDLE, 1);
        bundle.setData(DataComponentTypes.BUNDLE_CONTENTS, BundleContents.bundleContents(copies));
        return bundle;
    }

    public static List<ItemStack> bundleContents(ItemStack bundle) {
        if (bundle == null || bundle.getType() != Material.BUNDLE) return List.of();
        BundleContents data = bundle.getData(DataComponentTypes.BUNDLE_CONTENTS);
        return data == null ? List.of() : data.contents();
    }
}
