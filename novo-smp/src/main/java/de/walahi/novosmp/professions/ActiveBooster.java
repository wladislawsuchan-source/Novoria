package de.walahi.novosmp.professions;

public record ActiveBooster(BoosterCategory category, double multiplier, int remainingSeconds) {
    public ActiveBooster {
        if (multiplier < 1.0D) throw new IllegalArgumentException("multiplier muss mindestens 1 sein");
        if (remainingSeconds < 0) throw new IllegalArgumentException("remainingSeconds darf nicht negativ sein");
    }

    public ActiveBooster tick() {
        return new ActiveBooster(category, multiplier, Math.max(0, remainingSeconds - 1));
    }
}
