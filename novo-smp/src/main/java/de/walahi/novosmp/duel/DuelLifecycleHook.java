package de.walahi.novosmp.duel;

import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.List;

/** Optional bridge for specialized duel types. Normal duels use the no-op default. */
public interface DuelLifecycleHook {
    default boolean beforeRequestCreated(DuelDraft draft, Player challenger, Player target, boolean specialized) { return true; }
    default void onRequestCreated(DuelRequest request, boolean specialized) { }
    default boolean handlesRequestCreatedMessages(DuelRequest request) { return false; }
    default boolean isSpecializedRequest(DuelRequest request) { return false; }
    default boolean beforeAccept(DuelRequest request, Player challenger, Player target) { return true; }
    default boolean mayDeny(DuelRequest request, Player target) { return true; }
    default boolean beforeStart(DuelRequest request, Player challenger, Player target) { return true; }
    default void afterSnapshotsCaptured(DuelRequest request, Player challenger, Player target) { }
    default List<String> requestLore(DuelRequest request) { return List.of(); }
    default void onFinishedAfterRestore(DuelRequest request, UUID winner) { }
    default void onTechnicalAbort(DuelRequest request) { }
    default void onRequestRemoved(DuelRequest request) { }
    default boolean handleRequestDenied(DuelRequest request, Player target) { return false; }
    default boolean handleRequestExpired(DuelRequest request) { return false; }
    default long specializedRequestLifetimeMillis() { return 0L; }
}
