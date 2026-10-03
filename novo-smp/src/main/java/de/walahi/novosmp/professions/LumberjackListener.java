package de.walahi.novosmp.professions;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

public final class LumberjackListener implements Listener {
    private final ProfessionManager manager;
    private final ProfessionConfig config;
    private final PlacedWoodTracker placedWood;

    public LumberjackListener(ProfessionManager manager, ProfessionConfig config,
                              PlacedWoodTracker placedWood) {
        this.manager = manager;
        this.config = config;
        this.placedWood = placedWood;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        placedWood.mark(event.getBlockPlaced());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        double xp = config.xpFor(block.getType());
        if (xp <= 0D) return;

        // The marker must be consumed even when the breaker has no active lumberjack profession.
        if (placedWood.consume(block)) return;
        Player player = event.getPlayer();
        manager.addLumberjackXp(player, xp);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        event.blockList().forEach(placedWood::remove);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        event.blockList().forEach(placedWood::remove);
    }
}
