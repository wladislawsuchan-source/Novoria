package de.walahi.novosmp.professions;

import de.walahi.smpcore.SMPCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.StructureGrowEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/** Counts only player-planted saplings that really grow into a tree. */
public final class SaplingGrowthTracker implements Listener {
    private final SMPCorePlugin plugin;
    private final ProfessionManager manager;
    private final Map<BlockKey, TrackedSapling> tracked = new HashMap<>();

    public SaplingGrowthTracker(SMPCorePlugin plugin, ProfessionManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        try {
            for (TrackedSapling sapling : manager.loadTrackedSaplings()) {
                tracked.put(BlockKey.of(sapling), sapling);
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Gespeicherte Holzfäller-Setzlinge konnten nicht geladen werden", exception);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        remove(block);
        try {
            TrackedSapling sapling = manager.registerPlacedSapling(event.getPlayer(), block);
            if (sapling != null) tracked.put(BlockKey.of(sapling), sapling);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Gesetzter Holzfäller-Setzling konnte nicht gespeichert werden", exception);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        remove(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        event.blockList().forEach(this::remove);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        event.blockList().forEach(this::remove);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(StructureGrowEvent event) {
        Map<BlockKey, Material> plannedBlocks = new HashMap<>();
        boolean containsTreeResult = false;
        for (BlockState state : event.getBlocks()) {
            BlockKey key = BlockKey.of(state.getLocation());
            plannedBlocks.put(key, state.getType());
            if (isTreeResult(state.getType())) containsTreeResult = true;
        }

        // Manche fehlgeschlagenen Knochenmehl-Versuche lösen ein Wachstumsereignis aus,
        // ohne dass ein echter Baumblock vorgesehen ist. Solche Versuche dürfen niemals zählen.
        if (!containsTreeResult) return;

        Location base = event.getLocation();
        World world = base.getWorld();
        if (world == null) return;

        UUID worldId = world.getUID();
        int baseX = base.getBlockX();
        int baseY = base.getBlockY();
        int baseZ = base.getBlockZ();

        Set<BlockKey> candidateKeys = new HashSet<>();
        for (BlockKey key : plannedBlocks.keySet()) {
            if (tracked.containsKey(key)) candidateKeys.add(key);
        }

        // API-sicherer Fallback für 2x2-Bäume: Die vier Setzlinge liegen an oder direkt
        // neben der Event-Position. Gezählt wird später trotzdem nur, wenn dort real ein
        // passender Stamm beziehungsweise eine Mangrovenwurzel entstanden ist.
        for (int x = baseX - 1; x <= baseX + 1; x++) {
            for (int z = baseZ - 1; z <= baseZ + 1; z++) {
                BlockKey key = new BlockKey(worldId, x, baseY, z);
                if (tracked.containsKey(key)) candidateKeys.add(key);
            }
        }

        if (candidateKeys.isEmpty()) return;

        List<TrackedSapling> candidates = new ArrayList<>();
        for (BlockKey key : candidateKeys) {
            TrackedSapling sapling = tracked.get(key);
            if (sapling != null) candidates.add(sapling);
        }

        // Erst einen Tick nach dem Event prüfen. event.getBlocks() beschreibt nur die
        // geplanten Änderungen; maßgeblich ist, was anschließend wirklich in der Welt steht.
        Bukkit.getScheduler().runTask(plugin, () -> verifyAndCreditGrowth(candidates));
    }

    private void verifyAndCreditGrowth(List<TrackedSapling> candidates) {
        for (TrackedSapling sapling : candidates) {
            BlockKey key = BlockKey.of(sapling);
            if (!tracked.containsKey(key)) continue;

            World world = Bukkit.getWorld(sapling.worldId());
            if (world == null) continue;

            Material actual = world.getBlockAt(sapling.x(), sapling.y(), sapling.z()).getType();
            if (isSuccessfulTreeBase(sapling.material(), actual)) {
                try {
                    manager.creditGrownSapling(sapling);
                    tracked.remove(key);
                } catch (RuntimeException exception) {
                    plugin.getLogger().log(Level.WARNING, "Gewachsener Holzfäller-Setzling konnte nicht gewertet werden", exception);
                }
                continue;
            }

            // Bei einem fehlgeschlagenen Knochenmehl-Versuch steht der Setzling weiterhin dort
            // und bleibt registriert. Wurde er dagegen durch etwas anderes ersetzt, ist der
            // gespeicherte Eintrag nicht mehr gültig und wird entfernt.
            if (actual != sapling.material()) {
                discard(key, sapling);
            }
        }
    }

    private boolean isTreeResult(Material material) {
        if (material == null) return false;
        return switch (material) {
            case OAK_LOG, BIRCH_LOG, SPRUCE_LOG, ACACIA_LOG, DARK_OAK_LOG,
                    JUNGLE_LOG, CHERRY_LOG, PALE_OAK_LOG, MANGROVE_LOG,
                    MANGROVE_ROOTS, MUDDY_MANGROVE_ROOTS -> true;
            default -> false;
        };
    }

    private boolean isSuccessfulTreeBase(Material sapling, Material actual) {
        if (sapling == null || actual == null) return false;
        return switch (sapling) {
            case OAK_SAPLING -> actual == Material.OAK_LOG;
            case BIRCH_SAPLING -> actual == Material.BIRCH_LOG;
            case SPRUCE_SAPLING -> actual == Material.SPRUCE_LOG;
            case ACACIA_SAPLING -> actual == Material.ACACIA_LOG;
            case DARK_OAK_SAPLING -> actual == Material.DARK_OAK_LOG;
            case JUNGLE_SAPLING -> actual == Material.JUNGLE_LOG;
            case CHERRY_SAPLING -> actual == Material.CHERRY_LOG;
            case PALE_OAK_SAPLING -> actual == Material.PALE_OAK_LOG;
            case MANGROVE_PROPAGULE -> actual == Material.MANGROVE_LOG
                    || actual == Material.MANGROVE_ROOTS
                    || actual == Material.MUDDY_MANGROVE_ROOTS;
            default -> false;
        };
    }

    private void remove(Block block) {
        if (block == null) return;
        BlockKey key = BlockKey.of(block.getLocation());
        TrackedSapling removed = tracked.remove(key);
        if (removed == null) return;
        try {
            manager.discardTrackedSapling(removed);
        } catch (RuntimeException exception) {
            tracked.put(key, removed);
            plugin.getLogger().log(Level.WARNING, "Holzfäller-Setzling konnte nicht entfernt werden", exception);
        }
    }

    private void discard(BlockKey key, TrackedSapling sapling) {
        tracked.remove(key);
        try {
            manager.discardTrackedSapling(sapling);
        } catch (RuntimeException exception) {
            tracked.put(key, sapling);
            plugin.getLogger().log(Level.WARNING, "Holzfäller-Setzling konnte nicht entfernt werden", exception);
        }
    }

    private record BlockKey(UUID worldId, int x, int y, int z) {
        private static BlockKey of(Location location) {
            return new BlockKey(location.getWorld().getUID(),
                    location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }

        private static BlockKey of(TrackedSapling sapling) {
            return new BlockKey(sapling.worldId(), sapling.x(), sapling.y(), sapling.z());
        }
    }
}
