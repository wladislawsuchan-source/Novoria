package de.walahi.novosmp.economy.sell;

import org.bukkit.Material;

import java.util.UUID;

@FunctionalInterface
public interface SellBonusProvider {
    double multiplier(UUID playerId, Material material);

    static SellBonusProvider none() {
        return (playerId, material) -> 1D;
    }
}
