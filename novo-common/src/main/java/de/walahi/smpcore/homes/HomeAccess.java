package de.walahi.smpcore.homes;

import de.walahi.smpcore.services.Service;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;

/** Neutral contract. The concrete home persistence belongs to NovoSMP. */
public interface HomeAccess extends Service {
    SetHomeResult setHome(Player player, String rawName, Location location);
    HomeLookup find(Player player, String rawName);
    DeleteHomeResult delete(Player player, String rawName);
    List<String> getNames(Player player);
    List<String> getDisplayNames(Player player);
    int getLimit(Player player);
    String getDisplayName(Player player, String normalizedName);

    enum SetHomeStatus { CREATED, ALREADY_EXISTS, INVALID_NAME, LIMIT_REACHED }
    record SetHomeResult(SetHomeStatus status, String normalizedName, String displayName, int limit) {}
    record HomeLookup(String normalizedName, String displayName, Location location) {}
    record DeleteHomeResult(boolean deleted, String displayName) {}
}
