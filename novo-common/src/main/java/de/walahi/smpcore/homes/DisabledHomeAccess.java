package de.walahi.smpcore.homes;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;

/** Hub-side placeholder keeping the public API non-null without owning SMP data. */
public final class DisabledHomeAccess implements HomeAccess {
    @Override public SetHomeResult setHome(Player player, String rawName, Location location) {
        return new SetHomeResult(SetHomeStatus.LIMIT_REACHED, rawName.toLowerCase(), rawName, 0);
    }
    @Override public HomeLookup find(Player player, String rawName) { return null; }
    @Override public DeleteHomeResult delete(Player player, String rawName) { return new DeleteHomeResult(false, rawName); }
    @Override public List<String> getNames(Player player) { return List.of(); }
    @Override public List<String> getDisplayNames(Player player) { return List.of(); }
    @Override public int getLimit(Player player) { return 0; }
    @Override public String getDisplayName(Player player, String normalizedName) { return normalizedName; }
}
