package de.walahi.smpcore.gui;

import org.bukkit.Material;

/** Central material parsing with explicit fallbacks. */
public final class MaterialResolver {
    private MaterialResolver() {
    }

    public static Material resolve(String configured, Material fallback) {
        if (configured == null || configured.isBlank()) return fallback;
        Material material = Material.matchMaterial(configured.trim());
        return material == null ? fallback : material;
    }
}
