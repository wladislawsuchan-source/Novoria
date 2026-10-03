package de.walahi.novosmp.crates;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.BlockPosition;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.api.event.ActionContext;
import de.walahi.smpcore.economy.EconomyOperationResult;
import de.walahi.smpcore.gui.Gui;
import de.walahi.novosmp.items.CustomItemManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class CrateManager implements de.walahi.smpcore.bridge.CrateLocator {
    private static final List<String> FILES = List.of("daily.yml", "vanta.yml", "lumi.yml", "novo.yml", "playtime.yml", "premium.yml");
    private static final int[] ROLL_DELAYS = {2, 2, 2, 2, 3, 3, 4, 4, 5, 6, 7, 8, 10};
    private static final long WINNER_DISPLAY_TICKS = 35L;

    private final SMPCorePlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final KeyManager keys;
    private final CustomItemManager customItems;
    private final Map<String, CrateDefinition> crates = new LinkedHashMap<>();
    private final Map<String, String> locations = new HashMap<>();
    private final Map<UUID, AnimationSession> sessions = new HashMap<>();
    private final File locationFile;
    private YamlConfiguration locationConfig;
    private BukkitTask idleParticleTask;
    private long idleTick;

    public CrateManager(SMPCorePlugin plugin, CustomItemManager customItems) {
        this.plugin = plugin;
        this.customItems = Objects.requireNonNull(customItems, "customItems");
        this.keys = new KeyManager(plugin);
        this.locationFile = new File(plugin.getDataFolder(), "crate-locations.yml");
        reload();
        Bukkit.getPluginManager().registerEvents(new CrateListener(this), plugin);
        Bukkit.getPluginManager().registerEvents(new CrateKeyCraftProtectionListener(keys), plugin);
    }


    /** Stoppt alle laufenden Crate-Aufgaben sauber beim Plugin-Shutdown. */
    public void shutdown() {
        if (idleParticleTask != null) {
            idleParticleTask.cancel();
            idleParticleTask = null;
        }
        for (AnimationSession session : new ArrayList<>(sessions.values())) {
            finish(session, false);
        }
        sessions.clear();
    }

    /**
     * Permanente, private Idle-Aura für alle gesetzten Crates. Die Partikel
     * werden nur an Spieler in Reichweite gesendet. Mit dem passenden Key in
     * der Haupthand wird die Aura etwas schneller und dichter.
     */
    private void startIdleParticles() {
        if (idleParticleTask != null) idleParticleTask.cancel();
        long period = Math.max(1L, plugin.configs().main().getLong("crates.idle-effects.period-ticks", 2L));
        idleParticleTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            idleTick += period;
            if (!plugin.configs().main().getBoolean("crates.idle-effects.enabled", true)) return;

            double range = Math.max(2.0D, plugin.configs().main().getDouble("crates.idle-effects.view-distance", 18.0D));
            double rangeSquared = range * range;
            for (Map.Entry<String, String> entry : locations.entrySet()) {
                Location crateLocation = parseLocationKey(entry.getKey());
                if (crateLocation == null || crateLocation.getWorld() == null) continue;
                CrateDefinition crate = crates.get(entry.getValue());
                if (crate == null || !crate.enabled()) continue;
                if (!crateLocation.getBlock().getType().name().endsWith("SHULKER_BOX")) continue;

                Location center = crateLocation.clone().add(0.5D, 0.58D, 0.5D);
                // Nur innerhalb dieses Refreshes teilen; Key und Viewer werden weiterhin einzeln geprüft.
                List<IdleParticle> normalFrame = null;
                List<IdleParticle> keyFrame = null;
                for (Player player : crateLocation.getWorld().getPlayers()) {
                    if (player.getLocation().distanceSquared(center) > rangeSquared) continue;
                    if (hasActiveAnimation(player)) continue;
                    boolean matchingKey = keys.isHoldingKey(player, crate.id());
                    List<IdleParticle> frame = matchingKey ? keyFrame : normalFrame;
                    if (frame == null) {
                        frame = buildIdleOrbit(crate, center, entry.getKey().hashCode(), matchingKey);
                        if (matchingKey) keyFrame = frame;
                        else normalFrame = frame;
                    }
                    sendIdleOrbit(player, frame);
                }
            }
        }, 1L, period);
    }

    private Location parseLocationKey(String key) {
        String[] parts = key.split(";");
        if (parts.length != 4) return null;
        try {
            org.bukkit.World world = Bukkit.getWorld(parts[0]);
            if (world == null) return null;
            return new Location(world, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private List<IdleParticle> buildIdleOrbit(CrateDefinition crate, Location center, int seed, boolean matchingKey) {
        List<IdleParticle> frame = new ArrayList<>();
        String id = crate.id().toLowerCase(Locale.ROOT);
        double baseSpeed = switch (id) {
            case "vanta" -> 0.115D;
            case "daily" -> 0.072D;
            default -> 0.088D;
        };
        double speedMultiplier = plugin.configs().main().getDouble("crates.idle-effects.key-boost.speed-multiplier", 1.30D);
        double speed = baseSpeed * (matchingKey ? Math.max(1.0D, speedMultiplier) : 1.0D);
        double phase = idleTick * speed + (seed * 0.013D);

        double minRadius = plugin.configs().main().getDouble("crates.idle-effects.radius.min", 0.95D);
        double maxRadius = plugin.configs().main().getDouble("crates.idle-effects.radius.max", 1.35D);
        if (maxRadius < minRadius) { double swap = minRadius; minRadius = maxRadius; maxRadius = swap; }
        minRadius = Math.max(0.10D, minRadius);
        maxRadius = Math.max(minRadius, maxRadius);

        double minTilt = Math.toRadians(plugin.configs().main().getDouble("crates.idle-effects.tilt.min-degrees", 10.0D));
        double maxTilt = Math.toRadians(plugin.configs().main().getDouble("crates.idle-effects.tilt.max-degrees", 42.0D));
        if (maxTilt < minTilt) { double swap = minTilt; minTilt = maxTilt; maxTilt = swap; }
        double tiltRange = Math.max(0.0D, maxTilt - minTilt);
        double tiltX = Math.copySign(minTilt + Math.abs(Math.sin(phase * 0.11D + seed)) * tiltRange, Math.sin(phase * 0.11D + seed));
        double tiltZ = Math.copySign(minTilt + Math.abs(Math.cos(phase * 0.09D - seed)) * tiltRange, Math.cos(phase * 0.09D - seed));
        double direction = Math.sin(phase * 0.035D) >= 0.0D ? 1.0D : -1.0D;
        double angle = phase * direction;

        int rings = Math.max(1, plugin.configs().main().getInt("crates.idle-effects.particles.rings", 3));
        int normalPerRing = Math.max(1, plugin.configs().main().getInt("crates.idle-effects.particles.normal-per-ring", 7));
        int boostedPerRing = Math.max(normalPerRing, plugin.configs().main().getInt("crates.idle-effects.particles.with-key-per-ring", 10));
        int pointsPerRing = matchingKey ? boostedPerRing : normalPerRing;
        int floating = Math.max(0, plugin.configs().main().getInt(
                matchingKey ? "crates.idle-effects.particles.floating-with-key" : "crates.idle-effects.particles.floating", matchingKey ? 6 : 4));
        List<Color> palette = idlePalette(id);

        double minHeight = plugin.configs().main().getDouble("crates.idle-effects.height.min-offset", -0.48D);
        double maxHeight = plugin.configs().main().getDouble("crates.idle-effects.height.max-offset", 0.68D);
        if (maxHeight < minHeight) { double swap = minHeight; minHeight = maxHeight; maxHeight = swap; }
        double heightCenter = (minHeight + maxHeight) * 0.5D;
        double heightAmplitude = (maxHeight - minHeight) * 0.5D;

        float normalSize = (float) plugin.configs().main().getDouble("crates.idle-effects.particles.size", 0.82D);
        float boostedSize = (float) plugin.configs().main().getDouble("crates.idle-effects.particles.key-size", 1.00D);
        float size = matchingKey ? boostedSize : normalSize;

        for (int ring = 0; ring < rings; ring++) {
            double ringProgress = rings == 1 ? 0.5D : ring / (double) (rings - 1);
            double ringRadiusMultiplier = 0.88D + ringProgress * 0.18D;
            double ringSpeedMultiplier = 0.90D + ring * 0.09D;
            double ringPhase = ring * (Math.PI * 2.0D / rings);

            for (int i = 0; i < pointsPerRing; i++) {
                double trackOffset = i * (Math.PI * 2.0D / pointsPerRing);
                double a = angle * ringSpeedMultiplier + trackOffset + ringPhase;
                double radiusWave = (Math.sin(phase * (0.17D + ring * 0.025D) + i * 0.85D) + 1.0D) * 0.5D;
                double radius = (minRadius + (maxRadius - minRadius) * radiusWave) * ringRadiusMultiplier;

                double x = Math.cos(a) * radius;
                double y = heightCenter
                        + Math.sin(a * (1.08D + ring * 0.08D)) * heightAmplitude
                        + (ring - (rings - 1) / 2.0D) * 0.08D;
                double z = Math.sin(a) * radius;

                double localTiltX = tiltX + Math.sin(phase * 0.07D + ring) * 0.12D;
                double localTiltZ = tiltZ + Math.cos(phase * 0.08D - ring) * 0.12D;
                double y1 = y * Math.cos(localTiltX) - z * Math.sin(localTiltX);
                double z1 = y * Math.sin(localTiltX) + z * Math.cos(localTiltX);
                double x2 = x * Math.cos(localTiltZ) - y1 * Math.sin(localTiltZ);
                double y2 = x * Math.sin(localTiltZ) + y1 * Math.cos(localTiltZ);

                Location point = center.clone().add(x2, y2, z1);
                int colorIndex = Math.floorMod((int) (idleTick / 6L) + seed + ring * 3 + i, palette.size());
                Color color = palette.get(colorIndex);
                frame.add(new IdleParticle(Particle.DUST, point, 1, 0, 0, 0,
                        color, Math.max(0.10F, size)));

                if (id.equals("lumi") && ((idleTick + seed + i * 11L + ring * 7L) % (matchingKey ? 16L : 26L) == 0L)) {
                    frame.add(new IdleParticle(Particle.END_ROD, point, 1, 0.012D, 0.018D, 0.012D,
                            null, 0.0F));
                }
            }
        }

        // Frei schwebende Funken füllen den Raum zwischen den Ringen, ohne
        // wie eine starre Partikelwand auszusehen.
        for (int i = 0; i < floating; i++) {
            double a = phase * (0.55D + i * 0.06D) + seed * 0.021D + i * 2.399D;
            double radius = minRadius * 0.42D + (maxRadius - minRadius * 0.42D)
                    * ((Math.sin(phase * 0.13D + i * 1.91D) + 1.0D) * 0.5D);
            double y = minHeight + (maxHeight - minHeight)
                    * ((Math.sin(phase * 0.19D + i * 1.37D) + 1.0D) * 0.5D);
            Location point = center.clone().add(Math.cos(a) * radius, y, Math.sin(a) * radius);
            Color color = palette.get(Math.floorMod(seed + i + (int) (idleTick / 10L), palette.size()));
            frame.add(new IdleParticle(Particle.DUST, point, 1, 0.025D, 0.025D, 0.025D,
                    color, Math.max(0.10F, size * 0.82F)));
        }

        int burstInterval = Math.max(0, plugin.configs().main().getInt("crates.idle-effects.particles.burst-interval-ticks", 60));
        int burstCount = Math.max(0, plugin.configs().main().getInt("crates.idle-effects.particles.burst-count", 8));
        if (burstInterval > 0 && burstCount > 0 && Math.floorMod(idleTick + seed, burstInterval) < 2L) {
            Color burstColor = palette.get(Math.floorMod(seed + (int) idleTick, palette.size()));
            frame.add(new IdleParticle(Particle.DUST, center.clone().add(0.0D, 0.12D, 0.0D), burstCount,
                    0.52D, 0.46D, 0.52D, burstColor, Math.max(0.10F, size * 0.92F)));
        }
        return frame;
    }

    private void sendIdleOrbit(Player player, List<IdleParticle> frame) {
        for (IdleParticle point : frame) {
            if (point.particle() == Particle.END_ROD) {
                player.spawnParticle(Particle.END_ROD, point.location(), 1,
                        0.012D, 0.018D, 0.012D, 0.0D);
            } else {
                player.spawnParticle(Particle.DUST, point.location(), point.count(),
                        point.offsetX(), point.offsetY(), point.offsetZ(), 0.0D,
                        new Particle.DustOptions(point.color(), point.size()));
            }
        }
    }

    private record IdleParticle(Particle particle, Location location, int count,
                                double offsetX, double offsetY, double offsetZ,
                                Color color, float size) { }

    private List<Color> idlePalette(String crateId) {
        List<String> defaults = switch (crateId) {
            case "lumi" -> List.of("#F5C437", "#FFE36A", "#F39A2E", "#FFF4C2");
            case "vanta" -> List.of("#74131F", "#A51F2D", "#D13A43", "#4A1620");
            case "daily" -> List.of("#975B30", "#D77A2B", "#C99A3D", "#F1B84B");
            case "novo" -> List.of("#6D28D9", "#A855F7", "#C084FC", "#E879F9");
            case "playtime" -> List.of("#06B6D4", "#22D3EE", "#67E8F9", "#CFFAFE");
            default -> List.of("#B4B4B4", "#E0E0E0");
        };
        List<String> configured = plugin.configs().main().getStringList("crates.idle-effects.colors." + crateId);
        List<String> values = configured.isEmpty() ? defaults : configured;
        List<Color> colors = new ArrayList<>();
        for (String value : values) {
            String hex = value == null ? "" : value.trim().replace("#", "");
            if (hex.length() != 6) continue;
            try {
                colors.add(Color.fromRGB(Integer.parseInt(hex, 16)));
            } catch (NumberFormatException ignored) {
                // Ungültige Einträge werden übersprungen, statt die Aura zu stoppen.
            }
        }
        if (colors.isEmpty()) colors.add(Color.fromRGB(180, 180, 180));
        return colors;
    }

    public void reload() {
        File folder = new File(plugin.getDataFolder(), "crates");
        if (!folder.exists() && !folder.mkdirs()) plugin.getLogger().warning("Crate-Ordner konnte nicht erstellt werden.");
        for (String name : FILES) {
            File target = new File(folder, name);
            if (!target.exists()) {
                plugin.saveResource("crates/" + name, false);
            } else if ("playtime.yml".equals(name) && isLegacyEmptyPlaytime(target)) {
                // Bis 1.50.0 lag hier absichtlich nur ein deaktivierter Platzhalter. Beim ersten
                // Start mit dem echten Spielzeit-System darf genau dieser leere Altbestand durch
                // die neue Definition ersetzt werden. Spätere, nicht-leere Admin-Anpassungen
                // werden niemals automatisch überschrieben.
                plugin.saveResource("crates/" + name, true);
                plugin.getLogger().info("Leere alte playtime.yml auf die echte Spielzeit-Kiste aktualisiert.");
            }
        }
        crates.clear();
        for (String name : FILES) load(new File(folder, name));
        keys.reload();
        loadLocations();
        if (plugin.configs().server().getBoolean("crates.auto-recovery-enabled", false)) {
            int recovered = discoverMissingCrates();
            if (recovered > 0) {
                plugin.getLogger().info(recovered + " Crate-Blöcke automatisch im SMP-Spawn erkannt und gespeichert.");
            }
        }
        startIdleParticles();
        plugin.getLogger().info("Crates geladen: " + crates.size()
                + ", gesetzte Crate-Blöcke: " + locations.size());
    }

    private boolean isLegacyEmptyPlaytime(File file) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        if (!"playtime".equalsIgnoreCase(config.getString("id", "playtime"))) return false;
        ConfigurationSection rewards = config.getConfigurationSection("rewards");
        boolean emptyRewards = rewards == null || rewards.getKeys(false).isEmpty();
        return !config.getBoolean("enabled", false) && emptyRewards;
    }

    private void load(File file) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        String id = config.getString("id", file.getName().replace(".yml", "")).toLowerCase(Locale.ROOT);
        String displayName = config.getString("display-name", id);
        String keyDisplayName = config.getString("key-display-name", "");
        Material keyMaterial = Material.matchMaterial(config.getString("key-material", defaultKeyMaterial(id).name()));
        if (keyMaterial == null) {
            plugin.getLogger().warning("Ungültiges Key-Material für Crate " + id + "; verwende " + defaultKeyMaterial(id).name() + ".");
            keyMaterial = defaultKeyMaterial(id);
        }
        boolean enabled = config.getBoolean("enabled", false);
        List<CrateReward> rewards = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("rewards");
        if (section != null) for (String rewardId : section.getKeys(false)) {
            ConfigurationSection r = section.getConfigurationSection(rewardId);
            if (r == null) continue;
            try {
                CrateReward.Type type = CrateReward.Type.valueOf(r.getString("type", "ITEM").toUpperCase(Locale.ROOT));
                Material material = type == CrateReward.Type.ITEM
                        ? Material.matchMaterial(r.getString("material", "STONE"))
                        : null;
                if (type == CrateReward.Type.ITEM && material == null) {
                    throw new IllegalArgumentException("Ungültiges Material");
                }

                String customItemId = type == CrateReward.Type.CUSTOM_ITEM
                        ? r.getString("item", "").trim().toLowerCase(Locale.ROOT)
                        : "";
                if (type == CrateReward.Type.CUSTOM_ITEM) {
                    if (customItemId.isBlank()) {
                        throw new IllegalArgumentException("Für CUSTOM_ITEM fehlt der Eintrag 'item'");
                    }
                    if (customItems.find(customItemId).isEmpty()) {
                        throw new IllegalArgumentException("Unbekanntes Custom-Item '" + customItemId + "'");
                    }
                }

                rewards.add(new CrateReward(rewardId, r.getString("display-name", rewardId), r.getDouble("chance"), type,
                        material, Math.max(1, r.getInt("amount", 1)), r.getLong("coins", 0L),
                        r.getString("crate", ""), r.getString("command", ""),
                        Math.max(0, Math.min(3, r.getInt("firework.flight", 0))), customItemId,
                        r.getString("item-name", ""), readEnchantments(r.getConfigurationSection("enchantments"))));
            } catch (Exception exception) {
                plugin.getLogger().warning("Ungültiger Reward " + id + "/" + rewardId + ": " + exception.getMessage());
            }
        }
        double total = rewards.stream().mapToDouble(CrateReward::weight).sum();
        if (Math.abs(total - 100D) > 0.001D && !rewards.isEmpty()) plugin.getLogger().warning("Crate " + id + " hat insgesamt " + total + "% statt 100%.");
        crates.put(id, new CrateDefinition(id, displayName, keyDisplayName, keyMaterial, enabled, List.copyOf(rewards)));
    }

    private Material defaultKeyMaterial(String crateId) {
        String id = crateId == null ? "" : crateId.trim().toLowerCase(Locale.ROOT);
        return switch (id) {
            case "lumi" -> Material.TRIAL_KEY;
            case "vanta" -> Material.OMINOUS_TRIAL_KEY;
            case "novo" -> Material.AMETHYST_SHARD;
            case "playtime" -> Material.BREEZE_ROD;
            case "daily" -> Material.NAME_TAG;
            default -> Material.TRIPWIRE_HOOK;
        };
    }

    private void loadLocations() {
        locationConfig = YamlConfiguration.loadConfiguration(locationFile);
        locations.clear();
        ConfigurationSection section = locationConfig.getConfigurationSection("locations");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            String crateId = section.getString(key, "").toLowerCase(Locale.ROOT);
            if (crates.containsKey(crateId)) locations.put(key, crateId);
        }
    }

    /**
     * Stellt verlorene crate-locations.yml Einträge anhand der bereits gebauten
     * Shulkerboxen im SMP-Spawn wieder her. Gescannt werden ausschließlich
     * geladene Chunks in einem begrenzten Radius um den Weltspawn, damit keine
     * Spieler-Shulkerboxen in normalen SMP-Welten als Crates erkannt werden.
     */
    public int discoverMissingCrates() {
        String worldName = plugin.configs().server().getString("spawn-world", "smp_spawn");
        org.bukkit.World world = Bukkit.getWorld(worldName);
        if (world == null) return 0;

        Location spawn = world.getSpawnLocation();
        int radius = Math.max(16, plugin.configs().server().getInt("crates.auto-recovery-radius", 128));
        int radiusSquared = radius * radius;
        List<Location> candidates = new ArrayList<>();
        Set<String> configuredKeys = new HashSet<>(locations.keySet());

        for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
            for (org.bukkit.block.BlockState state : chunk.getTileEntities()) {
                Location location = state.getLocation();
                if (!state.getType().name().endsWith("SHULKER_BOX")) continue;
                int dx = location.getBlockX() - spawn.getBlockX();
                int dz = location.getBlockZ() - spawn.getBlockZ();
                if (dx * dx + dz * dz > radiusSquared) continue;
                if (configuredKeys.contains(locationKey(location))) continue;
                candidates.add(location);
            }
        }
        return assignDiscoveredCrates(candidates);
    }

    /** Wird beim späteren Laden eines SMP-Spawn-Chunks erneut ausgeführt. */
    public int discoverMissingCrates(org.bukkit.Chunk chunk) {
        if (chunk == null) return 0;
        String worldName = plugin.configs().server().getString("spawn-world", "smp_spawn");
        if (!chunk.getWorld().getName().equals(worldName)) return 0;
        Location spawn = chunk.getWorld().getSpawnLocation();
        int radius = Math.max(16, plugin.configs().server().getInt("crates.auto-recovery-radius", 128));
        int radiusSquared = radius * radius;
        List<Location> candidates = new ArrayList<>();
        for (org.bukkit.block.BlockState state : chunk.getTileEntities()) {
            Location location = state.getLocation();
            if (!state.getType().name().endsWith("SHULKER_BOX")) continue;
            int dx = location.getBlockX() - spawn.getBlockX();
            int dz = location.getBlockZ() - spawn.getBlockZ();
            if (dx * dx + dz * dz > radiusSquared) continue;
            if (locations.containsKey(locationKey(location))) continue;
            candidates.add(location);
        }
        return assignDiscoveredCrates(candidates);
    }

    private int assignDiscoveredCrates(List<Location> candidates) {
        if (candidates.isEmpty()) return 0;
        candidates.sort(Comparator.comparing((Location l) -> l.getWorld().getName())
                .thenComparingInt(Location::getBlockX)
                .thenComparingInt(Location::getBlockY)
                .thenComparingInt(Location::getBlockZ));

        Set<String> alreadyPlaced = new HashSet<>(locations.values());
        List<String> remaining = crates.values().stream()
                .filter(CrateDefinition::enabled)
                .map(CrateDefinition::id)
                .filter(id -> !alreadyPlaced.contains(id))
                .sorted(Comparator.comparingInt(this::crateRecoveryPriority))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        int recovered = 0;
        for (Location location : candidates) {
            String preferred = preferredCrateForMaterial(location.getBlock().getType());
            String crateId = preferred != null && remaining.remove(preferred)
                    ? preferred
                    : (remaining.isEmpty() ? null : remaining.remove(0));
            if (crateId == null) break;
            locations.put(locationKey(location), crateId);
            recovered++;
            plugin.getLogger().info("Crate automatisch zugeordnet: " + crateId + " bei "
                    + location.getWorld().getName() + " " + location.getBlockX() + " "
                    + location.getBlockY() + " " + location.getBlockZ());
        }
        if (recovered > 0) saveLocations();
        return recovered;
    }

    private int crateRecoveryPriority(String id) {
        return switch (id) {
            case "daily" -> 0;
            case "vanta" -> 1;
            case "lumi" -> 2;
            case "novo" -> 3;
            case "playtime" -> 4;
            default -> 10;
        };
    }

    private String preferredCrateForMaterial(Material material) {
        String name = material.name();
        if (material == Material.PURPLE_SHULKER_BOX) return "novo";
        if (material == Material.CYAN_SHULKER_BOX) return "playtime";
        if (name.startsWith("RED_") || name.startsWith("BLACK_")
                || name.startsWith("MAGENTA_")) return "vanta";
        if (name.startsWith("YELLOW_") || name.startsWith("LIME_")
                || name.startsWith("LIGHT_BLUE_")) return "lumi";
        if (name.startsWith("ORANGE_") || name.startsWith("BROWN_")
                || name.startsWith("WHITE_") || name.startsWith("LIGHT_GRAY_")
                || name.startsWith("GRAY_")) return "daily";
        return null;
    }

    private void saveLocations() {
        locationConfig.set("locations", null);
        locations.forEach((key, crateId) -> locationConfig.set("locations." + key, crateId));
        try { locationConfig.save(locationFile); }
        catch (IOException exception) { plugin.getLogger().log(java.util.logging.Level.SEVERE, "Crate-Positionen konnten nicht gespeichert werden.", exception); }
    }

    private String locationKey(Location location) {
        return location.getWorld().getName() + ";" + location.getBlockX() + ";" + location.getBlockY() + ";" + location.getBlockZ();
    }

    SMPCorePlugin plugin() { return plugin; }

    public int configuredCrateCount() { return crates.size(); }

    public int enabledCrateCount() {
        return (int) crates.values().stream().filter(CrateDefinition::enabled).count();
    }

    public int placementCount() { return locations.size(); }

    public List<String> validationWarnings() {
        List<String> warnings = new ArrayList<>();
        if (crates.isEmpty()) warnings.add("Keine Crate-Definitionen geladen.");
        for (CrateDefinition crate : crates.values()) {
            if (crate.enabled() && crate.rewards().isEmpty()) {
                warnings.add("Crate '" + crate.id() + "' ist aktiviert, hat aber keine Rewards.");
            }
        }
        for (Map.Entry<String, String> entry : locations.entrySet()) {
            Location location = parseLocationKey(entry.getKey());
            if (location == null || location.getWorld() == null) {
                warnings.add("Crate-Position nicht auflösbar: " + entry.getKey() + " -> " + entry.getValue());
                continue;
            }
            if (!location.getBlock().getType().name().endsWith("SHULKER_BOX")) {
                warnings.add("Crate-Block fehlt: " + entry.getValue() + " bei " + entry.getKey()
                        + " (gefunden: " + location.getBlock().getType() + ")");
            }
        }
        return List.copyOf(warnings);
    }

    public Optional<String> crateAt(Location location) { return Optional.ofNullable(locations.get(locationKey(location))); }

    @Override
    public List<de.walahi.smpcore.bridge.CrateLocator.CratePlacement> placements() {
        List<de.walahi.smpcore.bridge.CrateLocator.CratePlacement> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : locations.entrySet()) {
            Location location = parseLocationKey(entry.getKey());
            CrateDefinition definition = crates.get(entry.getValue());
            if (location == null || definition == null) continue;
            result.add(new de.walahi.smpcore.bridge.CrateLocator.CratePlacement(
                    location.clone(), definition.id(), definition.displayName()));
        }
        return List.copyOf(result);
    }

    public boolean setCrate(Location location, String crateId) {
        CrateDefinition crate = find(crateId).orElse(null);
        if (crate == null || location == null || location.getWorld() == null) return false;
        if (!location.getBlock().getType().name().endsWith("SHULKER_BOX")) return false;
        locations.put(locationKey(location), crate.id());
        saveLocations();
        // Der laufende Task liest die Map direkt; ein Neustart ist nicht nötig.
        if (idleParticleTask == null || idleParticleTask.isCancelled()) startIdleParticles();
        return true;
    }

    public boolean removeCrate(Location location) {
        if (locations.remove(locationKey(location)) == null) return false;
        saveLocations();
        return true;
    }

    public void openPreview(Player player, String crateId) {
        CrateDefinition crate = find(crateId).orElse(null);
        if (crate == null || !crate.enabled()) return;

        List<CrateReward> sorted = crate.rewards().stream()
                .sorted(Comparator.comparingDouble(CrateReward::weight))
                .toList();

        Component title = Component.text("Gewinne in der ", NamedTextColor.DARK_GRAY)
                .append(Component.text(crate.displayName(), NamedTextColor.GOLD));
        Gui gui = new Gui(6, title);
        applyPreviewBorder(gui);

        int[] rewardSlots = previewRewardSlots();
        for (int index = 0; index < sorted.size() && index < rewardSlots.length; index++) {
            gui.item(rewardSlots[index], previewItem(sorted.get(index)));
        }
        gui.open(player);
    }

    /**
     * 6-Reihen-Layout mit einem durchgehenden grauen Rahmen und sieben
     * Belohnungs-Slots pro Zeile. Die Items laufen von links nach rechts und
     * anschließend in der nächsten Zeile weiter.
     */
    private int[] previewRewardSlots() {
        int[] slots = new int[28];
        int index = 0;
        for (int row = 1; row <= 4; row++) {
            for (int column = 1; column <= 7; column++) {
                slots[index++] = row * 9 + column;
            }
        }
        return slots;
    }

    private void applyPreviewBorder(Gui gui) {
        ItemStack filler = previewFiller();
        for (int slot = 0; slot < 9; slot++) gui.item(slot, filler.clone());
        for (int slot = 45; slot < 54; slot++) gui.item(slot, filler.clone());
        for (int row = 1; row <= 4; row++) {
            gui.item(row * 9, filler.clone());
            gui.item(row * 9 + 8, filler.clone());
        }
    }

    private ItemStack previewFiller() {
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        meta.displayName(Component.empty());
        filler.setItemMeta(meta);
        return filler;
    }

    private ItemStack previewItem(CrateReward reward) {
        ItemStack item = reward.type() == CrateReward.Type.CUSTOM_ITEM
                ? customItems.create(reward.customItemId(), displayAmount(reward))
                : reward.type() == CrateReward.Type.ITEM
                    ? createRewardItem(reward, displayAmount(reward))
                    : new ItemStack(rewardIcon(reward), displayAmount(reward));
        if (item == null) item = new ItemStack(Material.PAPER);

        ItemMeta meta = item.getItemMeta();
        if (reward.type() != CrateReward.Type.CUSTOM_ITEM || meta.displayName() == null) {
            meta.displayName(Component.text(reward.displayName(), NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false));
        }
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        if (!lore.isEmpty()) lore.add(Component.empty());
        String chance = String.format(Locale.GERMANY, "%.2f", reward.weight());
        lore.add(Component.text("Chance: ", NamedTextColor.GRAY)
                .append(Component.text(chance + "%", NamedTextColor.YELLOW))
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public boolean hasActiveAnimation(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    public OpenResult startAnimatedOpen(Player player, String crateId, Location crateLocation, boolean consumeKey) {
        CrateDefinition crate = find(crateId).orElse(null);
        if (crate == null) return OpenResult.UNKNOWN_CRATE;
        if (!crate.enabled()) return OpenResult.DISABLED;
        if (crate.rewards().isEmpty()) return OpenResult.NO_REWARDS;
        if (hasActiveAnimation(player)) return OpenResult.ALREADY_OPENING;
        if (player.getInventory().firstEmpty() == -1) return OpenResult.INVENTORY_FULL;
        if (!crateLocation.getBlock().getType().name().endsWith("SHULKER_BOX")) return OpenResult.NOT_SHULKER;
        if (consumeKey && !keys.take(player, crate.id(), 1)) return OpenResult.NO_KEY;

        CrateReward winner = select(crate);
        ItemDisplay display = createPrivateDisplay(player, crateLocation, animationItem(randomReward(crate)));
        if (display == null) {
            if (consumeKey) keys.add(player, crate, 1);
            return OpenResult.ANIMATION_FAILED;
        }

        AnimationSession session = new AnimationSession(player.getUniqueId(), crate, winner, crateLocation.clone(), display, consumeKey);
        sessions.put(player.getUniqueId(), session);
        sendShulkerAction(player, crateLocation.getBlock(), true);
        player.playSound(crateLocation, Sound.BLOCK_SHULKER_BOX_OPEN, 1.0f, 1.0f);
        spawnCrateParticles(player, crate, crateLocation, false);
        scheduleRoll(session, 0);
        return OpenResult.SUCCESS;
    }

    private void scheduleRoll(AnimationSession session, int step) {
        if (session.finished) return;
        if (step >= ROLL_DELAYS.length) {
            showWinner(session);
            return;
        }
        session.task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = Bukkit.getPlayer(session.playerId);
            if (player == null || !player.isOnline()) {
                finish(session, false);
                return;
            }
            if (session.display == null || session.display.isDead()) {
                finish(session, false);
                return;
            }
            session.display.setItemStack(animationItem(randomReward(session.crate)));
            float pitch = Math.min(1.8f, 0.8f + (step * 0.07f));
            player.playSound(session.location, Sound.UI_BUTTON_CLICK, 0.55f, pitch);
            spawnCrateParticles(player, session.crate, session.location, false);
            scheduleRoll(session, step + 1);
        }, ROLL_DELAYS[step]);
    }

    private void showWinner(AnimationSession session) {
        if (session.finished) return;
        Player player = Bukkit.getPlayer(session.playerId);
        if (player == null || !player.isOnline()) {
            finish(session, false);
            return;
        }
        if (session.display == null || session.display.isDead()) {
            finish(session, false);
            return;
        }
        session.display.setItemStack(animationItem(session.winner));
        player.playSound(session.location, Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 1.25f);
        spawnCrateParticles(player, session.crate, session.location, true);
        session.task = Bukkit.getScheduler().runTaskLater(plugin, () -> finish(session, true), WINNER_DISPLAY_TICKS);
    }

    public void skipAnimation(Player player) {
        AnimationSession session = sessions.get(player.getUniqueId());
        if (session == null || session.finished) return;
        if (session.task != null) session.task.cancel();
        if (session.display != null && !session.display.isDead()) {
            session.display.setItemStack(animationItem(session.winner));
        }
        player.playSound(session.location, Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 1.35f);
        spawnCrateParticles(player, session.crate, session.location, true);
        finish(session, true);
    }

    public void finishAnimationImmediately(Player player) {
        AnimationSession session = sessions.get(player.getUniqueId());
        if (session != null) finish(session, player.isOnline());
    }

    private void finish(AnimationSession session, boolean grantReward) {
        if (session.finished) return;
        session.finished = true;
        if (session.task != null) session.task.cancel();
        sessions.remove(session.playerId);
        Player player = Bukkit.getPlayer(session.playerId);

        if (player != null) {
            sendShulkerAction(player, session.location.getBlock(), false);
            player.playSound(session.location, Sound.BLOCK_SHULKER_BOX_CLOSE, 1.0f, 1.0f);
        }
        if (session.display != null && !session.display.isDead()) session.display.remove();

        if (!grantReward || player == null || !player.isOnline()) {
            if (session.consumeKey) refundKey(session);
            return;
        }
        if (!grant(player, session.winner)) {
            if (session.consumeKey) refundKey(session);
            sendOpenError(player, OpenResult.GRANT_FAILED);
            return;
        }
        player.sendRichMessage("<dark_gray>[<gold>Kiste</gold>]</dark_gray> <green>Du hast <white>" + session.winner.displayName() + "</white> gewonnen!</green>");
    }

    private ItemDisplay createPrivateDisplay(Player viewer, Location crateLocation, ItemStack item) {
        try {
            double offsetX = plugin.configs().main().getDouble("crates.animation.item-display.offset-x", 0.5D);
            double offsetY = plugin.configs().main().getDouble("crates.animation.item-display.offset-y", 1.05D);
            double offsetZ = plugin.configs().main().getDouble("crates.animation.item-display.offset-z", 0.5D);
            float scale = (float) Math.max(0.05D, plugin.configs().main().getDouble("crates.animation.item-display.scale", 0.60D));
            float viewRange = (float) Math.max(0.1D, plugin.configs().main().getDouble("crates.animation.item-display.view-range", 0.6D));

            Location spawn = crateLocation.clone().add(offsetX, offsetY, offsetZ);
            ItemDisplay display = crateLocation.getWorld().spawn(spawn, ItemDisplay.class, entity -> {
                entity.setItemStack(item);
                entity.setBillboard(Display.Billboard.CENTER);
                entity.setPersistent(false);
                entity.setInvulnerable(true);
                entity.setVisibleByDefault(false);
                entity.setViewRange(viewRange);
                entity.setShadowRadius(0.0f);
                entity.setShadowStrength(0.0f);
                entity.setTransformation(new Transformation(
                        new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
            });
            viewer.showEntity(plugin, display);
            return display;
        } catch (Exception exception) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Private Crate-Animation konnte nicht erstellt werden.", exception);
            return null;
        }
    }

    /**
     * Private Crate-Partikel: Nur der öffnende Spieler erhält sie. Die Farben
     * orientieren sich am jeweiligen Kistentyp und erzeugen keine globalen
     * Jackpot- oder Chat-Effekte.
     */
    private void spawnCrateParticles(Player player, CrateDefinition crate, Location crateLocation, boolean winner) {
        Color color = switch (crate.id().toLowerCase(Locale.ROOT)) {
            case "lumi" -> Color.fromRGB(245, 196, 55);   // gelblich/golden
            case "vanta" -> Color.fromRGB(156, 38, 38);  // dunkelrot
            case "daily" -> Color.fromRGB(151, 91, 48);   // Daily: bräunlich/orange
            case "novo" -> Color.fromRGB(168, 85, 247);   // Novoria-Lila
            case "playtime" -> Color.fromRGB(34, 211, 238); // Spielzeit: cyan
            default -> Color.fromRGB(180, 180, 180);
        };

        Location center = crateLocation.clone().add(0.5D, 0.75D, 0.5D);
        int count = winner ? 22 : 4;
        double spread = winner ? 0.42D : 0.25D;
        float size = winner ? 1.15F : 0.75F;
        player.spawnParticle(Particle.DUST, center, count, spread, winner ? 0.45D : 0.25D, spread,
                0.0D, new Particle.DustOptions(color, size));
    }

    private void sendShulkerAction(Player player, Block block, boolean open) {
        try {
            PacketContainer packet = ProtocolLibrary.getProtocolManager().createPacket(PacketType.Play.Server.BLOCK_ACTION);
            packet.getBlockPositionModifier().write(0, new BlockPosition(block.getX(), block.getY(), block.getZ()));
            packet.getIntegers().write(0, 1);
            packet.getIntegers().write(1, open ? 1 : 0);
            packet.getBlocks().write(0, block.getType());
            ProtocolLibrary.getProtocolManager().sendServerPacket(player, packet);
        } catch (Exception exception) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Private Shulker-Animation konnte nicht gesendet werden.", exception);
        }
    }

    private CrateReward randomReward(CrateDefinition crate) {
        return crate.rewards().get(ThreadLocalRandom.current().nextInt(crate.rewards().size()));
    }

    private ItemStack animationItem(CrateReward reward) {
        if (reward.type() == CrateReward.Type.CUSTOM_ITEM) {
            ItemStack customItem = customItems.create(reward.customItemId(), displayAmount(reward));
            return customItem != null ? customItem : new ItemStack(Material.PAPER);
        }
        // Reine visuelle Anzeige ohne Custom-Namen. Echte Vanilla-Item-Rewards
        // bleiben dadurch vollständig vanilla und stackbar.
        return new ItemStack(rewardIcon(reward), displayAmount(reward));
    }

    private int displayAmount(CrateReward reward) {
        // Die sichtbare Menge kommt ausschließlich aus der jeweiligen Crate-YAML. Nicht nur
        // Vanilla-Items, sondern auch Keys und Custom-Items zeigen damit z. B. amount: 3 als x3.
        // Coins/Commands besitzen kein physisches Reward-Stack und bleiben deshalb bei 1.
        return switch (reward.type()) {
            case ITEM, KEY, CUSTOM_ITEM -> Math.max(1, Math.min(64, reward.amount()));
            case COINS, COMMAND -> 1;
        };
    }

    private void refundKey(AnimationSession session) {
        Player player = Bukkit.getPlayer(session.playerId);
        if (player != null && player.isOnline()) keys.add(player, session.crate, 1);
        else plugin.getLogger().warning("Key-Rückerstattung nicht möglich, weil " + session.playerId + " offline ist.");
    }

    private Material rewardIcon(CrateReward reward) {
        return switch (reward.type()) {
            case ITEM -> reward.material();
            case COINS -> Material.SUNFLOWER;
            case KEY -> find(reward.targetCrate())
                    .map(CrateDefinition::keyMaterial)
                    .orElse(defaultKeyMaterial(reward.targetCrate()));
            case COMMAND -> Material.PAPER;
            case CUSTOM_ITEM -> customItems.find(reward.customItemId())
                    .map(de.walahi.novosmp.items.CustomItemDefinition::material)
                    .orElse(Material.PAPER);
        };
    }

    public void sendOpenError(Player player, OpenResult result) {
        String text = switch (result) {
            case UNKNOWN_CRATE -> "Unbekannte Kiste.";
            case DISABLED -> "Diese Kiste ist noch deaktiviert.";
            case NO_REWARDS -> "Diese Kiste hat keine Belohnungen.";
            case NO_KEY -> "Du besitzt keinen passenden Schlüssel.";
            case INVENTORY_FULL -> "Dein Inventar ist voll. Schaffe mindestens einen freien Inventarplatz.";
            case ALREADY_OPENING -> "Du öffnest bereits eine Kiste. Shift-Rechtsklick zum Überspringen.";
            case NOT_SHULKER -> "Die gesetzte Crate muss eine Shulkerbox sein.";
            case ANIMATION_FAILED -> "Die Animation konnte nicht gestartet werden; dein Key wurde erstattet.";
            case GRANT_FAILED -> "Die Belohnung konnte nicht vergeben werden; dein Key wurde erstattet.";
            default -> "Öffnen fehlgeschlagen.";
        };
        player.sendRichMessage("<dark_gray>[<gold>Kiste</gold>]</dark_gray> <red>" + text + "</red>");
    }

    public Optional<CrateDefinition> find(String id) { return Optional.ofNullable(crates.get(id.toLowerCase(Locale.ROOT))); }
    public Collection<CrateDefinition> all() { return Collections.unmodifiableCollection(crates.values()); }
    public Set<String> ids() { return Collections.unmodifiableSet(crates.keySet()); }
    public KeyManager keys() { return keys; }

    /** Admin/Test-Öffnung ohne Weltanimation. */
    public OpenResult open(Player player, String crateId, boolean consumeKey) {
        CrateDefinition crate = find(crateId).orElse(null);
        if (crate == null) return OpenResult.UNKNOWN_CRATE;
        if (!crate.enabled()) return OpenResult.DISABLED;
        if (crate.rewards().isEmpty()) return OpenResult.NO_REWARDS;
        if (player.getInventory().firstEmpty() == -1) return OpenResult.INVENTORY_FULL;
        if (consumeKey && !keys.take(player, crate.id(), 1)) return OpenResult.NO_KEY;
        CrateReward reward = select(crate);
        if (!grant(player, reward)) {
            if (consumeKey) keys.add(player, crate, 1);
            return OpenResult.GRANT_FAILED;
        }
        player.sendRichMessage("<dark_gray>[<gold>Kiste</gold>]</dark_gray> <green>Du hast <white>" + reward.displayName() + "</white> gewonnen!</green>");
        return OpenResult.SUCCESS;
    }

    private CrateReward select(CrateDefinition crate) {
        double roll = ThreadLocalRandom.current().nextDouble(crate.totalWeight());
        double cursor = 0D;
        for (CrateReward reward : crate.rewards()) {
            cursor += reward.weight();
            if (roll < cursor) return reward;
        }
        return crate.rewards().getLast();
    }

    private boolean grant(Player player, CrateReward reward) {
        return switch (reward.type()) {
            case ITEM -> {
                // In sauberen Max-Stack-Portionen vergeben, statt ein einzelnes ItemStack mit
                // ggf. übergroßer Menge zu erzeugen. Ein übergroßer Einzelstack kann sich mit
                // regulären, korrekt großen Stacks nicht mehr zusammenlegen lassen.
                int maxStack = reward.material().getMaxStackSize();
                int remainingAmount = reward.amount();
                while (remainingAmount > 0) {
                    int chunk = Math.min(maxStack, remainingAmount);
                    ItemStack stack = createRewardItem(reward, chunk);
                    Map<Integer, ItemStack> remaining = player.getInventory().addItem(stack);
                    if (!remaining.isEmpty()) {
                        remaining.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
                    }
                    remainingAmount -= chunk;
                }
                yield true;
            }
            case COINS -> plugin.services().economy().deposit(player.getUniqueId(), reward.coins(),
                    "Crate reward: " + reward.id(), ActionContext.system(player.getUniqueId())) == EconomyOperationResult.SUCCESS;
            case KEY -> {
                if (find(reward.targetCrate()).isEmpty()) yield false;
                CrateDefinition target = find(reward.targetCrate()).orElse(null);
                if (target == null) yield false;
                keys.add(player, target, reward.amount());
                yield true;
            }
            case COMMAND -> {
                String command = reward.command().replace("%player%", player.getName());
                yield !command.isBlank() && Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            }
            case CUSTOM_ITEM -> {
                if (customItems.find(reward.customItemId()).isEmpty()) {
                    plugin.getLogger().warning("Crate-Belohnung " + reward.id()
                            + " verweist auf das unbekannte Custom-Item '"
                            + reward.customItemId() + "'.");
                    yield false;
                }
                customItems.give(player, reward.customItemId(), reward.amount());
                yield true;
            }
        };
    }


    private ItemStack createRewardItem(CrateReward reward, int amount) {
        ItemStack stack = new ItemStack(reward.material(), amount);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        if (reward.material() == Material.FIREWORK_ROCKET && reward.fireworkFlight() > 0
                && meta instanceof FireworkMeta fireworkMeta) {
            fireworkMeta.setPower(Math.max(1, Math.min(3, reward.fireworkFlight())));
            meta = fireworkMeta;
        }
        for (Map.Entry<String, Integer> entry : reward.enchantments().entrySet()) {
            Enchantment enchantment = resolveEnchantment(entry.getKey());
            if (enchantment == null) {
                plugin.getLogger().warning("Unbekannte Crate-Verzauberung '" + entry.getKey()
                        + "' bei Reward " + reward.id() + ".");
                continue;
            }
            meta.addEnchant(enchantment, entry.getValue(), true);
        }
        if (!reward.itemName().isBlank()) {
            meta.displayName(miniMessage.deserialize(reward.itemName()).decoration(TextDecoration.ITALIC, false));
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private Map<String, Integer> readEnchantments(ConfigurationSection section) {
        if (section == null) return Map.of();
        Map<String, Integer> values = new LinkedHashMap<>();
        for (String raw : section.getKeys(false)) {
            int level = section.getInt(raw, 0);
            if (level > 0) values.put(raw.toLowerCase(Locale.ROOT), level);
        }
        return values;
    }

    private Enchantment resolveEnchantment(String name) {
        if (name == null || name.isBlank()) return null;
        NamespacedKey key = NamespacedKey.fromString(name.contains(":") ? name : "minecraft:" + name);
        return key == null ? null : Registry.ENCHANTMENT.get(key);
    }

    public enum OpenResult {
        SUCCESS, UNKNOWN_CRATE, DISABLED, NO_REWARDS, NO_KEY, INVENTORY_FULL,
        ALREADY_OPENING, NOT_SHULKER, ANIMATION_FAILED, GRANT_FAILED
    }

    private static final class AnimationSession {
        private final UUID playerId;
        private final CrateDefinition crate;
        private final CrateReward winner;
        private final Location location;
        private final ItemDisplay display;
        private final boolean consumeKey;
        private BukkitTask task;
        private boolean finished;

        private AnimationSession(UUID playerId, CrateDefinition crate, CrateReward winner,
                                 Location location, ItemDisplay display, boolean consumeKey) {
            this.playerId = playerId;
            this.crate = crate;
            this.winner = winner;
            this.location = location;
            this.display = display;
            this.consumeKey = consumeKey;
        }
    }
}
