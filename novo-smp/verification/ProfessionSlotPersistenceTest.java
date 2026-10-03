package de.walahi.novosmp.professions;

import java.util.List;
import java.util.UUID;

/** Small dependency-free state-machine test, compiled and run directly. */
public final class ProfessionSlotPersistenceTest {
    private ProfessionSlotPersistenceTest() { }

    public static void main(String[] args) {
        loadedSlotsAndHistoricGap();
        emptyAndFailedLoad();
        immediateSaveAndUncertainCommit();
        reloadAndPrestigeProjection();
        System.out.println("ProfessionSlotPersistenceTest passed");
    }

    private static void loadedSlotsAndHistoricGap() {
        PlayerProfessionState state = new PlayerProfessionState(UUID.randomUUID());
        state.activeProfessions().addAll(List.of("miner", "hunter"));
        state.secondSlotUnlocked(true);
        state.markMetaClean();
        state.loadedSlots(List.of(slot(0, "miner"), slot(2, "hunter")));
        check(state.slotsNeedRewrite(), "first otherwise-dirty save must normalize a 0/2 gap");
        check(state.effectiveSlots().equals(List.of(slot(0, "miner"), slot(1, "hunter"))),
                "effective slots must be ordered and contiguous");
        state.confirmPersistedSlots();
        check(!state.slotsNeedRewrite(), "second pure XP save must omit slot rewrite");
    }

    private static void emptyAndFailedLoad() {
        PlayerProfessionState empty = new PlayerProfessionState(UUID.randomUUID());
        empty.loadedSlots(List.of());
        check(empty.slotsNeedRewrite(), "first otherwise-dirty save still rewrites empty slots");
        empty.confirmPersistedSlots();
        check(!empty.slotsNeedRewrite(), "confirmed empty slots need no further rewrite");

        PlayerProfessionState unknown = new PlayerProfessionState(UUID.randomUUID());
        check(!unknown.slotsNeedRewrite(), "failed load must not schedule a destructive slot delete");
    }

    private static void immediateSaveAndUncertainCommit() {
        PlayerProfessionState state = new PlayerProfessionState(UUID.randomUUID());
        state.activeProfessions().add("miner");
        state.loadedSlots(List.of(slot(0, "miner")));
        state.activeProfessions().set(0, "hunter");
        check(state.slotsNeedRewrite(), "a mutable-list slot change must be detected");
        state.confirmPersistedSlots(); // successful immediate save
        check(!state.slotsNeedRewrite(), "XP after successful immediate save omits slots");

        state.activeProfessions().set(0, "miner");
        state.activeProfessions().set(0, "hunter"); // RAM rollback after a failed attempt
        state.markSlotCommitUncertain();
        check(state.slotCommitUncertain() && state.slotsNeedRewrite(),
                "lost commit acknowledgement must force retry even after RAM rollback");
        // A successful profile/meta-only operation must not clear the uncertain slot state.
        state.markMetaClean();
        check(state.slotCommitUncertain(), "meta save must not confirm slots");
        state.confirmPersistedSlots(); // successful correcting transaction
        check(!state.slotCommitUncertain() && !state.slotsNeedRewrite(),
                "confirmed correction must clear the urgent retry");
    }

    private static void reloadAndPrestigeProjection() {
        PlayerProfessionState state = new PlayerProfessionState(UUID.randomUUID());
        state.activeProfessions().addAll(List.of("miner", "hunter"));
        state.secondSlotUnlocked(true);
        state.markMetaClean();
        state.loadedSlots(List.of(slot(0, "miner"), slot(1, "hunter")));
        state.confirmPersistedSlots();
        state.slotRules(3, 2); // reload without effective change
        check(!state.slotsNeedRewrite(), "unchanged reload must not require slots");
        state.slotRules(3, 1);
        check(state.slotsNeedRewrite() && state.effectiveSlots().size() == 1,
                "2-to-1 limit must remove the second DB slot on next save");
        state.confirmPersistedSlots();
        state.slotRules(3, 2);
        check(state.slotsNeedRewrite() && state.effectiveSlots().size() == 2,
                "1-to-2 limit must restore the second projected slot on next save");

        PlayerProfessionState prestige = new PlayerProfessionState(UUID.randomUUID());
        prestige.activeProfessions().addAll(List.of("miner", "hunter"));
        prestige.loadedSlots(List.of(slot(0, "miner")));
        prestige.confirmPersistedSlots(); // only slot 0 fits before prestige
        prestige.progress("miner").prestige(3);
        check(prestige.slotsNeedRewrite() && prestige.effectiveSlots().size() == 2,
                "prestige-induced slot-limit change must be detected");
    }

    private static PlayerProfessionState.SlotProjection slot(int index, String profession) {
        return new PlayerProfessionState.SlotProjection(index, profession);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
