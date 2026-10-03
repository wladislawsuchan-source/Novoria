package de.walahi.novosmp.professions;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.novosmp.angler.FishRegistry;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ProfessionConfig {
    public static final String LUMBERJACK_ID = "holzfaeller";
    public static final String MINER_ID = "bergarbeiter";
    public static final String HUNTER_ID = "jaeger";
    public static final String ANGLER_ID = "angler";

    private final SMPCorePlugin plugin;
    private final FishRegistry fishRegistry;
    private final Map<String, Map<Material, Double>> xpBlocks = new LinkedHashMap<>();
    private final Map<String, Set<Material>> sellMaterials = new LinkedHashMap<>();
    private final Map<String, MaterialGroup> materialGroups = new LinkedHashMap<>();
    private final Map<String, GrowthGroup> growthGroups = new LinkedHashMap<>();
    private final Map<String, MinedGroup> minedGroups = new LinkedHashMap<>();
    private final Map<String, HuntGroup> huntGroups = new LinkedHashMap<>();
    private final Map<String, Map<Integer, Map<Integer, MilestoneRequirement>>> requirements = new LinkedHashMap<>();
    private final Map<Integer, LevelReward> rewards = new LinkedHashMap<>();
    private final Map<String, BoosterDefinition> boosterDefinitions = new LinkedHashMap<>();

    private int maxLevel;
    private int maxPrestige;
    private double levelCurveExponent;
    private long switchCost;
    private int slotUnlockPrestige;
    private int maxActiveProfessionSlots;
    private int saveIntervalSeconds;

    public ProfessionConfig(SMPCorePlugin plugin, FishRegistry fishRegistry) {
        this.plugin = plugin;
        this.fishRegistry = fishRegistry;
        reload();
    }

    public void reload() {
        FileConfiguration config = plugin.configs().professions();
        maxLevel = clamp(config.getInt("max-level", 100), 2, 1000);
        maxPrestige = clamp(config.getInt("max-prestige", 5), 1, 20);
        levelCurveExponent = Math.max(0.25D, config.getDouble("level-curve-exponent", 1.35D));
        switchCost = Math.max(0L, config.getLong("switch-cost", 250_000L));
        slotUnlockPrestige = clamp(config.getInt("slot-unlock-prestige",
                config.getInt("second-slot-unlock-prestige", 3)), 1, maxPrestige);
        maxActiveProfessionSlots = clamp(config.getInt("max-active-profession-slots", 5), 1, 5);
        saveIntervalSeconds = Math.max(5, config.getInt("save-interval-seconds", 30));

        loadXpBlocks(config);
        loadSellMaterials(config);
        loadMaterialGroups(config);
        loadGrowthGroups(config);
        loadMinedGroups(config);
        loadHuntGroups(config);
        loadRequirements(config);
        loadRewards(config);
        loadBoosters(config);
    }

    SMPCorePlugin plugin() { return plugin; }
    public boolean enabled() { return plugin.configs().professions().getBoolean("enabled", true); }
    public int maxLevel() { return maxLevel; }
    public int maxPrestige() { return maxPrestige; }
    public long switchCost() { return switchCost; }
    public int slotUnlockPrestige() { return slotUnlockPrestige; }
    public int maxActiveProfessionSlots() { return maxActiveProfessionSlots; }
    public int saveIntervalSeconds() { return saveIntervalSeconds; }

    public boolean professionEnabled(String professionId) {
        return plugin.configs().professions().getBoolean(
                "professions." + normalize(professionId) + ".enabled", true);
    }

    public String professionDisplayName(String professionId) {
        String id = normalize(professionId);
        String fallback = switch (id) {
            case LUMBERJACK_ID -> "<green>Holzfäller</green>";
            case MINER_ID -> "<aqua>Bergarbeiter</aqua>";
            case HUNTER_ID -> "<red>Jäger</red>";
            case ANGLER_ID -> "<dark_aqua>Angler</dark_aqua>";
            default -> professionId;
        };
        return plugin.configs().professions().getString("professions." + id + ".display-name", fallback);
    }

    public double xpFor(Material material) {
        return xpFor(LUMBERJACK_ID, material);
    }

    public double xpFor(String professionId, Material material) {
        if (material == null) return 0D;
        return xpBlocks.getOrDefault(normalize(professionId), Map.of()).getOrDefault(material, 0D);
    }

    public boolean isLumberjackSellMaterial(Material material) {
        return isSellMaterial(LUMBERJACK_ID, material);
    }

    public boolean isSellMaterial(String professionId, Material material) {
        return material != null && sellMaterials.getOrDefault(normalize(professionId), Set.of()).contains(material);
    }

    public double sellMultiplier(int prestige) {
        return sellMultiplier(LUMBERJACK_ID, prestige);
    }

    public double sellMultiplier(String professionId, int prestige) {
        int safe = clamp(prestige, 0, maxPrestige);
        String id = normalize(professionId);
        FileConfiguration config = plugin.configs().professions();
        double fallback = safe >= 4 ? 2D : 1.5D;
        return Math.max(1D, config.getDouble(
                "professions." + id + ".sell-multiplier-by-prestige." + safe, fallback));
    }

    public double totalXp(int prestige) {
        return totalXp(LUMBERJACK_ID, prestige);
    }

    public double totalXp(String professionId, int prestige) {
        int safe = clamp(prestige, 0, maxPrestige);
        String id = normalize(professionId);
        FileConfiguration config = plugin.configs().professions();
        double direct = config.getDouble("professions." + id + ".xp-total-by-prestige." + safe, -1D);
        if (direct < 0D) direct = config.getDouble("professions." + id + ".xp-total-by-prestige.'" + safe + "'", -1D);
        if (direct >= 0D) return direct;
        return switch (safe) {
            case 0 -> 50_000D;
            case 1 -> 80_000D;
            case 2 -> 120_000D;
            case 3 -> 170_000D;
            default -> 240_000D;
        };
    }

    public double xpThreshold(int prestige, int level) {
        return xpThreshold(LUMBERJACK_ID, prestige, level);
    }

    public double xpThreshold(String professionId, int prestige, int level) {
        int safeLevel = clamp(level, 1, maxLevel);
        if (safeLevel <= 1) return 0D;
        if (safeLevel >= maxLevel) return totalXp(professionId, prestige);
        double progress = (safeLevel - 1D) / (maxLevel - 1D);
        return totalXp(professionId, prestige) * Math.pow(progress, levelCurveExponent);
    }

    public int levelForXp(int prestige, double xp) {
        return levelForXp(LUMBERJACK_ID, prestige, xp);
    }

    public int levelForXp(String professionId, int prestige, double xp) {
        double safeXp = Math.max(0D, Math.min(totalXp(professionId, prestige), xp));
        int low = 1;
        int high = maxLevel;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (xpThreshold(professionId, prestige, middle) <= safeXp + 0.000001D) low = middle;
            else high = middle - 1;
        }
        return low;
    }

    public List<Integer> milestones() {
        return List.of(25, 50, 75, 100).stream().filter(level -> level <= maxLevel).toList();
    }

    public int nextUncompletedMilestone(ProfessionProgress progress) {
        if (progress.prestige() >= maxPrestige && !maxPrestigeRequirementsEnabled(progress.professionId())) return -1;
        for (int milestone : milestones()) {
            if (milestone > progress.completedMilestone()) return milestone;
        }
        return -1;
    }

    public boolean maxPrestigeRequirementsEnabled() {
        return plugin.configs().professions().getBoolean(
                "max-prestige-continuation.milestone-requirements-enabled", false);
    }

    public boolean maxPrestigeRequirementsEnabled(String professionId) {
        return plugin.configs().professions().getBoolean(
                "professions." + normalize(professionId) + ".max-prestige-requirements-enabled",
                maxPrestigeRequirementsEnabled());
    }

    public MilestoneRequirement requirement(int prestige, int milestone) {
        return requirement(LUMBERJACK_ID, prestige, milestone);
    }

    public MilestoneRequirement requirement(String professionId, int prestige, int milestone) {
        if (prestige >= maxPrestige && !maxPrestigeRequirementsEnabled(professionId)) {
            return new MilestoneRequirement(milestone, 0L, Map.of(), Map.of(), Map.of(), Map.of());
        }
        return requirements.getOrDefault(normalize(professionId), Map.of())
                .getOrDefault(prestige, Map.of())
                .getOrDefault(milestone, new MilestoneRequirement(milestone, 0L, Map.of(), Map.of(), Map.of(), Map.of()));
    }

    public MaterialGroup materialGroup(String id) {
        return materialGroups.get(normalize(id));
    }

    public GrowthGroup growthGroup(String id) {
        return growthGroups.get(normalize(id));
    }

    public MinedGroup minedGroup(String id) {
        return minedGroups.get(normalize(id));
    }

    public HuntGroup huntGroup(String id) {
        return huntGroups.get(normalize(id));
    }

    public List<String> huntGroupsFor(String entityType, Set<String> requiredGroupIds) {
        if (entityType == null || requiredGroupIds == null || requiredGroupIds.isEmpty()) return List.of();
        String type = entityType.trim().toUpperCase(Locale.ROOT);
        return requiredGroupIds.stream()
                .map(this::huntGroup)
                .filter(group -> group != null && group.entityTypes().contains(type))
                .map(HuntGroup::id)
                .toList();
    }

    public double hunterXp(String entityType, int vanillaXp) {
        if (entityType == null || vanillaXp <= 0) return 0D;
        String path = "professions." + HUNTER_ID + ".xp-mobs." + entityType.toUpperCase(Locale.ROOT);
        FileConfiguration config = plugin.configs().professions();
        if (config.contains(path)) return Math.max(0D, config.getDouble(path, 0D));
        return Math.max(1D, vanillaXp);
    }

    public double hunterHeadChance(int prestige) {
        int safe = clamp(prestige, 0, maxPrestige);
        FileConfiguration config = plugin.configs().professions();
        double fallback = switch (safe) {
            case 0 -> 0.0030D;
            case 1 -> 0.0040D;
            case 2 -> 0.0060D;
            case 3 -> 0.0090D;
            case 4 -> 0.0130D;
            default -> 0.0200D;
        };
        return Math.max(0D, config.getDouble("professions." + HUNTER_ID + ".head-chance-by-prestige." + safe, fallback));
    }

    public double globalSwordHeadChance() {
        FileConfiguration config = plugin.configs().professions();
        // New name since v1.53.10. Keep the old physical config key as a compatibility fallback
        // so existing servers do not need an emergency config replacement during the update.
        if (config.contains("heads.global-sword-chance", true)) {
            return Math.max(0D, config.getDouble("heads.global-sword-chance", 0.002D));
        }
        return Math.max(0D, config.getDouble("heads.global-golden-sword-chance", 0.002D));
    }

    /** PvP player-head chance; intentionally independent of weapon, Looting and Hunter prestige. */
    public double playerKillHeadChance() {
        return Math.max(0D, Math.min(1D,
                plugin.configs().professions().getDouble("heads.player-kill-chance", 0.25D)));
    }

    public double headLootingMultiplierPerLevel() {
        return Math.max(0D, plugin.configs().professions().getDouble("heads.looting-relative-per-level", 0.20D));
    }

    public double headBossMultiplier(String entityType) {
        if (entityType == null) return 1D;
        return Math.max(0D, plugin.configs().professions().getDouble("heads.boss-multipliers." + entityType.toUpperCase(Locale.ROOT), 1D));
    }

    public double variantRerollChance(int prestige) {
        if (prestige < 3) return 0D;
        double fallback = prestige == 3 ? 0.10D : prestige == 4 ? 0.20D : 0.30D;
        return Math.max(0D, plugin.configs().professions().getDouble("heads.variant-reroll-by-prestige." + clamp(prestige, 0, maxPrestige), fallback));
    }

    public double missingVariantWeight(int prestige) {
        double fallback = prestige >= 5 ? 4D : prestige >= 4 ? 2D : 1D;
        return Math.max(1D, plugin.configs().professions().getDouble("heads.missing-variant-weight-by-prestige." + clamp(prestige, 0, maxPrestige), fallback));
    }

    public String growthGroupFor(Material sapling, Set<String> requiredGroupIds) {
        if (sapling == null || requiredGroupIds == null) return null;
        for (String rawId : requiredGroupIds) {
            GrowthGroup group = growthGroup(rawId);
            if (group != null && group.saplings().contains(sapling)) return group.id();
        }
        return null;
    }

    /** True for saplings/propagules that are part of the lumberjack growth catalog. */
    public boolean isLumberjackGrowthSapling(Material material) {
        if (material == null) return false;
        return growthGroups.values().stream().anyMatch(group -> group.saplings().contains(material));
    }

    /** XP granted for one player-planted sapling that actually grows into a tree. */
    public double saplingGrowthXp(Material material) {
        if (!isLumberjackGrowthSapling(material)) return 0D;
        return Math.max(0D, plugin.configs().professions().getDouble(
                "professions." + LUMBERJACK_ID + ".sapling-growth-xp", 2D));
    }

    public List<String> minedGroupsFor(Material material, Set<String> requiredGroupIds) {
        if (material == null || requiredGroupIds == null || requiredGroupIds.isEmpty()) return List.of();
        return requiredGroupIds.stream()
                .map(this::minedGroup)
                .filter(group -> group != null && group.materials().contains(material))
                .map(MinedGroup::id)
                .toList();
    }

    public List<LevelReward> rewards() {
        return rewards.values().stream()
                .sorted(Comparator.comparingInt(LevelReward::level))
                .toList();
    }

    public LevelReward reward(int level) {
        return rewards.get(level);
    }

    public List<LevelReward> rewardsUpTo(int level) {
        return rewards.values().stream()
                .filter(reward -> reward.level() <= level)
                .sorted(Comparator.comparingInt(LevelReward::level))
                .toList();
    }

    public long scaledRewardCoins(LevelReward reward, int prestige) {
        double scale = Math.max(0D, plugin.configs().professions()
                .getDouble("level-rewards.coin-scale-per-prestige", 0.25D));
        double value = reward.coins() * (1D + Math.max(0, prestige) * scale);
        return value >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0L, Math.round(value));
    }

    public String toolItemId(int prestige) {
        return toolItemId(LUMBERJACK_ID, prestige);
    }

    public String toolItemId(String professionId, int prestige) {
        if (prestige <= 0) return null;
        String id = normalize(professionId);
        String fallback;
        if (MINER_ID.equals(id)) {
            fallback = switch (prestige) {
                case 1 -> "eisenspitzhacke_schmelzer";
                case 2 -> "eisenspitzhacke_veinminer";
                case 3 -> "diamantspitzhacke_3x3";
                case 4 -> "diamantspitzhacke_veinminer_schmelzer";
                default -> "netheritespitzhacke_tnt";
            };
        } else if (HUNTER_ID.equals(id)) {
            fallback = switch (prestige) {
                case 1 -> "jaeger_trophaehenklinge";
                case 2 -> "jaeger_hetzjaegerspeer";
                case 3 -> "jaeger_jagdbogen";
                case 4 -> "jaeger_grosswild_mace";
                default -> "jaeger_meisterbrustplatte";
            };
        } else {
            fallback = switch (prestige) {
                case 1 -> "eisenaxt_holzschlag1";
                case 2 -> "eisenaxt_holzschlag2";
                case 3 -> "eisenaxt_holzschlag3";
                case 4 -> "diamantaxt_holzschlag4";
                default -> "netheritaxt_holzschlag5";
            };
        }
        return plugin.configs().professions().getString(
                "professions." + id + ".tools." + prestige, fallback);
    }

    public long replacementPrice(int prestige) {
        return replacementPrice(LUMBERJACK_ID, prestige);
    }

    public long replacementPrice(String professionId, int prestige) {
        String id = normalize(professionId);
        return Math.max(0L, plugin.configs().professions().getLong(
                "professions." + id + ".replacement-prices." + prestige,
                switch (prestige) {
                    case 1 -> 25_000L;
                    case 2 -> 50_000L;
                    case 3 -> 100_000L;
                    case 4 -> 200_000L;
                    default -> 400_000L;
                }));
    }

    public BoosterDefinition booster(String customItemId) {
        return boosterDefinitions.get(normalize(customItemId));
    }

    public int boosterDurationSeconds(BoosterCategory category) {
        if (category == BoosterCategory.LUMI) {
            return Math.max(60, plugin.configs().main().getInt("lumi.afk-zone.boosters.duration-seconds",
                    plugin.configs().professions().getInt("boosters.duration-seconds", 3600)));
        }
        return Math.max(60, plugin.configs().professions().getInt("boosters.duration-seconds", 3600));
    }

    public String string(String path, String fallback) {
        return plugin.configs().professions().getString(path, fallback);
    }

    public List<String> stringList(String path) {
        return plugin.configs().professions().getStringList(path);
    }

    private void loadXpBlocks(FileConfiguration config) {
        xpBlocks.clear();
        ConfigurationSection professions = config.getConfigurationSection("professions");
        ConfigurationSection defaultProfessions = config.getDefaults() == null
                ? null : config.getDefaults().getConfigurationSection("professions");
        if (professions == null && defaultProfessions == null) return;
        Set<String> professionIds = new LinkedHashSet<>();
        if (defaultProfessions != null) professionIds.addAll(defaultProfessions.getKeys(false));
        if (professions != null) professionIds.addAll(professions.getKeys(false));
        for (String rawProfession : professionIds) {
            String professionId = normalize(rawProfession);
            ConfigurationSection section = config.getConfigurationSection("professions." + rawProfession + ".xp-blocks");
            ConfigurationSection defaultSection = config.getDefaults() == null ? null
                    : config.getDefaults().getConfigurationSection("professions." + rawProfession + ".xp-blocks");
            if (section == null && defaultSection == null) continue;
            Set<String> materialKeys = new LinkedHashSet<>();
            if (defaultSection != null) materialKeys.addAll(defaultSection.getKeys(false));
            if (section != null) materialKeys.addAll(section.getKeys(false));
            Map<Material, Double> values = new EnumMap<>(Material.class);
            for (String key : materialKeys) {
                Material material = Material.matchMaterial(key);
                double xp = config.getDouble("professions." + rawProfession + ".xp-blocks." + key, 0D);
                if (material != null && xp > 0D) values.put(material, xp);
            }
            xpBlocks.put(professionId, values);
        }
    }

    private void loadSellMaterials(FileConfiguration config) {
        sellMaterials.clear();
        ConfigurationSection professions = config.getConfigurationSection("professions");
        if (professions == null) return;
        for (String rawProfession : professions.getKeys(false)) {
            String professionId = normalize(rawProfession);
            Set<Material> values = new LinkedHashSet<>();
            for (String raw : config.getStringList("professions." + rawProfession + ".sell-materials")) {
                Material material = Material.matchMaterial(raw);
                if (material != null) values.add(material);
            }
            sellMaterials.put(professionId, values);
        }
    }

    private void loadMaterialGroups(FileConfiguration config) {
        materialGroups.clear();
        ConfigurationSection root = config.getConfigurationSection("material-groups");
        if (root == null) return;
        for (String rawId : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(rawId);
            if (section == null) continue;
            Set<Material> materials = new LinkedHashSet<>();
            for (String raw : section.getStringList("materials")) {
                Material material = Material.matchMaterial(raw);
                if (material != null) materials.add(material);
            }
            Material icon = Material.matchMaterial(section.getString("icon", "BARRIER"));
            if (icon == null) icon = Material.BARRIER;
            String id = normalize(rawId);
            materialGroups.put(id, new MaterialGroup(id,
                    section.getString("display", rawId), icon, materials,
                    section.getStringList("description")));
        }
    }

    private void loadGrowthGroups(FileConfiguration config) {
        growthGroups.clear();
        ConfigurationSection root = config.getConfigurationSection("growth-groups");
        if (root != null) {
            for (String rawId : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(rawId);
                if (section == null) continue;
                Set<Material> saplings = new LinkedHashSet<>();
                for (String raw : section.getStringList("saplings")) {
                    Material material = Material.matchMaterial(raw);
                    if (material != null) saplings.add(material);
                }
                Material icon = Material.matchMaterial(section.getString("icon", "OAK_SAPLING"));
                if (icon == null) icon = Material.OAK_SAPLING;
                String id = normalize(rawId);
                if (!saplings.isEmpty()) {
                    growthGroups.put(id, new GrowthGroup(id,
                            section.getString("display", rawId), icon, saplings,
                            section.getStringList("description")));
                }
            }
        }
        ensureDefaultGrowthGroups();
    }

    private void loadMinedGroups(FileConfiguration config) {
        minedGroups.clear();
        ConfigurationSection root = config.getConfigurationSection("mined-groups");
        if (root == null) return;
        for (String rawId : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(rawId);
            if (section == null) continue;
            Set<Material> materials = new LinkedHashSet<>();
            for (String raw : section.getStringList("materials")) {
                Material material = Material.matchMaterial(raw);
                if (material != null) materials.add(material);
            }
            Material icon = Material.matchMaterial(section.getString("icon", "STONE_PICKAXE"));
            if (icon == null) icon = Material.STONE_PICKAXE;
            String id = normalize(rawId);
            if (!materials.isEmpty()) {
                minedGroups.put(id, new MinedGroup(id, section.getString("display", rawId), icon,
                        materials, section.getStringList("description")));
            }
        }
    }

    private void loadHuntGroups(FileConfiguration config) {
        huntGroups.clear();
        ConfigurationSection root = config.getConfigurationSection("hunt-groups");
        if (root == null) return;
        for (String rawId : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(rawId);
            if (section == null) continue;
            Set<String> entityTypes = new LinkedHashSet<>();
            for (String raw : section.getStringList("entity-types")) {
                if (raw != null && !raw.isBlank()) entityTypes.add(raw.trim().toUpperCase(Locale.ROOT));
            }
            Material icon = Material.matchMaterial(section.getString("icon", "IRON_SWORD"));
            if (icon == null) icon = Material.IRON_SWORD;
            String id = normalize(rawId);
            if (!entityTypes.isEmpty()) {
                huntGroups.put(id, new HuntGroup(id, section.getString("display", rawId), icon,
                        entityTypes, section.getStringList("description")));
            }
        }
    }

    private void ensureDefaultGrowthGroups() {
        Map<String, Set<Material>> defaults = new LinkedHashMap<>();
        defaults.put("setzlinge", Set.of(Material.OAK_SAPLING, Material.BIRCH_SAPLING,
                Material.SPRUCE_SAPLING, Material.ACACIA_SAPLING, Material.DARK_OAK_SAPLING,
                Material.JUNGLE_SAPLING, Material.CHERRY_SAPLING, Material.PALE_OAK_SAPLING,
                Material.MANGROVE_PROPAGULE));
        defaults.put("eichensetzlinge", Set.of(Material.OAK_SAPLING));
        defaults.put("birkensetzlinge", Set.of(Material.BIRCH_SAPLING));
        defaults.put("fichtensetzlinge", Set.of(Material.SPRUCE_SAPLING));
        defaults.put("akaziensetzlinge", Set.of(Material.ACACIA_SAPLING));
        defaults.put("schwarzeichensetzlinge", Set.of(Material.DARK_OAK_SAPLING));
        defaults.put("dschungelsetzlinge", Set.of(Material.JUNGLE_SAPLING));
        defaults.put("kirschsetzlinge", Set.of(Material.CHERRY_SAPLING));
        defaults.put("blasseichensetzlinge", Set.of(Material.PALE_OAK_SAPLING));
        defaults.put("mangrovenkeimlinge", Set.of(Material.MANGROVE_PROPAGULE));

        Map<String, String> displays = Map.ofEntries(
                Map.entry("setzlinge", "Gewachsene Setzlinge"),
                Map.entry("eichensetzlinge", "Gewachsene Eichensetzlinge"),
                Map.entry("birkensetzlinge", "Gewachsene Birkensetzlinge"),
                Map.entry("fichtensetzlinge", "Gewachsene Fichtensetzlinge"),
                Map.entry("akaziensetzlinge", "Gewachsene Akaziensetzlinge"),
                Map.entry("schwarzeichensetzlinge", "Gewachsene Schwarzeichensetzlinge"),
                Map.entry("dschungelsetzlinge", "Gewachsene Dschungelsetzlinge"),
                Map.entry("kirschsetzlinge", "Gewachsene Kirschsetzlinge"),
                Map.entry("blasseichensetzlinge", "Gewachsene Blasseichensetzlinge"),
                Map.entry("mangrovenkeimlinge", "Gewachsene Mangroven-Keimlinge")
        );
        Map<String, Material> icons = Map.ofEntries(
                Map.entry("setzlinge", Material.OAK_SAPLING),
                Map.entry("eichensetzlinge", Material.OAK_SAPLING),
                Map.entry("birkensetzlinge", Material.BIRCH_SAPLING),
                Map.entry("fichtensetzlinge", Material.SPRUCE_SAPLING),
                Map.entry("akaziensetzlinge", Material.ACACIA_SAPLING),
                Map.entry("schwarzeichensetzlinge", Material.DARK_OAK_SAPLING),
                Map.entry("dschungelsetzlinge", Material.JUNGLE_SAPLING),
                Map.entry("kirschsetzlinge", Material.CHERRY_SAPLING),
                Map.entry("blasseichensetzlinge", Material.PALE_OAK_SAPLING),
                Map.entry("mangrovenkeimlinge", Material.MANGROVE_PROPAGULE)
        );
        defaults.forEach((id, saplings) -> growthGroups.putIfAbsent(id,
                new GrowthGroup(id, displays.get(id), icons.get(id), saplings, List.of())));
    }

    private void loadRequirements(FileConfiguration config) {
        requirements.clear();
        ConfigurationSection root = config.getConfigurationSection("requirements");
        ConfigurationSection defaultRoot = config.getDefaults() == null ? null
                : config.getDefaults().getConfigurationSection("requirements");
        if (root == null && defaultRoot == null) return;
        for (String rawProfession : combinedKeys(root, defaultRoot)) {
            ConfigurationSection profession = config.getConfigurationSection("requirements." + rawProfession);
            ConfigurationSection defaultProfession = defaultRoot == null ? null
                    : defaultRoot.getConfigurationSection(rawProfession);
            if (profession == null) continue;
            Map<Integer, Map<Integer, MilestoneRequirement>> professionRequirements = new HashMap<>();
            for (String prestigeKey : combinedKeys(profession, defaultProfession)) {
                int prestige;
                try { prestige = Integer.parseInt(prestigeKey); }
                catch (NumberFormatException ignored) { continue; }
                ConfigurationSection prestigeSection = config.getConfigurationSection(
                        "requirements." + rawProfession + "." + prestigeKey);
                ConfigurationSection defaultPrestige = defaultProfession == null ? null
                        : defaultProfession.getConfigurationSection(prestigeKey);
                if (prestigeSection == null) continue;
                Map<Integer, MilestoneRequirement> levels = new HashMap<>();
                for (String levelKey : combinedKeys(prestigeSection, defaultPrestige)) {
                    int level;
                    try { level = Integer.parseInt(levelKey); }
                    catch (NumberFormatException ignored) { continue; }
                    ConfigurationSection section = config.getConfigurationSection(
                            "requirements." + rawProfession + "." + prestigeKey + "." + levelKey);
                    if (section == null) continue;
                    ConfigurationSection defaultSection = defaultPrestige == null ? null
                            : defaultPrestige.getConfigurationSection(levelKey);
                    Map<String, Long> materials = readRequirementMap(section.getConfigurationSection("materials"), materialGroups.keySet());
                    Map<String, Long> growths = readRequirementMap(section.getConfigurationSection("growths"), growthGroups.keySet());
                    Map<String, Long> mined = readRequirementMap(section.getConfigurationSection("mined"), minedGroups.keySet());
                    Map<String, Long> hunts = readRequirementMap(section.getConfigurationSection("hunts"), huntGroups.keySet());
                    Map<String, Long> fish = readRequirementMap(section.getConfigurationSection("fish"),
                            fishRegistry.definitions().stream().map(de.walahi.novosmp.angler.FishDefinition::id)
                                    .collect(java.util.stream.Collectors.toSet()));
                    Map<String, Long> skills = readMergedRequirementMap(section.getConfigurationSection("skills"),
                            defaultSection == null ? null : defaultSection.getConfigurationSection("skills"),
                            Set.of("green_hits", "max_combo"));
                    if (section.getConfigurationSection("growths") == null && LUMBERJACK_ID.equals(normalize(rawProfession))) {
                        growths.putAll(defaultGrowths(prestige, level));
                    }
                    levels.put(level, new MilestoneRequirement(level,
                            Math.max(0L, section.getLong("coin-cost", 0L)), materials, growths, mined, hunts, fish, skills));
                }
                professionRequirements.put(prestige, levels);
            }
            requirements.put(normalize(rawProfession), professionRequirements);
        }
    }

    private Set<String> combinedKeys(ConfigurationSection local, ConfigurationSection defaults) {
        Set<String> keys = new LinkedHashSet<>();
        if (defaults != null) keys.addAll(defaults.getKeys(false));
        if (local != null) keys.addAll(local.getKeys(false));
        return keys;
    }

    private Map<String, Long> readRequirementMap(ConfigurationSection section, Set<String> validIds) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (section == null) return result;
        for (String rawId : section.getKeys(false)) {
            String id = normalize(rawId);
            long amount = Math.max(0L, section.getLong(rawId, 0L));
            if (amount > 0L && validIds.contains(id)) result.put(id, amount);
        }
        return result;
    }

    private Map<String, Long> readMergedRequirementMap(ConfigurationSection local, ConfigurationSection defaults,
                                                         Set<String> validIds) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String rawId : combinedKeys(local, defaults)) {
            String id = normalize(rawId);
            if (!validIds.contains(id)) continue;
            long amount = local != null && local.isSet(rawId) ? local.getLong(rawId, 0L)
                    : defaults == null ? 0L : defaults.getLong(rawId, 0L);
            if (amount > 0L) result.put(id, amount);
        }
        return result;
    }

    private Map<String, Long> defaultGrowths(int prestige, int level) {
        Map<String, Long> values = new LinkedHashMap<>();
        if (prestige == 0) values.put("setzlinge", switch (level) {
            case 25 -> 25L; case 50 -> 50L; case 75 -> 100L; case 100 -> 200L; default -> 0L;
        });
        else if (prestige == 1) values.put("setzlinge", switch (level) {
            case 25 -> 40L; case 50 -> 80L; case 75 -> 160L; case 100 -> 300L; default -> 0L;
        });
        else if (prestige == 2) values.put("setzlinge", switch (level) {
            case 25 -> 60L; case 50 -> 120L; case 75 -> 240L; case 100 -> 450L; default -> 0L;
        });
        else if (prestige == 3) {
            switch (level) {
                case 25 -> { values.put("eichensetzlinge", 40L); values.put("birkensetzlinge", 40L); }
                case 50 -> { values.put("schwarzeichensetzlinge", 150L); values.put("kirschsetzlinge", 10L); }
                case 75 -> { values.put("dschungelsetzlinge", 160L); values.put("fichtensetzlinge", 160L); }
                case 100 -> {
                    values.put("eichensetzlinge", 150L); values.put("birkensetzlinge", 150L);
                    values.put("akaziensetzlinge", 110L); values.put("mangrovenkeimlinge", 75L);
                    values.put("kirschsetzlinge", 55L); values.put("blasseichensetzlinge", 60L);
                }
                default -> { }
            }
        } else if (prestige == 4) {
            switch (level) {
                case 25 -> {
                    values.put("kirschsetzlinge", 42L); values.put("blasseichensetzlinge", 42L);
                    values.put("mangrovenkeimlinge", 16L);
                }
                case 50 -> {
                    values.put("schwarzeichensetzlinge", 83L); values.put("dschungelsetzlinge", 83L);
                    values.put("mangrovenkeimlinge", 34L);
                }
                case 75 -> {
                    values.put("birkensetzlinge", 109L); values.put("kirschsetzlinge", 91L);
                    values.put("blasseichensetzlinge", 91L); values.put("mangrovenkeimlinge", 109L);
                }
                case 100 -> {
                    values.put("schwarzeichensetzlinge", 130L); values.put("dschungelsetzlinge", 139L);
                    values.put("eichensetzlinge", 139L); values.put("fichtensetzlinge", 139L);
                    values.put("akaziensetzlinge", 139L); values.put("mangrovenkeimlinge", 114L);
                }
                default -> { }
            }
        }
        values.values().removeIf(value -> value == null || value <= 0L);
        return values;
    }

    private void loadRewards(FileConfiguration config) {
        rewards.clear();
        ConfigurationSection root = config.getConfigurationSection("level-rewards.rewards");
        if (root == null) return;
        for (String levelKey : root.getKeys(false)) {
            int level;
            try { level = Integer.parseInt(levelKey); }
            catch (NumberFormatException ignored) { continue; }
            ConfigurationSection section = root.getConfigurationSection(levelKey);
            if (section == null) continue;
            Map<String, Integer> customItems = new LinkedHashMap<>();
            ConfigurationSection itemSection = section.getConfigurationSection("custom-items");
            if (itemSection != null) {
                for (String itemId : itemSection.getKeys(false)) {
                    int amount = Math.max(0, itemSection.getInt(itemId, 0));
                    if (amount > 0) customItems.put(normalize(itemId), amount);
                }
            }
            rewards.put(level, new LevelReward(level,
                    Math.max(0L, section.getLong("coins", 0L)),
                    Math.max(0L, section.getLong("lumis", 0L)), customItems));
        }
    }

    private void loadBoosters(FileConfiguration config) {
        boosterDefinitions.clear();
        loadBoosterSection(config.getConfigurationSection("boosters.items"), BoosterCategory.PROFESSION);
        loadBoosterSection(plugin.configs().main().getConfigurationSection("lumi.afk-zone.boosters.items"),
                BoosterCategory.LUMI);
        boosterDefinitions.putIfAbsent("lumi_booster_15", new BoosterDefinition(
                "lumi_booster_15", BoosterCategory.LUMI, 1.5D, "<gold>1,5× Lumi-Booster</gold>"));
        boosterDefinitions.putIfAbsent("lumi_booster_20", new BoosterDefinition(
                "lumi_booster_20", BoosterCategory.LUMI, 2.0D, "<yellow>2× Lumi-Booster</yellow>"));
    }

    private void loadBoosterSection(ConfigurationSection root, BoosterCategory expectedCategory) {
        if (root == null) return;
        for (String itemId : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(itemId);
            if (section == null) continue;
            try {
                BoosterCategory category = BoosterCategory.valueOf(
                        section.getString("category", expectedCategory.name()).toUpperCase(Locale.ROOT));
                if (category != expectedCategory) continue;
                double multiplier = Math.max(1D, section.getDouble("multiplier", 1D));
                boosterDefinitions.put(normalize(itemId), new BoosterDefinition(
                        normalize(itemId), category, multiplier,
                        section.getString("display", itemId)));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Ungültige Booster-Kategorie bei " + itemId + ".");
            }
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public record BoosterDefinition(String itemId, BoosterCategory category,
                                    double multiplier, String displayName) { }
}
