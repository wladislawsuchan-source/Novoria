package de.walahi.novosmp.duel;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.util.SideEffectSet;
import de.walahi.novosmp.NovoSMPPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** WorldEdit-backed arena snapshot and tick-budgeted reset. */
public final class WorldEditArenaService {
    private final NovoSMPPlugin plugin;
    private final DuelConfig config;
    private final File schematicFolder;
    private final Set<String> activeResets = ConcurrentHashMap.newKeySet();

    public WorldEditArenaService(NovoSMPPlugin plugin, DuelConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.schematicFolder = new File(plugin.getDataFolder(), "duel-schematics");
    }

    public boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("WorldEdit");
    }

    public boolean isResetting(String mapId) {
        return mapId != null && activeResets.contains(DuelConfig.normalize(mapId));
    }

    public DuelMap capture(org.bukkit.entity.Player bukkitPlayer, String id, String displayName, Material icon) throws Exception {
        if (!available()) throw new IllegalStateException("WorldEdit ist nicht installiert oder nicht aktiviert.");
        Player actor = BukkitAdapter.adapt(bukkitPlayer);
        Region selection = WorldEdit.getInstance().getSessionManager().get(actor).getSelection(actor.getWorld());
        if (!(selection instanceof CuboidRegion)) {
            throw new IllegalStateException("Bitte markiere die Arena mit einer normalen quaderförmigen WorldEdit-Auswahl.");
        }

        BlockVector3 min = selection.getMinimumPoint();
        BlockVector3 max = selection.getMaximumPoint();
        BlockArrayClipboard clipboard = new BlockArrayClipboard(selection);
        clipboard.setOrigin(min);
        try (EditSession editSession = WorldEdit.getInstance().newEditSession(actor.getWorld())) {
            ForwardExtentCopy copy = new ForwardExtentCopy(editSession, selection, clipboard, min);
            // Entities werden für Dekorationen (z. B. Armor Stands/Item Frames) mitgespeichert.
            // Biome werden nicht benötigt und würden den Arena-Reset unnötig vergrößern.
            copy.setCopyingEntities(true);
            copy.setCopyingBiomes(false);
            Operations.complete(copy);
        }

        if (!schematicFolder.exists() && !schematicFolder.mkdirs()) {
            throw new IllegalStateException("Schematic-Ordner konnte nicht erstellt werden.");
        }
        String fileName = DuelConfig.normalize(id) + ".schem";
        File file = new File(schematicFolder, fileName);
        try (ClipboardWriter writer = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(new FileOutputStream(file))) {
            writer.write(clipboard);
        }

        return new DuelMap(
                DuelConfig.normalize(id), displayName, icon,
                bukkitPlayer.getWorld().getName(),
                min.x(), min.y(), min.z(), max.x(), max.y(), max.z(),
                null, null,
                "duel-schematics/" + fileName
        );
    }

    /**
     * Resets an arena without completing one giant WorldEdit operation on a single server tick.
     * The schematic is read off-thread, then pasted in small cuboids on the main thread with a
     * configurable time budget. The callback is always executed on the main thread.
     *
     * @return false when a reset for this arena is already running
     */
    public boolean resetAsync(DuelMap map, Consumer<Throwable> completion) {
        String mapId = DuelConfig.normalize(map.id());
        if (!activeResets.add(mapId)) return false;

        if (!available()) {
            finishReset(mapId, completion, new IllegalStateException("WorldEdit ist nicht verfügbar."));
            return true;
        }
        World world = map.world();
        if (world == null) {
            finishReset(mapId, completion, new IllegalStateException("Duellwelt ist nicht geladen: " + map.worldName()));
            return true;
        }
        File file = new File(plugin.getDataFolder(), map.schematicFile());
        if (!file.isFile()) {
            finishReset(mapId, completion, new IllegalStateException("Arena-Schematic fehlt: " + file.getAbsolutePath()));
            return true;
        }

        removeArenaEntities(map);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Clipboard clipboard = loadClipboard(file);
                List<CuboidRegion> slices = createSlices(clipboard);
                Bukkit.getScheduler().runTask(plugin, () -> pasteSlices(mapId, world, map, clipboard, slices, completion));
            } catch (Throwable throwable) {
                Bukkit.getScheduler().runTask(plugin, () -> finishReset(mapId, completion, throwable));
            }
        });
        return true;
    }

    private Clipboard loadClipboard(File file) throws Exception {
        ClipboardFormat format = ClipboardFormats.findByFile(file);
        if (format == null) throw new IllegalStateException("Unbekanntes Schematic-Format: " + file.getName());
        try (ClipboardReader reader = format.getReader(new FileInputStream(file))) {
            return reader.read();
        }
    }

    private List<CuboidRegion> createSlices(Clipboard clipboard) {
        Region source = clipboard.getRegion();
        BlockVector3 min = source.getMinimumPoint();
        BlockVector3 max = source.getMaximumPoint();
        int width = config.resetTileSize();
        int height = config.resetTileHeight();
        List<CuboidRegion> slices = new ArrayList<>();

        // X/Z zuerst, damit mehrere vertikale Teilstücke desselben Chunks direkt nacheinander
        // bearbeitet werden und der Chunk nicht ständig neu angefasst werden muss.
        for (int x = min.x(); x <= max.x(); x += width) {
            int endX = Math.min(max.x(), x + width - 1);
            for (int z = min.z(); z <= max.z(); z += width) {
                int endZ = Math.min(max.z(), z + width - 1);
                for (int y = min.y(); y <= max.y(); y += height) {
                    int endY = Math.min(max.y(), y + height - 1);
                    slices.add(new CuboidRegion(
                            BlockVector3.at(x, y, z),
                            BlockVector3.at(endX, endY, endZ)
                    ));
                }
            }
        }
        return slices;
    }

    private void pasteSlices(String mapId, World world, DuelMap map, Clipboard clipboard,
                             List<CuboidRegion> slices, Consumer<Throwable> completion) {
        if (!plugin.isEnabled()) {
            finishReset(mapId, completion, new IllegalStateException("Plugin wurde während des Arena-Resets deaktiviert."));
            return;
        }
        if (slices.isEmpty()) {
            finishReset(mapId, completion, null);
            return;
        }

        final long budgetNanos = config.resetMaxMillisPerTick() * 1_000_000L;
        new BukkitRunnable() {
            private int index;

            @Override
            public void run() {
                long deadline = System.nanoTime() + budgetNanos;
                boolean processedAtLeastOne = false;
                try {
                    while (index < slices.size() && (!processedAtLeastOne || System.nanoTime() < deadline)) {
                        pasteSlice(world, map, clipboard, slices.get(index++));
                        processedAtLeastOne = true;
                    }
                    if (index >= slices.size()) {
                        cancel();
                        finishReset(mapId, completion, null);
                    }
                } catch (Throwable throwable) {
                    cancel();
                    finishReset(mapId, completion, throwable);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void pasteSlice(World world, DuelMap map, Clipboard clipboard, CuboidRegion slice) throws Exception {
        try (EditSession editSession = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
            // Keine Physik-, Licht- oder Nachbarupdates pro Block. Spieler sind beim Reset nicht
            // mehr in der Arena und laden die fertigen Chunks anschließend neu.
            editSession.setSideEffectApplier(SideEffectSet.none());
            Operation operation = new ClipboardHolder(clipboard)
                    .createPaste(editSession)
                    .to(BlockVector3.at(map.minX(), map.minY(), map.minZ()))
                    .copyRegion(slice)
                    .ignoreAirBlocks(false)
                    .copyEntities(true)
                    .copyBiomes(false)
                    .build();
            Operations.complete(operation);
        }
    }

    private void finishReset(String mapId, Consumer<Throwable> completion, Throwable error) {
        activeResets.remove(mapId);
        try {
            completion.accept(error);
        } catch (Throwable callbackError) {
            plugin.getLogger().severe("Fehler im Arena-Reset-Callback für " + mapId + ": " + callbackError.getMessage());
        }
    }

    public void removeArenaEntities(DuelMap map) {
        World world = map.world();
        if (world == null) return;
        for (Entity entity : world.getEntities()) {
            if (entity instanceof org.bukkit.entity.Player) continue;
            if (!map.contains(entity.getLocation())) continue;
            entity.remove();
        }
    }

    public File schematicFile(DuelMap map) {
        return new File(plugin.getDataFolder(), map.schematicFile());
    }
}
