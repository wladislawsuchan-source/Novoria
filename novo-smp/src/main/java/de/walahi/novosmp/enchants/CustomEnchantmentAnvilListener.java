package de.walahi.novosmp.enchants;

import de.walahi.novosmp.items.CustomItemManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Überträgt eigene Verzauberungen beider Werkzeugseiten auf das Amboss-Ergebnis.
 *
 * <p>Vanilla übernimmt beim Kombinieren nur die Metadaten der linken Seite. Dadurch
 * ging z. B. bei Veinminer + Schmelzer bisher die Fähigkeit des zweiten Werkzeugs
 * verloren. Hier werden beide PDC-Verzauberungen zusammengeführt und die bereits
 * registrierten Konfliktregeln respektiert.</p>
 */
public final class CustomEnchantmentAnvilListener implements Listener {
    public record MergeOutcome(ItemStack result, boolean fallback, boolean anglerChanged,
                               boolean invalid) { }
    private final CustomEnchantmentService enchantments;
    private final CustomItemManager customItems;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final PlainTextComponentSerializer plainText = PlainTextComponentSerializer.plainText();

    public CustomEnchantmentAnvilListener(CustomEnchantmentService enchantments,
                                          CustomItemManager customItems) {
        this.enchantments = enchantments;
        this.customItems = customItems;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        var inventory = event.getInventory();
        ItemStack left = inventory.getItem(0);
        ItemStack right = inventory.getItem(1);
        if (customItems != null && customItems.is(right, "reparaturkern")) return;
        MergeOutcome merge = merge(left, right, event.getResult());
        if (merge == null) return;
        if (merge.invalid()) {
            event.setResult(null);
            return;
        }
        if (merge.fallback() || (merge.anglerChanged() && event.getView().getRepairCost() < 1)) {
            // The existing custom-only fallback has a real level and ingredient cost.
            // A Vanilla result keeps its own cost unless it was unexpectedly zero.
            event.getView().setRepairCost(Math.max(1, event.getView().getRepairCost()));
            event.getView().setRepairItemCountCost(1);
        }
        event.setResult(merge.result());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrepareRepairCore(PrepareAnvilEvent event) {
        ItemStack right = event.getInventory().getItem(1);
        if (customItems == null || !customItems.is(right, "reparaturkern")) return;

        ItemStack repaired = repairWithCore(event.getInventory().getItem(0), right);
        event.setResult(repaired);
        if (repaired == null) return;

        // Paper's anvil consumes these costs only when the result is actually taken.
        event.getView().setRepairCost(1);
        event.getView().setRepairItemCountCost(1);
    }

    /** Returns a durability-only copy; the original item and core are never mutated. */
    public ItemStack repairWithCore(ItemStack left, ItemStack right) {
        if (customItems == null || !customItems.is(right, "reparaturkern")
                || left == null || left.getType().isAir() || left.getAmount() != 1) return null;
        ItemMeta originalMeta = left.getItemMeta();
        if (!(originalMeta instanceof Damageable originalDamage)
                || originalDamage.getDamage() <= 0
                || left.getType().getMaxDurability() <= 0
                && (!originalDamage.hasMaxDamage() || originalDamage.getMaxDamage() <= 0)) return null;

        ItemStack repaired = left.clone();
        Damageable resultMeta = (Damageable) repaired.getItemMeta();
        resultMeta.setDamage(0);
        repaired.setItemMeta(resultMeta);
        return repaired;
    }

    /** Combines custom PDC levels on top of the unmodified Vanilla result, if there is one. */
    public MergeOutcome merge(ItemStack left, ItemStack right, ItemStack vanillaResult) {
        if (left == null || right == null) return null;
        ItemStack normalizedLeft = withLegacyAnglerBookEnchant(left);
        ItemStack normalizedRight = withLegacyAnglerBookEnchant(right);

        Map<String, Integer> leftLevels = enchantments.levels(normalizedLeft);
        Map<String, Integer> rightLevels = enchantments.levels(normalizedRight);
        Map<String, Integer> combined = new LinkedHashMap<>(leftLevels);
        rightLevels.forEach((id, rightLevel) -> {
            int leftLevel = leftLevels.getOrDefault(id, 0);
            int combinedLevel = Math.max(leftLevel, rightLevel);
            if (AnglerEnchantmentDefinitions.includes(id) && leftLevel > 0 && leftLevel == rightLevel) {
                int maximum = enchantments.find(id).orElseThrow().maxLevel();
                combinedLevel = Math.min(maximum, leftLevel + 1);
            }
            combined.put(id, combinedLevel);
        });
        if (combined.isEmpty()) return null;

        boolean transfersCustomEnchantment = combined.entrySet().stream().anyMatch(entry ->
                entry.getValue() > leftLevels.getOrDefault(entry.getKey(), 0)
                        || vanillaResult != null && AnglerEnchantmentDefinitions.includes(entry.getKey())
                        && enchantments.level(vanillaResult, entry.getKey()) < entry.getValue());
        // Vanilla still produces an output for two books even when the left
        // level already exceeds the right level. Custom-only books need the
        // same fallback because Vanilla does not understand their PDC levels.
        boolean combiningAnglerBooks = normalizedLeft.getType() == Material.ENCHANTED_BOOK
                && normalizedRight.getType() == Material.ENCHANTED_BOOK
                && rightLevels.keySet().stream().anyMatch(AnglerEnchantmentDefinitions::includes);
        boolean preserveLeftTotemCharge = vanillaResult != null
                && normalizedLeft.getType() == Material.SHIELD
                && leftLevels.getOrDefault("totembindung", 0) > 0;
        boolean preserveLeftSpawnerUses = vanillaResult != null
                && CustomEnchantmentService.spawnergriffPickaxe(normalizedLeft.getType())
                && leftLevels.getOrDefault("spawnergriff", 0) > 0;
        if (!transfersCustomEnchantment && !combiningAnglerBooks
                && !preserveLeftTotemCharge && !preserveLeftSpawnerUses) return null;
        boolean anglerChanged = combined.entrySet().stream().anyMatch(entry ->
                AnglerEnchantmentDefinitions.includes(entry.getKey())
                        && (entry.getValue() > leftLevels.getOrDefault(entry.getKey(), 0)
                        || vanillaResult != null
                        && enchantments.level(vanillaResult, entry.getKey()) < entry.getValue()));

        ItemStack result;
        boolean fallback = vanillaResult == null;
        if (vanillaResult != null) {
            result = vanillaResult.clone();
        } else {
            // Zwei voll reparierte Custom-Werkzeuge erzeugen Vanilla-seitig oft gar
            // kein Ergebnis, weil Minecraft unsere PDC-Verzauberungen nicht kennt.
            if (normalizedRight.getType() != Material.ENCHANTED_BOOK
                    && normalizedLeft.getType() != normalizedRight.getType()) return null;
            result = normalizedLeft.clone();
        }
        for (Map.Entry<String, Integer> entry : combined.entrySet()) {
            if (enchantments.level(result, entry.getKey()) >= entry.getValue()) continue;
            CustomEnchantmentService.ApplyResult applied = enchantments.applyDetailed(
                    result, entry.getKey(), entry.getValue());
            if (applied == CustomEnchantmentService.ApplyResult.CONFLICT
                    || applied == CustomEnchantmentService.ApplyResult.NOT_APPLICABLE
                    || applied == CustomEnchantmentService.ApplyResult.INVALID_ITEM) {
                // Ungültige Kombinationen dürfen nicht ein Werkzeug verbrauchen und
                // anschließend nur eine der beiden Fähigkeiten behalten.
                return new MergeOutcome(null, fallback, anglerChanged, true);
            }
        }
        if (result.getType() == Material.SHIELD && combined.containsKey("totembindung"))
            enchantments.setTotemCharge(result, enchantments.totemCharge(normalizedLeft));
        if (CustomEnchantmentService.spawnergriffPickaxe(result.getType())
                && combined.containsKey("spawnergriff")) {
            // A book has no pickaxe charges. Only an already enchanted pickaxe
            // can supply remaining uses; a newly enchanted result starts at 3.
            int uses = leftLevels.containsKey("spawnergriff")
                    ? enchantments.spawnerUses(normalizedLeft)
                    : CustomEnchantmentService.spawnergriffPickaxe(normalizedRight.getType())
                    ? enchantments.spawnerUses(normalizedRight) : enchantments.maxSpawnerUses();
            enchantments.setSpawnerUses(result, uses);
        }
        if (result.getType() == Material.ENCHANTED_BOOK
                && combined.keySet().stream().anyMatch(AnglerEnchantmentDefinitions::includes)) {
            var meta = result.getItemMeta();
            meta.customName(null);
            result.setItemMeta(meta);
        }
        polishKnownCombination(result);
        return new MergeOutcome(result, fallback, anglerChanged, false);
    }

    private ItemStack withLegacyAnglerBookEnchant(ItemStack original) {
        if (original.getType() != Material.ENCHANTED_BOOK || customItems == null) return original;
        AnglerEnchantmentDefinitions.BookLevel legacy = AnglerEnchantmentDefinitions.bookLevel(
                customItems.identify(original));
        if (legacy == null || enchantments.level(original, legacy.enchantmentId()) > 0) return original;
        ItemStack normalized = original.clone();
        return enchantments.applyDetailed(normalized, legacy.enchantmentId(), legacy.level())
                == CustomEnchantmentService.ApplyResult.SUCCESS ? normalized : original;
    }

    private void polishKnownCombination(ItemStack result) {
        if (enchantments.level(result, MiningEnchantConfig.VEINMINER_ID) <= 0
                || enchantments.level(result, MiningEnchantConfig.SMELTER_ID) <= 0) return;

        var meta = result.getItemMeta();
        if (meta == null) return;
        List<Component> lore = meta.lore() == null
                ? new ArrayList<>()
                : new ArrayList<>(meta.lore());
        lore.removeIf(line -> {
            String text = plainText.serialize(line).trim().toLowerCase(Locale.ROOT);
            return text.contains("mit schmelzer kombinierbar")
                    || text.contains("mit veinminer kombinierbar")
                    || text.contains("kann mit schmelzer kombiniert werden")
                    || text.contains("kann mit veinminer kombiniert werden");
        });
        lore.add(miniMessage.deserialize("<green>✓ Veinminer + Schmelzer kombiniert</green>")
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        result.setItemMeta(meta);
    }

}
