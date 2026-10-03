package de.walahi.novosmp.feature;

import de.walahi.smpcore.SMPCorePlugin;
import io.papermc.paper.entity.Bucketable;
import io.papermc.paper.event.entity.EntityDyeEvent;
import io.papermc.paper.event.entity.EntityEquipmentChangedEvent;
import net.citizensnpcs.api.CitizensAPI;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Raid;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TrialSpawner;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.AsyncStructureGenerateEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Central, YAML-driven policy and lifecycle for manual and automatic entity cleanup. */
public final class PerformanceCleanupManager implements Listener {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final SMPCorePlugin plugin;
    private final NamespacedKey playerProtectedKey;
    private final NamespacedKey legacyPlayerCreatedKey;
    private final NamespacedKey trialSpawnerReferenceKey;
    private final NamespacedKey structureProtectedKey;
    private final boolean citizensAvailable;
    private boolean citizensCheckFailed;
    private BukkitTask task;
    private long intervalSeconds;
    private long remainingSeconds;
    private Set<Long> warningSeconds = Set.of();
    private Set<CreatureSpawnEvent.SpawnReason> playerProtectedSpawnReasons = Set.of();
    private volatile boolean structureEntityProtectionEnabled;
    private Policy policy;

    public PerformanceCleanupManager(SMPCorePlugin plugin) {
        this.plugin = plugin;
        this.playerProtectedKey = new NamespacedKey(plugin, "entity_player_protected");
        this.legacyPlayerCreatedKey = new NamespacedKey(plugin, "entity_player_created");
        this.trialSpawnerReferenceKey = new NamespacedKey(plugin, "entity_trial_spawner");
        this.structureProtectedKey = new NamespacedKey(plugin, "entity_structure_protected");
        this.citizensAvailable = Bukkit.getPluginManager().isPluginEnabled("Citizens");
    }

    public void start() {
        stop();
        policy = Policy.load(plugin, true);
        playerProtectedSpawnReasons = policy.playerProtectedSpawnReasons();
        structureEntityProtectionEnabled = plugin.configs().server().getBoolean(
                "performance-cleanup.structure-entity-protection.enabled", true);
        if (!plugin.configs().server().getBoolean("performance-cleanup.enabled", true)) return;
        intervalSeconds = Math.max(60L,
                plugin.configs().server().getLong("performance-cleanup.interval-seconds", 5400L));
        remainingSeconds = intervalSeconds;
        Set<Long> configuredWarnings = new HashSet<>();
        var configured = plugin.configs().server().getIntegerList("performance-cleanup.actionbar-warning-seconds");
        if (configured.isEmpty()) configured = java.util.List.of(600, 300, 180, 60, 30, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1);
        for (int seconds : configured) if (seconds > 0 && seconds < intervalSeconds) configuredWarnings.add((long) seconds);
        warningSeconds = Set.copyOf(configuredWarnings);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        plugin.getLogger().info("Automatischer Entity-Clear aktiv: alle " + intervalSeconds + " Sekunden.");
    }

    private void tick() {
        remainingSeconds--;
        if (warningSeconds.contains(remainingSeconds)
                && plugin.configs().server().getBoolean("performance-cleanup.actionbar-enabled", true)) {
            showActionbarWarning(remainingSeconds);
        }
        if (remainingSeconds <= 0L) {
            runCleanup(true);
            remainingSeconds = intervalSeconds;
        }
    }

    private void showActionbarWarning(long seconds) {
        String time = seconds >= 60L
                ? String.format("%02d:%02d", seconds / 60L, seconds % 60L)
                : seconds >= 10L ? "00:" + seconds : Long.toString(seconds);
        var message = MM.deserialize("<gray>Entity-Clear in <yellow>" + time + "</yellow></gray>");
        Bukkit.getOnlinePlayers().forEach(player -> player.sendActionBar(message));
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    public CleanupResult runCleanup(boolean automatic) {
        if (policy == null) policy = Policy.load(plugin, false);
        MutableCounts counts = new MutableCounts();
        for (World world : Bukkit.getWorlds()) {
            if (!plugin.isSmpGameplayWorld(world)) continue;
            // World#getEntities covers loaded chunks only and never loads chunks for this clear.
            for (Entity entity : world.getEntities()) {
                counts.scanned++;
                if (isTechnicallyProtected(entity)
                        || hasActiveSpecialContext(entity)
                        || hasPlayerRelevance(entity)
                        || isStructureProtected(entity)) {
                    counts.protectedEntities++;
                    continue;
                }

                Category category = nonLivingClearCategory(entity, policy);
                if (category == null && Boolean.TRUE.equals(policy.clearableEntityTypes().get(entity.getType()))) {
                    category = categoryForRemovedLivingEntity(entity);
                }
                if (category == null) {
                    counts.protectedEntities++;
                    continue;
                }
                entity.remove();
                counts.add(category);
            }
        }
        CleanupResult result = counts.freeze();
        if (automatic && plugin.configs().server().getBoolean("performance-cleanup.result-message", true)) {
            Bukkit.broadcast(MM.deserialize("<dark_gray>[<green>SMP</green>]</dark_gray> <gray>Entity-Clear: <yellow>"
                    + result.removed() + " entfernt</yellow>, <aqua>" + result.protectedEntities() + " geschützt</aqua>.</gray>"));
        }
        if (policy.debug()) plugin.getLogger().info("EntityClear Debug: " + result);
        return result;
    }

    /** Step 1: entity classes which the cleanup must never touch. */
    private boolean isTechnicallyProtected(Entity entity) {
        if (entity instanceof Player) return true;
        if (isCitizensNpc(entity)) return true;
        if (entity instanceof ArmorStand || entity instanceof Mannequin || entity instanceof Hanging
                || entity instanceof Display || entity instanceof Interaction || entity instanceof Marker
                || entity instanceof EnderCrystal) return true;
        // Deliberately concrete: only actual boats and minecarts are technical vehicles.
        if (entity instanceof Boat || entity instanceof Minecart) return true;
        if (hasExternalPluginData(entity.getPersistentDataContainer())) return true;
        return !entity.getScoreboardTags().isEmpty();
    }

    /** Step 2: temporary gameplay contexts which must not be interrupted. */
    private boolean hasActiveSpecialContext(Entity entity) {
        if (isActiveRaidMember(entity)) return true;
        return isActiveTrialSpawnerEntity(entity);
    }

    /** Step 3: durable or currently observable player ownership/use. */
    private boolean hasPlayerRelevance(Entity entity) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        if (pdc.has(playerProtectedKey, PersistentDataType.BYTE)) return true;
        if (pdc.has(legacyPlayerCreatedKey, PersistentDataType.BYTE)) {
            markPlayerProtected(entity);
            pdc.remove(legacyPlayerCreatedKey);
            return true;
        }
        if (entity.customName() != null) return true;
        if (entity instanceof Tameable tameable && (tameable.isTamed() || tameable.getOwner() != null)) return true;
        if (entity instanceof LivingEntity living && living.isLeashed()) return true;
        if (entity instanceof Ocelot ocelot && ocelot.isTrusting()) return true;
        if (entity instanceof Bucketable bucketable && bucketable.isFromBucket()) return true;
        if (hasPlayerEquipment(entity)) return true;
        if (entity instanceof ChestedHorse horse && horse.isCarryingChest()) return true;
        if (entity.getPassengers().stream().anyMatch(Player.class::isInstance)) return true;
        return playerProtectedSpawnReasons.contains(entity.getEntitySpawnReason());
    }

    private boolean hasPlayerEquipment(Entity entity) {
        if (entity instanceof Steerable steerable && steerable.hasSaddle()) return true;
        if (entity instanceof AbstractHorse horse && hasItem(horse.getInventory().getSaddle())) return true;
        if (entity instanceof AbstractNautilus nautilus) {
            if (hasItem(nautilus.getInventory().getSaddle()) || hasItem(nautilus.getInventory().getArmor())) return true;
        }
        if (!(entity instanceof LivingEntity living) || living.getEquipment() == null) return false;
        return hasItem(living.getEquipment().getItem(EquipmentSlot.SADDLE))
                || hasItem(living.getEquipment().getItem(EquipmentSlot.BODY));
    }

    private boolean hasExternalPluginData(PersistentDataContainer pdc) {
        for (NamespacedKey key : pdc.getKeys()) {
            if (!key.equals(trialSpawnerReferenceKey)
                    && !key.equals(playerProtectedKey)
                    && !key.equals(legacyPlayerCreatedKey)
                    && !key.equals(structureProtectedKey)) return true;
        }
        return false;
    }

    private boolean isActiveRaidMember(Entity entity) {
        Raider raider = entity instanceof Raider direct ? direct
                : entity instanceof Vex vex && vex.getSummoner() instanceof Raider owner ? owner : null;
        if (raider == null) return false;
        Raid raid = raider.getRaid();
        return raid != null && raid.getStatus() == Raid.RaidStatus.ONGOING && raid.getRaiders().contains(raider);
    }

    private boolean isActiveTrialSpawnerEntity(Entity entity) {
        String reference = entity.getPersistentDataContainer().get(trialSpawnerReferenceKey, PersistentDataType.STRING);
        if (reference == null) return false;
        String[] parts = reference.split(";", 4);
        if (parts.length != 4) {
            entity.getPersistentDataContainer().remove(trialSpawnerReferenceKey);
            return false;
        }
        try {
            World world = Bukkit.getWorld(UUID.fromString(parts[0]));
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            if (world == null) {
                entity.getPersistentDataContainer().remove(trialSpawnerReferenceKey);
                return false;
            }
            // Never load the spawner chunk for cleanup. Unknown state is handled conservatively.
            if (!world.isChunkLoaded(x >> 4, z >> 4)) return true;
            Block block = world.getBlockAt(x, y, z);
            if (!(block.getState() instanceof TrialSpawner trialSpawner)
                    || !(block.getBlockData() instanceof org.bukkit.block.data.type.TrialSpawner data)
                    || data.getTrialSpawnerState() != org.bukkit.block.data.type.TrialSpawner.State.ACTIVE
                    || !trialSpawner.isTrackingEntity(entity)) {
                entity.getPersistentDataContainer().remove(trialSpawnerReferenceKey);
                return false;
            }
            return true;
        } catch (IllegalArgumentException exception) {
            entity.getPersistentDataContainer().remove(trialSpawnerReferenceKey);
            return false;
        }
    }

    private Category nonLivingClearCategory(Entity entity, Policy policy) {
        if (policy.removeDrops() && entity instanceof Item item) {
            if (hasCustomItemData(item.getItemStack())) return null;
            return Category.DROPS;
        }
        if (policy.removeExperience() && entity instanceof ExperienceOrb) return Category.EXPERIENCE;
        if (policy.removeProjectiles() && entity instanceof Projectile
                && entity.getTicksLived() >= policy.projectileMinimumAge()) return Category.PROJECTILES;
        if ((policy.removeClouds() && entity instanceof AreaEffectCloud)
                || (policy.removeTnt() && entity instanceof TNTPrimed)
                || (policy.removeFalling() && entity instanceof FallingBlock)) return Category.TEMPORARY;
        return null;
    }

    private Category categoryForRemovedLivingEntity(Entity entity) {
        if (entity instanceof WaterMob || entity instanceof Ambient) return Category.WATER_AMBIENT;
        if (entity instanceof Monster) return Category.MONSTERS;
        if (entity instanceof Animals) return Category.ANIMALS;
        return Category.OTHER_MOBS;
    }

    private boolean hasCustomItemData(ItemStack stack) {
        return stack.hasItemMeta() && !stack.getItemMeta().getPersistentDataContainer().getKeys().isEmpty();
    }

    private boolean isCitizensNpc(Entity entity) {
        if (!citizensAvailable && !entity.hasMetadata("NPC")) return false;
        if (entity.hasMetadata("NPC")) return true;
        if (citizensCheckFailed) return true;
        try {
            return CitizensAPI.getNPCRegistry().isNPC(entity);
        } catch (RuntimeException exception) {
            citizensCheckFailed = true;
            plugin.getLogger().warning("Citizens-Prüfung fehlgeschlagen; betroffene Entities werden vorsichtshalber geschützt.");
            return true;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnterLoveMode(EntityEnterLoveModeEvent event) {
        if (event.getHumanEntity() != null) markPlayerProtected(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        markPlayerProtected(event.getMother());
        markPlayerProtected(event.getFather());
        markPlayerProtected(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTame(EntityTameEvent event) {
        markPlayerProtected(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeash(PlayerLeashEntityEvent event) {
        markPlayerProtected(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMount(EntityMountEvent event) {
        if (event.getEntity() instanceof Player) markPlayerProtected(event.getMount());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEntity(PlayerBucketEntityEvent event) {
        markPlayerProtected(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDye(EntityDyeEvent event) {
        if (event.getPlayer() != null) markPlayerProtected(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEquipmentChanged(EntityEquipmentChangedEvent event) {
        for (Map.Entry<EquipmentSlot, EntityEquipmentChangedEvent.EquipmentChange> entry
                : event.getEquipmentChanges().entrySet()) {
            if ((entry.getKey() == EquipmentSlot.SADDLE || entry.getKey() == EquipmentSlot.BODY)
                    && hasItem(entry.getValue().newItem())) {
                markPlayerProtected(event.getEntity());
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChestedHorseInteraction(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ChestedHorse)) return;
        ItemStack held = event.getPlayer().getInventory().getItem(event.getHand());
        if (held.getType() == Material.CHEST) markPlayerProtected(event.getRightClicked());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        PersistentDataContainer source = event.getEntity().getPersistentDataContainer();
        boolean playerProtected = source.has(playerProtectedKey, PersistentDataType.BYTE)
                || source.has(legacyPlayerCreatedKey, PersistentDataType.BYTE);
        boolean structureProtected = source.has(structureProtectedKey, PersistentDataType.BYTE);
        if (!playerProtected && !structureProtected) return;
        for (Entity transformed : event.getTransformedEntities()) {
            if (playerProtected) markPlayerProtected(transformed);
            if (structureProtected) markStructureProtected(transformed);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onStructureGenerate(AsyncStructureGenerateEvent event) {
        if (!structureEntityProtectionEnabled
                || event.getCause() != AsyncStructureGenerateEvent.Cause.WORLD_GENERATION) return;
        event.setEntityTransformer(structureProtectedKey, (region, x, y, z, entity, allowedToSpawn) -> {
            if (allowedToSpawn && entity instanceof LivingEntity) markStructureProtected(entity);
            return allowedToSpawn;
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (playerProtectedSpawnReasons.contains(event.getSpawnReason())) markPlayerProtected(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrialSpawnerSpawn(TrialSpawnerSpawnEvent event) {
        var location = event.getTrialSpawner().getLocation();
        String reference = location.getWorld().getUID() + ";" + location.getBlockX() + ";"
                + location.getBlockY() + ";" + location.getBlockZ();
        event.getEntity().getPersistentDataContainer().set(
                trialSpawnerReferenceKey, PersistentDataType.STRING, reference);
    }

    private void markPlayerProtected(Entity entity) {
        entity.getPersistentDataContainer().set(playerProtectedKey, PersistentDataType.BYTE, (byte) 1);
    }

    private boolean isStructureProtected(Entity entity) {
        return entity.getPersistentDataContainer().has(structureProtectedKey, PersistentDataType.BYTE);
    }

    private void markStructureProtected(Entity entity) {
        entity.getPersistentDataContainer().set(structureProtectedKey, PersistentDataType.BYTE, (byte) 1);
    }

    private static boolean hasItem(ItemStack stack) {
        return stack != null && !stack.getType().isAir();
    }

    private static Set<CreatureSpawnEvent.SpawnReason> parseSpawnReasons(SMPCorePlugin plugin, String path) {
        EnumSet<CreatureSpawnEvent.SpawnReason> reasons = EnumSet.noneOf(CreatureSpawnEvent.SpawnReason.class);
        for (String raw : plugin.configs().server().getStringList(path)) {
            try { reasons.add(CreatureSpawnEvent.SpawnReason.valueOf(raw.trim().toUpperCase(Locale.ROOT))); }
            catch (IllegalArgumentException exception) { plugin.getLogger().warning("Ungültiger Entity-SpawnReason: " + raw); }
        }
        return Set.copyOf(reasons);
    }

    private enum Category { DROPS, EXPERIENCE, MONSTERS, ANIMALS, WATER_AMBIENT, PROJECTILES, TEMPORARY, OTHER_MOBS }

    private static final class MutableCounts {
        int scanned, protectedEntities, drops, experience, monsters, animals, waterAmbient, projectiles, temporary, otherMobs;
        void add(Category category) { switch (category) {
            case DROPS -> drops++; case EXPERIENCE -> experience++; case MONSTERS -> monsters++;
            case ANIMALS -> animals++; case WATER_AMBIENT -> waterAmbient++; case PROJECTILES -> projectiles++;
            case TEMPORARY -> temporary++; case OTHER_MOBS -> otherMobs++;
        }}
        CleanupResult freeze() {
            int removed = drops + experience + monsters + animals + waterAmbient + projectiles + temporary + otherMobs;
            return new CleanupResult(scanned, removed, protectedEntities, drops, experience, monsters, animals,
                    waterAmbient, projectiles, temporary, otherMobs);
        }
    }

    public record CleanupResult(int scanned, int removed, int protectedEntities, int drops, int experience,
                                int monsters, int animals, int waterAmbient, int projectiles,
                                int temporaryEntities, int otherMobs) { public int total() { return removed; } }

    private record Policy(boolean removeDrops, boolean removeExperience, boolean removeProjectiles,
                          boolean removeClouds, boolean removeTnt, boolean removeFalling,
                          int projectileMinimumAge, Map<EntityType, Boolean> clearableEntityTypes,
                          Set<CreatureSpawnEvent.SpawnReason> playerProtectedSpawnReasons, boolean debug) {
        static Policy load(SMPCorePlugin plugin, boolean warnMissing) {
            var config = plugin.configs().server();
            String remove = "performance-cleanup.remove.";
            String entityTypesPath = "performance-cleanup.entity-types";
            ConfigurationSection section = config.getConfigurationSection(entityTypesPath);
            EnumMap<EntityType, Boolean> policies = new EnumMap<>(EntityType.class);
            if (section != null) {
                for (String raw : section.getKeys(false)) {
                    try {
                        EntityType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException exception) {
                        plugin.getLogger().warning("Unbekannter EntityType in EntityClear-Policy: " + raw);
                    }
                }
            }
            // Read through FileConfiguration so bundled defaults are honored even when an
            // administrator's existing physical config.yml does not yet contain the new map.
            for (EntityType type : EntityType.values()) {
                String path = entityTypesPath + "." + type.name();
                if (config.contains(path)) policies.put(type, config.getBoolean(path));
            }
            if (warnMissing) {
                for (EntityType type : EntityType.values()) {
                    if (type != EntityType.UNKNOWN && type.isAlive() && !policies.containsKey(type)) {
                        plugin.getLogger().warning("EntityType " + type + " besitzt keine EntityClear-Policy und wird nicht entfernt.");
                    }
                }
            }
            return new Policy(config.getBoolean(remove + "drops", true),
                    config.getBoolean(remove + "experience-orbs", true),
                    config.getBoolean(remove + "projectiles", true),
                    config.getBoolean(remove + "area-effect-clouds", true),
                    config.getBoolean(remove + "primed-tnt", true),
                    config.getBoolean(remove + "falling-blocks", false),
                    Math.max(0, config.getInt(remove + "projectile-min-age-ticks", 1200)),
                    Map.copyOf(policies),
                    parseSpawnReasons(plugin, "performance-cleanup.player-protection.spawn-reasons"),
                    config.getBoolean("performance-cleanup.debug", false));
        }
    }
}
