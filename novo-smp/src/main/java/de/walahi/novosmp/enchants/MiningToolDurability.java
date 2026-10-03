package de.walahi.novosmp.enchants;

import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.concurrent.ThreadLocalRandom;

/** Gemeinsame Haltbarkeitslogik für Holzschlag und besondere Spitzhacken. */
public final class MiningToolDurability {
    private MiningToolDurability() {}

    /**
     * Verbraucht Haltbarkeit wie Vanilla und berücksichtigt Unbreaking.
     *
     * @return false, wenn das Werkzeug dabei zerbrochen ist
     */
    public static boolean damage(Player player, ItemStack tool, int amount) {
        if (tool == null || tool.getType().isAir() || amount <= 0) return true;
        short maxDurability = tool.getType().getMaxDurability();
        if (maxDurability <= 0) return true;

        ItemMeta meta = tool.getItemMeta();
        if (meta == null || meta.isUnbreakable() || !(meta instanceof Damageable damageable)) return true;

        int unbreaking = tool.getEnchantmentLevel(Enchantment.UNBREAKING);
        int applied = 0;
        for (int i = 0; i < amount; i++) {
            if (unbreaking > 0 && ThreadLocalRandom.current().nextInt(unbreaking + 1) != 0) continue;
            applied++;
        }
        if (applied == 0) return true;

        int newDamage = damageable.getDamage() + applied;
        if (newDamage >= maxDurability) {
            player.getInventory().setItemInMainHand(null);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0F, 1.0F);
            return false;
        }
        damageable.setDamage(newDamage);
        tool.setItemMeta(meta);
        return true;
    }

    /** Konservative Prüfung: Unbreaking wird nicht als garantierte Rettung eingerechnet. */
    public static boolean wouldBreak(ItemStack tool, long requiredDurability) {
        if (tool == null || tool.getType().isAir() || requiredDurability <= 0) return false;
        short maxDurability = tool.getType().getMaxDurability();
        if (maxDurability <= 0) return false;

        ItemMeta meta = tool.getItemMeta();
        if (meta == null || meta.isUnbreakable() || !(meta instanceof Damageable damageable)) return false;
        int remaining = maxDurability - damageable.getDamage();
        return requiredDurability >= remaining;
    }
}
