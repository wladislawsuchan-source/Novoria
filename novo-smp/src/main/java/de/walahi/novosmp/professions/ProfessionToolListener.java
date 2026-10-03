package de.walahi.novosmp.professions;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

public final class ProfessionToolListener implements Listener {
    private final ProfessionToolService tools;

    public ProfessionToolListener(ProfessionToolService tools) {
        this.tools = tools;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHeld(PlayerItemHeldEvent event) {
        ItemStack item = event.getPlayer().getInventory().getItem(event.getNewSlot());
        tools.sanitizeForPlayer(event.getPlayer(), item);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            tools.sanitizeForPlayer(player, event.getCurrentItem());
            tools.sanitizeForPlayer(player, event.getCursor());
        } else {
            tools.sanitizeRevoked(event.getCurrentItem());
            tools.sanitizeRevoked(event.getCursor());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            tools.sanitizeForPlayer(player, event.getItem().getItemStack());
        }
    }


    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        tools.sanitizeForPlayer(event.getPlayer(), event.getItem());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onShoot(EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player player) tools.sanitizeForPlayer(player, event.getBow());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        tools.sanitizeForPlayer(event.getPlayer(), event.getMainHandItem());
        tools.sanitizeForPlayer(event.getPlayer(), event.getOffHandItem());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        for (ItemStack item : player.getInventory().getContents()) tools.sanitizeForPlayer(player, item);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        tools.sanitizeForPlayer(player, player.getInventory().getItemInMainHand());
    }
}
