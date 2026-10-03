package de.walahi.novosmp.professions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PlayerProfessionState {
    private final UUID playerId;
    private final Map<String, ProfessionProgress> progress = new LinkedHashMap<>();
    private final List<String> activeProfessions = new ArrayList<>();
    private boolean freeSwitch;
    private boolean secondSlotUnlocked; // Legacy flag kept for existing databases.
    private int slotUnlockPrestige = 3;
    private int maxActiveSlots = 5;
    private boolean metaDirty;
    private List<SlotProjection> confirmedPersistedSlots;
    private boolean rewriteSlotsOnNextNormalSave;
    private boolean slotCommitUncertain;

    public record SlotProjection(int index, String professionId) { }

    public PlayerProfessionState(UUID playerId) {
        this.playerId = playerId;
    }

    public UUID playerId() { return playerId; }
    public Map<String, ProfessionProgress> progress() { return progress; }
    public List<String> activeProfessions() { return activeProfessions; }
    public boolean freeSwitch() { return freeSwitch; }
    public boolean secondSlotUnlocked() { return secondSlotUnlocked; }
    public boolean metaDirty() { return metaDirty; }
    public boolean slotCommitUncertain() { return slotCommitUncertain; }

    public ProfessionProgress progress(String professionId) {
        return progress.computeIfAbsent(professionId,
                id -> new ProfessionProgress(id, 0, 1, 0D, 0, null));
    }

    public boolean isActive(String professionId) {
        return activeProfessions.stream().anyMatch(id -> id.equalsIgnoreCase(professionId));
    }

    public int slotLimit() {
        long prestigeUnlocks = progress.values().stream()
                .filter(value -> value.prestige() >= slotUnlockPrestige)
                .count();
        int calculated = 1 + (int) Math.min(Integer.MAX_VALUE - 1L, prestigeUnlocks);
        if (secondSlotUnlocked) calculated = Math.max(2, calculated);
        return Math.max(1, Math.min(maxActiveSlots, calculated));
    }

    public void slotRules(int unlockPrestige, int maximumSlots) {
        slotUnlockPrestige = Math.max(1, unlockPrestige);
        maxActiveSlots = Math.max(1, maximumSlots);
    }

    public void freeSwitch(boolean value) {
        freeSwitch = value;
        metaDirty = true;
    }

    public void secondSlotUnlocked(boolean value) {
        secondSlotUnlocked = value;
        metaDirty = true;
    }

    public void markMetaClean() {
        metaDirty = false;
    }

    /** The first otherwise-required save still normalizes historic slot gaps. */
    public void loadedSlots(List<SlotProjection> persisted) {
        confirmedPersistedSlots = List.copyOf(persisted);
        rewriteSlotsOnNextNormalSave = true;
        slotCommitUncertain = false;
    }

    public List<SlotProjection> effectiveSlots() {
        int maximum = Math.min(slotLimit(), activeProfessions.size());
        List<SlotProjection> result = new ArrayList<>(maximum);
        for (int index = 0; index < maximum; index++) {
            result.add(new SlotProjection(index, activeProfessions.get(index)));
        }
        return List.copyOf(result);
    }

    public boolean slotsNeedRewrite() {
        // An unsuccessful DB load gives us no safe basis for deleting real DB slots.
        if (confirmedPersistedSlots == null && !slotCommitUncertain) return false;
        return rewriteSlotsOnNextNormalSave || slotCommitUncertain
                || !effectiveSlots().equals(confirmedPersistedSlots);
    }

    public void markSlotCommitUncertain() {
        slotCommitUncertain = true;
    }

    public void confirmPersistedSlots() {
        confirmedPersistedSlots = effectiveSlots();
        rewriteSlotsOnNextNormalSave = false;
        slotCommitUncertain = false;
    }
}
