package de.walahi.novosmp.daily;

import java.time.LocalDate;
import java.util.UUID;

public record DailyProgress(UUID playerUuid, int currentDay, LocalDate lastClaimDate, long totalClaims, int claimMask) {
    public DailyProgress {
        if (currentDay < 1 || currentDay > 7) {
            throw new IllegalArgumentException("currentDay must be between 1 and 7");
        }
    }

    public boolean claimedOn(LocalDate date) {
        return sameDate(date) && (claimMask & DailyRewardType.COMPLETE_BIT) != 0;
    }

    public boolean tierClaimedOn(LocalDate date, DailyRewardType type) {
        return sameDate(date) && type != null && (claimMask & type.bit()) != 0;
    }

    private boolean sameDate(LocalDate date) {
        return date != null && date.equals(lastClaimDate);
    }
}
