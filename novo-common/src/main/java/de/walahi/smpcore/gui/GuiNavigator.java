package de.walahi.smpcore.gui;

import de.walahi.smpcore.SMPCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.server.PluginDisableEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Reusable GUI navigation stack.
 * 11.9.9 adds optional E/ESC back navigation for SMPCore GUIs.
 */
public final class GuiNavigator implements Listener {
    private final SMPCorePlugin plugin;
    private final Map<UUID, Deque<Destination>> stacks = new ConcurrentHashMap<>();
    private final Set<UUID> transitions = ConcurrentHashMap.newKeySet();
    private final Set<UUID> blockedReopen = ConcurrentHashMap.newKeySet();

    public GuiNavigator(SMPCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void resetAndOpen(Player player, Destination destination) {
        Deque<Destination> stack = new ArrayDeque<>();
        stack.addLast(destination);
        stacks.put(player.getUniqueId(), stack);
        openDestination(player, destination);
    }

    public void seedAndOpen(Player player, List<Destination> destinations) {
        Deque<Destination> stack = new ArrayDeque<>(destinations);
        if (stack.isEmpty()) {
            player.closeInventory();
            stacks.remove(player.getUniqueId());
            return;
        }
        stacks.put(player.getUniqueId(), stack);
        openDestination(player, stack.getLast());
    }

    public void openChild(Player player, Destination destination) {
        Deque<Destination> stack = stacks.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayDeque<>());
        stack.addLast(destination);
        openDestination(player, destination);
    }

    public void replaceCurrent(Player player, Destination destination) {
        Deque<Destination> stack = stacks.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayDeque<>());
        if (!stack.isEmpty()) stack.removeLast();
        stack.addLast(destination);
        openDestination(player, destination);
    }

    public void back(Player player) {
        Deque<Destination> stack = stacks.get(player.getUniqueId());
        if (stack == null || stack.isEmpty()) {
            player.closeInventory();
            return;
        }
        stack.removeLast();
        if (stack.isEmpty()) {
            stacks.remove(player.getUniqueId());
            player.closeInventory();
            return;
        }
        openDestination(player, stack.getLast());
    }

    /** Temporarily suppresses E/ESC back-navigation while an external input UI (for example a virtual sign) is open. */
    public void setExternalInput(Player player, boolean active) {
        UUID id = player.getUniqueId();
        if (active) blockedReopen.add(id);
        else blockedReopen.remove(id);
    }

    public void clear(Player player) {
        UUID id = player.getUniqueId();
        stacks.remove(id);
        transitions.remove(id);
        blockedReopen.remove(id);
    }

    private void openDestination(Player player, Destination destination) {
        UUID id = player.getUniqueId();
        transitions.add(id);
        destination.open(player);
        Bukkit.getScheduler().runTask(plugin, () -> transitions.remove(id));
    }

    private boolean closeNavigationEnabled() {
        return plugin.configs().main().getBoolean("auction-house.close-navigation.enabled", true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof GuiHolder)) return;
        if (!closeNavigationEnabled()) return;

        UUID id = player.getUniqueId();
        if (transitions.contains(id) || blockedReopen.contains(id)) return;

        Deque<Destination> stack = stacks.get(id);
        if (stack == null || stack.isEmpty()) return;

        // Closing the root menu should really close the GUI.
        if (stack.size() <= 1) {
            stacks.remove(id);
            return;
        }

        stack.removeLast();
        Destination previous = stack.peekLast();
        if (previous == null) {
            stacks.remove(id);
            return;
        }

        long delay = Math.max(1L, plugin.configs().main().getLong("auction-house.close-navigation.reopen-delay-ticks", 1L));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || blockedReopen.contains(id)) return;
            Deque<Destination> current = stacks.get(id);
            if (current == null || current.isEmpty() || current.peekLast() != previous) return;
            openDestination(player, previous);
        }, delay);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTeleport(PlayerTeleportEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        blockedReopen.add(id);
        stacks.remove(id);
        Bukkit.getScheduler().runTaskLater(plugin, () -> blockedReopen.remove(id), 2L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer());
    }

    @EventHandler
    public void onKick(PlayerKickEvent event) {
        clear(event.getPlayer());
    }

    @EventHandler
    public void onDisable(PluginDisableEvent event) {
        if (event.getPlugin() == plugin) {
            stacks.clear();
            transitions.clear();
            blockedReopen.clear();
        }
    }

    public record Destination(Consumer<Player> opener) {
        public Destination {
            if (opener == null) throw new IllegalArgumentException("opener darf nicht null sein");
        }

        public void open(Player player) {
            opener.accept(player);
        }
    }
}
