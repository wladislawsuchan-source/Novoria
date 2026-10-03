package de.walahi.novosmp.professions;

import de.walahi.smpcore.SMPCorePlugin;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/** Mining XP, natural-block validation and TNT-pickaxe profession tracking. */
public final class MinerListener implements Listener {
    private final ProfessionManager manager;
    private final ProfessionConfig config;
    private final PlacedMiningTracker nonNatural;
    private final NamespacedKey tntOwnerKey;

    public MinerListener(SMPCorePlugin plugin, ProfessionManager manager, ProfessionConfig config) {
        this.manager = manager;
        this.config = config;
        this.nonNatural = new PlacedMiningTracker(plugin, config);
        this.tntOwnerKey = new NamespacedKey(plugin, "tnt_pickaxe_owner");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        nonNatural.mark(event.getBlockPlaced());
    }

    /**
     * Fluid-generated stone/cobblestone/basalt must not become an infinite profession-XP generator.
     * Obsidian is the deliberate exception: lava + water is a legitimate miner task.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        Block block = event.getBlock();
        Material result = event.getNewState().getType();
        if (result == Material.OBSIDIAN) {
            nonNatural.allow(block);
            return;
        }
        if (config.xpFor(ProfessionConfig.MINER_ID, result) <= 0D) return;
        // The block currently still has its old material, so mark after the form completed.
        manager.plugin().getServer().getScheduler().runTask(manager.plugin(), () -> {
            if (block.getType() == result) nonNatural.mark(block);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (config.xpFor(ProfessionConfig.MINER_ID, block.getType()) <= 0D) return;
        if (nonNatural.consume(block)) return;
        manager.recordMinerBlock(event.getPlayer(), block.getType());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplosion(EntityExplodeEvent event) {
        UUID ownerId = null;
        if (event.getEntity() instanceof TNTPrimed tnt) {
            String raw = tnt.getPersistentDataContainer().get(tntOwnerKey, PersistentDataType.STRING);
            if (raw != null) {
                try { ownerId = UUID.fromString(raw); }
                catch (IllegalArgumentException ignored) { }
            }
        }
        Player owner = ownerId == null ? null : manager.plugin().getServer().getPlayer(ownerId);
        for (Block block : event.blockList()) {
            boolean blocked = nonNatural.consume(block);
            if (owner != null && owner.isOnline() && !blocked
                    && config.xpFor(ProfessionConfig.MINER_ID, block.getType()) > 0D) {
                manager.recordMinerBlock(owner, block.getType());
            }
        }
    }
}
