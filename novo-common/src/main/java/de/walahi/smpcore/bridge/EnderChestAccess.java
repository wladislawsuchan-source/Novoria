package de.walahi.smpcore.bridge;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/** Neutral bridge used by shared staff commands without depending on NovoSMP ender-chest classes. */
public interface EnderChestAccess {
    void open(Player player);

    /** Opens the real expandable ender chest of an online or offline owner for viewer. */
    boolean open(Player viewer, OfflinePlayer owner);
}
