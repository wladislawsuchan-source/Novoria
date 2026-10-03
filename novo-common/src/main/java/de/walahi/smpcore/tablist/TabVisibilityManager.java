package de.walahi.smpcore.tablist;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.ranks.RankManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Trennt ausschließlich die Tab-Einträge. Spieler-Entities werden nicht versteckt. */
public final class TabVisibilityManager implements Listener {
    private final SMPCorePlugin plugin;
    private final RankManager rankManager;
    private final Set<UUID> joiningPlayers = new HashSet<>();
    private final Map<UUID, UUID> duelOpponents = new HashMap<>();
    private final Map<VisibilityPair, Boolean> appliedVisibility = new HashMap<>();
    private BukkitTask task;
    private Method listPlayerMethod;
    private Method unlistPlayerMethod;
    private Method isListedMethod;

    private record VisibilityPair(UUID viewer, UUID target) {}

    public TabVisibilityManager(SMPCorePlugin plugin, RankManager rankManager) {
        this.plugin = plugin;
        this.rankManager = rankManager;
        try {
            listPlayerMethod = Player.class.getMethod("listPlayer", Player.class);
            unlistPlayerMethod = Player.class.getMethod("unlistPlayer", Player.class);
            try {
                isListedMethod = Player.class.getMethod("isListed", Player.class);
            } catch (NoSuchMethodException ignored) {
                // Older Paper versions cannot validate changes made outside this manager.
            }
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning("Paper listPlayer/unlistPlayer wurde nicht gefunden. Tablist-Trennung ist deaktiviert.");
        }
    }

    public boolean isAvailable() {
        return listPlayerMethod != null && unlistPlayerMethod != null;
    }

    /** Gives duel players a private two-person tablist while SMP viewers still see both duelists. */
    public void setDuelPair(Player first, Player second) {
        if (first == null || second == null) return;
        duelOpponents.put(first.getUniqueId(), second.getUniqueId());
        duelOpponents.put(second.getUniqueId(), first.getUniqueId());
        ensureTask();
        if (enabled()) syncAll();
    }

    public void clearDuelPair(UUID first, UUID second) {
        if (first != null) duelOpponents.remove(first);
        if (second != null) duelOpponents.remove(second);
        if (enabled()) syncAll();
        else restoreAllListed();
    }

    public void start() {
        stop();
        if (enabled()) syncAll();
        ensureTask();
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        joiningPlayers.clear();
        duelOpponents.clear();
        appliedVisibility.clear();
        if (isAvailable()) {
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                for (Player target : Bukkit.getOnlinePlayers()) applyVisibility(viewer, target, true);
            }
        }
        appliedVisibility.clear();
        rankManager.applyAll();
    }

    public void refreshNow() {
        ensureTask();
        if (!enabled()) {
            rankManager.applyAll();
            return;
        }
        syncAll();
    }

    /**
     * Wird direkt nach dem abgeschlossenen Hub-Teleport aufgerufen. Erst jetzt wird der
     * neue Spieler endgültig für seine richtige Gruppe freigegeben.
     */
    public void finishJoin(Player player) {
        joiningPlayers.remove(player.getUniqueId());
        if (!player.isOnline()) return;
        syncPlayer(player);
        rankManager.applyAll();
    }

    private boolean enabled() {
        return isAvailable() && (plugin.configs().scoreboards().getBoolean("tablist.separate-hub-and-smp", true)
                || !duelOpponents.isEmpty()
                || plugin.getStaffCommands() != null && plugin.getStaffCommands().hasOnlineDndPlayers());
    }

    private void ensureTask() {
        if (task != null) return;
        // This task owns the one-second rank reconcile even when TAB separation is disabled.
        // Visibility pairs are only checked while the visibility feature is active.
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (enabled()) syncAll(false);
            rankManager.applyAll();
        }, 5L, 20L);
    }

    private void restoreAllListed() {
        if (!isAvailable()) return;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            for (Player target : Bukkit.getOnlinePlayers()) applyVisibility(viewer, target, true);
        }
        rankManager.applyAll();
    }

    private void syncAll() {
        syncAll(true);
    }

    private void syncAll(boolean refreshRanks) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            for (Player target : Bukkit.getOnlinePlayers()) {
                applyVisibility(viewer, target, shouldList(viewer, target));
            }
        }
        // listPlayer kann die clientseitige List-Order zurücksetzen. Deshalb danach sortieren.
        if (refreshRanks) rankManager.applyAll();
    }

    private void syncPlayer(Player player) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            applyVisibility(other, player, shouldList(other, player));
            applyVisibility(player, other, shouldList(player, other));
        }
    }

    private boolean shouldList(Player viewer, Player target) {
        if (viewer.equals(target)) return true;
        if (plugin.getStaffCommands() != null && plugin.getStaffCommands().isDnd(target)
                && !plugin.getStaffCommands().canSeeDnd(viewer, target)) return false;
        if (!plugin.configs().scoreboards().getBoolean("tablist.separate-hub-and-smp", true)
                && duelOpponents.isEmpty()) return true;

        UUID duelOpponent = duelOpponents.get(viewer.getUniqueId());
        if (duelOpponent != null) {
            return target.getUniqueId().equals(duelOpponent);
        }
        if (duelOpponents.containsKey(target.getUniqueId())) {
            if (!isSmp(viewer)) return false;
            if (plugin.getStaffCommands() != null && plugin.getStaffCommands().isVanished(target)) {
                return plugin.getStaffCommands().canSeeVanished(viewer, target);
            }
            return true;
        }

        // Ein frisch joinender Spieler wird bis zum abgeschlossenen Hub-Teleport als Hub-Spieler
        // behandelt. So taucht er niemals vorübergehend in der SMP-Tabliste auf.
        boolean viewerSmp = isJoining(viewer) ? false : isSmp(viewer);
        boolean targetSmp = isJoining(target) ? false : isSmp(target);
        if (viewerSmp != targetSmp) return false;
        if (plugin.getStaffCommands() != null && plugin.getStaffCommands().isVanished(target)) {
            return plugin.getStaffCommands().canSeeVanished(viewer, target);
        }
        return true;
    }

    private boolean isJoining(Player player) {
        return joiningPlayers.contains(player.getUniqueId());
    }

    private void applyVisibility(Player viewer, Player target, boolean listed) {
        VisibilityPair pair = new VisibilityPair(viewer.getUniqueId(), target.getUniqueId());
        Boolean applied = appliedVisibility.get(pair);
        if (applied != null && applied == listed && isActuallyListed(viewer, target, listed)) return;
        try {
            if (listed) listPlayerMethod.invoke(viewer, target);
            else unlistPlayerMethod.invoke(viewer, target);
            appliedVisibility.put(pair, listed);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            // Join/Quit und gleichzeitige Vanish-Updates können Paper kurzzeitig in einen
            // ungültigen Verbindungszustand bringen. Der periodische Sync korrigiert das.
            if (plugin.configs().main().getBoolean("debug.tablist-visibility", false)) {
                plugin.getLogger().warning("Tablist-Sichtbarkeit konnte nicht gesetzt werden ("
                        + exception.getClass().getSimpleName() + ").");
            }
        }
    }

    private boolean isActuallyListed(Player viewer, Player target, boolean expected) {
        if (isListedMethod == null) return true;
        try {
            return (boolean) isListedMethod.invoke(viewer, target) == expected;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            // If the read fails, retry the write instead of trusting a potentially stale cache.
            return false;
        }
    }

    private void invalidateVisibility(UUID playerId) {
        appliedVisibility.keySet().removeIf(pair -> pair.viewer().equals(playerId) || pair.target().equals(playerId));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        invalidateVisibility(event.getPlayer().getUniqueId());
        if (!enabled()) return;

        Player joining = event.getPlayer();
        joiningPlayers.add(joining.getUniqueId());

        // Sofort im frühestmöglichen Join-Event einsortieren, bevor andere Plugin-Logik läuft.
        // Bei aktiviertem Hub-Join gilt der Spieler während des Teleports bereits als Hub-Spieler.
        syncPlayer(joining);
        rankManager.applyAll();

        // Sicherheits-Syncs für den Fall, dass Paper/andere Plugins die List-Flags beim Join erneut setzen.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (joining.isOnline()) syncPlayer(joining);
        });
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!joining.isOnline()) return;
            syncPlayer(joining);
            rankManager.applyAll();
        }, 1L);

        // Falls der automatische Hub-Teleport deaktiviert ist, ist die Join-Welt bereits final.
        if (!plugin.configs().main().getBoolean("settings.send-to-hub-on-join", true)) {
            Bukkit.getScheduler().runTask(plugin, () -> finishJoin(joining));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (isJoining(player)) return; // Der Hub-Join wird über finishJoin() freigegeben.
        syncPlayer(player);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            syncPlayer(player);
            rankManager.applyAll();
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        joiningPlayers.remove(event.getPlayer().getUniqueId());
        invalidateVisibility(event.getPlayer().getUniqueId());
        Bukkit.getScheduler().runTask(plugin, rankManager::applyAll);
    }

    private boolean isSmp(Player player) {
        // NovoSMP ist ein eigener Paper-Server. smp_spawn, smp_world,
        // smp_nether und smp_end gehören deshalb immer zur selben Tab-Gruppe.
        return plugin.isSmpGameplayWorld(player.getWorld());
    }
}
