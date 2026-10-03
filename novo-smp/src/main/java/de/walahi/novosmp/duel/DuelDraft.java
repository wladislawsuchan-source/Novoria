package de.walahi.novosmp.duel;

import java.util.UUID;

public final class DuelDraft {
    private final UUID challenger;
    private final UUID target;
    private String mapId;
    private int durationSeconds;
    private long wager;
    private String kitId;
    private DuelRules rules;

    public DuelDraft(UUID challenger, UUID target, String mapId, int durationSeconds, DuelRules rules) {
        this.challenger = challenger;
        this.target = target;
        this.mapId = mapId;
        this.durationSeconds = durationSeconds;
        this.rules = rules;
    }

    public UUID challenger() { return challenger; }
    public UUID target() { return target; }
    public String mapId() { return mapId; }
    public void mapId(String value) { mapId = value; }
    public int durationSeconds() { return durationSeconds; }
    public void durationSeconds(int value) { durationSeconds = value; }
    public long wager() { return wager; }
    public void wager(long value) { wager = Math.max(0L, value); }
    public String kitId() { return kitId; }
    public void kitId(String value) { kitId = value; }
    public DuelRules rules() { return rules; }
    public void rules(DuelRules value) { rules = value; }
}
