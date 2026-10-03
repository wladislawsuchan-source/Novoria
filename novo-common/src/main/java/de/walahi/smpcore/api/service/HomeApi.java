package de.walahi.smpcore.api.service;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

/** Public home API. Internal service implementations are intentionally hidden. */
public interface HomeApi {
    HomeSetResult set(Player player, String name, Location location);
    Optional<HomeData> find(Player player, String name);
    HomeDeleteResult delete(Player player, String name);
    List<HomeData> all(Player player);
    int limit(Player player);
}
