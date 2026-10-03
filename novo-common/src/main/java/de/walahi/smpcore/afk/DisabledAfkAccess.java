package de.walahi.smpcore.afk;

import org.bukkit.entity.Player;

public final class DisabledAfkAccess implements AfkAccess {
    @Override public boolean isAfk(Player player) { return false; }
    @Override public boolean toggle(Player player) { return false; }
}
