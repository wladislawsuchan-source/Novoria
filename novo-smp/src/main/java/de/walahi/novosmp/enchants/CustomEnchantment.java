package de.walahi.novosmp.enchants;

import org.bukkit.Material;

import java.util.Locale;

/**
 * Beschreibung einer eigenen Verzauberung.
 *
 * <p>Bewusst keine echte Registry-Verzauberung: Stufe und Zugehörigkeit liegen im
 * {@link org.bukkit.persistence.PersistentDataContainer} des Items, die Anzeige erfolgt
 * über eine Lore-Zeile. Das übersteht Serverwechsel und Versionssprünge und lässt sich
 * ohne Registry-Eingriffe erweitern.</p>
 *
 * @param id           eindeutige Kennung, kleingeschrieben
 * @param displayName  Klartextname für die Lore-Zeile, z. B. "Holzschlag"
 * @param color        MiniMessage-Farbe der Lore-Zeile
 * @param maxLevel     höchste zulässige Stufe
 * @param applicable   auf welche Items die Verzauberung gesetzt werden darf
 */
public record CustomEnchantment(
        String id,
        String displayName,
        String color,
        int maxLevel,
        Applicability applicable
) {
    public enum Applicability {
        /** Nur Äxte. */
        AXE,
        /** Nur Spitzhacken. */
        PICKAXE,
        /** Spitzhacken oder Schaufeln. */
        PICKAXE_OR_SHOVEL,
        /** Spitzhacke, Axt, Schaufel, Hacke oder Schwert. */
        TOOL_OR_WEAPON,
        /** Ausschließlich Angelruten. */
        FISHING_ROD,
        /** Ausschließlich Schilde. */
        SHIELD,
        /** Werkzeuge, Waffen und ausdrücklich benannte weitere Survival-Ausrüstung. */
        SOULBOUND_GEAR,
        /** Beliebige Items. */
        ANY;

        public boolean matches(Material material) {
            if (material == null || material.isAir()) return false;
            String name = material.name().toUpperCase(Locale.ROOT);
            return switch (this) {
                case AXE -> name.endsWith("_AXE");
                case PICKAXE -> name.endsWith("_PICKAXE");
                case PICKAXE_OR_SHOVEL -> name.endsWith("_PICKAXE") || name.endsWith("_SHOVEL");
                case TOOL_OR_WEAPON -> name.endsWith("_PICKAXE") || name.endsWith("_AXE")
                        || name.endsWith("_SHOVEL") || name.endsWith("_HOE") || name.endsWith("_SWORD");
                case FISHING_ROD -> material == Material.FISHING_ROD;
                case SHIELD -> material == Material.SHIELD;
                case SOULBOUND_GEAR -> name.endsWith("_PICKAXE") || name.endsWith("_AXE")
                        || name.endsWith("_SHOVEL") || name.endsWith("_HOE") || name.endsWith("_SWORD")
                        || material == Material.FISHING_ROD || material == Material.SHEARS
                        || material == Material.FLINT_AND_STEEL || material == Material.BOW
                        || material == Material.CROSSBOW || material == Material.TRIDENT
                        || material == Material.MACE;
                case ANY -> true;
            };
        }
    }
}
