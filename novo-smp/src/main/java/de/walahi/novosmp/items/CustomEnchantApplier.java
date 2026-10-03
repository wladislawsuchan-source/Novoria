package de.walahi.novosmp.items;

import org.bukkit.inventory.ItemStack;

/**
 * Schnittstelle zwischen Item-System und eigenen Verzauberungen.
 *
 * <p>Damit kann die {@code items.yml} eine Verzauberung wie Holzschlag direkt auf einem
 * Custom-Item vorgeben, ohne dass das Item-System das Verzauberungssystem kennen muss.</p>
 */
public interface CustomEnchantApplier {

    /**
     * Setzt eine eigene Verzauberung auf das Item.
     *
     * @return {@code true}, wenn die Verzauberung bekannt war und gesetzt wurde
     */
    boolean applyCustomEnchantment(ItemStack item, String enchantmentId, int level);
}
