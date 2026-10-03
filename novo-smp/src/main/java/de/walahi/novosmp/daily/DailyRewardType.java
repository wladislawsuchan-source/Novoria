package de.walahi.novosmp.daily;

public enum DailyRewardType {
    STANDARD(1, "standard"),
    PREMIUM(2, "premium"),
    PREMIUM_PLUS(4, "premium-plus");

    public static final int ALL_TIER_MASK = STANDARD.bit | PREMIUM.bit | PREMIUM_PLUS.bit;
    public static final int COMPLETE_BIT = 8;

    private final int bit;
    private final String configKey;

    DailyRewardType(int bit, String configKey) {
        this.bit = bit;
        this.configKey = configKey;
    }

    public int bit() {
        return bit;
    }

    public String configKey() {
        return configKey;
    }
}
