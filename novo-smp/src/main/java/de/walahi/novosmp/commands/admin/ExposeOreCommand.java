package de.walahi.novosmp.commands.admin;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.logging.Level;

/** Admin-only inspection of real server block data, independent of Anti-Xray packets. */
public final class ExposeOreCommand extends BaseCommand {
    private static final int DEFAULT_RADIUS = 5;
    private static final int MAX_RADIUS = 10;
    private static final int MAX_RESULTS = 20;
    private static final int MAX_BLOCKS_PER_TICK = 32_768;
    private static final long MAX_NANOS_PER_TICK = 2_000_000L;
    private static final int[][] NEIGHBORS = {
            {0, 1, 0}, {0, -1, 0}, {0, 0, -1},
            {0, 0, 1}, {1, 0, 0}, {-1, 0, 0}
    };
    private static final EnumSet<Material> ORES = EnumSet.of(
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
            Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.NETHER_GOLD_ORE, Material.NETHER_QUARTZ_ORE,
            Material.ANCIENT_DEBRIS
    );
    private static final List<String> ORE_NAMES = ORES.stream()
            .map(material -> material.name().toLowerCase(Locale.ROOT)).sorted().toList();
    private static final List<String> MODES = List.of("air", "water", "lava", "any");
    private static final List<String> RADII = List.of("1", "3", "5", "8", "10");
    private static final Comparator<OreResult> NEAREST_FIRST = Comparator
            .comparingDouble(OreResult::distanceSquared)
            .thenComparingInt(OreResult::x).thenComparingInt(OreResult::y).thenComparingInt(OreResult::z);

    private final Map<UUID, ScanTask> activeScans = new HashMap<>();

    public ExposeOreCommand(SMPCorePlugin plugin) {
        super(plugin);
    }

    @Override
    protected String permission() {
        return "novosmp.admin.exposeore";
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendRichMessage("<dark_gray>[<aqua>NovoSMP</aqua>]</dark_gray> <red>Dieser Befehl funktioniert nur im Spiel.</red>");
            return true;
        }
        if (args.length < 2 || args.length > 3) {
            player.sendRichMessage("<dark_gray>[<aqua>NovoSMP</aqua>]</dark_gray> <gray>Benutzung: <yellow>/exposeore <ore> <air|water|lava|any> [radius]</yellow></gray>");
            return true;
        }

        Material ore = Material.matchMaterial(args[0]);
        if (ore == null || !ORES.contains(ore)) {
            player.sendRichMessage("<dark_gray>[<aqua>NovoSMP</aqua>]</dark_gray> <red>Ungültiges Erz. Nutze Tab-Completion für erlaubte Erztypen.</red>");
            return true;
        }
        Exposure mode;
        try {
            mode = Exposure.valueOf(args[1].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            player.sendRichMessage("<dark_gray>[<aqua>NovoSMP</aqua>]</dark_gray> <red>Ungültiger Modus. Erlaubt: air, water, lava, any.</red>");
            return true;
        }

        int radius = DEFAULT_RADIUS;
        if (args.length == 3) {
            try {
                radius = Integer.parseInt(args[2]);
            } catch (NumberFormatException ex) {
                player.sendRichMessage("<dark_gray>[<aqua>NovoSMP</aqua>]</dark_gray> <red>Der Radius muss eine ganze Zahl von 1 bis 10 Chunks sein.</red>");
                return true;
            }
            if (radius < 1 || radius > MAX_RADIUS) {
                player.sendRichMessage("<dark_gray>[<aqua>NovoSMP</aqua>]</dark_gray> <red>Der Radius muss zwischen 1 und 10 Chunks liegen.</red>");
                return true;
            }
        }
        if (activeScans.containsKey(player.getUniqueId())) {
            player.sendRichMessage("<dark_gray>[<aqua>NovoSMP</aqua>]</dark_gray> <red>Dein vorheriger Erzscan läuft noch.</red>");
            return true;
        }

        World world = player.getWorld();
        Location origin = player.getLocation();
        int scanRadius = radius;
        int centerX = origin.getBlockX() >> 4;
        int centerZ = origin.getBlockZ() >> 4;
        List<Chunk> loadedChunks = Arrays.stream(world.getLoadedChunks())
                .filter(chunk -> Math.abs(chunk.getX() - centerX) <= scanRadius
                        && Math.abs(chunk.getZ() - centerZ) <= scanRadius)
                .toList();
        ScanTask scan = new ScanTask(player, world, origin, ore, mode, radius, loadedChunks);
        activeScans.put(player.getUniqueId(), scan);
        try {
            scan.runTaskTimer(plugin, 1L, 1L);
        } catch (RuntimeException ex) {
            activeScans.remove(player.getUniqueId());
            throw ex;
        }
        player.sendRichMessage("<dark_gray>[<aqua>NovoSMP</aqua>]</dark_gray> <gray>Erzscan gestartet: <yellow>"
                + ore.name() + "</yellow>, <yellow>" + mode.name() + "</yellow>, <yellow>" + radius
                + "</yellow> Chunks. Nur aktuell geladene Chunks werden geprüft.</gray>");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player) || !sender.hasPermission(permission())) return List.of();
        List<String> choices = switch (args.length) {
            case 1 -> ORE_NAMES;
            case 2 -> MODES;
            case 3 -> RADII;
            default -> List.of();
        };
        if (choices.isEmpty()) return choices;
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(choice -> choice.startsWith(prefix)).toList();
    }

    private enum Exposure { AIR, WATER, LAVA, ANY }

    private record OreResult(int x, int y, int z, EnumSet<Exposure> exposures, double distanceSquared) { }

    private final class ScanTask extends BukkitRunnable {
        private final Player player;
        private final World world;
        private final Location origin;
        private final Material ore;
        private final Exposure mode;
        private final int radius;
        private final List<Chunk> chunks;
        private final int minY;
        private final int maxY;
        private final int positionsPerChunk;
        private final PriorityQueue<OreResult> nearest = new PriorityQueue<>(MAX_RESULTS + 1, NEAREST_FIRST.reversed());
        private int chunkIndex;
        private int positionIndex;
        private int scannedChunks;
        private long matches;
        private ChunkSnapshot snapshot;

        private ScanTask(Player player, World world, Location origin, Material ore, Exposure mode, int radius,
                         List<Chunk> chunks) {
            this.player = player;
            this.world = world;
            this.origin = origin;
            this.ore = ore;
            this.mode = mode;
            this.radius = radius;
            this.chunks = chunks;
            this.minY = world.getMinHeight();
            this.maxY = world.getMaxHeight();
            this.positionsPerChunk = (maxY - minY) * 256;
        }

        @Override
        public void run() {
            if (!player.isOnline() || player.getWorld() != world) {
                stop();
                return;
            }
            try {
                scanTick();
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.SEVERE, "Fehler beim /exposeore-Scan von " + player.getName(), ex);
                player.sendRichMessage("<dark_gray>[<aqua>NovoSMP</aqua>]</dark_gray> <red>Der Erzscan musste wegen eines Fehlers abgebrochen werden.</red>");
                stop();
            }
        }

        private void scanTick() {
            long deadline = System.nanoTime() + MAX_NANOS_PER_TICK;
            int processed = 0;
            while (chunkIndex < chunks.size() && processed < MAX_BLOCKS_PER_TICK) {
                if (snapshot == null) {
                    Chunk chunk = chunks.get(chunkIndex);
                    if (!chunk.isLoaded()) {
                        chunkIndex++;
                        continue;
                    }
                    // Snapshot reads real server data and never asks the client or Anti-Xray packet layer.
                    snapshot = chunk.getChunkSnapshot(false, false, false);
                    positionIndex = 0;
                }

                int y = minY + (positionIndex >>> 8);
                int localX = positionIndex & 15;
                int localZ = (positionIndex >>> 4) & 15;
                positionIndex++;
                processed++;
                if (snapshot.getBlockType(localX, y, localZ) == ore) {
                    EnumSet<Exposure> exposures = adjacentExposures(localX, y, localZ);
                    if (!exposures.isEmpty() && (mode == Exposure.ANY || exposures.contains(mode))) {
                        int x = (snapshot.getX() << 4) + localX;
                        int z = (snapshot.getZ() << 4) + localZ;
                        double dx = x + 0.5 - origin.getX();
                        double dy = y + 0.5 - origin.getY();
                        double dz = z + 0.5 - origin.getZ();
                        addResult(new OreResult(x, y, z, exposures, dx * dx + dy * dy + dz * dz));
                    }
                }
                if (positionIndex >= positionsPerChunk) {
                    snapshot = null;
                    chunkIndex++;
                    scannedChunks++;
                }
                if ((processed & 511) == 0 && System.nanoTime() >= deadline) break;
            }
            if (chunkIndex >= chunks.size()) {
                showResults();
                stop();
            }
        }

        private EnumSet<Exposure> adjacentExposures(int localX, int y, int localZ) {
            EnumSet<Exposure> found = EnumSet.noneOf(Exposure.class);
            for (int[] offset : NEIGHBORS) {
                int nx = localX + offset[0];
                int ny = y + offset[1];
                int nz = localZ + offset[2];
                if (ny < minY || ny >= maxY) {
                    found.add(Exposure.AIR); // Bukkit's out-of-world neighbor is VOID_AIR.
                    continue;
                }
                Material neighbor;
                BlockData data = null;
                if (nx >= 0 && nx < 16 && nz >= 0 && nz < 16) {
                    neighbor = snapshot.getBlockType(nx, ny, nz);
                    if (neighbor != Material.WATER && neighbor != Material.LAVA && !neighbor.isAir()) {
                        data = snapshot.getBlockData(nx, ny, nz);
                    }
                } else {
                    int worldX = (snapshot.getX() << 4) + nx;
                    int worldZ = (snapshot.getZ() << 4) + nz;
                    if (!world.isChunkLoaded(worldX >> 4, worldZ >> 4)) continue;
                    Block block = world.getBlockAt(worldX, ny, worldZ);
                    neighbor = block.getType();
                    if (neighbor != Material.WATER && neighbor != Material.LAVA && !neighbor.isAir()) {
                        data = block.getBlockData();
                    }
                }
                if (neighbor.isAir()) found.add(Exposure.AIR);
                if (neighbor == Material.WATER || data instanceof Waterlogged waterlogged && waterlogged.isWaterlogged()) {
                    found.add(Exposure.WATER);
                }
                if (neighbor == Material.LAVA) found.add(Exposure.LAVA);
            }
            return found;
        }

        private void addResult(OreResult result) {
            matches++;
            if (nearest.size() < MAX_RESULTS) {
                nearest.add(result);
            } else if (NEAREST_FIRST.compare(result, nearest.peek()) < 0) {
                nearest.remove();
                nearest.add(result);
            }
        }

        private void showResults() {
            int possibleChunks = (radius * 2 + 1) * (radius * 2 + 1);
            player.sendRichMessage("<dark_gray>[<aqua>NovoSMP</aqua>]</dark_gray> <gray>Exposed <yellow>"
                    + ore.name() + "</yellow> gefunden: <green>" + matches + "</green> <dark_gray>("
                    + scannedChunks + "/" + possibleChunks + " Chunks gescannt; ungeladene übersprungen)</dark_gray></gray>");
            List<OreResult> results = new ArrayList<>(nearest);
            results.sort(NEAREST_FIRST);
            for (int index = 0; index < results.size(); index++) {
                OreResult result = results.get(index);
                String coordinates = "X: " + result.x() + " Y: " + result.y() + " Z: " + result.z();
                String teleport = "/tp @s " + result.x() + " " + result.y() + " " + result.z();
                Component line = Component.text((index + 1) + ". ", NamedTextColor.DARK_GRAY)
                        .append(Component.text(coordinates, NamedTextColor.YELLOW)
                                .clickEvent(ClickEvent.suggestCommand(teleport))
                                .hoverEvent(HoverEvent.showText(Component.text("Klicken für Teleport-Befehl"))))
                        .append(Component.text(" | " + exposureText(result.exposures()), NamedTextColor.AQUA))
                        .append(Component.text(" | " + Math.round(Math.sqrt(result.distanceSquared())) + "m", NamedTextColor.GRAY));
                player.sendMessage(line);
            }
            if (matches > results.size()) {
                player.sendRichMessage("<dark_gray>... und <yellow>" + (matches - results.size()) + "</yellow> weitere Ergebnisse.</dark_gray>");
            }
        }

        private String exposureText(EnumSet<Exposure> exposures) {
            List<String> labels = new ArrayList<>(3);
            for (Exposure exposure : List.of(Exposure.AIR, Exposure.WATER, Exposure.LAVA)) {
                if (exposures.contains(exposure)) labels.add(exposure.name());
            }
            return String.join(", ", labels);
        }

        private void stop() {
            activeScans.remove(player.getUniqueId(), this);
            cancel();
        }
    }
}
