package de.walahi.novosmp.items;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.novosmp.enchants.AnglerEnchantmentDefinitions;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Zentrale Verwaltung aller Custom-Items.
 *
 * <p>Items werden ausschließlich über die {@code items.yml} beschrieben und über einen
 * Eintrag im {@link org.bukkit.persistence.PersistentDataContainer} wiedererkannt. Der
 * Anzeigename spielt für die Erkennung keine Rolle — umbenannte oder im Amboss
 * bearbeitete Items bleiben dadurch gültig, gefälschte Items werden nicht erkannt.</p>
 *
 * <p>Neue Items erfordern keinen Java-Code. Für Belohnungen und Shops stehen
 * {@link #give(Player, String, int)} und {@link #take(Player, String, int)} bereit.</p>
 */
public final class CustomItemManager {
    /** Dateiname im Server-Konfigurationsordner. */
    public static final String CONFIG_FILE = "items.yml";

    private final SMPCorePlugin plugin;
    private final NamespacedKey itemIdKey;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<String, CustomItemDefinition> definitions = new LinkedHashMap<>();

    private CustomEnchantApplier enchantApplier;
    private String currencyId = "werkzeugfragment";

    public CustomItemManager(SMPCorePlugin plugin) {
        this.plugin = plugin;
        this.itemIdKey = new NamespacedKey(plugin, "custom_item_id");
        reload();
    }

    /**
     * Hinterlegt das Verzauberungssystem. Wird nachgereicht, weil die Verzauberungen
     * ihrerseits Custom-Items erzeugen können und beide sonst zirkulär voneinander
     * abhängen würden.
     */
    public void enchantApplier(CustomEnchantApplier applier) {
        this.enchantApplier = applier;
    }

    /** Kennung des Items, das als Währung dient (Werkzeugfragmente). */
    public String currencyId() {
        return currencyId;
    }

    public Collection<CustomItemDefinition> definitions() {
        return definitions.values();
    }

    public Optional<CustomItemDefinition> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(definitions.get(normalize(id)));
    }

    // ------------------------------------------------------------------
    // Erzeugen und Erkennen
    // ------------------------------------------------------------------

    /**
     * Baut das Item zur angegebenen Kennung.
     *
     * @return das fertige Item oder {@code null}, wenn die Kennung unbekannt ist
     */
    public ItemStack create(String id, int amount) {
        CustomItemDefinition definition = definitions.get(normalize(id));
        return definition == null ? null : create(definition, amount);
    }

    public ItemStack create(CustomItemDefinition definition, int amount) {
        ItemStack item = new ItemStack(definition.material(), 1);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        if (AnglerEnchantmentDefinitions.bookLevel(definition.id()) == null
                && definition.displayName() != null && !definition.displayName().isBlank()) {
            Component displayName = withoutItalic(miniMessage.deserialize(definition.displayName()));
            meta.customName(displayName);
        }
        if (!definition.lore().isEmpty()) {
            List<Component> lore = new ArrayList<>(definition.lore().size());
            for (String line : definition.lore()) {
                lore.add(withoutItalic(miniMessage.deserialize(line)));
            }
            meta.lore(lore);
        }
        if (definition.customModelData() != null) {
            meta.setCustomModelData(definition.customModelData());
        }
        if (definition.itemModel() != null && !definition.itemModel().isBlank()) {
            NamespacedKey modelKey = NamespacedKey.fromString(definition.itemModel());
            if (modelKey == null) {
                plugin.getLogger().warning("Ungültiges item-model '" + definition.itemModel()
                        + "' bei Item '" + definition.id() + "'.");
            } else {
                meta.setItemModel(modelKey);
            }
        }
        if (definition.maxStackSize() != null) {
            meta.setMaxStackSize(definition.maxStackSize());
        }
        meta.setUnbreakable(definition.unbreakable());
        if (definition.unbreakable()) {
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        }
        definition.enchantments().forEach((name, level) -> {
            Enchantment enchantment = resolveEnchantment(name);
            if (enchantment == null) {
                plugin.getLogger().warning("Unbekannte Verzauberung '" + name + "' bei Item '" + definition.id() + "'.");
                return;
            }
            meta.addEnchant(enchantment, level, true);
        });

        if (definition.glow() && definition.enchantments().isEmpty()) {
            // Schimmer ohne echte Verzauberung: sichtbarer Effekt, keine Spielmechanik.
            meta.setEnchantmentGlintOverride(true);
        }

        if ("jaeger_meisterbrustplatte".equals(definition.id())) {
            applyHunterMasterChestBonus(meta);
        }
        applyInitialInfiniteRocketTankBar(meta, definition.id());

        meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, definition.id());
        item.setItemMeta(meta);
        if (definition.hideAdditionalTooltip()) {
            // Moderne Paper/Minecraft-Versionen stellen die Disc-Beschreibung über die
            // JUKEBOX_PLAYABLE-Data-Component dar. Genau diese Zeile (z. B. "Music Disc - 5")
            // ausblenden, ohne das eigentliche DISC_FRAGMENT_5-Material zu verändern.
            item.setData(
                    DataComponentTypes.TOOLTIP_DISPLAY,
                    TooltipDisplay.tooltipDisplay()
                            .addHiddenComponents(DataComponentTypes.JUKEBOX_PLAYABLE)
                            .build()
            );
        }

        definition.customEnchantments().forEach((enchantmentId, level) -> {
            if (enchantApplier == null) {
                plugin.getLogger().warning("Verzauberungssystem nicht bereit — '" + enchantmentId
                        + "' konnte auf '" + definition.id() + "' nicht gesetzt werden.");
                return;
            }
            if (!enchantApplier.applyCustomEnchantment(item, enchantmentId, level)) {
                plugin.getLogger().warning("Unbekannte eigene Verzauberung '" + enchantmentId
                        + "' bei Item '" + definition.id() + "'.");
            }
        });
        item.setAmount(Math.max(1, Math.min(item.getMaxStackSize(), amount)));
        return item;
    }

    /** Kennung des Custom-Items, oder {@code null} bei gewöhnlichen Items. */
    public String identify(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING);
    }

    public boolean is(ItemStack item, String id) {
        String stored = identify(item);
        return stored != null && stored.equals(normalize(id));
    }

    /**
     * Wandelt Werkzeugfragmente aus älteren Versionen auf die aktuelle neutrale Basis um.
     * Das sichtbare Disc-Fragment-Modell wird ausschließlich über item_model gesetzt.
     */
    public boolean migrateLegacyWerkzeugfragment(ItemStack item) {
        if (item == null || !is(item, "werkzeugfragment") || item.getType() == Material.RECOVERY_COMPASS) {
            return false;
        }
        int amount = item.getAmount();
        ItemStack replacement = create("werkzeugfragment", amount);
        if (replacement == null) return false;

        item.setType(replacement.getType());
        item.setItemMeta(replacement.getItemMeta());
        item.setAmount(Math.min(amount, item.getMaxStackSize()));
        return true;
    }

    /** Ensures the Prestige-V Hunter chestplate keeps its identity and +2-heart bonus after smithing. */
    public boolean preserveHunterMasterChestUpgrade(ItemStack base, ItemStack result) {
        if (!is(base, "jaeger_meisterbrustplatte") || result == null || result.getType().isAir()) return false;
        ItemMeta meta = result.getItemMeta();
        if (meta == null) return false;
        meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, "jaeger_meisterbrustplatte");
        applyHunterMasterChestBonus(meta);
        result.setItemMeta(meta);
        return true;
    }

    private void applyInitialInfiniteRocketTankBar(ItemMeta meta, String itemId) {
        if (!(meta instanceof Damageable damageable) || itemId == null || !itemId.startsWith("infinite_rocket_")) return;
        int level;
        try {
            level = Integer.parseInt(itemId.substring("infinite_rocket_".length()));
        } catch (NumberFormatException ignored) {
            return;
        }
        int fallback = switch (level) {
            case 1 -> 2304;
            case 2 -> 4608;
            case 3 -> 6912;
            default -> 0;
        };
        if (fallback <= 0) return;
        int capacity = Math.max(1, plugin.configs().items().getInt("infinite-rocket.tiers." + level + ".capacity", fallback));
        damageable.setMaxDamage(capacity + 1);
        damageable.setDamage(capacity);
    }

    private void applyHunterMasterChestBonus(ItemMeta meta) {
        NamespacedKey key = new NamespacedKey(plugin, "hunter_master_chest_health");
        Collection<AttributeModifier> existing = meta.getAttributeModifiers(Attribute.MAX_HEALTH);
        if (existing != null) {
            for (AttributeModifier modifier : List.copyOf(existing)) {
                if (key.equals(modifier.getKey())) meta.removeAttributeModifier(Attribute.MAX_HEALTH, modifier);
            }
        }
        AttributeModifier hearts = new AttributeModifier(
                key, 4.0D, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlot.CHEST.getGroup());
        meta.addAttributeModifier(Attribute.MAX_HEALTH, hearts);
    }

    /**
     * Removes the effects that came from this plugin's configured Custom-Item definition while
     * keeping the physical vanilla item itself. This is used when a serialised prestige/reward
     * item is replaced: the old copy may still exist, but it must no longer keep its special power.
     */
    public void stripToVanilla(ItemStack item) {
        String id = identify(item);
        if (id == null) return;
        CustomItemDefinition definition = definitions.get(normalize(id));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        if (definition != null) {
            for (String enchantmentName : definition.enchantments().keySet()) {
                Enchantment enchantment = resolveEnchantment(enchantmentName);
                if (enchantment != null) meta.removeEnchant(enchantment);
            }
        }

        if ("jaeger_meisterbrustplatte".equals(id)) {
            NamespacedKey key = new NamespacedKey(plugin, "hunter_master_chest_health");
            Collection<AttributeModifier> existing = meta.getAttributeModifiers(Attribute.MAX_HEALTH);
            if (existing != null) {
                for (AttributeModifier modifier : List.copyOf(existing)) {
                    if (key.equals(modifier.getKey())) meta.removeAttributeModifier(Attribute.MAX_HEALTH, modifier);
                }
            }
        }

        meta.getPersistentDataContainer().remove(itemIdKey);
        meta.displayName(null);
        meta.lore(null);
        if (meta.hasEnchantmentGlintOverride()) meta.setEnchantmentGlintOverride(null);
        item.setItemMeta(meta);
    }

    // ------------------------------------------------------------------
    // Inventar-Operationen
    // ------------------------------------------------------------------

    /** Zählt, wie viele Exemplare der Spieler im Inventar hat. */
    public int count(Player player, String id) {
        String normalized = normalize(id);
        int amount = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (is(stack, normalized)) amount += stack.getAmount();
        }
        return amount;
    }

    /**
     * Gibt Items in sauberen Stack-Portionen. Was nicht ins Inventar passt, wird vor dem
     * Spieler gedroppt, damit nichts verloren geht.
     *
     * @return Anzahl der gedroppten Items
     */
    public int give(Player player, String id, int amount) {
        CustomItemDefinition definition = definitions.get(normalize(id));
        if (definition == null || amount <= 0) return 0;

        ItemStack sample = create(definition, 1);
        int maxStack = Math.max(1, sample.getMaxStackSize());
        int remaining = amount;
        int dropped = 0;
        while (remaining > 0) {
            int chunk = Math.min(maxStack, remaining);
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(create(definition, chunk));
            for (ItemStack leftover : leftovers.values()) {
                dropped += leftover.getAmount();
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
            remaining -= chunk;
        }
        return dropped;
    }

    /**
     * Entfernt Items aus dem Inventar. Es wird nichts entfernt, wenn der Spieler nicht
     * genug hat — für Käufe ist der Aufruf damit ohne Zwischenschritt sicher.
     */
    public boolean take(Player player, String id, int amount) {
        String normalized = normalize(id);
        if (amount <= 0 || count(player, normalized) < amount) return false;

        int remaining = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (!is(stack, normalized)) continue;
            int removed = Math.min(stack.getAmount(), remaining);
            if (removed >= stack.getAmount()) player.getInventory().setItem(slot, null);
            else stack.setAmount(stack.getAmount() - removed);
            remaining -= removed;
        }
        return remaining == 0;
    }

    // ------------------------------------------------------------------
    // Konfiguration
    // ------------------------------------------------------------------

    public void reload() {
        plugin.configs().itemsFile().reload();
        var config = plugin.configs().items();
        migrateItemConfig(config);

        definitions.clear();
        currencyId = normalize(config.getString("currency-id", "werkzeugfragment"));

        ConfigurationSection section = config.getConfigurationSection("items");
        ConfigurationSection defaultSection = config.getDefaults() == null
                ? null
                : config.getDefaults().getConfigurationSection("items");
        if (section == null && defaultSection == null) {
            plugin.getLogger().warning(CONFIG_FILE + " enthält keinen Abschnitt 'items'.");
            return;
        }

        // ConfigurationFile hängt neue JAR-Werte absichtlich nur als Defaults an, ohne die
        // vorhandene Server-YAML umzuschreiben. Bukkit zählt bei copyDefaults(false) neue
        // Untersektionen nicht in getKeys() auf. Deshalb bilden wir hier explizit die Vereinigung:
        // alte Admin-Einträge + neu mitgelieferte Item-IDs wie die Novo-Kisten-Rewards.
        Set<String> itemIds = new LinkedHashSet<>();
        if (defaultSection != null) itemIds.addAll(defaultSection.getKeys(false));
        if (section != null) itemIds.addAll(section.getKeys(false));

        for (String rawId : itemIds) {
            ConfigurationSection itemSection = section == null ? null : section.getConfigurationSection(rawId);
            if (itemSection == null && defaultSection != null) itemSection = defaultSection.getConfigurationSection(rawId);
            if (itemSection == null) continue;
            try {
                ConfigurationSection defaultItem = defaultSection == null
                        ? null : defaultSection.getConfigurationSection(rawId);
                CustomItemDefinition definition = read(normalize(rawId), itemSection, defaultItem);
                definitions.put(definition.id(), definition);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Custom-Item '" + rawId + "' ist ungültig: " + exception.getMessage());
            }
        }

        if (!definitions.containsKey(currencyId)) {
            plugin.getLogger().warning("Das als Währung eingetragene Item '" + currencyId
                    + "' fehlt in " + CONFIG_FILE + ".");
        }
        plugin.getLogger().info("Custom-Items geladen: " + definitions.size()
                + " (Währung: " + currencyId + ")");
    }

    /**
     * Einmalige kleine Migration für bereits existierende items.yml-Dateien.
     * Neue JAR-Defaults überschreiben absichtlich keine Admin-Datei; deshalb würden
     * Material-/Stack-Anpassungen sonst auf bestehenden Servern nicht wirksam.
     */
    private void migrateItemConfig(org.bukkit.configuration.file.FileConfiguration config) {
        int localVersion = config.isSet("config-version") ? config.getInt("config-version", 0) : 0;
        boolean changed = false;

        if (localVersion < 2) {
            config.set("items.werkzeugfragment.hide-additional-tooltip", true);
            for (String booster : List.of("berufe_booster_15", "berufe_booster_20", "lumi_booster_15", "lumi_booster_20")) {
                config.set("items." + booster + ".max-stack-size", 64);
            }
            config.set("items.lumi_booster_20.material", "ECHO_SHARD");
            localVersion = 2;
            changed = true;
        }

        if (localVersion < 3) {
            // DISC_FRAGMENT_5 fügt seine Beschreibung über die Item-Klasse selbst hinzu;
            // weder HIDE_ADDITIONAL_TOOLTIP noch hidden JUKEBOX_PLAYABLE kann diese Zeile
            // entfernen. Deshalb neutrales PAPER als Basis und nur das Vanilla-Modell nutzen.
            config.set("items.werkzeugfragment.material", "PAPER");
            config.set("items.werkzeugfragment.item-model", "minecraft:disc_fragment_5");
            config.set("items.werkzeugfragment.hide-additional-tooltip", null);
            localVersion = 3;
            changed = true;
        }

        if (localVersion < 4) {
            // PAPER löst Vanilla-Rezepte/Recipe-Book-Einträge aus. DEBUG_STICK ist eine
            // neutrale, rezeptfreie Basis; das sichtbare Modell bleibt disc_fragment_5.
            config.set("items.werkzeugfragment.material", "DEBUG_STICK");
            config.set("items.werkzeugfragment.item-model", "minecraft:disc_fragment_5");
            config.set("items.werkzeugfragment.max-stack-size", 64);
            localVersion = 4;
            changed = true;
        }

        if (localVersion < 5) {
            // DEBUG_STICK war optisch im erweiterten Tooltip unschön. RECOVERY_COMPASS
            // bleibt rezeptneutral als Zutat und das sichtbare Modell bleibt disc_fragment_5.
            config.set("items.werkzeugfragment.material", "RECOVERY_COMPASS");
            config.set("items.werkzeugfragment.item-model", "minecraft:disc_fragment_5");
            config.set("items.werkzeugfragment.max-stack-size", 64);
            localVersion = 5;
            changed = true;
        }

        if (!changed) return;
        config.set("config-version", localVersion);
        plugin.configs().itemsFile().save();
        plugin.getLogger().info("items.yml auf Config-Version " + localVersion
                + " aktualisiert (Werkzeugfragment auf aktueller neutraler Basis).");
    }

    private CustomItemDefinition read(String id, ConfigurationSection section,
                                      ConfigurationSection defaultSection) {
        String materialName = section.getString("material", "");
        Material material = Material.matchMaterial(materialName);
        if (material == null || material.isAir()) {
            throw new IllegalArgumentException("Unbekanntes Material '" + materialName + "'");
        }

        Map<String, Integer> enchantments = readLevels(section.getConfigurationSection("enchantments"));
        Map<String, Integer> customEnchantments = readCustomEnchantments(section);
        // Existing physical items.yml files predate the Angler book PDC mappings.
        // Keep every admin-defined field, but inherit just the missing enchant mapping
        // from the bundled default instead of rewriting the server file.
        if (AnglerEnchantmentDefinitions.bookLevel(id) != null
                && customEnchantments.isEmpty()
                && defaultSection != null) {
            customEnchantments = readCustomEnchantments(defaultSection);
        }

        return new CustomItemDefinition(
                id,
                material,
                section.getString("display-name", ""),
                section.getStringList("lore"),
                section.contains("custom-model-data") ? section.getInt("custom-model-data") : null,
                section.getString("item-model", ""),
                section.contains("max-stack-size") ? clampStackSize(section.getInt("max-stack-size")) : null,
                section.getBoolean("glow", false),
                section.getBoolean("unbreakable", false),
                enchantments,
                customEnchantments,
                section.getBoolean("mending-blocked", false),
                section.getBoolean("hide-additional-tooltip", false)
        );
    }

    private Map<String, Integer> readCustomEnchantments(ConfigurationSection itemSection) {
        // Einstufige Verzauberungen dürfen ohne künstliche ": 1" als Liste stehen.
        // Das bisherige Mapping bleibt für Holzschlag I-V und alte Configs kompatibel.
        if (itemSection.isList("custom-enchantments")) {
            Map<String, Integer> levels = new LinkedHashMap<>();
            for (String id : itemSection.getStringList("custom-enchantments")) {
                String normalized = normalize(id);
                if (!normalized.isBlank()) levels.put(normalized, 1);
            }
            return levels;
        }
        return readLevels(itemSection.getConfigurationSection("custom-enchantments"));
    }

    private Map<String, Integer> readLevels(ConfigurationSection section) {
        if (section == null) return Map.of();
        Map<String, Integer> levels = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            int level = section.getInt(key, 0);
            if (level > 0) levels.put(normalize(key), level);
        }
        return levels;
    }

    private int clampStackSize(int configured) {
        return Math.max(1, Math.min(99, configured));
    }

    private Enchantment resolveEnchantment(String name) {
        NamespacedKey key = NamespacedKey.fromString(name.contains(":") ? name : "minecraft:" + name);
        return key == null ? null : Registry.ENCHANTMENT.get(key);
    }

    private Component withoutItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    private String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
}
