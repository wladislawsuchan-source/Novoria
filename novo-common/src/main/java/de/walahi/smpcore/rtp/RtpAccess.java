package de.walahi.smpcore.rtp;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Neutral contract used by NovoCommon. The concrete RTP implementation belongs to NovoSMP. */
public interface RtpAccess {
    void findSafeLocation(Player player, String worldName, Consumer<Location> callback);

    /** Lädt den Zielchunk und einen kleinen Umkreis vor, bevor der Spieler teleportiert wird. */
    CompletableFuture<Boolean> prepareDestination(Location location);
}
