package de.walahi.novosmp.duel;

import org.bukkit.Material;

public record DuelKit(String id, String displayName, Material icon, DuelLoadout loadout) {
    public DuelKit {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("kit id");
        if (displayName == null || displayName.isBlank()) displayName = id;
        if (icon == null || !icon.isItem()) icon = Material.IRON_SWORD;
        if (loadout == null) loadout = DuelLoadout.empty();
    }
}
