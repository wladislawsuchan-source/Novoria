package de.walahi.novosmp.combat;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.stats.StatsAccess;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.GameMode;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Timestamp-based PvP tagging, display, command policy and logout lifecycle. */
public final class CombatManager implements Listener {
    public static final String FINALIZED_DEATH_METADATA = "novosmp-finalized-combat-death";
    private record ExplosiveCredit(UUID attacker, String name, long at) { }

    private final NovoSMPPlugin plugin;
    private final CombatRelationshipService relationships;
    private final ValidPlayerKillService kills;
    private final CombatLogoutService logout;
    private final Map<UUID, CombatEntry> entries = new HashMap<>();
    private final Map<UUID, ExplosiveCredit> explosives = new HashMap<>();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private Set<String> blockedCommands = Set.of();
    private long durationMillis;
    private long dummyDurationMillis;
    private boolean actionbarEnabled;
    private String actionbarText;
    private String commandBlockedText;
    private long updateTicks;
    private BukkitTask updateTask;

    public CombatManager(NovoSMPPlugin plugin, CombatRelationshipService relationships, StatsAccess stats) {
        this.plugin = plugin;
        this.relationships = relationships;
        this.kills = new ValidPlayerKillService(plugin, stats);
        this.logout = new CombatLogoutService(plugin, kills, relationships);
        reload();
    }

    public void start() {
        logout.start();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startUpdateTask();
    }

    public void reload() {
        durationMillis = Math.max(1L, plugin.configs().server().getLong("combat.duration-seconds", 20L)) * 1000L;
        dummyDurationMillis = Math.max(1L,
                plugin.configs().server().getLong("combat.dummy-duration-seconds", 30L)) * 1000L;
        actionbarEnabled = plugin.configs().server().getBoolean("combat.actionbar.enabled", true);
        actionbarText = plugin.configs().server().getString("combat.actionbar.text",
                "<red>⚔ Combat: <yellow>%seconds%s</yellow></red>");
        commandBlockedText = plugin.configs().server().getString("combat.messages.command-blocked",
                "<dark_gray>[<red>Combat</red>]</dark_gray> <red>Dieser Befehl ist im Kampf noch <yellow>%seconds%s</yellow> gesperrt.</red>");
        updateTicks = Math.max(1L, plugin.configs().server().getLong("combat.actionbar.update-ticks", 10L));
        Set<String> configured = new HashSet<>();
        for (String command : plugin.configs().server().getStringList("combat.blocked-commands")) {
            String normalized = normalizeCommand(command);
            if (!normalized.isEmpty()) configured.add(normalized);
        }
        blockedCommands = Set.copyOf(configured);
        if (updateTask != null) startUpdateTask();
    }

    public void shutdown() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        for (UUID playerId : new HashSet<>(entries.keySet())) clear(playerId);
        explosives.clear();
        logout.shutdown();
    }

    public boolean isInCombat(UUID playerId) {
        CombatEntry entry = entries.get(playerId);
        if (entry == null) return false;
        if (entry.active(System.currentTimeMillis())) return true;
        entries.remove(playerId);
        return false;
    }

    public long remainingMillis(UUID playerId) {
        CombatEntry entry = entries.get(playerId);
        return entry == null ? 0L : Math.max(0L, entry.combatUntil() - System.currentTimeMillis());
    }

    public UUID lastOpponent(UUID playerId) {
        CombatEntry entry = entries.get(playerId);
        return entry != null && entry.active(System.currentTimeMillis()) ? entry.lastOpponent() : null;
    }

    /** Duel lifecycle bridge; only lethal combat conclusions call this method. */
    public void publishDuelKill(UUID killerId, String killerName, UUID victimId,
                                String victimName, org.bukkit.Location location) {
        kills.publish(killerId, killerName, victimId, victimName, ValidKillCause.DUEL, location);
    }

    public void tag(Player first, Player second) {
        if (first == null || second == null || first.getUniqueId().equals(second.getUniqueId())) return;
        long until = System.currentTimeMillis() + durationMillis;
        entries.put(first.getUniqueId(), new CombatEntry(until, second.getUniqueId()));
        entries.put(second.getUniqueId(), new CombatEntry(until, first.getUniqueId()));
        plugin.cancelCombatEscape(first.getUniqueId());
        plugin.cancelCombatEscape(second.getUniqueId());
    }

    public void clear(UUID playerId) {
        if (playerId == null || entries.remove(playerId) == null) return;
        Player player = plugin.getServer().getPlayer(playerId);
        if (player != null && player.isOnline()) player.sendActionBar(Component.empty());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPvpDamage(EntityDamageByEntityEvent event) {
        rememberExplosiveOwner(event);
        if (!(event.getEntity() instanceof Player victim) || isDummy(victim) || event.getFinalDamage() <= 0D) return;
        Player attacker = causingPlayer(event);
        if (attacker == null || isDummy(attacker) || attacker.getUniqueId().equals(victim.getUniqueId())) return;
        if (relationships.blocksClanCombat(attacker.getUniqueId(), victim.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (!eligible(attacker) || !eligible(victim)) return;
        if (!relationships.shouldTag(attacker.getUniqueId(), victim.getUniqueId())) return;
        tag(attacker, victim);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!isInCombat(player.getUniqueId())) return;
        String command = normalizeCommand(event.getMessage());
        if (!blockedCommands.contains(command)) return;
        event.setCancelled(true);
        long seconds = secondsCeil(remainingMillis(player.getUniqueId()));
        player.sendMessage(miniMessage.deserialize(commandBlockedText.replace("%seconds%", Long.toString(seconds))));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (isInCombat(player.getUniqueId()) && !plugin.isPlayerInDuel(player.getUniqueId())) {
            logout.createFor(player, dummyDurationMillis);
        }
        entries.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        logout.handleJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        if (isDummy(victim)) return;
        clear(victim.getUniqueId());
        if (logout.consumePendingDeath(event)) return;
        if (plugin.isPlayerInDuel(victim.getUniqueId())) return;
        Player killer = victim.getKiller();
        if (killer == null || isDummy(killer)) return;
        kills.publish(killer.getUniqueId(), killer.getName(), victim.getUniqueId(), victim.getName(),
                ValidKillCause.NORMAL_PVP, victim.getLocation());
    }

    private void startUpdateTask() {
        if (updateTask != null) updateTask.cancel();
        updateTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateAll,
                updateTicks, updateTicks);
    }

    private void updateAll() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, CombatEntry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, CombatEntry> mapEntry = iterator.next();
            Player player = plugin.getServer().getPlayer(mapEntry.getKey());
            if (!mapEntry.getValue().active(now)) {
                iterator.remove();
                if (player != null && player.isOnline()) player.sendActionBar(Component.empty());
                continue;
            }
            if (actionbarEnabled && player != null && player.isOnline()) {
                long seconds = secondsCeil(mapEntry.getValue().combatUntil() - now);
                player.sendActionBar(miniMessage.deserialize(actionbarText.replace("%seconds%", Long.toString(seconds))));
            }
        }
        explosives.entrySet().removeIf(entry -> now - entry.getValue().at() > 60_000L);
        logout.tick(now);
    }

    private void rememberExplosiveOwner(EntityDamageByEntityEvent event) {
        Entity target = event.getEntity();
        if (!(target instanceof EnderCrystal) && target.getType() != EntityType.TNT_MINECART) return;
        Player attacker = causingPlayer(event);
        if (attacker != null) {
            explosives.put(target.getUniqueId(),
                    new ExplosiveCredit(attacker.getUniqueId(), attacker.getName(), System.currentTimeMillis()));
        }
    }

    private Player causingPlayer(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        if (damager instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player player) return player;
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player) return player;
        if (damager instanceof Tameable tameable && tameable.getOwner() instanceof Player player) return player;
        if (damager instanceof EnderCrystal || damager.getType() == EntityType.TNT_MINECART) {
            ExplosiveCredit credit = explosives.get(damager.getUniqueId());
            if (credit != null && System.currentTimeMillis() - credit.at() <= 60_000L) {
                return plugin.getServer().getPlayer(credit.attacker());
            }
        }
        return null;
    }

    private Player causingPlayer(EntityDamageByEntityEvent event) {
        Player direct = causingPlayer(event.getDamager());
        if (direct != null) return direct;
        Entity causing = event.getDamageSource().getCausingEntity();
        return causing instanceof Player player ? player : null;
    }

    private boolean eligible(Player player) {
        GameMode mode = player.getGameMode();
        return !player.isDead() && (mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE)
                && plugin.isSmpGameplayWorld(player.getWorld());
    }

    private boolean isDummy(Entity entity) {
        return entity != null && entity.hasMetadata(CombatLogoutService.DUMMY_METADATA);
    }

    private String normalizeCommand(String input) {
        if (input == null) return "";
        String normalized = input.trim();
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        int space = normalized.indexOf(' ');
        if (space >= 0) normalized = normalized.substring(0, space);
        int namespace = normalized.indexOf(':');
        if (namespace >= 0) normalized = normalized.substring(namespace + 1);
        return normalized.toLowerCase(Locale.ROOT);
    }

    private long secondsCeil(long millis) {
        return Math.max(1L, (Math.max(0L, millis) + 999L) / 1000L);
    }
}
