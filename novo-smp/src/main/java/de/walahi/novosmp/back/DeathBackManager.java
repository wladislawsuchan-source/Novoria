package de.walahi.novosmp.back;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.novosmp.rtp.RtpManager;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.text.Component;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

public final class DeathBackManager implements Listener {
    private final NovoSMPPlugin plugin;
    private final RtpManager rtpManager;
    private final DeathBackRepository repository;
    private final Set<UUID> streamMode = ConcurrentHashMap.newKeySet();
    private final Set<UUID> teleporting = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BukkitTask> teleportTasks = new ConcurrentHashMap<>();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public DeathBackManager(NovoSMPPlugin plugin, RtpManager rtpManager, DeathBackRepository repository) {
        this.plugin = plugin;
        this.rtpManager = rtpManager;
        this.repository = repository;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                repository.deleteExpired(System.currentTimeMillis());
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.WARNING, "Abgelaufene /back-Punkte konnten nicht bereinigt werden.", exception);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (player.hasMetadata("novosmp-combat-dummy")
                || player.hasMetadata("novosmp-finalized-combat-death")) return;
        if (plugin.isPlayerInDuel(player.getUniqueId())) return;
        Location death = player.getLocation().clone();
        UUID uuid = player.getUniqueId();
        cancelTeleport(uuid);

        int expiryDays = Math.max(1, plugin.configs().server().getInt("death-back.expiry-days", 5));
        long createdAt = System.currentTimeMillis();
        long expiresAt = createdAt + Duration.ofDays(expiryDays).toMillis();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                repository.save(uuid, player.getName(), death, createdAt, expiresAt);
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.SEVERE, "Der /back-Punkt von " + player.getName() + " konnte nicht gespeichert werden.", exception);
            }
        });

        if (!streamMode.contains(uuid)) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || streamMode.contains(uuid)) return;
                int radius = Math.max(1, plugin.configs().server().getInt("death-back.respawn-radius", 2000));
                String template = plugin.configs().server().getString(
                        "death-back.death-message",
                        "<red>Du bist bei <aqua>%x% %y% %z%</aqua> in <aqua>%world%</aqua> gestorben. Nutze <green>/back</green>, um dich einmalig in einem Radius von <yellow>%radius% Blöcken</yellow> um deinen Todespunkt teleportieren zu lassen.</red>"
                );
                // Alte bereits vorhandene Config-Texte automatisch auf die neue Erklärung erweitern.
                if (!template.contains("%radius%") && !template.toLowerCase(java.util.Locale.ROOT).contains("radius")) {
                    template = "<red>Du bist bei <aqua>%x% %y% %z%</aqua> in <aqua>%world%</aqua> gestorben. Nutze <green>/back</green>, um dich einmalig in einem Radius von <yellow>%radius% Blöcken</yellow> um deinen Todespunkt teleportieren zu lassen.</red>";
                }
                String message = template
                        .replace("%x%", Integer.toString(death.getBlockX()))
                        .replace("%y%", Integer.toString(death.getBlockY()))
                        .replace("%z%", Integer.toString(death.getBlockZ()))
                        .replace("%world%", displayWorldName(death))
                        .replace("%radius%", Integer.toString(radius));
                player.sendMessage(miniMessage.deserialize(message));
            });
        }
    }

    public boolean toggleStream(Player player) {
        UUID uuid = player.getUniqueId();
        if (streamMode.remove(uuid)) return false;
        streamMode.add(uuid);
        return true;
    }

    public boolean isStreamMode(Player player) {
        return streamMode.contains(player.getUniqueId());
    }

    public void useBack(Player player) {
        UUID uuid = player.getUniqueId();
        if (!teleporting.add(uuid)) {
            player.sendRichMessage("<dark_gray>[<green>SMP</green>]</dark_gray> <yellow>Es wird bereits ein sicherer Ort gesucht.</yellow>");
            return;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            final DeathBackPoint point;
            try {
                point = repository.load(uuid);
                if (point != null && point.expired(System.currentTimeMillis())) {
                    repository.delete(uuid);
                    finishOnMainThread(uuid, () -> player.sendRichMessage("<dark_gray>[<green>SMP</green>]</dark_gray> <red>Dein letzter /back-Punkt ist abgelaufen.</red>"));
                    return;
                }
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.SEVERE, "Der /back-Punkt von " + player.getName() + " konnte nicht geladen werden.", exception);
                finishOnMainThread(uuid, () -> player.sendRichMessage("<dark_gray>[<green>SMP</green>]</dark_gray> <red>Dein /back konnte gerade nicht geladen werden.</red>"));
                return;
            }

            plugin.getServer().getScheduler().runTask(plugin, () -> beginTeleport(player, point));
        });
    }

    private void beginTeleport(Player player, DeathBackPoint point) {
        UUID uuid = player.getUniqueId();
        if (!teleporting.contains(uuid)) return;
        if (!player.isOnline()) {
            teleporting.remove(uuid);
            return;
        }
        if (point == null) {
            teleporting.remove(uuid);
            player.sendRichMessage("<dark_gray>[<green>SMP</green>]</dark_gray> <red>Du hast keinen ungenutzten Todespunkt.</red>");
            return;
        }

        Location death = point.toLocation();
        if (death == null) {
            teleporting.remove(uuid);
            player.sendRichMessage("<dark_gray>[<green>SMP</green>]</dark_gray> <red>Die Welt deines Todespunkts ist momentan nicht verfügbar. Dein /back bleibt gespeichert.</red>");
            return;
        }

        int radius = Math.max(1, plugin.configs().server().getInt("death-back.respawn-radius", 2000));
        startBackCountdown(player, point, death, radius);
    }

    private void startBackCountdown(Player player, DeathBackPoint point, Location death, int radius) {
        UUID uuid = player.getUniqueId();
        int delay = player.hasPermission("smpcore.bypass.delay") || player.hasPermission("smpcore.rtp.bypass.delay")
                ? 0 : Math.max(0, plugin.configs().menus().getInt("rtp.delay-seconds", 3));

        AtomicReference<Location> targetRef = new AtomicReference<>();
        AtomicBoolean searchFinished = new AtomicBoolean(false);
        AtomicBoolean teleportStarted = new AtomicBoolean(false);
        int[] remaining = {delay};

        rtpManager.findSafeLocationAround(player, death, radius, target -> {
            targetRef.set(target);
            searchFinished.set(true);
        });

        if (remaining[0] > 0) {
            plugin.sendRtpCountdownFeedback(player, remaining[0]--);
        } else {
            plugin.sendRtpSearchFeedback(player);
        }

        final BukkitTask[] holder = new BukkitTask[1];
        holder[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) {
                holder[0].cancel();
                teleportTasks.remove(uuid, holder[0]);
                teleporting.remove(uuid);
                return;
            }

            if (remaining[0] > 0) {
                plugin.sendRtpCountdownFeedback(player, remaining[0]--);
                return;
            }

            if (!searchFinished.get()) {
                plugin.sendRtpSearchFeedback(player);
                return;
            }

            if (!teleportStarted.compareAndSet(false, true)) return;
            holder[0].cancel();
            teleportTasks.remove(uuid, holder[0]);
            player.sendActionBar(Component.empty());

            Location target = targetRef.get();
            if (target == null) {
                teleporting.remove(uuid);
                player.sendRichMessage("<dark_gray>[<green>SMP</green>]</dark_gray> <red>Es konnte kein sicherer Ort gefunden werden. Dein /back bleibt verfügbar.</red>");
                return;
            }

            rtpManager.prepareDestination(target).thenCompose(prepared -> player.teleportAsync(target)).thenAccept(success -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!success) {
                    teleporting.remove(uuid);
                    player.sendRichMessage("<dark_gray>[<green>SMP</green>]</dark_gray> <red>Teleport fehlgeschlagen. Dein /back bleibt verfügbar.</red>");
                    return;
                }
                plugin.sendRtpSuccessFeedback(player);
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> consumeAfterTeleport(player, point));
            }));
        }, 20L, 20L);
        teleportTasks.put(uuid, holder[0]);
    }

    public void cancelTeleport(UUID playerId) {
        if (playerId == null) return;
        BukkitTask task = teleportTasks.remove(playerId);
        if (task != null) task.cancel();
        teleporting.remove(playerId);
        Player player = plugin.getServer().getPlayer(playerId);
        if (player != null && player.isOnline()) player.sendActionBar(Component.empty());
    }

    private String displayWorldName(Location location) {
        if (location == null || location.getWorld() == null) return "Unbekannt";
        return switch (location.getWorld().getEnvironment()) {
            case NETHER -> "Nether";
            case THE_END -> "End";
            default -> "Overworld";
        };
    }

    private void consumeAfterTeleport(Player player, DeathBackPoint point) {
        UUID uuid = player.getUniqueId();
        try {
            repository.deleteIfUnchanged(uuid, point.createdAt());
            finishOnMainThread(uuid, () -> {
                if (player.isOnline()) {
                    player.sendRichMessage("<dark_gray>[<green>SMP</green>]</dark_gray> <green>Du wurdest in die Nähe deines Todespunkts teleportiert.</green>");
                }
            });
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "Der verwendete /back-Punkt von " + player.getName() + " konnte nicht gelöscht werden.", exception);
            finishOnMainThread(uuid, () -> {
                if (player.isOnline()) {
                    player.sendRichMessage("<dark_gray>[<green>SMP</green>]</dark_gray> <yellow>Teleport erfolgreich, aber der /back-Punkt konnte nicht aus der Datenbank entfernt werden.</yellow>");
                }
            });
        }
    }

    private void finishOnMainThread(UUID uuid, Runnable action) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            teleporting.remove(uuid);
            action.run();
        });
    }
}
