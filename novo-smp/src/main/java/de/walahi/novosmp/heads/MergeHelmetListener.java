package de.walahi.novosmp.heads;

import de.walahi.novosmp.items.CustomItemManager;
import de.walahi.novosmp.professions.ProfessionToolService;
import de.walahi.smpcore.SMPCorePlugin;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Merge-Helm + Master-Merge-Helm, including Netherite upgrade and collection wardrobe. */
public final class MergeHelmetListener implements Listener {
    public static final String MERGE_HELMET_ID = "merge_helm";
    public static final String MASTER_MERGE_HELMET_ID = "master_merge_helm";

    private static final Set<Material> VANILLA_MOB_HEADS = Set.of(
            Material.ZOMBIE_HEAD, Material.SKELETON_SKULL, Material.WITHER_SKELETON_SKULL,
            Material.CREEPER_HEAD, Material.DRAGON_HEAD, Material.PIGLIN_HEAD, Material.PLAYER_HEAD);

    private final CustomItemManager customItems;
    private final HeadCollectionManager heads;
    private final ProfessionToolService serialItems;
    private final NamespacedKey mergeKey;
    private final NamespacedKey tierKey;
    private final NamespacedKey customItemIdKey;
    private final NamespacedKey armorKey;
    private final NamespacedKey toughnessKey;
    private final NamespacedKey knockbackKey;

    public MergeHelmetListener(SMPCorePlugin plugin, CustomItemManager customItems,
                               HeadCollectionManager heads, ProfessionToolService serialItems) {
        this.customItems = customItems;
        this.heads = heads;
        this.serialItems = serialItems;
        this.mergeKey = new NamespacedKey(plugin, "merge_helmet");
        this.tierKey = new NamespacedKey(plugin, "merge_helmet_tier");
        this.customItemIdKey = new NamespacedKey(plugin, "custom_item_id");
        this.armorKey = new NamespacedKey(plugin, "merge_helmet_armor");
        this.toughnessKey = new NamespacedKey(plugin, "merge_helmet_toughness");
        this.knockbackKey = new NamespacedKey(plugin, "merge_helmet_knockback");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMasterInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!event.getAction().isRightClick()) return;
        ItemStack item = event.getItem();
        if (item == null) return;
        serialItems.sanitizeForPlayer(event.getPlayer(), item);
        if (!isMaster(item)) return;

        String serial = serialItems.serial(item);
        if (serial == null || !ProfessionToolService.HEAD_REWARD_GROUP.equals(serialItems.serialGroup(item))
                || !serialItems.validateForAbility(item)) {
            event.getPlayer().sendActionBar(Component.text("Dieser Master-Merge-Helm ist nicht aktiv registriert.", NamedTextColor.RED));
            return;
        }
        event.setCancelled(true);
        heads.openWardrobe(event.getPlayer(), definition -> {
            if (!heads.hasCollected(event.getPlayer().getUniqueId(), definition)) return;
            ItemStack current = findBySerial(event.getPlayer(), serial);
            if (current == null || !isMaster(current) || !serialItems.validateForAbility(current)) {
                event.getPlayer().closeInventory();
                event.getPlayer().sendActionBar(Component.text("Der Master-Merge-Helm wurde nicht mehr gefunden.", NamedTextColor.RED));
                return;
            }
            ItemStack appearance = heads.appearanceHead(definition);
            if (appearance == null) return;
            ItemStack converted = createAppearance(current, appearance, Tier.MASTER, false);
            if (converted == null) return;
            replaceBySerial(event.getPlayer(), serial, converted);
            event.getPlayer().closeInventory();
            event.getPlayer().sendActionBar(Component.text("Helm-Optik gewählt: " + definition.displayName(), NamedTextColor.LIGHT_PURPLE));
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        ItemStack base = event.getInventory().getInputEquipment();
        ItemStack template = event.getInventory().getInputTemplate();
        ItemStack mineral = event.getInventory().getInputMineral();
        if (base == null || template == null || mineral == null) return;
        serialItems.sanitizeRevoked(base);
        if (!isDiamondMerge(base)) return;
        if (template.getType() != Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE || mineral.getType() != Material.NETHERITE_INGOT) return;

        ItemStack result;
        if (isMerged(base)) {
            result = base.clone();
            applyTier(result, Tier.NETHERITE, currentDamage(base));
            ItemMeta meta = result.getItemMeta();
            if (meta != null) {
                meta.getPersistentDataContainer().set(tierKey, PersistentDataType.STRING, Tier.NETHERITE.id);
                meta.getPersistentDataContainer().set(customItemIdKey, PersistentDataType.STRING, MERGE_HELMET_ID);
                result.setItemMeta(meta);
            }
            copyHelmetGameplayComponents(result, Material.NETHERITE_HELMET);
        } else {
            result = event.getResult();
            if (result == null || result.getType().isAir()) result = new ItemStack(Material.NETHERITE_HELMET);
            else result = result.clone();
            ItemMeta meta = result.getItemMeta();
            if (meta == null) return;
            meta.getPersistentDataContainer().set(customItemIdKey, PersistentDataType.STRING, MERGE_HELMET_ID);
            meta.getPersistentDataContainer().set(tierKey, PersistentDataType.STRING, Tier.NETHERITE.id);
            result.setItemMeta(meta);
            serialItems.transferSerialMetadata(base, result);
        }
        event.setResult(result);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack left = event.getInventory().getItem(0);
        ItemStack right = event.getInventory().getItem(1);
        if (left == null || left.getType().isAir()) return;
        serialItems.sanitizeRevoked(left);

        if (!isMaster(left) && (isMergeBase(left) || isMergedMerge(left)) && right != null && isUsableHead(right)) {
            prepareAppearanceMerge(event, left, right);
            return;
        }
        if ((isMerged(left) || isMaster(left)) && right != null && !right.getType().isAir()) {
            prepareHelmetAnvil(event, left, right);
        }
    }

    private void prepareAppearanceMerge(PrepareAnvilEvent event, ItemStack left, ItemStack right) {
        Tier tier = tier(left);
        if (tier == Tier.MASTER) return;
        ItemStack result = createAppearance(left, right, tier, true);
        if (result == null) return;
        event.getView().setRepairCost(Math.max(1, event.getView().getRepairCost()));
        event.getView().setRepairItemCountCost(1);
        event.setResult(result);
    }

    private ItemStack createAppearance(ItemStack helmet, ItemStack appearance, Tier tier, boolean consumeSignedHead) {
        ItemStack result = appearance.clone();
        result.setAmount(1);
        if (consumeSignedHead) heads.clearSignature(result);
        ItemMeta resultMeta = result.getItemMeta();
        ItemMeta helmetMeta = helmet.getItemMeta();
        if (resultMeta == null || helmetMeta == null) return null;

        for (NamespacedKey key : Set.copyOf(resultMeta.getPersistentDataContainer().getKeys())) {
            resultMeta.getPersistentDataContainer().remove(key);
        }
        resultMeta.setAttributeModifiers(null);
        for (Enchantment enchantment : List.copyOf(resultMeta.getEnchants().keySet())) resultMeta.removeEnchant(enchantment);
        for (Map.Entry<Enchantment, Integer> enchantment : helmet.getEnchantments().entrySet()) {
            resultMeta.addEnchant(enchantment.getKey(), enchantment.getValue(), true);
        }

        Component appearanceName = appearance.getItemMeta() != null && appearance.getItemMeta().displayName() != null
                ? appearance.getItemMeta().displayName()
                : Component.translatable(appearance.translationKey()).color(NamedTextColor.GRAY);
        boolean master = tier == Tier.MASTER;
        resultMeta.displayName(Component.text(master ? "Master-Merge-Helm" : "Merge-Helm",
                master ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Optik: ", NamedTextColor.GRAY).append(appearanceName).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text(master ? "Vollwertiger Netheritehelm • Meister-Garderobe" :
                        (tier == Tier.NETHERITE ? "Vollwertiger Netheritehelm" : "Vollwertiger Diamanthelm"), NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        if (master) {
            lore.add(Component.text("Rechtsklick: gesammelte Kopf-Optik kostenlos wählen", NamedTextColor.LIGHT_PURPLE)
                    .decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("Ein neuer Kopf ersetzt die aktuelle Optik.", NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        if (serialItems.serial(helmet) != null) {
            lore.add(Component.empty());
            lore.add(Component.text("Belohnungsitem • Kopfsammlung", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        }
        resultMeta.lore(lore);
        resultMeta.setMaxStackSize(1);
        resultMeta.getPersistentDataContainer().set(mergeKey, PersistentDataType.BYTE, (byte) 1);
        resultMeta.getPersistentDataContainer().set(tierKey, PersistentDataType.STRING, tier.id);
        resultMeta.getPersistentDataContainer().set(customItemIdKey, PersistentDataType.STRING,
                master ? MASTER_MERGE_HELMET_ID : MERGE_HELMET_ID);
        applyArmorAttributes(resultMeta, tier);
        result.setItemMeta(resultMeta);
        serialItems.transferSerialMetadata(helmet, result);
        applyTier(result, tier, currentDamage(helmet));
        copyHelmetGameplayComponents(result, tier.helmetMaterial);
        return result;
    }

    private void prepareHelmetAnvil(PrepareAnvilEvent event, ItemStack left, ItemStack right) {
        Tier tier = tier(left);
        Material templateMaterial = tier.helmetMaterial;
        Map<Enchantment, Integer> offered = new LinkedHashMap<>();
        ItemMeta rightMeta = right.getItemMeta();
        if (rightMeta instanceof EnchantmentStorageMeta storage) offered.putAll(storage.getStoredEnchants());
        if (rightMeta != null) offered.putAll(rightMeta.getEnchants());

        boolean helmetSource = right.getType() == templateMaterial;
        if (offered.isEmpty() && !helmetSource) return;
        ItemStack result = left.clone();
        ItemStack helmetTemplate = new ItemStack(templateMaterial);
        int applied = 0;
        for (Map.Entry<Enchantment, Integer> entry : offered.entrySet()) {
            Enchantment enchantment = entry.getKey();
            if (!enchantment.canEnchantItem(helmetTemplate) || conflictsWithExisting(result, enchantment)) continue;
            int current = result.getEnchantmentLevel(enchantment);
            int incoming = entry.getValue();
            int combined = current == incoming && current > 0 ? Math.min(enchantment.getMaxLevel(), current + 1) : Math.max(current, incoming);
            if (combined <= current) continue;
            result.addUnsafeEnchantment(enchantment, combined);
            applied++;
        }
        if (helmetSource) {
            int max = maxDamage(result, tier);
            int leftRemaining = max - currentDamage(left);
            int rightMax = Math.max(1, right.getType().getMaxDurability());
            int rightRemaining = rightMax - currentDamage(right);
            int repairedRemaining = Math.min(max, leftRemaining + rightRemaining + Math.max(1, max * 12 / 100));
            result.setData(DataComponentTypes.DAMAGE, Math.max(0, max - repairedRemaining));
        }
        if (applied == 0 && !helmetSource) return;
        copyHelmetGameplayComponents(result, templateMaterial);
        event.getView().setRepairCost(Math.max(1, event.getView().getRepairCost()));
        event.getView().setRepairItemCountCost(1);
        event.setResult(result);
    }

    private boolean conflictsWithExisting(ItemStack result, Enchantment candidate) {
        for (Enchantment existing : result.getEnchantments().keySet()) {
            if (!existing.equals(candidate) && (candidate.conflictsWith(existing) || existing.conflictsWith(candidate))) return true;
        }
        return false;
    }

    private void copyHelmetGameplayComponents(ItemStack target, Material helmetMaterial) {
        ItemStack template = new ItemStack(helmetMaterial);
        var enchantable = template.getData(DataComponentTypes.ENCHANTABLE);
        if (enchantable != null) target.setData(DataComponentTypes.ENCHANTABLE, enchantable);
        var repairable = template.getData(DataComponentTypes.REPAIRABLE);
        if (repairable != null) target.setData(DataComponentTypes.REPAIRABLE, repairable);
    }

    private void applyTier(ItemStack item, Tier tier, int damage) {
        int max = tier == Tier.DIAMOND ? 363 : 407;
        item.setData(DataComponentTypes.MAX_DAMAGE, max);
        item.setData(DataComponentTypes.DAMAGE, Math.max(0, Math.min(max - 1, damage)));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            applyArmorAttributes(meta, tier);
            meta.getPersistentDataContainer().set(tierKey, PersistentDataType.STRING, tier.id);
            item.setItemMeta(meta);
        }
    }

    private void applyArmorAttributes(ItemMeta meta, Tier tier) {
        removeModifier(meta, Attribute.ARMOR, armorKey);
        removeModifier(meta, Attribute.ARMOR_TOUGHNESS, toughnessKey);
        removeModifier(meta, Attribute.KNOCKBACK_RESISTANCE, knockbackKey);
        meta.addAttributeModifier(Attribute.ARMOR, new AttributeModifier(
                armorKey, 3.0D, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlot.HEAD.getGroup()));
        meta.addAttributeModifier(Attribute.ARMOR_TOUGHNESS, new AttributeModifier(
                toughnessKey, tier == Tier.DIAMOND ? 2.0D : 3.0D,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlot.HEAD.getGroup()));
        if (tier != Tier.DIAMOND) {
            meta.addAttributeModifier(Attribute.KNOCKBACK_RESISTANCE, new AttributeModifier(
                    knockbackKey, 0.1D, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlot.HEAD.getGroup()));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        serialItems.sanitizeForPlayer(event.getPlayer(), event.getItemInHand());
        if (!isMerged(event.getItemInHand())) return;
        event.setCancelled(true);
        event.getPlayer().sendActionBar(Component.text("Merge-Helme können nicht platziert werden.", NamedTextColor.RED));
    }

    private boolean isMergeBase(ItemStack item) {
        return customItems.is(item, MERGE_HELMET_ID)
                && (item.getType() == Material.DIAMOND_HELMET || item.getType() == Material.NETHERITE_HELMET);
    }

    private boolean isDiamondMerge(ItemStack item) {
        return (isMergeBase(item) || isMergedMerge(item)) && tier(item) == Tier.DIAMOND;
    }

    private boolean isMergedMerge(ItemStack item) {
        return isMerged(item) && !isMaster(item);
    }

    private boolean isMerged(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        Byte value = item.getItemMeta().getPersistentDataContainer().get(mergeKey, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    private boolean isMaster(ItemStack item) {
        if (customItems.is(item, MASTER_MERGE_HELMET_ID)) return true;
        return isMerged(item) && tier(item) == Tier.MASTER;
    }

    private Tier tier(ItemStack item) {
        if (item != null && item.hasItemMeta()) {
            String value = item.getItemMeta().getPersistentDataContainer().get(tierKey, PersistentDataType.STRING);
            if ("master".equalsIgnoreCase(value)) return Tier.MASTER;
            if ("netherite".equalsIgnoreCase(value)) return Tier.NETHERITE;
        }
        if (item != null && item.getType() == Material.NETHERITE_HELMET) return Tier.NETHERITE;
        return Tier.DIAMOND;
    }

    private boolean isUsableHead(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        if (heads.isSignedMobHead(item)) return true;
        return VANILLA_MOB_HEADS.contains(item.getType());
    }

    private int currentDamage(ItemStack item) {
        Integer componentDamage = item.getData(DataComponentTypes.DAMAGE);
        if (componentDamage != null) return Math.max(0, componentDamage);
        ItemMeta meta = item.getItemMeta();
        return meta instanceof Damageable damageable ? Math.max(0, damageable.getDamage()) : 0;
    }

    private int maxDamage(ItemStack item, Tier tier) {
        Integer max = item.getData(DataComponentTypes.MAX_DAMAGE);
        return max == null ? (tier == Tier.DIAMOND ? 363 : 407) : Math.max(1, max);
    }

    private void removeModifier(ItemMeta meta, Attribute attribute, NamespacedKey key) {
        var existing = meta.getAttributeModifiers(attribute);
        if (existing == null) return;
        for (AttributeModifier modifier : List.copyOf(existing)) if (key.equals(modifier.getKey())) meta.removeAttributeModifier(attribute, modifier);
    }

    private ItemStack findBySerial(org.bukkit.entity.Player player, String serial) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (serialItems.matchesSerial(item, serial)) return item;
        }
        return null;
    }

    private void replaceBySerial(org.bukkit.entity.Player player, String serial, ItemStack replacement) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            if (serialItems.matchesSerial(player.getInventory().getItem(slot), serial)) {
                player.getInventory().setItem(slot, replacement);
                return;
            }
        }
    }


    private enum Tier {
        DIAMOND("diamond", Material.DIAMOND_HELMET),
        NETHERITE("netherite", Material.NETHERITE_HELMET),
        MASTER("master", Material.NETHERITE_HELMET);
        private final String id;
        private final Material helmetMaterial;
        Tier(String id, Material helmetMaterial) { this.id = id; this.helmetMaterial = helmetMaterial; }
    }
}
