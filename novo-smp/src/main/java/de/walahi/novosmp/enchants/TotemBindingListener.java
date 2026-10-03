package de.walahi.novosmp.enchants;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** One stored vanilla totem per Totembindung shield. */
public final class TotemBindingListener implements Listener {
    private final CustomEnchantmentService enchantments;

    public TotemBindingListener(CustomEnchantmentService enchantments) {
        this.enchantments = enchantments;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)
                || !(event.getClickedInventory() instanceof PlayerInventory)
                || event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        if (current == null || current.getType() != Material.SHIELD
                || enchantments.level(current, "totembindung") < 1
                || cursor == null || cursor.getType().isAir()) return;

        // Do not let Vanilla swap the shield and cursor item, even if the charge is full.
        event.setCancelled(true);
        ItemStack shield = current.clone();
        ItemStack remaining = cursor.clone();
        if (!loadOne(shield, remaining)) return;
        event.setCurrentItem(shield);
        event.getView().setCursor(remaining.getAmount() == 0
                ? new ItemStack(Material.AIR) : remaining);
    }

    /** Called only by the cursor-on-shield interaction; returns false without changing either item. */
    public boolean loadOne(ItemStack shield, ItemStack cursor) {
        if (shield == null || shield.getType() != Material.SHIELD
                || enchantments.level(shield, "totembindung") < 1
                || enchantments.totemCharge(shield) != 0
                || cursor == null || cursor.getType() != Material.TOTEM_OF_UNDYING
                || cursor.getAmount() < 1) return false;
        enchantments.setTotemCharge(shield, 1);
        cursor.setAmount(cursor.getAmount() - 1);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || event.getHand() != null || !event.isCancelled()) return;
        PlayerInventory inventory = player.getInventory();
        ItemStack mainHand = inventory.getItemInMainHand();
        ItemStack offHand = inventory.getItemInOffHand();
        EquipmentSlot charged = chargedHand(mainHand, offHand);
        if (charged == null) return;

        // No vanilla totem was found. Uncancelling lets Paper apply the normal
        // totem death-protection effects and animation without consuming a hand item.
        ItemStack shield = (charged == EquipmentSlot.HAND ? mainHand : offHand).clone();
        enchantments.setTotemCharge(shield, 0);
        if (charged == EquipmentSlot.HAND) inventory.setItemInMainHand(shield);
        else inventory.setItemInOffHand(shield);
        event.setCancelled(false);
    }

    /** Deterministic choice; a charged shield elsewhere in the inventory is ignored. */
    public EquipmentSlot chargedHand(ItemStack mainHand, ItemStack offHand) {
        if (enchantments.totemCharge(mainHand) > 0) return EquipmentSlot.HAND;
        if (enchantments.totemCharge(offHand) > 0) return EquipmentSlot.OFF_HAND;
        return null;
    }
}
