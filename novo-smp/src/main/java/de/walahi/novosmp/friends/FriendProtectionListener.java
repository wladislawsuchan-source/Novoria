package de.walahi.novosmp.friends;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;

import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** Friendly-fire and vanished-item protection. */
public final class FriendProtectionListener implements Listener {
    private final FriendStateCache cache;
    private final FriendConfiguration config;
    private final Predicate<Player> vanished;
    private final BiPredicate<Player, Player> suppressed;

    public FriendProtectionListener(FriendStateCache cache, FriendConfiguration config,
                                    Predicate<Player> vanished, BiPredicate<Player, Player> suppressed) {
        this.cache = cache;
        this.config = config;
        this.vanished = vanished;
        this.suppressed = suppressed;
    }

    @EventHandler(ignoreCancelled = true)
    public void damage(EntityDamageByEntityEvent event) {
        Player attacker = source(event.getDamager());
        if (attacker == null || !(event.getEntity() instanceof Player victim)) return;
        if (suppressed.test(attacker, victim)) return;
        if (cache.areFriends(attacker.getUniqueId(), victim.getUniqueId())
                && !cache.settings(attacker.getUniqueId(), victim.getUniqueId()).friendlyFire()) {
            event.setCancelled(true);
            attacker.sendActionBar(config.component("messages.friendly-fire-disabled",
                    "<red>Friendly Fire ist deaktiviert.</red>"));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void pickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && vanished.test(player)) event.setCancelled(true);
    }

    private Player source(Entity entity) {
        if (entity instanceof Player player) return player;
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        return null;
    }
}
