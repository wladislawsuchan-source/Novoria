package de.walahi.novosmp.items;

import org.bukkit.Material;

import java.util.List;
import java.util.Map;

/**
 * Ein vollständig über die Config beschriebenes Custom-Item.
 *
 * <p>Bewusst ein reines Datenobjekt: Der {@link CustomItemManager} baut daraus den
 * {@link org.bukkit.inventory.ItemStack}. Neue Items brauchen deshalb keinen Java-Code,
 * sondern nur einen weiteren Eintrag in der {@code items.yml}.</p>
 *
 * @param id             eindeutige Kennung, immer kleingeschrieben
 * @param material       Basis-Material des Items
 * @param displayName    Anzeigename als MiniMessage
 * @param lore           Lore-Zeilen als MiniMessage
 * @param customModelData optional für Resourcepacks, {@code null} = nicht setzen
 * @param itemModel      optionales Vanilla/Resourcepack-Item-Model (z. B. minecraft:disc_fragment_5)
 * @param maxStackSize   optionale Stackgröße, {@code null} = Vanilla-Verhalten
 * @param glow           erzeugt den Verzauberungsschimmer ohne echte Verzauberung
 * @param unbreakable    Item verliert keine Haltbarkeit
 * @param enchantments   Vanilla-Verzauberungen (Schlüssel = Registry-Name)
 * @param customEnchantments eigene Verzauberungen wie Holzschlag (Schlüssel = Kennung)
 * @param mendingBlocked wenn true, kann dieses Custom-Item niemals Reparatur/Mending erhalten
 * @param hideAdditionalTooltip blendet Vanilla-Zusatzinfos wie den Disc-Fragment-Untertitel aus
 */
public record CustomItemDefinition(
        String id,
        Material material,
        String displayName,
        List<String> lore,
        Integer customModelData,
        String itemModel,
        Integer maxStackSize,
        boolean glow,
        boolean unbreakable,
        Map<String, Integer> enchantments,
        Map<String, Integer> customEnchantments,
        boolean mendingBlocked,
        boolean hideAdditionalTooltip
) {
    public CustomItemDefinition {
        lore = List.copyOf(lore);
        enchantments = Map.copyOf(enchantments);
        customEnchantments = Map.copyOf(customEnchantments);
    }
}
