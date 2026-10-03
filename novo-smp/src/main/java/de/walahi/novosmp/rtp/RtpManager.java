package de.walahi.novosmp.rtp;

import de.walahi.smpcore.rtp.RtpAccess;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import de.walahi.smpcore.SMPCorePlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Gemeinsame Safe-Location-Engine für /rtp und /back.
 *
 * Für normales RTP wird zusätzlich eine kleine, begrenzte Warteschlange bereits
 * geprüfter Positionen vorgehalten. Dadurch muss beim Klick normalerweise kein
 * neuer Chunk generiert werden. Die Queue speichert nur wenige Locations; sie
 * hält keine Chunks dauerhaft geladen und verursacht daher kaum RAM-Verbrauch.
 */
public final class RtpManager implements RtpAccess {

    private final SMPCorePlugin plugin;
    private final Map<String, Queue<Location>> preparedLocations = new ConcurrentHashMap<>();
    private final Set<String> refillRunning = ConcurrentHashMap.newKeySet();

    public RtpManager(SMPCorePlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskLater(plugin, this::warmConfiguredWorlds, 40L);
    }


    @Override
    public CompletableFuture<Boolean> prepareDestination(Location location) {
        if (location == null || location.getWorld() == null) {
            return CompletableFuture.completedFuture(false);
        }

        World world = location.getWorld();
        int radius = Math.max(0, Math.min(2, plugin.configs().menus().getInt("rtp.preload-chunk-radius", 1)));
        int centerChunkX = location.getBlockX() >> 4;
        int centerChunkZ = location.getBlockZ() >> 4;
        List<CompletableFuture<Chunk>> futures = new ArrayList<>();

        for (int x = centerChunkX - radius; x <= centerChunkX + radius; x++) {
            for (int z = centerChunkZ - radius; z <= centerChunkZ + radius; z++) {
                futures.add(world.getChunkAtAsync(x, z, true));
            }
        }

        CompletableFuture<Boolean> result = new CompletableFuture<>();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> {
            if (error != null || !plugin.isEnabled()) {
                result.complete(false);
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                List<Chunk> chunks = new ArrayList<>(futures.size());
                try {
                    for (CompletableFuture<Chunk> future : futures) {
                        Chunk chunk = future.join();
                        chunk.addPluginChunkTicket(plugin);
                        chunks.add(chunk);
                    }
                    long holdTicks = Math.max(20L, Math.min(200L,
                            plugin.configs().menus().getLong("rtp.preload-hold-ticks", 80L)));
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        for (Chunk chunk : chunks) chunk.removePluginChunkTicket(plugin);
                    }, holdTicks);
                    result.complete(true);
                } catch (Throwable throwable) {
                    for (Chunk chunk : chunks) chunk.removePluginChunkTicket(plugin);
                    result.complete(false);
                }
            });
        });
        return result;
    }

    @Override
    public void findSafeLocation(Player player, String worldName, Consumer<Location> callback) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("RTP-Welt '" + worldName + "' ist nicht geladen.");
            callback.accept(null);
            return;
        }

        Queue<Location> queue = queue(world);
        Location prepared = pollPrepared(queue, world);
        if (prepared != null) {
            callback.accept(prepared);
            scheduleRefill(world);
            return;
        }

        FileConfiguration config = plugin.configs().main();
        int maxAttempts = Math.max(1, config.getInt("rtp.max-attempts", 64));
        startSearch(player, world, result -> {
            callback.accept(result);
            scheduleRefill(world);
        }, maxAttempts, configuredBatchSize(), () -> randomCoordinates(world));
    }

    /** Nutzt exakt dieselbe Such-Pipeline wie /rtp, aber rund um den Todespunkt. */
    public void findSafeLocationAround(Player player, Location center, int radius, Consumer<Location> callback) {
        if (center == null || center.getWorld() == null) {
            callback.accept(null);
            return;
        }

        World world = center.getWorld();
        int safeRadius = Math.max(16, radius);
        int maxAttempts = Math.max(1, plugin.configs().server().getInt("death-back.max-attempts",
                plugin.configs().menus().getInt("rtp.max-attempts", 64)));
        int centerX = center.getBlockX();
        int centerZ = center.getBlockZ();

        startSearch(player, world, callback, maxAttempts, configuredBatchSize(),
                () -> randomCoordinatesAround(world, centerX, centerZ, safeRadius));
    }

    private void warmConfiguredWorlds() {
        // Beim Start nur die Standard-RTP-Welt vorwärmen. Nether/End werden erst
        // beim ersten Gebrauch befüllt, damit es keine unnötige Startlast gibt.
        String defaultWorld = plugin.configs().menus().getString("rtp.world", "smp_world");
        if (defaultWorld == null || defaultWorld.isBlank()) return;
        World world = Bukkit.getWorld(defaultWorld);
        if (world != null) scheduleRefill(world);
    }

    private Queue<Location> queue(World world) {
        return preparedLocations.computeIfAbsent(world.getName(), ignored -> new ConcurrentLinkedQueue<>());
    }

    private Location pollPrepared(Queue<Location> queue, World world) {
        Location location;
        while ((location = queue.poll()) != null) {
            if (location.getWorld() == world && insideBorder(world, location.getBlockX(), location.getBlockZ())) {
                return location.clone();
            }
        }
        return null;
    }

    private void scheduleRefill(World world) {
        int target = Math.max(0, Math.min(32, plugin.configs().menus().getInt("rtp.prepared-locations", 12)));
        if (target == 0 || queue(world).size() >= target) return;
        if (!refillRunning.add(world.getName())) return;
        refillOne(world, target);
    }

    /**
     * Füllt absichtlich seriell nach. Das verhindert gleichzeitige Chunk-
     * Generierungsspitzen und hält den Server-Tick stabil.
     */
    private void refillOne(World world, int target) {
        Queue<Location> queue = queue(world);
        if (!plugin.isEnabled() || queue.size() >= target) {
            refillRunning.remove(world.getName());
            return;
        }

        int attempts = Math.max(8, plugin.configs().menus().getInt("rtp.prepared-search-attempts", 32));
        startSearch(null, world, result -> {
            if (result != null && queue.size() < target) queue.offer(result.clone());
            long delay = Math.max(1L, plugin.configs().menus().getLong("rtp.prepared-refill-delay-ticks", 10L));
            Bukkit.getScheduler().runTaskLater(plugin, () -> refillOne(world, target), delay);
        }, attempts, 1, () -> randomCoordinates(world));
    }

    private int configuredBatchSize() {
        return Math.max(1, Math.min(8, plugin.configs().menus().getInt("rtp.search-batch-size", 4)));
    }

    private void startSearch(Player player, World world, Consumer<Location> callback,
                             int maxAttempts, int batchSize, CoordinateSupplier supplier) {
        SearchSession session = new SearchSession(player, world, callback, maxAttempts,
                batchSize, supplier, configuredUnsafeBlocks());
        searchNextBatch(session);
    }

    private void searchNextBatch(SearchSession session) {
        if (session.finished.get()) return;
        if (session.player != null && !session.player.isOnline()) {
            finish(session, null);
            return;
        }

        int remainingAttempts = session.maxAttempts - session.startedAttempts.get();
        if (remainingAttempts <= 0) {
            finish(session, null);
            return;
        }

        int requested = Math.min(session.batchSize, remainingAttempts);
        AtomicInteger completed = new AtomicInteger();

        for (int i = 0; i < requested; i++) {
            Coordinates coordinates = nextUniqueCoordinates(session);
            session.startedAttempts.incrementAndGet();
            if (coordinates == null) {
                onCandidateFinished(session, completed, requested, null);
                continue;
            }

            int chunkX = coordinates.x() >> 4;
            int chunkZ = coordinates.z() >> 4;
            if (session.world.isChunkLoaded(chunkX, chunkZ)) {
                inspectCandidate(session, completed, requested, coordinates);
                continue;
            }

            session.world.getChunkAtAsync(chunkX, chunkZ, true).thenAccept(chunk ->
                    Bukkit.getScheduler().runTask(plugin,
                            () -> inspectCandidate(session, completed, requested, coordinates))
            ).exceptionally(error -> {
                Bukkit.getScheduler().runTask(plugin,
                        () -> onCandidateFinished(session, completed, requested, null));
                return null;
            });
        }
    }

    private Coordinates nextUniqueCoordinates(SearchSession session) {
        for (int tries = 0; tries < 16; tries++) {
            Coordinates coordinates = session.supplier.next();
            if (coordinates == null) continue;
            long chunkKey = (((long) (coordinates.x() >> 4)) << 32)
                    ^ ((coordinates.z() >> 4) & 0xffffffffL);
            if (session.visitedChunks.add(chunkKey)) return coordinates;
        }
        return null;
    }

    private void inspectCandidate(SearchSession session, AtomicInteger completed,
                                  int currentBatchSize, Coordinates coordinates) {
        if (session.finished.get()) return;
        if (session.player != null && !session.player.isOnline()) {
            finish(session, null);
            return;
        }

        Location safe = findSafeLocation(session.world, coordinates.x(), coordinates.z(), session.unsafeBlocks);
        onCandidateFinished(session, completed, currentBatchSize, safe);
    }

    private void onCandidateFinished(SearchSession session, AtomicInteger completed,
                                     int currentBatchSize, Location safe) {
        if (session.finished.get()) return;
        if (safe != null) {
            finish(session, safe);
            return;
        }
        if (completed.incrementAndGet() >= currentBatchSize) searchNextBatch(session);
    }

    private void finish(SearchSession session, Location result) {
        if (session.finished.compareAndSet(false, true)) session.callback.accept(result);
    }

    private Coordinates randomCoordinates(World world) {
        FileConfiguration config = plugin.configs().main();
        int minRadius = Math.max(0, config.getInt("rtp.min-radius", config.getInt("rtp.min-coordinate", 2500)));
        int maxRadius = Math.max(minRadius, config.getInt("rtp.max-radius", config.getInt("rtp.max-coordinate", 20000)));
        if (maxRadius <= minRadius) return null;

        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 32; i++) {
            double angle = random.nextDouble(Math.PI * 2.0);
            double distance = Math.sqrt(random.nextDouble((double) minRadius * minRadius,
                    (double) maxRadius * maxRadius + 1.0));
            int x = (int) Math.round(Math.cos(angle) * distance);
            int z = (int) Math.round(Math.sin(angle) * distance);
            if (insideBorder(world, x, z)) return new Coordinates(x, z);
        }
        return null;
    }

    private Coordinates randomCoordinatesAround(World world, int centerX, int centerZ, int radius) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 32; i++) {
            double angle = random.nextDouble(Math.PI * 2.0);
            double distance = Math.sqrt(random.nextDouble()) * radius;
            int x = centerX + (int) Math.round(Math.cos(angle) * distance);
            int z = centerZ + (int) Math.round(Math.sin(angle) * distance);
            if (insideBorder(world, x, z)) return new Coordinates(x, z);
        }
        return null;
    }

    private boolean insideBorder(World world, int x, int z) {
        WorldBorder border = world.getWorldBorder();
        Location center = border.getCenter();
        double half = border.getSize() / 2.0 - 16.0;
        return x >= center.getX() - half && x <= center.getX() + half
                && z >= center.getZ() - half && z <= center.getZ() + half;
    }

    private Location findSafeLocation(World world, int x, int z, Set<Material> unsafeBlocks) {
        return world.getEnvironment() == World.Environment.NETHER
                ? findNetherLocation(world, x, z, unsafeBlocks)
                : findSurfaceLocation(world, x, z, unsafeBlocks);
    }

    private Location findSurfaceLocation(World world, int x, int z, Set<Material> unsafeBlocks) {
        int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (y < world.getMinHeight() || y + 2 >= world.getMaxHeight()) return null;
        return createSafeLocation(world, x, y, z, unsafeBlocks);
    }

    private Location findNetherLocation(World world, int x, int z, Set<Material> unsafeBlocks) {
        int highestY = Math.min(world.getMaxHeight() - 3, 125);
        for (int y = highestY; y > world.getMinHeight(); y--) {
            Location safe = createSafeLocation(world, x, y, z, unsafeBlocks);
            if (safe != null) return safe;
        }
        return null;
    }

    private Location createSafeLocation(World world, int x, int groundY, int z, Set<Material> unsafeBlocks) {
        Block ground = world.getBlockAt(x, groundY, z);
        Block feet = world.getBlockAt(x, groundY + 1, z);
        Block head = world.getBlockAt(x, groundY + 2, z);
        if (!isSafeGround(ground.getType(), unsafeBlocks)) return null;
        if (!feet.getType().isAir() || !head.getType().isAir()) return null;

        Location location = new Location(world, x + 0.5, groundY + 1.0, z + 0.5);
        location.setYaw(ThreadLocalRandom.current().nextFloat() * 360.0f);
        return location;
    }

    private boolean isSafeGround(Material material, Set<Material> unsafeBlocks) {
        if (!material.isSolid() || material.isAir() || material == Material.BEDROCK) return false;
        String name = material.name();
        if (name.endsWith("_LEAVES") || name.endsWith("_TRAPDOOR")
                || name.endsWith("_SLAB") || name.endsWith("_STAIRS")) return false;
        return !unsafeBlocks.contains(material);
    }

    private Set<Material> configuredUnsafeBlocks() {
        Set<Material> unsafe = new HashSet<>();
        for (String configured : plugin.configs().menus().getStringList("rtp.unsafe-blocks")) {
            try {
                unsafe.add(Material.valueOf(configured.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return Collections.unmodifiableSet(unsafe);
    }

    @FunctionalInterface
    private interface CoordinateSupplier { Coordinates next(); }

    private static final class SearchSession {
        private final Player player;
        private final World world;
        private final Consumer<Location> callback;
        private final int maxAttempts;
        private final int batchSize;
        private final CoordinateSupplier supplier;
        private final Set<Material> unsafeBlocks;
        private final Set<Long> visitedChunks = ConcurrentHashMap.newKeySet();
        private final AtomicInteger startedAttempts = new AtomicInteger();
        private final AtomicBoolean finished = new AtomicBoolean();

        private SearchSession(Player player, World world, Consumer<Location> callback,
                              int maxAttempts, int batchSize, CoordinateSupplier supplier,
                              Set<Material> unsafeBlocks) {
            this.player = player;
            this.world = world;
            this.callback = callback;
            this.maxAttempts = maxAttempts;
            this.batchSize = batchSize;
            this.supplier = supplier;
            this.unsafeBlocks = unsafeBlocks;
        }
    }

    private record Coordinates(int x, int z) {}
}
