package de.walahi.novosmp.enchants;

import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Moves final vanilla/custom drop results into the owner's inventory without touching XP. */
public final class MagnetListener implements Listener {
    public static final String ID = "magnet";
    private final CustomEnchantmentService enchantments;

    public MagnetListener(CustomEnchantmentService enchantments) {
        this.enchantments = enchantments;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDrops(BlockDropItemEvent event) {
        Player player = event.getPlayer();
        if (!active(player.getInventory().getItemInMainHand())) return;
        Location origin = event.getBlockState().getLocation().add(.5, .5, .5);
        List<ItemStack> stacks = event.getItems().stream().map(Item::getItemStack).map(ItemStack::clone).toList();
        event.getItems().forEach(Item::remove);
        event.getItems().clear();
        collect(player, origin, stacks);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobDrops(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null || !active(killer.getInventory().getItemInMainHand()) || event.getDrops().isEmpty()) return;
        List<ItemStack> drops = new ArrayList<>(event.getDrops());
        event.getDrops().clear();
        Map<Integer, ItemStack> leftovers = killer.getInventory().addItem(drops.toArray(ItemStack[]::new));
        event.getDrops().addAll(leftovers.values());
    }

    private void collect(Player player, Location origin, List<ItemStack> drops) {
        if (drops.isEmpty()) return;
        for (ItemStack leftover : player.getInventory().addItem(drops.toArray(ItemStack[]::new)).values()) {
            if (origin.getWorld() != null) origin.getWorld().dropItemNaturally(origin, leftover);
        }
    }

    private boolean active(ItemStack item) {
        return enchantments.level(item, ID) > 0;
    }
}
