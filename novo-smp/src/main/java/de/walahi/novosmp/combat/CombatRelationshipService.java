package de.walahi.novosmp.combat;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.novosmp.friends.FriendManager;

import java.util.UUID;

/** Central relationship boundary. Clan and team providers can be added here later. */
public final class CombatRelationshipService {
    private final NovoSMPPlugin plugin;
    private final FriendManager friends;
    private long nextFailureLogAt;

    public CombatRelationshipService(NovoSMPPlugin plugin, FriendManager friends) {
        this.plugin = plugin;
        this.friends = friends;
    }

    public boolean isFriendly(UUID first, UUID second) {
        if (first == null || second == null || first.equals(second)) return true;
        try {
            boolean friendsMatch = friends != null && friends.areFriends(first, second);
            boolean clanMatch = plugin.clanManager() != null
                    && plugin.clanManager().sameClan(first, second)
                    && !plugin.clanManager().friendlyFire(first, second);
            return friendsMatch || clanMatch;
        } catch (RuntimeException exception) {
            long now = System.currentTimeMillis();
            if (now >= nextFailureLogAt) {
                nextFailureLogAt = now + 30_000L;
                plugin.getLogger().warning("Combat-Freundesprüfung fehlgeschlagen; Treffer wird sicherheitshalber nicht markiert: "
                        + exception.getMessage());
            }
            return true;
        }
    }

    public boolean blocksClanCombat(UUID first, UUID second) {
        return !plugin.areDuelOpponents(first, second)
                && plugin.clanManager() != null && plugin.clanManager().sameClan(first, second)
                && !plugin.clanManager().friendlyFire(first, second);
    }

    /** Duels own their complete inventory/death lifecycle and must not create logout dummies. */
    public boolean shouldTag(UUID first, UUID second) {
        return !plugin.areDuelOpponents(first, second) && !isFriendly(first, second);
    }
}
