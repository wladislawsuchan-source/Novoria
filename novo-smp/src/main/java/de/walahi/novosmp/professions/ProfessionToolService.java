package de.walahi.novosmp.professions;

import de.walahi.novosmp.enchants.CustomEnchantmentService;
import de.walahi.novosmp.enchants.HolzschlagConfig;
import de.walahi.novosmp.enchants.MiningEnchantConfig;
import de.walahi.novosmp.items.CustomItemManager;
import de.walahi.smpcore.SMPCorePlugin;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Server-side serial registry for profession tools and other one-per-entitlement reward items.
 * Replacements never need to locate the old physical item: its serial is revoked in SQL and the
 * old copy is converted to an ordinary vanilla item the next time the server sees it.
 */
public final class ProfessionToolService {
    public static final String HEAD_REWARD_GROUP = "head_rewards";

    private static final String TOOL_MARKER_PREFIX = "Berufswerkzeug •";
    private static final String REWARD_MARKER_PREFIX = "Belohnungsitem •";
    private static final String SERIAL_MARKER_PREFIX = "Seriennummer:";

    private final SMPCorePlugin plugin;
    private final ProfessionRepository repository;
    private final CustomItemManager customItems;
    private final CustomEnchantmentService enchantments;
    private final ProfessionConfig config;
    private final NamespacedKey serialKey;
    private final NamespacedKey professionKey;
    private final NamespacedKey prestigeKey;
    private final NamespacedKey mergeKey;
    private final NamespacedKey mergeTierKey;
    private final NamespacedKey mergeArmorKey;
    private final NamespacedKey mergeToughnessKey;
    private final NamespacedKey mergeKnockbackKey;
    private final Map<String, Boolean> activeCache = new ConcurrentHashMap<>();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();

    public ProfessionToolService(SMPCorePlugin plugin, ProfessionRepository repository,
                                 CustomItemManager customItems,
                                 CustomEnchantmentService enchantments,
                                 ProfessionConfig config) {
        this.plugin = plugin;
        this.repository = repository;
        this.customItems = customItems;
        this.enchantments = enchantments;
        this.config = config;
        this.serialKey = new NamespacedKey(plugin, "profession_tool_serial");
        this.professionKey = new NamespacedKey(plugin, "profession_tool_id");
        this.prestigeKey = new NamespacedKey(plugin, "profession_tool_prestige");
        this.mergeKey = new NamespacedKey(plugin, "merge_helmet");
        this.mergeTierKey = new NamespacedKey(plugin, "merge_helmet_tier");
        this.mergeArmorKey = new NamespacedKey(plugin, "merge_helmet_armor");
        this.mergeToughnessKey = new NamespacedKey(plugin, "merge_helmet_toughness");
        this.mergeKnockbackKey = new NamespacedKey(plugin, "merge_helmet_knockback");
    }

    public ItemStack createLumberjackTool(String customItemId, String serial, int prestige) {
        ItemStack item = customItems.create(customItemId, 1);
        if (item == null) return null;
        applySerialMetadata(item, serial, ProfessionConfig.LUMBERJACK_ID, prestige,
                "<dark_gray>Berufswerkzeug • Holzfäller</dark_gray>", false);
        return item;
    }

    public ItemStack createMinerTool(String customItemId, int prestige) {
        return createMinerTool(customItemId, null, prestige);
    }

    public ItemStack createMinerTool(String customItemId, String serial, int prestige) {
        ItemStack item = customItems.create(customItemId, 1);
        if (item == null) return null;
        applySerialMetadata(item, serial, ProfessionConfig.MINER_ID, prestige,
                "<dark_gray>Berufswerkzeug • Bergarbeiter</dark_gray>", true);
        return item;
    }

    public ItemStack createHunterReward(String customItemId, String serial, int prestige) {
        ItemStack item = customItems.create(customItemId, 1);
        if (item == null) return null;
        applySerialMetadata(item, serial, ProfessionConfig.HUNTER_ID, prestige,
                "<dark_gray>Belohnungsitem • Jäger Prestige " + roman(prestige) + "</dark_gray>", false);
        return item;
    }

    public ItemStack createHeadReward(String customItemId, String serial, int rewardSlot, String label) {
        ItemStack item = customItems.create(customItemId, 1);
        if (item == null) return null;
        String safeLabel = label == null || label.isBlank() ? "Kopfsammlung" : label;
        applySerialMetadata(item, serial, HEAD_REWARD_GROUP, rewardSlot,
                "<dark_gray>Belohnungsitem • " + safeLabel + "</dark_gray>", false);
        return item;
    }

    private void applySerialMetadata(ItemStack item, String serial, String group, int slot,
                                     String marker, boolean mendingHint) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        if (serial != null && !serial.isBlank()) {
            meta.getPersistentDataContainer().set(serialKey, PersistentDataType.STRING, serial);
        }
        meta.getPersistentDataContainer().set(professionKey, PersistentDataType.STRING, group);
        meta.getPersistentDataContainer().set(prestigeKey, PersistentDataType.INTEGER, slot);
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Component.empty());
        lore.add(miniMessage.deserialize(marker).decoration(TextDecoration.ITALIC, false));
        if (mendingHint) {
            lore.add(miniMessage.deserialize("<green>✓ Mending kann hinzugefügt werden</green>")
                    .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        if (serial != null && !serial.isBlank()) activeCache.put(serial, true);
    }

    public String serial(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(serialKey, PersistentDataType.STRING);
    }

    public String serialGroup(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(professionKey, PersistentDataType.STRING);
    }

    public int serialSlot(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return 0;
        Integer value = item.getItemMeta().getPersistentDataContainer().get(prestigeKey, PersistentDataType.INTEGER);
        return value == null ? 0 : value;
    }

    public boolean matchesSerial(ItemStack item, String serial) {
        String stored = serial(item);
        return stored != null && stored.equals(serial);
    }

    /** Copies serial entitlement metadata when a helmet is visually converted into a head. */
    public void transferSerialMetadata(ItemStack from, ItemStack to) {
        if (from == null || to == null || !from.hasItemMeta() || !to.hasItemMeta()) return;
        ItemMeta fromMeta = from.getItemMeta();
        ItemMeta toMeta = to.getItemMeta();
        String serial = fromMeta.getPersistentDataContainer().get(serialKey, PersistentDataType.STRING);
        String group = fromMeta.getPersistentDataContainer().get(professionKey, PersistentDataType.STRING);
        Integer slot = fromMeta.getPersistentDataContainer().get(prestigeKey, PersistentDataType.INTEGER);
        if (serial != null) toMeta.getPersistentDataContainer().set(serialKey, PersistentDataType.STRING, serial);
        if (group != null) toMeta.getPersistentDataContainer().set(professionKey, PersistentDataType.STRING, group);
        if (slot != null) toMeta.getPersistentDataContainer().set(prestigeKey, PersistentDataType.INTEGER, slot);
        to.setItemMeta(toMeta);
    }

    public boolean registerSerial(UUID owner, String group, int slot, String serial) {
        boolean created = repository.claimLegacyCollectedTool(owner, group, slot, serial);
        if (created) markActive(serial);
        return created;
    }

    public List<String> replaceSerial(UUID owner, String group, int slot, String newSerial) {
        List<String> revoked = repository.replaceCollectedTool(owner, group, slot, newSerial);
        revoked.forEach(this::markInactive);
        markActive(newSerial);
        return revoked;
    }

    /**
     * Registers legacy Miner/Hunter rewards on first contact. New rewards always arrive with a
     * serial already attached, so old duplicate copies are automatically stripped once one copy
     * has claimed the entitlement.
     */
    public void sanitizeForPlayer(Player player, ItemStack item) {
        if (player == null || item == null || item.getType().isAir() || !item.hasItemMeta()) return;
        String serial = serial(item);
        if (serial != null) {
            hideVisibleSerial(item);
            sanitizeRevoked(item);
            return;
        }

        ItemMeta meta = item.getItemMeta();
        String professionId = meta.getPersistentDataContainer().get(professionKey, PersistentDataType.STRING);
        Integer prestige = meta.getPersistentDataContainer().get(prestigeKey, PersistentDataType.INTEGER);
        if (ProfessionConfig.MINER_ID.equals(professionId) && prestige != null && prestige > 0) {
            registerLegacy(player, item, ProfessionConfig.MINER_ID, prestige, true);
            return;
        }

        String customId = customItems.identify(item);
        int hunterPrestige = hunterPrestige(customId);
        if (hunterPrestige > 0) registerLegacy(player, item, ProfessionConfig.HUNTER_ID, hunterPrestige, false);
    }

    private void registerLegacy(Player player, ItemStack item, String group, int slot, boolean miner) {
        try {
            PlayerProfessionState state = repository.load(player.getUniqueId());
            if (state.progress(group).prestige() < slot) {
                if (miner) sanitizeMinerProfessionTool(item);
                else sanitizeHunterReward(item);
                return;
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Legacy-Reward konnte nicht gegen die Freischaltung geprüft werden", exception);
            return;
        }
        String newSerial = newSerial();
        boolean claimed = repository.claimLegacyCollectedTool(player.getUniqueId(), group, slot, newSerial);
        if (!claimed) {
            if (miner) sanitizeMinerProfessionTool(item);
            else sanitizeHunterReward(item);
            return;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(serialKey, PersistentDataType.STRING, newSerial);
        meta.getPersistentDataContainer().set(professionKey, PersistentDataType.STRING, group);
        meta.getPersistentDataContainer().set(prestigeKey, PersistentDataType.INTEGER, slot);
        // Serial stays internal in PDC/SQL. Do not expose it in player-visible lore.
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        meta.lore(lore);
        item.setItemMeta(meta);
        markActive(newSerial);
    }

    private int hunterPrestige(String customId) {
        if (customId == null) return 0;
        for (int prestige = 1; prestige <= config.maxPrestige(); prestige++) {
            String configured = config.toolItemId(ProfessionConfig.HUNTER_ID, prestige);
            if (customId.equals(configured)) return prestige;
        }
        return 0;
    }

    /** Active serialised items keep their ability; revoked ones are immediately neutralised. */
    public boolean validateForAbility(ItemStack item) {
        String serial = serial(item);
        if (serial == null) return true;
        if (isActive(serial)) return true;
        sanitizeRevoked(item);
        return false;
    }

    public boolean isActive(String serial) {
        if (serial == null || serial.isBlank()) return false;
        return activeCache.computeIfAbsent(serial, key -> {
            try {
                return repository.isToolActive(key);
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Serienitem konnte nicht geprüft werden", exception);
                // Bei einem Datenbankfehler lieber nicht versehentlich ein echtes Reward-Item entwerten.
                return true;
            }
        });
    }

    public void markInactive(String serial) {
        if (serial != null && !serial.isBlank()) activeCache.put(serial, false);
    }

    public void markActive(String serial) {
        if (serial != null && !serial.isBlank()) activeCache.put(serial, true);
    }

    public boolean sanitizeRevoked(ItemStack item) {
        String serial = serial(item);
        if (serial == null || isActive(serial)) return false;
        ItemMeta before = item.getItemMeta();
        if (before == null) return false;
        String group = before.getPersistentDataContainer().get(professionKey, PersistentDataType.STRING);
        if (ProfessionConfig.MINER_ID.equals(group)) {
            sanitizeMinerProfessionTool(item);
        } else if (ProfessionConfig.HUNTER_ID.equals(group)) {
            sanitizeHunterReward(item);
        } else if (HEAD_REWARD_GROUP.equals(group)) {
            sanitizeHeadReward(item);
        } else {
            enchantments.remove(item, HolzschlagConfig.ENCHANTMENT_ID);
            clearSerialMetadata(item, false);
        }
        return true;
    }

    private void sanitizeMinerProfessionTool(ItemStack item) {
        enchantments.remove(item, MiningEnchantConfig.THREE_BY_THREE_ID);
        enchantments.remove(item, MiningEnchantConfig.VEINMINER_ID);
        enchantments.remove(item, MiningEnchantConfig.SMELTER_ID);
        enchantments.remove(item, MiningEnchantConfig.TNT_ID);
        clearSerialMetadata(item, true);
    }

    private void sanitizeHunterReward(ItemStack item) {
        customItems.stripToVanilla(item);
        clearSerialMetadata(item, false);
    }

    private void sanitizeHeadReward(ItemStack item) {
        customItems.stripToVanilla(item);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().remove(mergeKey);
            meta.getPersistentDataContainer().remove(mergeTierKey);
            removeModifier(meta, Attribute.ARMOR, mergeArmorKey);
            removeModifier(meta, Attribute.ARMOR_TOUGHNESS, mergeToughnessKey);
            removeModifier(meta, Attribute.KNOCKBACK_RESISTANCE, mergeKnockbackKey);
            item.setItemMeta(meta);
        }
        // A merged helmet is physically a head. Resetting the copied helmet components makes the
        // revoked copy an actual ordinary head again instead of invisible armor with a head model.
        item.resetData(DataComponentTypes.MAX_DAMAGE);
        item.resetData(DataComponentTypes.DAMAGE);
        item.resetData(DataComponentTypes.ENCHANTABLE);
        item.resetData(DataComponentTypes.REPAIRABLE);
        clearSerialMetadata(item, false);
    }

    private void removeModifier(ItemMeta meta, Attribute attribute, NamespacedKey key) {
        var existing = meta.getAttributeModifiers(attribute);
        if (existing == null) return;
        for (AttributeModifier modifier : List.copyOf(existing)) {
            if (key.equals(modifier.getKey())) meta.removeAttributeModifier(attribute, modifier);
        }
    }

    /** Removes legacy visible serial-number lore while keeping the internal PDC serial intact. */
    private void hideVisibleSerial(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = meta.lore();
        if (lore == null || lore.isEmpty()) return;
        List<Component> cleaned = new ArrayList<>();
        boolean changed = false;
        for (Component line : lore) {
            String text = plain.serialize(line).trim();
            if (text.startsWith(SERIAL_MARKER_PREFIX)) {
                changed = true;
                continue;
            }
            cleaned.add(line);
        }
        if (!changed) return;
        while (!cleaned.isEmpty() && plain.serialize(cleaned.getLast()).isBlank()) cleaned.removeLast();
        meta.lore(cleaned.isEmpty() ? null : cleaned);
        item.setItemMeta(meta);
    }

    private void clearSerialMetadata(ItemStack item, boolean removeMendingHint) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().remove(serialKey);
        meta.getPersistentDataContainer().remove(professionKey);
        meta.getPersistentDataContainer().remove(prestigeKey);
        List<Component> lore = meta.lore();
        if (lore != null) {
            List<Component> cleaned = new ArrayList<>();
            for (Component line : lore) {
                String text = plain.serialize(line).trim();
                if (text.startsWith(TOOL_MARKER_PREFIX) || text.startsWith(REWARD_MARKER_PREFIX)
                        || text.startsWith(SERIAL_MARKER_PREFIX)) continue;
                if (removeMendingHint && text.contains("Mending kann hinzugefügt werden")) continue;
                cleaned.add(line);
            }
            while (!cleaned.isEmpty() && plain.serialize(cleaned.getLast()).isBlank()) cleaned.removeLast();
            meta.lore(cleaned.isEmpty() ? null : cleaned);
        }
        item.setItemMeta(meta);
    }

    private String roman(int value) {
        return switch (value) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> Integer.toString(value);
        };
    }

    public static String newSerial() {
        return UUID.randomUUID().toString();
    }
}
