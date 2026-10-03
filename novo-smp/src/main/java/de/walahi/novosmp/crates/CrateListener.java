package de.walahi.novosmp.crates;

import org.bukkit.block.ShulkerBox;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Verknüpft physische Kistenblöcke mit Vorschau und privater Öffnungsanimation. */
public final class CrateListener implements Listener {
    private final CrateManager crates;

    public CrateListener(CrateManager crates) {
        this.crates = crates;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) return;
        String crateId = crates.crateAt(event.getClickedBlock().getLocation()).orElse(null);
        if (crateId == null) return;

        // Beide Vanilla-Aktionen ausdrücklich sperren. Nur setCancelled(true) reicht bei
        // Shift + Rechtsklick mit benutzbaren Items auf modernen Paper-Versionen nicht
        // in jeder Paketkombination zuverlässig aus.
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        event.setCancelled(true);

        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            crates.openPreview(event.getPlayer(), crateId);
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        if (event.getPlayer().isSneaking() && crates.hasActiveAnimation(event.getPlayer())) {
            crates.skipAnimation(event.getPlayer());
            return;
        }

        CrateDefinition crate = crates.find(crateId).orElse(null);
        if (crate != null && !crates.keys().isHoldingKey(event.getPlayer(), crateId)) {
            event.getPlayer().sendRichMessage("<dark_gray>[<gold>Kiste</gold>]</dark_gray> <red>Du hast keinen "
                    + crates.keys().keyDisplayName(crate) + " in der Hand.</red>");
            return;
        }

        CrateManager.OpenResult result = crates.startAnimatedOpen(event.getPlayer(), crateId,
                event.getClickedBlock().getLocation(), true);
        if (result != CrateManager.OpenResult.SUCCESS) crates.sendOpenError(event.getPlayer(), result);
    }

    /**
     * Zusätzliche letzte Sicherung: Eine als Crate registrierte Shulker darf niemals ihr
     * echtes Vanilla-Inventar öffnen – auch nicht über Shift-Rechtsklick oder ein anderes Plugin.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShulkerBox shulker)) return;
        if (crates.crateAt(shulker.getLocation()).isEmpty()) return;
        event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        crates.finishAnimationImmediately(event.getPlayer());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (crates.plugin().configs().server().getBoolean("crates.auto-recovery-enabled", false)) {
            crates.discoverMissingCrates(event.getChunk());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (crates.crateAt(event.getBlock().getLocation()).isEmpty()) return;
        if (event.getPlayer().hasPermission("smpcore.crate.admin")) return;
        event.setCancelled(true);
        event.getPlayer().sendRichMessage("<dark_gray>[<gold>Kiste</gold>]</dark_gray> <red>Diese Kiste kann nicht abgebaut werden.</red>");
    }
}
