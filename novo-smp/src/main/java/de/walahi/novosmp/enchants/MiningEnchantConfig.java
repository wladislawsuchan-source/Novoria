package de.walahi.novosmp.enchants;

import de.walahi.smpcore.SMPCorePlugin;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Lädt alle Einstellungen der besonderen Spitzhacken aus mining-enchants.yml. */
public final class MiningEnchantConfig {
    public static final String THREE_BY_THREE_ID = "dreixdrei";
    public static final String VEINMINER_ID = "veinminer";
    public static final String SMELTER_ID = "schmelzer";
    public static final String TNT_ID = "tnt";

    public record SmeltRecipe(Material result, double experiencePerItem) {}

    private final SMPCorePlugin plugin;
    private final Map<String, CustomEnchantment> enchantments = new LinkedHashMap<>();
    private final Set<Material> threeByThreeExcluded = new HashSet<>();
    private final Map<Material, String> oreFamilyByMaterial = new EnumMap<>(Material.class);
    private final Map<Material, SmeltRecipe> smeltRecipes = new EnumMap<>(Material.class);

    private boolean enabled;
    private boolean respectProtection;
    private boolean durabilitySafetyEnabled;
    private int durabilityConfirmationSeconds;
    private String toolWouldBreakMessage;
    private int veinMaxBlocks;
    private boolean veinIncludeDiagonals;
    private int tntCooldownSeconds;
    private float tntPower;
    private int tntFuseTicks;
    private boolean tntIncendiary;
    private String tntCooldownMessage;
    private boolean tntCooldownActionbar;
    private boolean netheriteObsidianThreeByThree;

    public MiningEnchantConfig(SMPCorePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.configs().miningEnchantsFile().reload();
        FileConfiguration config = plugin.configs().miningEnchants();

        enabled = config.getBoolean("enabled", true);
        respectProtection = config.getBoolean("respect-protection", true);
        durabilitySafetyEnabled = config.getBoolean("durability-safety.enabled", true);
        durabilityConfirmationSeconds = Math.max(1,
                config.getInt("durability-safety.confirmation-seconds", 10));
        toolWouldBreakMessage = config.getString("messages.tool-would-break",
                "<red>Dein Werkzeug würde bei diesem Abbau zerbrechen. Baue innerhalb von "
                        + "<white>%seconds%</white> Sekunden erneut ab, wenn es trotzdem zerbrechen darf.</red>");
        tntCooldownMessage = config.getString("messages.tnt-cooldown",
                "<red>Die TNT-Spitzhacke ist noch <white>%seconds%s</white> im Cooldown.</red>");
        tntCooldownActionbar = config.getBoolean("messages.tnt-cooldown-actionbar", true);
        netheriteObsidianThreeByThree = config.getBoolean("three-by-three.netherite-obsidian.enabled", true);

        enchantments.clear();
        enchantments.put(THREE_BY_THREE_ID, readEnchantment(
                config, THREE_BY_THREE_ID, "3×3", "<aqua>",
                CustomEnchantment.Applicability.PICKAXE_OR_SHOVEL));
        enchantments.put(VEINMINER_ID, readEnchantment(
                config, VEINMINER_ID, "Veinminer", "<dark_aqua>",
                CustomEnchantment.Applicability.PICKAXE));
        enchantments.put(SMELTER_ID, readEnchantment(
                config, SMELTER_ID, "Schmelzer", "<gold>",
                CustomEnchantment.Applicability.PICKAXE));
        enchantments.put(TNT_ID, readEnchantment(
                config, TNT_ID, "TNT", "<red>",
                CustomEnchantment.Applicability.PICKAXE));

        threeByThreeExcluded.clear();
        for (String raw : config.getStringList("three-by-three.excluded-materials")) {
            Material material = Material.matchMaterial(raw);
            if (material == null) {
                plugin.getLogger().warning("mining-enchants.yml: unbekanntes 3x3-Ausschlussmaterial '" + raw + "'.");
                continue;
            }
            threeByThreeExcluded.add(material);
        }

        veinMaxBlocks = Math.max(1, config.getInt("veinminer.max-blocks", 32));
        veinIncludeDiagonals = config.getBoolean("veinminer.include-diagonals", true);
        loadOreFamilies(config.getConfigurationSection("veinminer.ore-families"));
        loadSmeltRecipes(config);

        tntCooldownSeconds = Math.max(0, config.getInt("tnt.cooldown-seconds", 3));
        tntPower = (float) Math.max(0.0D, Math.min(32.0D, config.getDouble("tnt.power", 4.0D)));
        tntFuseTicks = Math.max(0, Math.min(1200, config.getInt("tnt.fuse-ticks", 0)));
        tntIncendiary = config.getBoolean("tnt.incendiary", false);

        if (!respectProtection) {
            plugin.getLogger().warning("Besondere Spitzhacken: respect-protection=false. Zusätzliche Blöcke können Schutzplugins umgehen.");
        }
        plugin.getLogger().info("Mining-Verzauberungen geladen: 3x3, Veinminer, Schmelzer und TNT.");
    }

    private CustomEnchantment readEnchantment(FileConfiguration config, String id,
                                                String fallbackName, String fallbackColor,
                                                CustomEnchantment.Applicability applicability) {
        String path = "enchantments." + id + ".";
        return new CustomEnchantment(
                id,
                config.getString(path + "display-name", fallbackName),
                config.getString(path + "color", fallbackColor),
                1,
                applicability
        );
    }

    private void loadOreFamilies(ConfigurationSection section) {
        oreFamilyByMaterial.clear();
        if (section == null) return;
        for (String family : section.getKeys(false)) {
            for (String raw : section.getStringList(family)) {
                Material material = Material.matchMaterial(raw);
                if (material == null) {
                    plugin.getLogger().warning("mining-enchants.yml: unbekanntes Erzmaterial '" + raw + "'.");
                    continue;
                }
                oreFamilyByMaterial.put(material, family.toLowerCase(Locale.ROOT));
            }
        }
    }

    private void loadSmeltRecipes(FileConfiguration config) {
        smeltRecipes.clear();
        ConfigurationSection section = config.getConfigurationSection("smelter.recipes");
        ConfigurationSection defaultSection = config.getDefaults() == null
                ? null : config.getDefaults().getConfigurationSection("smelter.recipes");
        if (section == null && defaultSection == null) return;
        Set<String> recipeKeys = new LinkedHashSet<>();
        if (defaultSection != null) recipeKeys.addAll(defaultSection.getKeys(false));
        if (section != null) recipeKeys.addAll(section.getKeys(false));
        for (String rawSource : recipeKeys) {
            Material source = Material.matchMaterial(rawSource);
            String path = "smelter.recipes." + rawSource + ".";
            if (source == null || (!config.contains(path + "result", true)
                    && !config.contains(path + "experience-per-item", true))) {
                plugin.getLogger().warning("mining-enchants.yml: ungültiges Schmelzer-Rezept '" + rawSource + "'.");
                continue;
            }
            Material result = Material.matchMaterial(config.getString(path + "result", ""));
            if (result == null || result.isAir() || !result.isItem()) {
                plugin.getLogger().warning("mining-enchants.yml: ungültiges Schmelzer-Ergebnis bei '" + rawSource + "'.");
                continue;
            }
            smeltRecipes.put(source, new SmeltRecipe(
                    result,
                    Math.max(0.0D, config.getDouble(path + "experience-per-item", 0.0D))
            ));
        }
    }

    public Collection<CustomEnchantment> enchantments() {
        return Collections.unmodifiableCollection(enchantments.values());
    }

    public CustomEnchantment enchantment(String id) {
        return enchantments.get(id);
    }

    public boolean enabled() { return enabled; }
    public boolean respectProtection() { return respectProtection; }
    public boolean durabilitySafetyEnabled() { return durabilitySafetyEnabled; }
    public int durabilityConfirmationSeconds() { return durabilityConfirmationSeconds; }
    public String toolWouldBreakMessage() { return toolWouldBreakMessage; }
    public boolean isThreeByThreeExcluded(Material material) { return threeByThreeExcluded.contains(material); }
    public boolean netheriteObsidianThreeByThree() { return netheriteObsidianThreeByThree; }
    public String oreFamily(Material material) { return oreFamilyByMaterial.get(material); }
    public int veinMaxBlocks() { return veinMaxBlocks; }
    public boolean veinIncludeDiagonals() { return veinIncludeDiagonals; }
    public SmeltRecipe smeltRecipe(Material source) { return smeltRecipes.get(source); }
    public int tntCooldownSeconds() { return tntCooldownSeconds; }
    public float tntPower() { return tntPower; }
    public int tntFuseTicks() { return tntFuseTicks; }
    public boolean tntIncendiary() { return tntIncendiary; }
    public String tntCooldownMessage() { return tntCooldownMessage; }
    public boolean tntCooldownActionbar() { return tntCooldownActionbar; }
}
