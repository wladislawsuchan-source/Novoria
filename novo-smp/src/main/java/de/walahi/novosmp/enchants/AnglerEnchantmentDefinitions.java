package de.walahi.novosmp.enchants;

import java.util.List;
import java.util.Set;

/** Angler enchant metadata only; gameplay effects are intentionally absent. */
public final class AnglerEnchantmentDefinitions {
    public record BookLevel(String enchantmentId, int level) { }
    private static final List<CustomEnchantment> DEFINITIONS = List.of(
            new CustomEnchantment("ausdauer", "Ausdauer", "<aqua>", 5,
                    CustomEnchantment.Applicability.FISHING_ROD),
            new CustomEnchantment("ruhige_hand", "Ruhige Hand", "<aqua>", 3,
                    CustomEnchantment.Applicability.FISHING_ROD),
            new CustomEnchantment("nachfassen", "Nachfassen", "<aqua>", 2,
                    CustomEnchantment.Applicability.FISHING_ROD),
            new CustomEnchantment("konzentration", "Konzentration", "<aqua>", 3,
                    CustomEnchantment.Applicability.FISHING_ROD),
            new CustomEnchantment("meistergriff", "Meistergriff", "<aqua>", 2,
                    CustomEnchantment.Applicability.FISHING_ROD),
            new CustomEnchantment("totembindung", "Totembindung", "<dark_purple>", 1,
                    CustomEnchantment.Applicability.SHIELD),
            new CustomEnchantment("spawnergriff", "Spawnergriff", "<dark_purple>", 1,
                    CustomEnchantment.Applicability.PICKAXE),
            new CustomEnchantment("seelenbindung", "Seelenbindung", "<dark_purple>", 1,
                    CustomEnchantment.Applicability.SOULBOUND_GEAR)
    );
    private static final Set<String> IDS = Set.of("ausdauer", "ruhige_hand", "nachfassen",
            "konzentration", "meistergriff", "totembindung", "spawnergriff", "seelenbindung");

    private AnglerEnchantmentDefinitions() { }

    public static void register(CustomEnchantmentService service) {
        DEFINITIONS.forEach(service::register);
    }

    public static boolean includes(String id) { return IDS.contains(id); }

    /** Recognizes already-issued Angler books that predate their enchant PDC. */
    public static BookLevel bookLevel(String customItemId) {
        if (customItemId == null || !customItemId.startsWith("angler_buch_")) return null;
        String suffix = customItemId.substring("angler_buch_".length());
        int separator = suffix.lastIndexOf('_');
        if (separator < 1) return null;
        String enchantmentId = suffix.substring(0, separator);
        if (!includes(enchantmentId)) return null;
        int level;
        try { level = Integer.parseInt(suffix.substring(separator + 1)); }
        catch (NumberFormatException exception) { return null; }
        for (CustomEnchantment definition : DEFINITIONS)
            if (definition.id().equals(enchantmentId) && level >= 1 && level <= definition.maxLevel())
                return new BookLevel(enchantmentId, level);
        return null;
    }
}
