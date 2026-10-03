package de.walahi.smpcore.stats;

import de.walahi.smpcore.StatType;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/** Shared contract used by common command/UI code without owning the SMP implementation. */
public interface StatsAccess {
    void shutdown();
    long getStat(UUID uuid, String path);
    long getStat(UUID uuid, StatType type);
    void addStat(UUID uuid, StatType type, long amount);
    void setStat(UUID uuid, StatType type, long value);
    int registeredPlayers();
    String getFormattedPlaytime(UUID uuid);
    void openStats(Player player);
    boolean openStats(Player viewer, String playerName);
    List<String> registeredPlayerNames(String prefix);
    void openLeaderboards(Player viewer);
}
