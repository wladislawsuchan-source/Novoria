package de.walahi.smpcore.api.internal;

import de.walahi.smpcore.api.service.*;
import de.walahi.smpcore.homes.HomeAccess;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class DefaultHomeApi implements HomeApi {
    private final HomeAccess service;
    DefaultHomeApi(HomeAccess service) { this.service = Objects.requireNonNull(service, "service"); }

    @Override public HomeSetResult set(Player player, String name, Location location) {
        var result = service.setHome(player, name, location);
        return new HomeSetResult(HomeSetResult.Status.valueOf(result.status().name()),
                result.normalizedName(), result.displayName(), result.limit());
    }
    @Override public Optional<HomeData> find(Player player, String name) {
        var home = service.find(player, name);
        return home == null ? Optional.empty() : Optional.of(toData(home));
    }
    @Override public HomeDeleteResult delete(Player player, String name) {
        var result = service.delete(player, name);
        return new HomeDeleteResult(result.deleted(), result.displayName());
    }
    @Override public List<HomeData> all(Player player) {
        List<HomeData> homes = new ArrayList<>();
        for (String name : service.getNames(player)) {
            var home = service.find(player, name);
            if (home != null) homes.add(toData(home));
        }
        return List.copyOf(homes);
    }
    @Override public int limit(Player player) { return service.getLimit(player); }
    private HomeData toData(HomeAccess.HomeLookup home) {
        return new HomeData(home.normalizedName(), home.displayName(), home.location());
    }
}
