package de.walahi.novosmp.feature;

import de.walahi.novosmp.NovoSMPPlugin;
import io.papermc.paper.event.entity.WaterBottleSplashEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes invisibility a viewer-specific identity mechanic without hiding the actual entity.
 * While invisible, the target is anonymous to non-friends unless that exact viewer has revealed
 * the target with a thrown water bottle during the current invisibility session. Friends always
 * keep the target's normal identity, while the invisible player still sees their own chat anonymously.
 */
public final class InvisibilityAnonymityService implements Listener {
    private static final String ANONYMOUS_TEAM = "nv_anon";
    private final NovoSMPPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Set<RevealKey> waterReveals = ConcurrentHashMap.newKeySet();
    private BukkitTask refreshTask;

    private record RevealKey(UUID viewer, UUID target) { }

    public InvisibilityAnonymityService(NovoSMPPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stopTask();
        // Rank-/Tab-Teams are refreshed every second. A one-tick identity pass keeps the
        // viewer-specific hidden team authoritative without changing entity visibility/combat.
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, 1L, 1L);
    }

    public void stop() {
        stopTask();
        waterReveals.clear();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Scoreboard board = viewer.getScoreboard();
            Team hidden = board.getTeam(ANONYMOUS_TEAM);
            if (hidden == null) continue;
            for (String entry : Set.copyOf(hidden.getEntries())) {
                Player target = Bukkit.getPlayerExact(entry);
                if (target != null) plugin.getRankManager().restoreEntry(target, board);
                else hidden.removeEntry(entry);
            }
            if (hidden.getEntries().isEmpty()) hidden.unregister();
        }
    }

    public boolean shouldAnonymize(Player viewer, Player target) {
        return shouldAnonymize(viewer, target, target != null
                && target.hasPotionEffect(PotionEffectType.INVISIBILITY));
    }

    public boolean shouldAnonymize(Player viewer, Player target, boolean targetInvisible) {
        if (!enabled() || viewer == null || target == null || !targetInvisible) return false;

        // Friends always keep the real identity. The target itself remains anonymous in its own
        // chat view, as requested, so only a different viewer can receive the friend bypass.
        if (!viewer.getUniqueId().equals(target.getUniqueId()) && plugin.friendManager() != null) {
            try {
                if (plugin.friendManager().areFriends(viewer.getUniqueId(), target.getUniqueId())) return false;
            } catch (RuntimeException ignored) {
                // If friendship data is temporarily unavailable, keep the safer anonymous view.
            }
        }

        return !waterReveals.contains(new RevealKey(viewer.getUniqueId(), target.getUniqueId()));
    }

    public Component anonymousName() {
        String configured = plugin.configs().main().getString(
                "gameplay.invisibility-anonymity.chat-name", "<gray>Anonym</gray>");
        return miniMessage.deserialize(configured == null ? "<gray>Anonym</gray>" : configured);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWaterBottleSplash(WaterBottleSplashEvent event) {
        if (!enabled() || !(event.getPotion().getShooter() instanceof Player thrower)) return;
        boolean changed = false;
        @SuppressWarnings("deprecation")
        var affected = event.getAffectedEntities();
        for (LivingEntity entity : affected) {
            if (!(entity instanceof Player target) || target.getUniqueId().equals(thrower.getUniqueId())) continue;
            if (!target.hasPotionEffect(PotionEffectType.INVISIBILITY)) continue;
            changed |= waterReveals.add(new RevealKey(thrower.getUniqueId(), target.getUniqueId()));
        }
        if (changed) refreshViewer(thrower);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPotionEffect(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || !PotionEffectType.INVISIBILITY.equals(event.getModifiedType())) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            // Adding/replacing/removing invisibility always starts or ends one identity session.
            waterReveals.removeIf(key -> key.target().equals(player.getUniqueId()));
            refreshTarget(player);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> refreshViewer(event.getPlayer()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        waterReveals.removeIf(key -> key.viewer().equals(id) || key.target().equals(id));
    }

    private void refreshAll() {
        if (!enabled()) {
            restoreAnonymousTeams();
            waterReveals.clear();
            return;
        }
        ArrayList<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        Set<UUID> onlineIds = online.stream().map(Player::getUniqueId).collect(java.util.stream.Collectors.toSet());
        waterReveals.removeIf(key -> !onlineIds.contains(key.viewer()) || !onlineIds.contains(key.target())
                || !hasInvisibility(key.target()));

        java.util.LinkedHashMap<UUID, Player> candidates = new java.util.LinkedHashMap<>();
        for (Player target : online) {
            if (target.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
                candidates.put(target.getUniqueId(), target);
            }
        }
        for (Player viewer : online) {
            Team team = viewer.getScoreboard().getTeam(ANONYMOUS_TEAM);
            if (team == null) continue;
            for (String entry : team.getEntries()) {
                Player target = Bukkit.getPlayerExact(entry);
                if (target != null) candidates.put(target.getUniqueId(), target);
            }
        }
        if (candidates.isEmpty()) return;

        java.util.List<Player> targets = new ArrayList<>(candidates.values());
        for (Player viewer : online) refreshViewer(viewer, targets);
    }

    private void refreshViewer(Player viewer) {
        refreshViewer(viewer, new ArrayList<>(Bukkit.getOnlinePlayers()));
    }

    private void refreshViewer(Player viewer, java.util.List<Player> online) {
        if (viewer == null || !viewer.isOnline()) return;
        Scoreboard board = viewer.getScoreboard();
        Team anonymous = board.getTeam(ANONYMOUS_TEAM);

        for (Player target : online) {
            if (target.getUniqueId().equals(viewer.getUniqueId())) continue;
            boolean hideName = shouldAnonymize(viewer, target);
            String entry = target.getName();
            if (hideName) {
                if (anonymous == null) anonymous = ensureAnonymousTeam(board);
                if (!anonymous.hasEntry(entry)) anonymous.addEntry(entry);
            } else if (anonymous != null && anonymous.hasEntry(entry)) {
                plugin.getRankManager().restoreEntry(target, board);
            }
        }
    }

    private void refreshTarget(Player target) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.getUniqueId().equals(target.getUniqueId())) refreshViewer(viewer);
        }
    }

    private Team ensureAnonymousTeam(Scoreboard board) {
        Team team = board.getTeam(ANONYMOUS_TEAM);
        if (team == null) team = board.registerNewTeam(ANONYMOUS_TEAM);
        if (!Component.empty().equals(team.prefix())) team.prefix(Component.empty());
        if (!Component.empty().equals(team.suffix())) team.suffix(Component.empty());
        if (team.getOption(Team.Option.NAME_TAG_VISIBILITY) != Team.OptionStatus.NEVER) {
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        }
        return team;
    }

    private void restoreAnonymousTeams() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Scoreboard board = viewer.getScoreboard();
            Team hidden = board.getTeam(ANONYMOUS_TEAM);
            if (hidden == null) continue;
            for (String entry : Set.copyOf(hidden.getEntries())) {
                Player target = Bukkit.getPlayerExact(entry);
                if (target != null) plugin.getRankManager().restoreEntry(target, board);
                else hidden.removeEntry(entry);
            }
        }
    }

    private boolean hasInvisibility(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        return player != null && player.hasPotionEffect(PotionEffectType.INVISIBILITY);
    }

    private boolean enabled() {
        return plugin.configs().main().getBoolean("gameplay.invisibility-anonymity.enabled", true);
    }

    private void stopTask() {
        if (refreshTask != null) refreshTask.cancel();
        refreshTask = null;
    }
}
