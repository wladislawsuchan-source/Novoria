package de.walahi.novosmp.chestlog;

import de.walahi.novosmp.NovoSMPPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ChestLogManager implements Listener {
    private static final long SAVE_DELAY_MILLIS = 500L;
    private static final long RETRY_DELAY_MILLIS = 5_000L;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.systemDefault());

    private final NovoSMPPlugin plugin;
    private final Logger logger;
    private final File file;
    private final YamlConfiguration data;
    private final Map<UUID, OpenSession> sessions = new HashMap<>();
    private final Map<String, List<String>> liveEntries = new HashMap<>();
    private final ScheduledThreadPoolExecutor writer = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "NovoSMP-ChestLog-Writer");
        thread.setDaemon(true);
        return thread;
    });
    // The YAML, these revisions and the scheduled save belong exclusively to the writer thread.
    private ScheduledFuture<?> pendingSave;
    private long appliedRevision;
    private long savedRevision;
    private long nextRevision;
    private boolean closed;

    public ChestLogManager(NovoSMPPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.file = new File(plugin.getDataFolder(), "chestlog.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        var containers = data.getConfigurationSection("containers");
        if (containers != null) {
            for (String key : containers.getKeys(true)) {
                if (containers.isList(key)) {
                    liveEntries.put(key, new ArrayList<>(containers.getStringList(key)));
                }
            }
        }
        writer.setRemoveOnCancelPolicy(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Location location = containerLocation(event.getInventory());
        if (location == null || !plugin.isSmpGameplayWorld(location.getWorld())) return;

        String key = key(location);
        sessions.put(player.getUniqueId(), new OpenSession(key, cloneContents(event.getInventory().getContents())));
        append(key, player.getName() + "|" + System.currentTimeMillis() + "|OPEN|");
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        HumanEntity human = event.getPlayer();
        if (!(human instanceof Player player)) return;
        OpenSession session = sessions.remove(player.getUniqueId());
        if (session == null) return;

        Map<Material, Integer> before = count(session.before());
        Map<Material, Integer> after = count(event.getInventory().getContents());
        List<String> changes = new ArrayList<>();

        for (Material material : union(before, after).keySet()) {
            int difference = after.getOrDefault(material, 0) - before.getOrDefault(material, 0);
            if (difference < 0) changes.add("-" + Math.abs(difference) + " " + material.name());
            if (difference > 0) changes.add("+" + difference + " " + material.name());
        }

        if (!changes.isEmpty()) {
            append(session.key(), player.getName() + "|" + System.currentTimeMillis() + "|CHANGE|" + String.join(",", changes));
        }
    }

    public Location normalizeContainerLocation(Container container) {
        InventoryHolder holder = container.getInventory().getHolder();
        if (holder instanceof DoubleChest doubleChest) return doubleChest.getLocation();
        return container.getLocation();
    }

    public List<String> formattedEntries(Location location, int limit) {
        List<String> raw = liveEntries.getOrDefault(key(location), List.of());
        List<String> result = new ArrayList<>();
        if (limit <= 0) return result;
        // Nur die neuesten Einträge auswählen, diese aber chronologisch ausgeben:
        // ältester oben, neuester ganz unten direkt vor der Abschlusslinie.
        int firstIndex = Math.max(0, raw.size() - limit);
        for (int index = firstIndex; index < raw.size(); index++) {
            String[] parts = raw.get(index).split("\\|", 4);
            if (parts.length < 3) continue;
            long timestamp;
            try {
                timestamp = Long.parseLong(parts[1]);
            } catch (NumberFormatException ignored) {
                continue;
            }
            String time = TIME_FORMAT.format(Instant.ofEpochMilli(timestamp));
            if ("OPEN".equals(parts[2])) {
                result.add("§8[§7" + time + "§8] §e" + parts[0] + " §7hat die Kiste geöffnet.");
            } else if ("CHANGE".equals(parts[2]) && parts.length == 4) {
                StringBuilder text = new StringBuilder("§8[§7").append(time).append("§8] §e")
                        .append(parts[0]).append("§7: ");
                String[] changes = parts[3].split(",");
                for (int i = 0; i < changes.length; i++) {
                    if (i > 0) text.append("§8, ");
                    String change = changes[i];
                    boolean removed = change.startsWith("-");
                    text.append(removed ? "§c" : "§a").append(pretty(change));
                }
                result.add(text.toString());
            }
        }
        return result;
    }

    private String pretty(String value) {
        int space = value.indexOf(' ');
        if (space < 0) return value;
        String amount = value.substring(0, space);
        String material = value.substring(space + 1).toLowerCase(Locale.ROOT).replace('_', ' ');
        return amount + " " + material;
    }

    private void append(String key, String entry) {
        List<String> entries = new ArrayList<>(liveEntries.getOrDefault(key, List.of()));
        entries.add(entry);
        int max = Math.max(20, plugin.configs().main().getInt("chestlog.max-entries-per-container", 100));
        if (entries.size() > max) entries = new ArrayList<>(entries.subList(entries.size() - max, entries.size()));
        liveEntries.put(key, entries);
        List<String> snapshotEntries = entries;
        long revision = ++nextRevision;
        try {
            writer.execute(() -> {
                data.set("containers." + key, snapshotEntries);
                appliedRevision = revision;
                if (pendingSave == null) scheduleSave(SAVE_DELAY_MILLIS);
            });
        } catch (RejectedExecutionException exception) {
            logger.severe("ChestLog-Schreiber ist beendet; neuer Eintrag liegt nur im RAM.");
        }
    }

    /** Complete pending writes before a configuration reload, without replacing the live log state. */
    public void flushForReload() {
        if (!closed && !flushBlocking()) {
            logger.severe("ChestLog konnte vor dem Reload nicht vollständig gespeichert werden.");
        }
    }

    /** Drains queued entries and stops the dedicated writer before plugin disable completes. */
    public void shutdown() {
        if (closed) return;
        closed = true;
        boolean saved = flushBlocking();
        writer.shutdown();
        try {
            if (!writer.awaitTermination(2L, TimeUnit.SECONDS)) {
                writer.shutdownNow();
                if (!writer.awaitTermination(2L, TimeUnit.SECONDS)) {
                    logger.severe("ChestLog-Schreiber konnte nicht rechtzeitig beendet werden.");
                    return;
                }
            }
        } catch (InterruptedException exception) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
            logger.severe("ChestLog-Shutdown wurde unterbrochen.");
            return;
        }
        if (!saved || savedRevision < nextRevision) {
            // The worker has terminated: this YAML may now safely be used on the main thread.
            liveEntries.forEach((key, entries) -> data.set("containers." + key, entries));
            appliedRevision = nextRevision;
            if (!persist()) {
                logger.severe("ChestLog blieb nach synchronem Shutdown-Fallback ungespeichert.");
            }
        }
    }

    private boolean flushBlocking() {
        Future<Boolean> flush;
        try {
            flush = writer.submit(() -> {
                if (pendingSave != null) pendingSave.cancel(false);
                pendingSave = null;
                boolean saved = persist();
                if (!saved && !closed) scheduleSave(RETRY_DELAY_MILLIS);
                return saved;
            });
            return flush.get(10L, TimeUnit.SECONDS);
        } catch (RejectedExecutionException | ExecutionException | TimeoutException exception) {
            logger.severe("ChestLog-Flush fehlgeschlagen: " + exception.getMessage());
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void scheduleSave(long delayMillis) {
        pendingSave = writer.schedule(() -> {
            pendingSave = null;
            if (!persist()) scheduleSave(RETRY_DELAY_MILLIS);
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    private boolean persist() {
        if (savedRevision >= appliedRevision) return true;
        try {
            byte[] bytes = data.saveToString().getBytes(StandardCharsets.UTF_8);
            Path target = file.toPath();
            Path temporary = target.resolveSibling(file.getName() + ".tmp");
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            savedRevision = appliedRevision;
            return true;
        } catch (IOException | RuntimeException exception) {
            logger.warning("chestlog.yml konnte nicht gespeichert werden: " + exception.getMessage());
            return false;
        }
    }

    private Location containerLocation(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof Container container) return container.getLocation();
        if (holder instanceof DoubleChest doubleChest) return doubleChest.getLocation();
        return null;
    }

    private String key(Location location) {
        return location.getWorld().getName() + ";" + location.getBlockX() + ";" + location.getBlockY() + ";" + location.getBlockZ();
    }

    private ItemStack[] cloneContents(ItemStack[] source) {
        ItemStack[] copy = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) copy[i] = source[i] == null ? null : source[i].clone();
        return copy;
    }

    private Map<Material, Integer> count(ItemStack[] contents) {
        Map<Material, Integer> result = new LinkedHashMap<>();
        for (ItemStack item : contents) {
            if (item == null || item.getType().isAir()) continue;
            result.merge(item.getType(), item.getAmount(), Integer::sum);
        }
        return result;
    }

    private Map<Material, Boolean> union(Map<Material, Integer> first, Map<Material, Integer> second) {
        Map<Material, Boolean> result = new LinkedHashMap<>();
        first.keySet().forEach(material -> result.put(material, Boolean.TRUE));
        second.keySet().forEach(material -> result.put(material, Boolean.TRUE));
        return result;
    }

    private record OpenSession(String key, ItemStack[] before) {}
}
