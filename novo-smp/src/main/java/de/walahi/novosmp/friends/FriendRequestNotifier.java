package de.walahi.novosmp.friends;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.friends.FriendRepository;
import de.walahi.smpcore.friends.FriendRequest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Polls and deduplicates incoming friend request notifications. */
public final class FriendRequestNotifier {
    private final NovoSMPPlugin plugin;
    private final FriendRepository repository;
    private final FriendConfiguration config;
    private final Set<String> shownRequests = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean pollRunning = new AtomicBoolean();
    private BukkitTask task;

    public FriendRequestNotifier(NovoSMPPlugin plugin, FriendRepository repository, FriendConfiguration config) {
        this.plugin = plugin;
        this.repository = repository;
        this.config = config;
    }

    public void start() {
        long period = Math.max(20L, config.longValue("settings.request-poll-ticks", 20L));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::poll, period, period);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
        shownRequests.clear();
        pollRunning.set(false);
    }

    public void notifyNow(Player player) {
        if (player == null || !player.isOnline()) return;
        notifyRequests(player, repository.incomingRequests(player.getUniqueId()));
    }

    private void poll() {
        List<UUID> targets = Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).toList();
        if (targets.isEmpty() || !pollRunning.compareAndSet(false, true)) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<UUID, List<FriendRequest>> requests;
            try {
                requests = repository.incomingRequests(targets);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Freundesanfragen konnten nicht als Batch geladen werden: "
                        + exception.getMessage());
                pollRunning.set(false);
                return;
            }
            if (!plugin.isEnabled()) {
                pollRunning.set(false);
                return;
            }
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        Set<String> activeRequests = new HashSet<>();
                        for (UUID target : targets) {
                            Player player = Bukkit.getPlayer(target);
                            if (player == null || !player.isOnline()) continue;
                            notifyRequests(player, requests.getOrDefault(target, List.of()), activeRequests);
                        }
                        shownRequests.retainAll(activeRequests);
                    } finally {
                        pollRunning.set(false);
                    }
                });
            } catch (RuntimeException exception) {
                pollRunning.set(false);
                if (plugin.isEnabled()) {
                    plugin.getLogger().warning("Freundesanfragen konnten nicht an den Hauptthread übergeben werden: "
                            + exception.getMessage());
                }
            }
        });
    }

    private void notifyRequests(Player player, List<FriendRequest> requests) {
        notifyRequests(player, requests, null);
    }

    private void notifyRequests(Player player, List<FriendRequest> requests, Set<String> activeRequests) {
        if (player == null || !player.isOnline()) return;
        for (FriendRequest request : requests) {
            String key = player.getUniqueId() + ":" + request.senderUuid() + ":" + request.createdAt();
            if (activeRequests != null) activeRequests.add(key);
            if (!shownRequests.add(key)) continue;
            sendPrompt(player, request.senderName());
        }
    }

    private void sendPrompt(Player target, String senderName) {
        Component prefix = config.component("messages.prefix",
                "<dark_gray>[<light_purple>Freunde</light_purple>]</dark_gray> ");
        Component info = config.component("messages.request-prompt",
                "<light_purple><player></light_purple><gray> hat dir eine Freundesanfrage gesendet.</gray>",
                Map.of("player", senderName));
        Component accept = config.component("messages.request-accept-label", "<green>[Annehmen]</green>")
                .clickEvent(ClickEvent.runCommand("/friend accept " + senderName))
                .hoverEvent(HoverEvent.showText(config.component("messages.request-accept-hover",
                        "<green>Freundesanfrage annehmen</green>")));
        Component deny = config.component("messages.request-deny-label", "<red>[Ablehnen]</red>")
                .clickEvent(ClickEvent.runCommand("/friend deny " + senderName))
                .hoverEvent(HoverEvent.showText(config.component("messages.request-deny-hover",
                        "<red>Freundesanfrage ablehnen</red>")));
        target.sendMessage(prefix.append(info));
        target.sendMessage(config.component("messages.request-click-prefix", "<gray>Klicke: </gray>")
                .append(accept).append(Component.space()).append(deny));
    }
}
