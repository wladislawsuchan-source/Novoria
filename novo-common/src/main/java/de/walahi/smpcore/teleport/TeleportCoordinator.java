package de.walahi.smpcore.teleport;

import de.walahi.smpcore.SMPCorePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Central owner of delayed teleports and movement cancellation.
 * RTP may register its own asynchronous search task through the same registry,
 * so a player can never have two independent countdowns at once.
 */
public final class TeleportCoordinator implements Listener {
    private final SMPCorePlugin plugin;
    private final Consumer<UUID> movementCancellationHook;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, PendingTeleport> pending = new HashMap<>();

    public TeleportCoordinator(SMPCorePlugin plugin, Consumer<UUID> movementCancellationHook) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.movementCancellationHook = Objects.requireNonNull(movementCancellationHook, "movementCancellationHook");
    }

    public void beginStandard(Player player, Location target, String successMessagePath) {
        cancel(player.getUniqueId());
        int delay = Math.max(0, plugin.configs().main().getInt("settings.teleport-delay-seconds", 0));
        if (delay == 0 || player.hasPermission("smpcore.bypass.delay")) {
            teleportImmediately(player, target, successMessagePath);
            return;
        }

        final int[] remaining = {delay};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) {
                cancel(player.getUniqueId());
                return;
            }
            if (remaining[0] <= 0) {
                cancel(player.getUniqueId());
                teleportImmediately(player, target, successMessagePath);
                return;
            }
            plugin.sendConfigured(player, "messages.teleport-countdown",
                    "<seconds>", Integer.toString(remaining[0]));
            plugin.playConfigured(player, "sounds.countdown");
            remaining[0]--;
        }, 0L, 20L);

        register(player.getUniqueId(), task,
                plugin.configs().main().getBoolean("settings.cancel-on-move", false),
                "messages.teleport-cancelled", false);
    }

    public void beginSpawn(Player player, Location target) {
        cancel(player.getUniqueId());
        int delay = Math.max(0, plugin.configs().main().getInt("spawn.delay-seconds", 3));
        boolean bypass = player.hasPermission("smpcore.bypass.delay")
                || player.hasPermission("smpcore.spawn.bypass.delay");
        if (delay == 0 || bypass) {
            teleportImmediately(player, target, "messages.teleported-to-spawn");
            return;
        }

        final int[] remaining = {delay};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) {
                cancel(player.getUniqueId());
                return;
            }
            if (remaining[0] <= 0) {
                cancel(player.getUniqueId());
                player.sendActionBar(Component.empty());
                teleportImmediately(player, target, "messages.teleported-to-spawn");
                return;
            }
            String raw = plugin.configs().main().getString(
                    "spawn.actionbar",
                    "<gold>[SMP]</gold> <yellow>Teleport in <green>%seconds%s</green>..."
            ).replace("%seconds%", Integer.toString(remaining[0]));
            player.sendActionBar(miniMessage.deserialize(raw));
            plugin.playConfigured(player, "sounds.countdown");
            remaining[0]--;
        }, 0L, 20L);

        register(player.getUniqueId(), task,
                plugin.configs().main().getBoolean("spawn.cancel-on-move", true),
                "messages.spawn-teleport-cancelled", true);
    }

    public void beginHome(Player player, Location target, Runnable successFeedback) {
        cancel(player.getUniqueId());
        // Nur warnen, niemals blockieren: Homes duerfen absichtlich riskant gesetzt sein.
        // Vor dem eigentlichen Teleport wird erneut geprueft, falls sich die Umgebung
        // waehrend des Countdowns veraendert hat.
        final boolean warnedUnsafeAtStart = warnIfUnsafeHome(player, target);
        boolean bypass = player.hasPermission("smpcore.bypass.delay")
                || player.hasPermission("smpcore.home.bypass.delay");
        int delay = Math.max(0, plugin.configs().main().getInt("homes.delay-seconds", 3));
        if (delay == 0 || bypass) {
            completeHomeTeleport(player, target, successFeedback, warnedUnsafeAtStart);
            return;
        }

        final int[] remaining = {delay};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) {
                cancel(player.getUniqueId());
                return;
            }
            if (remaining[0] <= 0) {
                cancel(player.getUniqueId());
                player.sendActionBar(Component.empty());
                completeHomeTeleport(player, target, successFeedback, warnedUnsafeAtStart);
                return;
            }
            String raw = plugin.configs().main().getString(
                    "homes.actionbar",
                    "<yellow>Teleport in <green>%seconds%s</green>..."
            ).replace("%seconds%", Integer.toString(remaining[0]));
            player.sendActionBar(miniMessage.deserialize(raw));
            plugin.playConfigured(player, "sounds.countdown");
            remaining[0]--;
        }, 0L, 20L);

        register(player.getUniqueId(), task,
                plugin.configs().main().getBoolean("homes.cancel-on-move", true),
                "messages.home-teleport-cancelled", true);
    }

    public void register(UUID playerId, BukkitTask task, boolean cancelOnMove,
                         String cancelMessagePath, boolean usesActionBar) {
        PendingTeleport previous = pending.put(playerId,
                new PendingTeleport(task, cancelOnMove, cancelMessagePath, usesActionBar));
        if (previous != null && previous.task() != task) previous.task().cancel();
    }

    public boolean isPending(UUID playerId) {
        return pending.containsKey(playerId);
    }

    public void cancel(UUID playerId) {
        PendingTeleport teleport = pending.remove(playerId);
        if (teleport != null) teleport.task().cancel();
    }

    public void shutdown() {
        for (PendingTeleport teleport : pending.values()) teleport.task().cancel();
        pending.clear();
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        PendingTeleport teleport = pending.get(event.getPlayer().getUniqueId());
        if (teleport == null || !teleport.cancelOnMove()) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        UUID playerId = event.getPlayer().getUniqueId();
        cancel(playerId);
        movementCancellationHook.accept(playerId);
        if (teleport.usesActionBar()) event.getPlayer().sendActionBar(Component.empty());
        plugin.sendConfigured(event.getPlayer(), teleport.cancelMessagePath());
    }

    private void teleportImmediately(Player player, Location target, String successMessagePath) {
        player.teleportAsync(target).thenAccept(success -> {
            if (!success) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if ("messages.teleported-to-spawn".equals(successMessagePath)) {
                    applySpawnRestore(player);
                }
                if (successMessagePath != null && !successMessagePath.isBlank()) {
                    plugin.sendConfigured(player, successMessagePath);
                }
                plugin.playConfigured(player, "sounds.success");
            });
        });
    }

    private void completeHomeTeleport(Player player, Location target, Runnable successFeedback,
                                      boolean warnedUnsafeAtStart) {
        // War das Home beim Start noch sicher, aber ist inzwischen gefaehrlich geworden,
        // bekommt der Spieler unmittelbar vor dem Teleport noch die Warnung.
        if (!warnedUnsafeAtStart) warnIfUnsafeHome(player, target);
        player.teleportAsync(target).thenAccept(success -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!success || !player.isOnline()) return;
            successFeedback.run();
            plugin.playConfigured(player, "sounds.success");
        }));
    }

    private boolean warnIfUnsafeHome(Player player, Location target) {
        if (!plugin.configs().main().getBoolean("homes.safety-warning.enabled", true)) return false;
        if (!isUnsafeHome(target)) return false;
        plugin.messages().sendConfiguredAuto(
                player,
                plugin.configs().messages(),
                "messages.home-unsafe-warning",
                "<yellow>⚠ Achtung: Dieses Home befindet sich möglicherweise an einer unsicheren Position.</yellow>"
        );
        return true;
    }

    private boolean isUnsafeHome(Location target) {
        if (target == null || target.getWorld() == null) return true;
        var world = target.getWorld();
        int x = target.getBlockX();
        int z = target.getBlockZ();
        Block ground = world.getBlockAt(x, (int) Math.floor(target.getY() - 0.01D), z);
        Block feet = world.getBlockAt(x, target.getBlockY(), z);
        Block head = world.getBlockAt(x, (int) Math.floor(target.getY() + 1.62D), z);

        if (isDangerousHomeBlock(ground) || isDangerousHomeBlock(feet) || isDangerousHomeBlock(head)) return true;
        if (ground.getType().isAir() || ground.isLiquid() || ground.isPassable()) return true;
        if (head.getType().isSolid()) return true;

        // Ein Home auf einer Slab/Stufe hat absichtlich einen Bruchteil in der Y-Position.
        // Ein nachtraeglich in die Fussposition gesetzter Vollblock sitzt dagegen fast
        // immer auf einer ganzzahligen Spieler-Y und wird als blockiertes Ziel gewarnt.
        double yFraction = target.getY() - Math.floor(target.getY());
        return feet.getType().isSolid() && yFraction < 0.20D;
    }

    private boolean isDangerousHomeBlock(Block block) {
        String material = block.getType().name();
        java.util.List<String> configuredBlocks = plugin.configs().main()
                .getStringList("homes.safety-warning.dangerous-blocks");
        if (configuredBlocks.isEmpty()) {
            configuredBlocks = java.util.List.of(
                    "LAVA", "FIRE", "SOUL_FIRE", "MAGMA_BLOCK", "CACTUS", "CAMPFIRE",
                    "SOUL_CAMPFIRE", "POWDER_SNOW", "SWEET_BERRY_BUSH", "WITHER_ROSE",
                    "POINTED_DRIPSTONE"
            );
        }
        for (String configured : configuredBlocks) {
            if (material.equalsIgnoreCase(configured)) return true;
        }
        return false;
    }

    private void applySpawnRestore(Player player) {
        if (plugin.configs().main().getBoolean("spawn.heal-on-teleport", true)) {
            var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealth != null) player.setHealth(maxHealth.getValue());
        }
        if (plugin.configs().main().getBoolean("spawn.restore-food-on-teleport", true)) {
            player.setFoodLevel(20);
            player.setSaturation(20.0f);
            player.setExhaustion(0.0f);
        }
        if (plugin.configs().main().getBoolean("spawn.clear-fire-on-teleport", true)) {
            player.setFireTicks(0);
        }
    }

    private record PendingTeleport(BukkitTask task, boolean cancelOnMove,
                                   String cancelMessagePath, boolean usesActionBar) {
    }
}
