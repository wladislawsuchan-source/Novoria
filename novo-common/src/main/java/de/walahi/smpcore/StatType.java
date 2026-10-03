package de.walahi.smpcore;

/** Einheitliche Statistik-Schlüssel für Stats-Menü und Leaderboards. */
public enum StatType {
    KILLS("kills"),
    DEATHS("deaths"),
    MOB_KILLS("mobs-killed"),
    BLOCKS_BROKEN("blocks-mined"),
    BLOCKS_PLACED("blocks-placed"),
    PLAYTIME("playtime-seconds"),
    SELL_EARNINGS("sell-earnings"),
    ADVANCEMENTS("advancements"),
    PRESTIGE("prestige"),
    HEADS_COLLECTED("heads-collected");

    private final String path;

    StatType(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }
}
