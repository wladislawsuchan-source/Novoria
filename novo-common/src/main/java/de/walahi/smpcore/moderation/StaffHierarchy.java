package de.walahi.smpcore.moderation;

import de.walahi.smpcore.ranks.RankManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

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
}
