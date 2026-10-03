package de.walahi.smpcore.afk;

import org.bukkit.entity.Player;

public interface AfkAccess {
    default void start() { }
    default void shutdown() { }
    boolean isAfk(Player player);
    boolean toggle(Player player);
    default void markActivity(Player player) { }
}
