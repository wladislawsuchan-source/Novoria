package de.walahi.novosmp.duel;

import de.walahi.novosmp.NovoSMPPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class DuelConfig {
    private final NovoSMPPlugin plugin;
    private final File configFile;
    private final File layoutsFile;
    private YamlConfiguration config;
    private YamlConfiguration layouts;
    private final Map<String, DuelKit> kits = new LinkedHashMap<>();
    private final Map<String, DuelMap> maps = new LinkedHashMap<>();
    private final Map<String, LinkedHashMap<String, DuelMapInstance>> mapInstances = new LinkedHashMap<>();

    public DuelConfig(NovoSMPPlugin plugin) {
        this.plugin = plugin;
        this.configFile = plugin.configs().duelsFile().file();
        this.layoutsFile = new File(plugin.getServerConfigFolder(), "duel-layouts.yml");
        reload();
    }

    public synchronized void reload() {
        plugin.configs().duelsFile().reload();
        config = plugin.configs().duelsFile().yaml();
        layouts = YamlConfiguration.loadConfiguration(layoutsFile);
        loadKits();
        loadMaps();
    }

    public YamlConfiguration raw() { return config; }

    public String text(String path, String fallback, String... replacements) {
        String value = config.getString(path, fallback);
        if (value == null) value = fallback == null ? "" : fallback;
        for (int index = 0; index + 1 < replacements.length; index += 2) {
            value = value.replace(replacements[index], replacements[index + 1]);
        }
        return value;
    }

    public String message(String key, String fallback, String... replacements) {
        return text("messages." + key, fallback, replacements);
    }

    public int integer(String path, int fallback, int minimum, int maximum) {
        return clamp(config.getInt(path, fallback), minimum, maximum);
    }

    public Material configuredMaterial(String path, Material fallback) {
        return material(config.getString(path), fallback);
    }

    public Integer customModelData(String path) {
        if (!config.contains(path)) return null;
        int value = config.getInt(path, 0);
        return value > 0 ? value : null;
    }

    public List<String> strings(String path, List<String> fallback) {
        List<String> configured = config.getStringList(path);
        return configured.isEmpty() ? List.copyOf(fallback) : List.copyOf(configured);
    }

    public List<Integer> integers(String path, List<Integer> fallback) {
        List<Integer> configured = config.getIntegerList(path);
        return configured.isEmpty() ? List.copyOf(fallback) : List.copyOf(configured);
    }

    public String messagePrefix() {
        String centralized = plugin.configs().messages().getString("message-api.prefixes.duel");
        if (centralized != null && !centralized.isBlank()) return centralized;
        return config.getString("settings.message-prefix",
                "<dark_gray>[<light_purple>Duel</light_purple>]</dark_gray> ");
    }

    public int requestExpirySeconds() { return positive("settings.request-expiry-seconds", 60); }
    public int countdownSeconds() { return Math.max(0, config.getInt("settings.countdown-seconds", 3)); }
    public int endDelaySeconds() { return Math.max(0, config.getInt("settings.end-delay-seconds", 10)); }
    public int defaultDurationSeconds() { return positive("settings.default-duration-seconds", 300); }
    public int damageAttributionSeconds() { return positive("settings.damage-attribution-seconds", 10); }
    public int maximumArenaLeaveWarnings() { return Math.max(0, config.getInt("settings.arena-boundary.max-warnings", 5)); }
    public long arenaLeaveWarningCooldownMillis() {
        return Math.max(0L, config.getLong("settings.arena-boundary.warning-cooldown-millis", 1000L));
    }
    public List<Integer> timeAnnouncementSeconds() {
        return integers("settings.time-announcements.seconds", List.of(600, 300, 180, 120, 60, 30, 10, 5, 4, 3, 2, 1));
    }
    public List<Integer> endDelayAnnouncementSeconds() {
        return integers("settings.end-delay-announcements.seconds", List.of(10, 5, 3, 2, 1));
    }
    public int timeWarningSoundSecond() {
        return Math.max(0, config.getInt("settings.time-announcements.warning-sound-second", 10));
    }
    public boolean isTimeAnnouncement(long remainingSeconds) {
        if (remainingSeconds < 0L || remainingSeconds > Integer.MAX_VALUE) return false;
        if (timeAnnouncementSeconds().contains((int) remainingSeconds)) return true;
        int interval = Math.max(0, config.getInt("settings.time-announcements.interval-seconds", 60));
        return interval > 0 && remainingSeconds > 0L && remainingSeconds % interval == 0L;
    }
    public List<String> allowedCommands() {
        List<String> configured = strings("settings.allowed-commands", List.of("/duel leave", "/duell leave"));
        List<String> normalized = new ArrayList<>();
        for (String command : configured) {
            if (command == null || command.isBlank()) continue;
            String value = command.trim().toLowerCase(Locale.ROOT);
            if (!value.startsWith("/")) value = "/" + value;
            normalized.add(value);
        }
        return List.copyOf(normalized);
    }
    public boolean compareTotalDamage() { return config.getBoolean("settings.timeout.compare-total-damage", true); }
    public boolean countAbsorptionDamage() { return config.getBoolean("settings.timeout.count-absorption-damage", true); }
    public boolean compareHealthOnTie() { return config.getBoolean("settings.timeout.compare-health-on-tie", true); }
    public boolean instantLossOnDisconnect() { return config.getBoolean("settings.disconnect.instant-loss", true); }
    public boolean resetBeforeMatch() { return config.getBoolean("settings.arena-reset.before-match", false); }
    public boolean resetAfterMatch() { return config.getBoolean("settings.arena-reset.after-match", true); }
    public int resetTileSize() { return clamp(config.getInt("settings.arena-reset.tile-size", 16), 4, 32); }
    public int resetTileHeight() { return clamp(config.getInt("settings.arena-reset.tile-height", 16), 4, 64); }
    public int resetMaxMillisPerTick() { return clamp(config.getInt("settings.arena-reset.max-millis-per-tick", 12), 1, 40); }
    public long minimumWager() { return Math.max(0L, config.getLong("settings.wager.minimum", 0L)); }
    public long configuredMaximumWager() { return config.getLong("settings.wager.maximum", -1L); }

    public long maximumWager() {
        long technical = Long.MAX_VALUE / 2L;
        long configured = configuredMaximumWager();
        return configured < 0L ? technical : Math.min(technical, configured);
    }

    public List<Integer> durations() {
        List<Integer> result = new ArrayList<>();
        for (Integer value : config.getIntegerList("settings.durations-seconds")) {
            if (value != null && value > 0 && !result.contains(value)) result.add(value);
        }
        if (result.isEmpty()) result.add(defaultDurationSeconds());
        if (!result.contains(defaultDurationSeconds())) result.add(defaultDurationSeconds());
        Collections.sort(result);
        return result;
    }

    public DuelRules defaultRules() {
        return new DuelRules(
                config.getBoolean("settings.default-rules.crystals-allowed", true),
                config.getBoolean("settings.default-rules.crystal-damage", true),
                config.getBoolean("settings.default-rules.tnt-allowed", true),
                config.getBoolean("settings.default-rules.tnt-damage", true)
        );
    }

    public Map<String, DuelKit> kits() { return Collections.unmodifiableMap(kits); }
    /** Logical maps shown in the player menu. Additional copies are intentionally hidden here. */
    public Map<String, DuelMap> maps() { return Collections.unmodifiableMap(maps); }
    public DuelKit kit(String id) { return id == null ? null : kits.get(normalize(id)); }
    public DuelMap map(String id) { return id == null ? null : maps.get(normalize(id)); }

    /** Returns the original arena followed by every configured physical copy. */
    public List<DuelMap> arenas(String mapId) {
        String key = normalize(mapId);
        DuelMap original = maps.get(key);
        if (original == null) return List.of();
        List<DuelMap> result = new ArrayList<>();
        result.add(original);
        Map<String, DuelMapInstance> definitions = mapInstances.get(key);
        if (definitions != null) {
            for (DuelMapInstance definition : definitions.values()) result.add(definition.createArena(key, original));
        }
        return Collections.unmodifiableList(result);
    }

    public List<DuelMap> allArenas() {
        List<DuelMap> result = new ArrayList<>();
        for (String mapId : maps.keySet()) result.addAll(arenas(mapId));
        return Collections.unmodifiableList(result);
    }

    public int arenaCount(String mapId) { return arenas(mapId).size(); }

    /** "base"/"original" address the original arena; all other IDs address copies. */
    public DuelMap arena(String mapId, String instanceId) {
        String key = normalize(mapId);
        DuelMap original = maps.get(key);
        if (original == null) return null;
        String instanceKey = normalize(instanceId);
        if (instanceKey.isBlank() || instanceKey.equals("base") || instanceKey.equals("original")
                || instanceKey.equals("primary") || instanceKey.equals(key)) return original;
        Map<String, DuelMapInstance> definitions = mapInstances.get(key);
        DuelMapInstance definition = definitions == null ? null : definitions.get(instanceKey);
        return definition == null ? null : definition.createArena(key, original);
    }

    public List<String> instanceIds(String mapId) {
        String key = normalize(mapId);
        if (!maps.containsKey(key)) return List.of();
        List<String> result = new ArrayList<>();
        result.add("base");
        Map<String, DuelMapInstance> definitions = mapInstances.get(key);
        if (definitions != null) result.addAll(definitions.keySet());
        return Collections.unmodifiableList(result);
    }

    public DuelMap previewInstance(String mapId, String instanceId, String worldName, int offsetX, int offsetY, int offsetZ) {
        DuelMap original = map(mapId);
        if (original == null) return null;
        return new DuelMapInstance(instanceId, worldName, offsetX, offsetY, offsetZ)
                .createArena(normalize(mapId), original);
    }

    public synchronized void saveKit(String id, String displayName, Material icon, DuelLoadout loadout) {
        String key = normalize(id);
        config.set("kits." + key, null);
        ConfigurationSection section = config.createSection("kits." + key);
        section.set("display-name", displayName == null || displayName.isBlank() ? id : displayName);
        section.set("icon", icon == null ? Material.IRON_SWORD.name() : icon.name());
        ConfigurationSection contents = section.createSection("loadout");
        loadout.save(contents);
        saveConfig();
        kits.put(key, new DuelKit(key, section.getString("display-name", id), material(section.getString("icon"), Material.IRON_SWORD), loadout.copy()));
        clearLayoutsForKit(key);
    }

    public synchronized boolean updateKitIcon(String id, Material icon) {
        String key = normalize(id);
        DuelKit kit = kits.get(key);
        if (kit == null) return false;
        Material resolved = icon == null || !icon.isItem() ? kit.icon() : icon;
        config.set("kits." + key + ".icon", resolved.name());
        saveConfig();
        kits.put(key, new DuelKit(kit.id(), kit.displayName(), resolved, kit.loadout()));
        return true;
    }

    public synchronized boolean deleteKit(String id) {
        String key = normalize(id);
        if (!config.contains("kits." + key)) return false;
        config.set("kits." + key, null);
        kits.remove(key);
        clearLayoutsForKit(key);
        saveConfig();
        return true;
    }

    public synchronized void saveMap(DuelMap map) {
        String key = normalize(map.id());
        String path = "maps." + key;
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) section = config.createSection(path);
        // Do not clear the section: existing instance offsets must survive spawn/icon edits.
        section.set("display-name", map.displayName());
        section.set("icon", map.icon().name());
        section.set("world", map.worldName());
        section.set("region.min", List.of(map.minX(), map.minY(), map.minZ()));
        section.set("region.max", List.of(map.maxX(), map.maxY(), map.maxZ()));
        setLocation(section, "spawn-one", map.spawnOne());
        setLocation(section, "spawn-two", map.spawnTwo());
        section.set("schematic", map.schematicFile());
        saveConfig();
        maps.put(key, map);
        mapInstances.putIfAbsent(key, new LinkedHashMap<>());
    }

    public synchronized boolean saveInstance(String mapId, String instanceId, String worldName,
                                             int offsetX, int offsetY, int offsetZ) {
        String mapKey = normalize(mapId);
        String instanceKey = normalize(instanceId);
        if (!maps.containsKey(mapKey) || instanceKey.isBlank() || isReservedInstanceId(mapKey, instanceKey)) return false;
        LinkedHashMap<String, DuelMapInstance> definitions = mapInstances.computeIfAbsent(mapKey, ignored -> new LinkedHashMap<>());
        if (definitions.containsKey(instanceKey)) return false;
        DuelMapInstance definition = new DuelMapInstance(instanceKey, worldName, offsetX, offsetY, offsetZ);
        definitions.put(instanceKey, definition);
        String path = "maps." + mapKey + ".instances." + instanceKey;
        config.set(path + ".world", worldName);
        config.set(path + ".offset", List.of(offsetX, offsetY, offsetZ));
        saveConfig();
        return true;
    }

    public synchronized boolean deleteInstance(String mapId, String instanceId) {
        String mapKey = normalize(mapId);
        String instanceKey = normalize(instanceId);
        Map<String, DuelMapInstance> definitions = mapInstances.get(mapKey);
        if (definitions == null || definitions.remove(instanceKey) == null) return false;
        config.set("maps." + mapKey + ".instances." + instanceKey, null);
        saveConfig();
        return true;
    }

    public synchronized boolean deleteMap(String id) {
        String key = normalize(id);
        DuelMap removed = maps.remove(key);
        if (removed == null) return false;
        mapInstances.remove(key);
        config.set("maps." + key, null);
        saveConfig();
        return true;
    }

    public synchronized DuelLoadout layout(UUID playerId, String kitId) {
        DuelKit kit = kit(kitId);
        if (kit == null) return null;
        ConfigurationSection section = layouts.getConfigurationSection("players." + playerId + "." + normalize(kitId));
        if (section == null) return kit.loadout().copy();
        DuelLoadout loaded = DuelLoadout.load(section);
        return kit.loadout().sameItemTotals(loaded) ? loaded : kit.loadout().copy();
    }

    public synchronized void saveLayout(UUID playerId, String kitId, DuelLoadout loadout) {
        DuelKit kit = kit(kitId);
        if (kit == null || !kit.loadout().sameItemTotals(loadout)) return;
        String path = "players." + playerId + "." + normalize(kitId);
        layouts.set(path, null);
        ConfigurationSection section = layouts.createSection(path);
        loadout.save(section);
        saveLayouts();
    }

    public synchronized void clearLayoutsForKit(String kitId) {
        String key = normalize(kitId);
        ConfigurationSection players = layouts.getConfigurationSection("players");
        if (players == null) return;
        for (String uuid : players.getKeys(false)) layouts.set("players." + uuid + "." + key, null);
        saveLayouts();
    }

    private void loadKits() {
        kits.clear();
        ConfigurationSection root = config.getConfigurationSection("kits");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) continue;
            DuelLoadout loadout = DuelLoadout.load(section.getConfigurationSection("loadout"));
            kits.put(normalize(key), new DuelKit(
                    normalize(key),
                    section.getString("display-name", key),
                    material(section.getString("icon"), Material.IRON_SWORD),
                    loadout
            ));
        }
    }

    private void loadMaps() {
        maps.clear();
        mapInstances.clear();
        ConfigurationSection root = config.getConfigurationSection("maps");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) continue;
            List<Integer> min = section.getIntegerList("region.min");
            List<Integer> max = section.getIntegerList("region.max");
            if (min.size() < 3 || max.size() < 3) continue;
            String mapKey = normalize(key);
            String worldName = section.getString("world");
            maps.put(mapKey, new DuelMap(
                    mapKey,
                    section.getString("display-name", key),
                    material(section.getString("icon"), Material.GRASS_BLOCK),
                    worldName,
                    min.get(0), min.get(1), min.get(2),
                    max.get(0), max.get(1), max.get(2),
                    deserializeLocation(section.getConfigurationSection("spawn-one"), worldName),
                    deserializeLocation(section.getConfigurationSection("spawn-two"), worldName),
                    section.getString("schematic", "duel-schematics/" + mapKey + ".schem")
            ));

            LinkedHashMap<String, DuelMapInstance> definitions = new LinkedHashMap<>();
            ConfigurationSection instancesSection = section.getConfigurationSection("instances");
            if (instancesSection != null) {
                for (String instanceId : instancesSection.getKeys(false)) {
                    ConfigurationSection instanceSection = instancesSection.getConfigurationSection(instanceId);
                    if (instanceSection == null) continue;
                    List<Integer> offset = instanceSection.getIntegerList("offset");
                    if (offset.size() < 3) continue;
                    String instanceKey = normalize(instanceId);
                    if (instanceKey.isBlank() || isReservedInstanceId(mapKey, instanceKey)) continue;
                    String instanceWorld = instanceSection.getString("world", worldName);
                    if (instanceWorld == null || instanceWorld.isBlank()) continue;
                    definitions.put(instanceKey, new DuelMapInstance(
                            instanceKey, instanceWorld, offset.get(0), offset.get(1), offset.get(2)));
                }
            }
            mapInstances.put(mapKey, definitions);
        }
    }

    private boolean isReservedInstanceId(String mapKey, String instanceKey) {
        return instanceKey.equals("base") || instanceKey.equals("original") || instanceKey.equals("primary")
                || instanceKey.equals(mapKey);
    }

    private int positive(String path, int fallback) { return Math.max(1, config.getInt(path, fallback)); }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private Material material(String value, Material fallback) {
        if (value == null) return fallback;
        Material found = Material.matchMaterial(value);
        return found == null || found.isAir() || !found.isItem() ? fallback : found;
    }

    private void setLocation(ConfigurationSection parent, String path, Location location) {
        parent.set(path, null);
        if (location == null || location.getWorld() == null) return;
        ConfigurationSection section = parent.createSection(path);
        section.set("world", location.getWorld().getName());
        section.set("x", location.getX());
        section.set("y", location.getY());
        section.set("z", location.getZ());
        section.set("yaw", location.getYaw());
        section.set("pitch", location.getPitch());
    }

    private Location deserializeLocation(ConfigurationSection section, String fallbackWorld) {
        if (section == null) return null;
        String worldName = section.getString("world", fallbackWorld);
        if (worldName == null) return null;
        return new Location(
                Bukkit.getWorld(worldName),
                section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
                (float) section.getDouble("yaw"), (float) section.getDouble("pitch")
        );
    }

    private void saveConfig() {
        plugin.configs().duelsFile().save();
    }

    private void saveLayouts() {
        try { layouts.save(layoutsFile); }
        catch (IOException exception) { plugin.getLogger().severe("duel-layouts.yml konnte nicht gespeichert werden: " + exception.getMessage()); }
    }

    public static String normalize(String input) {
        if (input == null) return "";
        return input.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
    }
}
