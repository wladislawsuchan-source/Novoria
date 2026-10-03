package de.walahi.novosmp.daily;

import org.bukkit.Material;

public record DailyReward(int day, int slot, Material material, DailyRewardTier base,
                          DailyRewardTier premium, DailyRewardTier premiumPlus) {
    public DailyReward {
        if (day < 1 || day > 7) throw new IllegalArgumentException("day must be between 1 and 7");
        slot = Math.max(0, slot);
        material = material == null ? Material.CHEST : material;
        base = base == null ? DailyRewardTier.EMPTY : base;
        premium = premium == null ? DailyRewardTier.EMPTY : premium;
        premiumPlus = premiumPlus == null ? DailyRewardTier.EMPTY : premiumPlus;
    }

    public DailyRewardTier payout(boolean receivesPremium, boolean receivesPremiumPlus) {
        DailyRewardTier result = base;
        if (receivesPremium || receivesPremiumPlus) result = result.plus(premium);
        if (receivesPremiumPlus) result = result.plus(premiumPlus);
        return result;
    }
}
