package de.walahi.novosmp.crates;

import org.bukkit.Material;

import java.util.Map;

/**
 * Eine Belohnung einer Crate.
 *
 * @param customItemId Kennung aus der items.yml, nur bei {@link Type#CUSTOM_ITEM} gesetzt
 * @param itemName optionaler echter Anzeigename für ITEM-Rewards; leer = Vanilla-Name beibehalten
 * @param enchantments optionale Vanilla-Verzauberungen für ITEM-Rewards
 */
public record CrateReward(String id, String displayName, double weight, Type type,
                          Material material, int amount, long coins, String targetCrate, String command,
                          int fireworkFlight, String customItemId, String itemName,
                          Map<String, Integer> enchantments) {
    public CrateReward {
        enchantments = enchantments == null ? Map.of() : Map.copyOf(enchantments);
        itemName = itemName == null ? "" : itemName;
    }

    public enum Type { ITEM, COINS, KEY, COMMAND, CUSTOM_ITEM }
}
