package de.walahi.novosmp.stats;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.StatType;
import org.bukkit.Bukkit;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Iterator;

/** Bukkit event tracking and periodic playtime collection. */
final class StatsTracker implements Listener {
    private final NovoSMPPlugin plugin;
    private final StatsCache cache;
    private final BukkitTask playtimeTask;
    private final long playtimeIncrementSeconds;

    StatsTracker(NovoSMPPlugin plugin, StatsCache cache) {
        this.plugin = plugin;
        this.cache = cache;
        long period = Math.max(20L,
                plugin.configs().scoreboards().getLong("stats.playtime-period-ticks", 20L));
        this.playtimeIncrementSeconds = Math.max(1L, period / 20L);
        this.playtimeTask = Bukkit.getScheduler().runTaskTimer(plugin, this::trackPlaytime, period, period);
    }

    void shutdown() {
        playtimeTask.cancel();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Identity repair must run for every join on this backend, even before the
        // player reaches a gameplay world. This also repairs old UUID placeholders.
        cache.touchNow(player.getUniqueId(), player.getName(), System.currentTimeMillis());
        if (!isSmpWorld(player)) return;
        syncAdvancements(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (isSmpWorld(player)) cache.touchNow(player.getUniqueId(), player.getName(), System.currentTimeMillis());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!isTrackable(player)) return;
        ensure(player);
        cache.add(player.getUniqueId(), StatType.BLOCKS_BROKEN.path(), 1L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!isTrackable(player)) return;
        ensure(player);
        cache.add(player.getUniqueId(), StatType.BLOCKS_PLACED.path(), 1L);
    }

    @EventHandler
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        Player player = event.getPlayer();
        if (!isSmpWorld(player) || isRecipeAdvancement(event.getAdvancement())) return;
        ensure(player);
        syncAdvancements(player);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        if (victim.hasMetadata("novosmp-combat-dummy")
                || victim.hasMetadata("novosmp-finalized-combat-death")) return;
        if (!isTrackable(victim)) return;

        ensure(victim);
        cache.add(victim.getUniqueId(), StatType.DEATHS.path(), 1L);

        Player killer = event.getEntity().getKiller();
        if (killer != null && isTrackable(killer)) {
            ensure(killer);
            cache.add(killer.getUniqueId(), StatType.KILLS.path(), 1L);
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        Entity victim = event.getEntity();
        if (victim.hasMetadata("novosmp-combat-dummy")) return;
        if (victim instanceof Player) return;
        Player killer = event.getEntity().getKiller();
        if (killer == null || !isSmpWorld(killer)) return;
        ensure(killer);
        cache.add(killer.getUniqueId(), StatType.MOB_KILLS.path(), 1L);
    }

    private void trackPlaytime() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isSmpWorld(player)) continue;
            boolean vanished = plugin.getStaffCommands() != null && plugin.getStaffCommands().isVanished(player);
            if (vanished) continue;
            ensure(player);
            cache.add(player.getUniqueId(), StatType.PLAYTIME.path(), playtimeIncrementSeconds);
        }
    }

    private void ensure(Player player) {
        cache.ensure(player.getUniqueId(), player.getName());
    }

    private void syncAdvancements(Player player) {
        long completed = 0L;
        Iterator<Advancement> iterator = Bukkit.advancementIterator();
        while (iterator.hasNext()) {
            Advancement advancement = iterator.next();
            if (isRecipeAdvancement(advancement)) continue;
            if (player.getAdvancementProgress(advancement).isDone()) completed++;
        }
        cache.set(player.getUniqueId(), StatType.ADVANCEMENTS.path(), completed);
    }

    private boolean isRecipeAdvancement(Advancement advancement) {
        return advancement.getKey().getKey().startsWith("recipes/");
    }

    private boolean isTrackable(Player player) {
        return isSmpWorld(player) && !plugin.isPlayerInDuel(player.getUniqueId());
    }

    private boolean isSmpWorld(Player player) {
        return plugin.isSmpGameplayWorld(player.getWorld());
    }
}
