package de.walahi.smpcore.moderation;

import de.walahi.smpcore.ranks.RankManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.util.UUID;

/** Vergleicht Teamränge anhand der priority aus ranks.yml. Niedriger = höher. */
public final class StaffHierarchy {
    private final RankManager rankManager;

    public StaffHierarchy(RankManager rankManager) {
        this.rankManager = rankManager;
    }

    public boolean mayActOn(CommandSender actor, Player target) {
        if (!(actor instanceof Player actorPlayer)) return true;
        if (actorPlayer.getUniqueId().equals(target.getUniqueId())) return false;
        int actorPriority = rankManager.resolve(actorPlayer).priority();
        int targetPriority = rankManager.resolve(target).priority();
        return actorPriority < targetPriority;
    }

    /** UUID-based ban checks use the same configured priorities and persisted LuckPerms groups. */
    public boolean mayActOn(CommandSender actor, UUID targetUuid) {
        if (!(actor instanceof Player actorPlayer)) return true;
        if (actorPlayer.getUniqueId().equals(targetUuid)) return false;
        return rankManager.resolveForModeration(targetUuid)
                .map(targetRank -> rankManager.resolve(actorPlayer).priority() < targetRank.priority())
                .orElse(false);
    }
}
