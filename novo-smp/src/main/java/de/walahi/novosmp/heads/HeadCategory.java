package de.walahi.novosmp.heads;

import org.bukkit.Material;

public enum HeadCategory {
    ANIMALS("Tiere", Material.COW_SPAWN_EGG),
    MONSTERS("Monster", Material.ZOMBIE_HEAD),
    NETHER("Nether", Material.BLAZE_ROD),
    END("End", Material.ENDER_EYE),
    WATER("Wasser", Material.HEART_OF_THE_SEA),
    SPECIAL("Bosse & Besondere", Material.NETHER_STAR);

    private final String display;
    private final Material icon;
    HeadCategory(String display, Material icon) { this.display = display; this.icon = icon; }
    public String display() { return display; }
    public Material icon() { return icon; }
}
