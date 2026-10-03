package de.walahi.novosmp.combat;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.StatType;
import de.walahi.smpcore.stats.StatsAccess;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.UUID;

/** Single publication point for every legitimate SMP player kill. */
public final class ValidPlayerKillService {
    private final NovoSMPPlugin plugin;
    private final StatsAccess stats;

    public ValidPlayerKillService(NovoSMPPlugin plugin, StatsAccess stats) {
        this.plugin = plugin;
        this.stats = stats;
    }

    public boolean publish(UUID killerId, String killerName, UUID victimId, String victimName,
                           ValidKillCause cause, Location location) {
        if (killerId == null || victimId == null || killerId.equals(victimId)) return false;

        Bukkit.getPluginManager().callEvent(new ValidPlayerKillEvent(
                killerId, killerName, victimId, victimName, cause, location));
        return true;
    }

    /** Commits every externally visible effect of one authoritative dummy PvP death. */
    public boolean processCombatDummyDeath(UUID killerId, String killerName, Player onlineKiller,
                                           UUID victimId, String victimName, LivingEntity dummy,
                                           Location location) {
        if (stats != null) stats.addStat(victimId, StatType.DEATHS, 1L);
        if (killerId == null || victimId == null || killerId.equals(victimId)) return false;

        if (stats != null) stats.addStat(killerId, StatType.KILLS, 1L);
        plugin.broadcastCombatDummyDeath(
                victimId, victimName, killerId, killerName, onlineKiller, dummy);
        Bukkit.getPluginManager().callEvent(new ValidPlayerKillEvent(
                killerId, killerName, victimId, victimName,
                ValidKillCause.COMBAT_LOG_DUMMY, location));
        return true;
    }
}
