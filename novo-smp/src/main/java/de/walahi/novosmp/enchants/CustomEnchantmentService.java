package de.walahi.novosmp.enchants;

import de.walahi.novosmp.items.CustomEnchantApplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Verwaltet eigene Verzauberungen auf Items.
 *
 * <p>Die Stufe liegt im PersistentDataContainer. Die Lore ist ausschließlich die
 * sichtbare Darstellung und darf von anderen Plugins verändert werden, ohne die
 * Funktion der Verzauberung zu verlieren.</p>
 */
public final class CustomEnchantmentService implements CustomEnchantApplier {
    public enum ApplyResult {
        SUCCESS,
        UNKNOWN_ENCHANTMENT,
        INVALID_ITEM,
        NOT_APPLICABLE,
        CONFLICT
    }

    private final JavaPlugin plugin;
    private final Supplier<FileConfiguration> anglerConfiguration;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final PlainTextComponentSerializer plainText = PlainTextComponentSerializer.plainText();
    private final Map<String, CustomEnchantment> enchantments = new LinkedHashMap<>();
    private final Map<String, NamespacedKey> keys = new LinkedHashMap<>();
    private final Map<String, Set<String>> conflicts = new LinkedHashMap<>();
    private final NamespacedKey totemChargeKey;
    private final NamespacedKey spawnerUsesKey;

    public CustomEnchantmentService(JavaPlugin plugin, Supplier<FileConfiguration> anglerConfiguration) {
        this.plugin = plugin;
        this.anglerConfiguration = anglerConfiguration;
        this.totemChargeKey = new NamespacedKey(plugin, "totembindung_charge");
        this.spawnerUsesKey = new NamespacedKey(plugin, "spawnergriff_uses");
    }

    public void register(CustomEnchantment enchantment) {
        String id = normalize(enchantment.id());
        enchantments.put(id, new CustomEnchantment(
                id,
                enchantment.displayName(),
                enchantment.color(),
                enchantment.maxLevel(),
                enchantment.applicable()
        ));
        keys.put(id, new NamespacedKey(plugin, "enchant_" + id));
    }

    /** Definiert einen beidseitigen Konflikt zwischen zwei Verzauberungen. */
    public void registerConflict(String first, String second) {
        String a = normalize(first);
        String b = normalize(second);
        if (a.isBlank() || b.isBlank() || a.equals(b)) return;
        conflicts.computeIfAbsent(a, ignored -> new LinkedHashSet<>()).add(b);
        conflicts.computeIfAbsent(b, ignored -> new LinkedHashSet<>()).add(a);
    }

    public Collection<CustomEnchantment> registered() {
        return List.copyOf(enchantments.values());
    }

    public Optional<CustomEnchantment> find(String id) {
        return Optional.ofNullable(enchantments.get(normalize(id)));
    }

    /** Aktuelle Stufe, oder 0 wenn die Verzauberung nicht auf dem Item liegt. */
    public int level(ItemStack item, String enchantmentId) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return 0;
        NamespacedKey key = keys.get(normalize(enchantmentId));
        if (key == null) return 0;
        PersistentDataContainer container = item.getItemMeta().getPersistentDataContainer();
        Integer stored = container.get(key, PersistentDataType.INTEGER);
        return stored == null ? 0 : Math.max(0, stored);
    }

    /** Liefert alle aktuell auf einem Item gespeicherten eigenen Verzauberungen. */
    public Map<String, Integer> levels(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return Map.of();
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String id : enchantments.keySet()) {
            int level = level(item, id);
            if (level > 0) result.put(id, level);
        }
        return Map.copyOf(result);
    }

    /** Liefert die erste vorhandene Verzauberung, die mit der gewünschten kollidiert. */
    public Optional<CustomEnchantment> conflictingEnchantment(ItemStack item, String enchantmentId) {
        String normalized = normalize(enchantmentId);
        for (String conflictId : conflicts.getOrDefault(normalized, Set.of())) {
            if (level(item, conflictId) <= 0) continue;
            CustomEnchantment conflict = enchantments.get(conflictId);
            if (conflict != null) return Optional.of(conflict);
        }
        return Optional.empty();
    }

    @Override
    public boolean applyCustomEnchantment(ItemStack item, String enchantmentId, int level) {
        return applyDetailed(item, enchantmentId, level) == ApplyResult.SUCCESS;
    }

    public boolean apply(ItemStack item, String enchantmentId, int level) {
        return applyDetailed(item, enchantmentId, level) == ApplyResult.SUCCESS;
    }

    public ApplyResult applyDetailed(ItemStack item, String enchantmentId, int level) {
        String normalized = normalize(enchantmentId);
        CustomEnchantment enchantment = enchantments.get(normalized);
        if (enchantment == null) return ApplyResult.UNKNOWN_ENCHANTMENT;
        if (item == null || item.getType().isAir()) return ApplyResult.INVALID_ITEM;
        if (item.getType() != Material.ENCHANTED_BOOK && !enchantment.applicable().matches(item.getType())) return ApplyResult.NOT_APPLICABLE;
        if (normalized.equals("spawnergriff") && item.getType() != Material.ENCHANTED_BOOK
                && !spawnergriffPickaxe(item.getType())) return ApplyResult.NOT_APPLICABLE;
        if (conflictingEnchantment(item, normalized).isPresent()) return ApplyResult.CONFLICT;

        int clamped = Math.max(1, Math.min(enchantment.maxLevel(), level));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return ApplyResult.INVALID_ITEM;

        meta.getPersistentDataContainer().set(keys.get(normalized), PersistentDataType.INTEGER, clamped);
        List<Component> lore = strippedLore(meta, enchantment);
        String visibleName = enchantment.maxLevel() == 1
                ? enchantment.displayName()
                : enchantment.displayName() + " " + roman(clamped);
        lore.addFirst(miniMessage
                .deserialize(enchantment.color() + visibleName)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        if (normalized.equals("totembindung") && item.getType() == Material.SHIELD)
            setTotemCharge(item, totemCharge(item));
        if (normalized.equals("spawnergriff") && spawnergriffPickaxe(item.getType()))
            spawnerUses(item);
        return ApplyResult.SUCCESS;
    }

    public static boolean spawnergriffPickaxe(Material material) {
        return material == Material.WOODEN_PICKAXE || material == Material.STONE_PICKAXE
                || material == Material.IRON_PICKAXE || material == Material.GOLDEN_PICKAXE
                || material == Material.DIAMOND_PICKAXE || material == Material.NETHERITE_PICKAXE;
    }

    public int maxSpawnerUses() {
        FileConfiguration configuration = anglerConfiguration.get();
        String path = "enchants.spawnergriff.max-uses";
        if (configuration == null || !configuration.isInt(path)) return 3;
        int configured = configuration.getInt(path);
        return configured > 0 ? configured : 3;
    }

    /** Repair legacy pickaxes without a counter (or the invalid zero written by the old anvil path). */
    public int spawnerUses(ItemStack pickaxe) {
        if (pickaxe == null || !spawnergriffPickaxe(pickaxe.getType())
                || level(pickaxe, "spawnergriff") < 1) return 0;
        ItemMeta meta = pickaxe.getItemMeta();
        Integer stored = meta.getPersistentDataContainer()
                .get(spawnerUsesKey, PersistentDataType.INTEGER);
        // A genuine last use removes the enchant entirely; zero alongside it was
        // only produced by the previous anvil bug and cannot be a valid charge.
        int uses = stored == null || stored <= 0 ? maxSpawnerUses() : stored;
        if (stored == null || stored != uses || !hasSpawnerUsesLore(meta, uses))
            setSpawnerUses(pickaxe, uses);
        return uses;
    }

    public void setSpawnerUses(ItemStack pickaxe, int uses) {
        if (pickaxe == null || !spawnergriffPickaxe(pickaxe.getType())
                || level(pickaxe, "spawnergriff") < 1) return;
        ItemMeta meta = pickaxe.getItemMeta();
        int remaining = Math.max(0, uses);
        meta.getPersistentDataContainer().set(spawnerUsesKey, PersistentDataType.INTEGER, remaining);
        List<Component> lore = meta.lore() == null
                ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.removeIf(line -> plainText.serialize(line).trim().startsWith("Spawner-Nutzungen:"));
        lore.add(miniMessage.deserialize("<gray>Spawner-Nutzungen: <yellow>"
                + remaining + "/" + maxSpawnerUses() + "</yellow></gray>")
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        pickaxe.setItemMeta(meta);
    }

    private boolean hasSpawnerUsesLore(ItemMeta meta, int uses) {
        List<Component> lore = meta.lore();
        if (lore == null) return false;
        int count = 0;
        for (Component line : lore) {
            String text = plainText.serialize(line).trim();
            if (!text.startsWith("Spawner-Nutzungen:")) continue;
            if (!text.equals("Spawner-Nutzungen: " + uses + "/" + maxSpawnerUses())) return false;
            count++;
        }
        return count == 1;
    }

    /** Consume one successful spawner pickup, removing only Spawnergriff at zero. */
    public boolean consumeSpawnerUse(ItemStack pickaxe) {
        int uses = spawnerUses(pickaxe);
        if (uses <= 0) return false;
        if (uses == 1) return remove(pickaxe, "spawnergriff");
        setSpawnerUses(pickaxe, uses - 1);
        return true;
    }

    /** The charge belongs to the shield item, not the player or the enchantment book. */
    public int totemCharge(ItemStack shield) {
        if (shield == null || shield.getType() != Material.SHIELD
                || level(shield, "totembindung") < 1) return 0;
        Integer stored = shield.getItemMeta().getPersistentDataContainer()
                .get(totemChargeKey, PersistentDataType.INTEGER);
        return stored != null && stored > 0 ? 1 : 0;
    }

    public void setTotemCharge(ItemStack shield, int charge) {
        if (shield == null || shield.getType() != Material.SHIELD
                || level(shield, "totembindung") < 1) return;
        int stored = charge > 0 ? 1 : 0;
        ItemMeta meta = shield.getItemMeta();
        meta.getPersistentDataContainer().set(totemChargeKey, PersistentDataType.INTEGER, stored);
        List<Component> lore = meta.lore() == null
                ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.removeIf(line -> plainText.serialize(line).trim().startsWith("Totemladung:"));
        lore.add(miniMessage.deserialize("<gray>Totemladung: <yellow>" + stored + "/1</yellow></gray>")
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        shield.setItemMeta(meta);
    }

    /** Entfernt die Verzauberung samt Lore-Zeile. */
    public boolean remove(ItemStack item, String enchantmentId) {
        CustomEnchantment enchantment = enchantments.get(normalize(enchantmentId));
        if (enchantment == null || item == null || !item.hasItemMeta()) return false;

        ItemMeta meta = item.getItemMeta();
        NamespacedKey key = keys.get(normalize(enchantment.id()));
        if (key != null) meta.getPersistentDataContainer().remove(key);
        List<Component> lore = strippedLore(meta, enchantment);
        if (enchantment.id().equals("totembindung") && item.getType() == Material.SHIELD) {
            meta.getPersistentDataContainer().remove(totemChargeKey);
            lore.removeIf(line -> plainText.serialize(line).trim().startsWith("Totemladung:"));
        }
        if (enchantment.id().equals("spawnergriff")) {
            meta.getPersistentDataContainer().remove(spawnerUsesKey);
            lore.removeIf(line -> plainText.serialize(line).trim().startsWith("Spawner-Nutzungen:"));
        }
        meta.lore(lore.isEmpty() ? null : lore);
        item.setItemMeta(meta);
        return true;
    }

    private List<Component> strippedLore(ItemMeta meta, CustomEnchantment enchantment) {
        List<Component> current = meta.lore();
        List<Component> result = new ArrayList<>();
        if (current == null) return result;
        String marker = enchantment.displayName().toLowerCase(Locale.ROOT);
        for (Component line : current) {
            if (plainText.serialize(line).trim().toLowerCase(Locale.ROOT).startsWith(marker)) continue;
            result.add(line);
        }
        return result;
    }

    public static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> String.valueOf(level);
        };
    }

    private String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
}
