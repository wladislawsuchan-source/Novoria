package de.walahi.novosmp.duel;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.Location;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.UUID;

/** Owns duel damage filtering, attribution, lethal hits and death handling. */
final class DuelCombatListener implements Listener {
    private final DuelManager manager;

    DuelCombatListener(DuelManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            DuelMatch arenaMatch = manager.matchAt(event.getEntity().getLocation());
            if (event.getEntity() instanceof EnderCrystal crystal) {
                if (arenaMatch != null && (!arenaMatch.running() || !arenaMatch.request.rules().crystalsAllowed())) {
                    event.setCancelled(true);
                    return;
                }
                DuelMatch attackerMatch = manager.matchForDamager(event.getDamager());
                UUID detonator = attackerMatch == null ? null : attackingPlayer(event.getDamager(), attackerMatch);
                if (detonator != null && attackerMatch.map.contains(crystal.getLocation())) {
                    manager.rememberExplosiveOwner(crystal.getUniqueId(), detonator);
                }
            } else if (event.getEntity().getType() == EntityType.TNT_MINECART
                    && arenaMatch != null && (!arenaMatch.running() || !arenaMatch.request.rules().tntAllowed())) {
                event.setCancelled(true);
            }
            return;
        }

        DuelMatch match = manager.match(victim.getUniqueId());
        if (match == null) return;
        if (!match.running()) {
            event.setCancelled(true);
            return;
        }

        boolean tnt = event.getDamager() instanceof TNTPrimed
                || event.getDamager().getType() == EntityType.TNT_MINECART;
        boolean crystal = event.getDamager() instanceof EnderCrystal;
        if (tnt && !match.request.rules().tntDamage()) {
            event.setCancelled(true);
            return;
        }
        if (crystal && !match.request.rules().crystalDamage()) {
            event.setCancelled(true);
            return;
        }
        if ((tnt || crystal) && match.map.contains(event.getDamager().getLocation())) {
            return; // Vanilla explosion damage, including self-damage, remains active.
        }

        UUID attacker = attackingPlayer(event.getDamager(), match);
        if (attacker == null || !attacker.equals(match.opponent(victim.getUniqueId()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onAnyDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        DuelMatch match = manager.match(victim.getUniqueId());
        if (match == null) return;
        if (!match.running()) {
            event.setCancelled(true);
            return;
        }
        if (event.isCancelled()) return;

        if (event instanceof EntityDamageByEntityEvent byEntity) {
            addDamage(match, attackingPlayer(byEntity.getDamager(), match), victim, event.getFinalDamage());
        } else {
            DuelMatch.LastDamage last = match.lastDamage.get(victim.getUniqueId());
            if (last != null && System.currentTimeMillis() - last.atMillis()
                    <= manager.config().damageAttributionSeconds() * 1000L) {
                addDamage(match, last.attacker(), victim, event.getFinalDamage());
            }
        }

        double pool = victim.getHealth() + victim.getAbsorptionAmount();
        if (event.getFinalDamage() + 0.0001D < pool || canResurrectWithTotem(victim, event)) return;

        event.setCancelled(true);
        UUID winner = match.opponent(victim.getUniqueId());
        Bukkit.getScheduler().runTask(manager.plugin(), () -> manager.finishFromCombat(
                match, winner, manager.config().message("match.finished", "<red>Das Duell ist beendet.</red>")
        ));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        DuelMatch match = manager.match(victim.getUniqueId());
        if (match == null) return;

        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.deathMessage(null);
        Bukkit.getScheduler().runTask(manager.plugin(), () -> {
            // Echte Todesfälle sind nur ein Fallback. Sofort serverseitig respawnen, damit
            // weder Bett noch Respawn-Anker kurz als Zwischenstation verwendet werden.
            if (victim.isOnline() && victim.isDead()) victim.spigot().respawn();
            manager.finishFromCombat(
                    match, match.opponent(victim.getUniqueId()),
                    manager.config().message("match.finished", "<red>Das Duell ist beendet.</red>")
            );
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        DuelMatch match = manager.match(player.getUniqueId());
        if (match == null) return;

        Location spawn = manager.smpSpawnLocation();
        if (spawn != null) event.setRespawnLocation(spawn);

        // Zustand erst nach dem eigentlichen Respawn wiederherstellen. Paper setzt Teile des
        // Player-Zustands zwischen RespawnEvent und dem fertigen Respawn erneut zurück.
        Bukkit.getScheduler().runTask(manager.plugin(), () -> {
            DuelMatch current = manager.match(player.getUniqueId());
            if (current == match && current.state == DuelMatch.State.ENDING) {
                manager.restoreEndingPlayer(match, player);
            }
        });
    }

    private UUID attackingPlayer(Entity damager, DuelMatch match) {
        if (damager instanceof Player player) {
            return match.contains(player.getUniqueId()) ? player.getUniqueId() : null;
        }
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player && match.contains(player.getUniqueId())) {
                return player.getUniqueId();
            }
        }
        if (damager instanceof AreaEffectCloud cloud) {
            ProjectileSource source = cloud.getSource();
            if (source instanceof Player player && match.contains(player.getUniqueId())) {
                return player.getUniqueId();
            }
        }
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player
                && match.contains(player.getUniqueId())) {
            return player.getUniqueId();
        }
        if (damager instanceof EnderCrystal crystal) {
            return manager.explosiveOwner(crystal.getUniqueId());
        }
        if (damager.getType() == EntityType.TNT_MINECART) {
            return manager.explosiveOwner(damager.getUniqueId());
        }
        return null;
    }

    private void addDamage(DuelMatch match, UUID attacker, Player victim, double finalDamage) {
        if (attacker == null || attacker.equals(victim.getUniqueId())
                || !attacker.equals(match.opponent(victim.getUniqueId()))) return;

        double absorption = victim.getAbsorptionAmount();
        double pool = victim.getHealth() + absorption;
        double measured = manager.config().countAbsorptionDamage()
                ? finalDamage
                : Math.max(0D, finalDamage - absorption);
        double actual = Math.max(0D, Math.min(measured, pool));
        if (actual <= 0D) return;

        match.damage.merge(attacker, actual, Double::sum);
        match.lastDamage.put(victim.getUniqueId(), new DuelMatch.LastDamage(attacker, System.currentTimeMillis()));
    }

    private boolean canResurrectWithTotem(Player player, EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID) return false;
        return player.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || player.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING;
    }
}
