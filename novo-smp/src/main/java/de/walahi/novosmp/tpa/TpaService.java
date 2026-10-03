package de.walahi.novosmp.tpa;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.tpa.TpaAccess;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public final class TpaService implements TpaAccess, Listener {
    private final NovoSMPPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, Request> requestsByTarget = new HashMap<>();
    private final Map<UUID, UUID> targetByRequester = new HashMap<>();
    private final Map<UUID, PendingTeleport> pendingTeleports = new HashMap<>();

    public TpaService(NovoSMPPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void request(Player requester, String[] args) {
        if (args.length != 1) {
            plugin.sendConfigured(requester, "messages.tpa-usage");
            return;
        }
        if (plugin.getStaffCommands() != null && plugin.getStaffCommands().isVanished(requester)) {
            plugin.sendConfigured(requester, "messages.tpa-vanish-blocked");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            plugin.sendConfigured(requester, "messages.tpa-player-not-found", "<player>", args[0]);
            return;
        }
        if (target.getUniqueId().equals(requester.getUniqueId())) {
            plugin.sendConfigured(requester, "messages.tpa-self");
            return;
        }
        if (!plugin.isSmpGameplayWorld(requester.getWorld()) || !plugin.isSmpGameplayWorld(target.getWorld())) {
            plugin.sendConfigured(requester, "messages.tpa-wrong-world");
            return;
        }

        if (hasPendingRequest(requester.getUniqueId(), target.getUniqueId())) {
            plugin.sendConfigured(requester, "messages.tpa-already-sent");
            return;
        }

        removeFor(requester.getUniqueId(), false);
        removeFor(target.getUniqueId(), true);
        int timeout = Math.max(5, plugin.configs().main().getInt("tpa.request-timeout-seconds", 60));
        BukkitTask expiry = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Request current = requestsByTarget.get(target.getUniqueId());
            if (current == null || !current.requesterId().equals(requester.getUniqueId())) return;
            removeFor(target.getUniqueId(), true);
            if (requester.isOnline()) plugin.sendConfigured(requester, "messages.tpa-expired-sender", "<player>", visibleName(requester, target));
            if (target.isOnline()) plugin.sendConfigured(target, "messages.tpa-expired-target", "<player>", visibleName(target, requester));
        }, timeout * 20L);

        Request request = new Request(requester.getUniqueId(), target.getUniqueId(), expiry);
        requestsByTarget.put(target.getUniqueId(), request);
        targetByRequester.put(requester.getUniqueId(), target.getUniqueId());

        plugin.sendConfigured(requester, "messages.tpa-sent", "<player>", visibleName(requester, target));
        plugin.playConfigured(requester, "sounds.tpa-request");
        plugin.sendConfigured(target, "messages.tpa-received", "<player>", visibleName(target, requester));
        plugin.playConfigured(target, "sounds.tpa-request");
        sendButtons(target, requester);
    }

    private boolean hasPendingRequest(UUID requesterId, UUID targetId) {
        UUID mappedTarget = targetByRequester.get(requesterId);
        if (!targetId.equals(mappedTarget)) return false;

        Request request = requestsByTarget.get(targetId);
        return request != null
                && request.requesterId().equals(requesterId)
                && request.targetId().equals(targetId);
    }

    private void sendButtons(Player target, Player requester) {
        Component prefix = miniMessage.deserialize(plugin.configs().messages().getString("messages.tpa-prefix", "<dark_gray>[<gold>TPA</gold>]</dark_gray> "));
        String introRaw = plugin.configs().messages().getString("messages.tpa-click-intro", "<gray>Klicke zum </gray>")
                .replace("<player>", visibleName(target, requester));
        Component intro = miniMessage.deserialize(introRaw);
        Component accept = miniMessage.deserialize(plugin.configs().messages().getString("messages.tpa-accept-button", "<green>[Annehmen]</green>"))
                .clickEvent(ClickEvent.runCommand("/tpaccept"))
                .hoverEvent(HoverEvent.showText(miniMessage.deserialize("<green>Teleportanfrage annehmen</green>")));
        Component deny = miniMessage.deserialize(plugin.configs().messages().getString("messages.tpa-deny-button", "<red>[Ablehnen]</red>"))
                .clickEvent(ClickEvent.runCommand("/tpdeny"))
                .hoverEvent(HoverEvent.showText(miniMessage.deserialize("<red>Teleportanfrage ablehnen</red>")));
        target.sendMessage(prefix.append(intro).append(accept).append(Component.space()).append(deny));
        if (isFloodgatePlayer(target)) {
            if (isAnonymousTo(target, requester)) {
                plugin.messages().smp(target, "<gray>Bedrock: <green>/tpaccept</green> oder <red>/tpdeny</red></gray>");
            } else {
                plugin.sendConfigured(target, "messages.tpa-bedrock-hint", "<player>", requester.getName());
            }
        }
    }

    @Override
    public void accept(Player target, String[] args) {
        Request request = find(target, args);
        if (request == null) return;
        Player requester = Bukkit.getPlayer(request.requesterId());
        if (requester == null || !requester.isOnline()) {
            removeFor(target.getUniqueId(), true);
            plugin.sendConfigured(target, "messages.tpa-player-left");
            return;
        }
        if (!plugin.isSmpGameplayWorld(requester.getWorld()) || !plugin.isSmpGameplayWorld(target.getWorld())) {
            removeFor(target.getUniqueId(), true);
            plugin.sendConfigured(target, "messages.tpa-wrong-world");
            return;
        }
        removeFor(target.getUniqueId(), true);
        plugin.sendConfigured(requester, "messages.tpa-accepted-sender", "<player>", visibleName(requester, target));
        beginTeleport(requester, target, target.getLocation().clone());
    }

    @Override
    public void deny(Player target, String[] args) {
        Request request = find(target, args);
        if (request == null) return;
        Player requester = Bukkit.getPlayer(request.requesterId());
        removeFor(target.getUniqueId(), true);
        plugin.sendConfigured(target, "messages.tpa-denied-target", "<player>", requester == null ? "Spieler" : visibleName(target, requester));
        if (requester != null && requester.isOnline()) {
            plugin.sendConfigured(requester, "messages.tpa-denied-sender", "<player>", visibleName(requester, target));
        }
    }

    @Override
    public void cancel(Player requester) {
        UUID targetId = targetByRequester.get(requester.getUniqueId());
        if (targetId == null) {
            plugin.sendConfigured(requester, "messages.tpa-nothing-to-cancel");
            return;
        }
        Player target = Bukkit.getPlayer(targetId);
        removeFor(requester.getUniqueId(), false);
        plugin.sendConfigured(requester, "messages.tpa-cancelled-sender");
        if (target != null && target.isOnline()) {
            plugin.sendConfigured(target, "messages.tpa-cancelled-target", "<player>", visibleName(target, requester));
        }
    }

    private Request find(Player target, String[] args) {
        Request request = requestsByTarget.get(target.getUniqueId());
        if (request == null) {
            plugin.sendConfigured(target, "messages.tpa-no-request");
            return null;
        }
        if (args.length > 1) {
            plugin.sendConfigured(target, "messages.tpa-accept-usage");
            return null;
        }
        if (args.length == 1) {
            Player requester = Bukkit.getPlayer(request.requesterId());
            if (requester == null || !requester.getName().equalsIgnoreCase(args[0])) {
                plugin.sendConfigured(target, "messages.tpa-no-request-from", "<player>", args[0]);
                return null;
            }
        }
        return request;
    }

    private void beginTeleport(Player requester, Player target, Location destination) {
        cancelPending(requester.getUniqueId(), false);
        boolean bypass = requester.hasPermission("smpcore.bypass.delay")
                || requester.hasPermission("smpcore.tpa.bypass.delay")
                || (plugin.configs().main().getBoolean("tpa.friends-bypass-teleport-delay", true)
                    && plugin.friendManager() != null
                    && plugin.friendManager().repository().areFriends(requester.getUniqueId(), target.getUniqueId()));
        int delay = Math.max(0, plugin.configs().main().getInt("tpa.delay-seconds", 3));
        if (delay == 0 || bypass) {
            finishTeleport(requester, target, destination);
            return;
        }
        final int[] remaining = {delay};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!requester.isOnline() || !target.isOnline()) {
                cancelPending(requester.getUniqueId(), false);
                return;
            }
            if (remaining[0] <= 0) {
                cancelPending(requester.getUniqueId(), false);
                requester.sendActionBar(Component.empty());
                finishTeleport(requester, target, destination);
                return;
            }
            String raw = plugin.configs().main().getString("tpa.actionbar", "<yellow>Teleport in <green>%seconds%s</green>...")
                    .replace("%seconds%", Integer.toString(remaining[0]));
            requester.sendActionBar(miniMessage.deserialize(raw));
            plugin.playConfigured(requester, "sounds.countdown");
            remaining[0]--;
        }, 0L, 20L);
        pendingTeleports.put(requester.getUniqueId(), new PendingTeleport(task, requester.getLocation().clone()));
    }

    private void finishTeleport(Player requester, Player target, Location destination) {
        if (!requester.isOnline() || !target.isOnline()) return;
        requester.teleportAsync(destination).thenAccept(success -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (success) {
                plugin.sendConfigured(requester, "messages.tpa-teleported");
                plugin.playConfigured(requester, "sounds.success");
            } else {
                plugin.sendConfigured(requester, "messages.tpa-teleport-failed");
            }
        }));
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        PendingTeleport pending = pendingTeleports.get(event.getPlayer().getUniqueId());
        if (pending == null || !plugin.configs().main().getBoolean("tpa.cancel-on-move", true)) return;
        if (event.getTo() == null) return;
        Location from = pending.start();
        Location to = event.getTo();
        if (from.getWorld() != to.getWorld() || from.distanceSquared(to) > 0.01D) {
            cancelPending(event.getPlayer().getUniqueId(), true);
        }
    }

    @Override
    public List<String> tabComplete(Player player, String commandName, String prefix) {
        String lowered = prefix.toLowerCase(Locale.ROOT);
        if (commandName.equals("tpa")) {
            List<String> matches = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!online.getUniqueId().equals(player.getUniqueId())
                        && !isAnonymousTo(player, online)
                        && online.getName().toLowerCase(Locale.ROOT).startsWith(lowered)) {
                    matches.add(online.getName());
                }
            }
            return matches;
        }
        Request request = requestsByTarget.get(player.getUniqueId());
        if (request == null) return List.of();
        Player requester = Bukkit.getPlayer(request.requesterId());
        return requester != null
                && !isAnonymousTo(player, requester)
                && requester.getName().toLowerCase(Locale.ROOT).startsWith(lowered)
                ? List.of(requester.getName()) : List.of();
    }

    @Override
    public void handleQuit(Player player) {
        removeFor(player.getUniqueId(), false);
        removeFor(player.getUniqueId(), true);
        cancelPending(player.getUniqueId(), false);
    }

    @Override
    public void shutdown() {
        for (Request request : new ArrayList<>(requestsByTarget.values())) request.expiryTask().cancel();
        requestsByTarget.clear();
        targetByRequester.clear();
        for (PendingTeleport pending : pendingTeleports.values()) pending.task().cancel();
        pendingTeleports.clear();
    }

    private void cancelPending(UUID uuid, boolean notify) {
        PendingTeleport pending = pendingTeleports.remove(uuid);
        if (pending == null) return;
        pending.task().cancel();
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            player.sendActionBar(Component.empty());
            if (notify) plugin.sendConfigured(player, "messages.tpa-teleport-cancelled");
        }
    }

    private void removeFor(UUID playerId, boolean playerIsTarget) {
        Request request;
        if (playerIsTarget) {
            request = requestsByTarget.remove(playerId);
        } else {
            UUID targetId = targetByRequester.remove(playerId);
            request = targetId == null ? null : requestsByTarget.remove(targetId);
        }
        if (request != null) {
            request.expiryTask().cancel();
            targetByRequester.remove(request.requesterId());
        }
    }

    /**
     * Resolves a player name exactly as this viewer is allowed to see it. MiniMessage tags are
     * intentional here: old messages.yml entries often wrap <player> in yellow, so the explicit
     * gray tag keeps an anonymous identity gray without requiring a config migration.
     */
    private String visibleName(Player viewer, Player subject) {
        return isAnonymousTo(viewer, subject) ? "<gray>Anonym</gray>" : subject.getName();
    }

    private boolean isAnonymousTo(Player viewer, Player subject) {
        return viewer != null
                && subject != null
                && plugin.invisibilityAnonymity() != null
                && plugin.invisibilityAnonymity().shouldAnonymize(viewer, subject);
    }

    private boolean isFloodgatePlayer(Player player) {
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Object result = apiClass.getMethod("isFloodgatePlayer", UUID.class).invoke(api, player.getUniqueId());
            return result instanceof Boolean bool && bool;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private record Request(UUID requesterId, UUID targetId, BukkitTask expiryTask) { }
    private static final class PendingTeleport {
        private final BukkitTask task;
        private final Location start;

        private PendingTeleport(BukkitTask task, Location start) {
            this.task = task;
            this.start = start;
        }

        private BukkitTask task() { return task; }
        private Location start() { return start; }
    }
}
