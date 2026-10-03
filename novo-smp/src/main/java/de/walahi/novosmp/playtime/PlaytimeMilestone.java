package de.walahi.novosmp.playtime;

public record PlaytimeMilestone(int id, long hours, long coins, int xp) {
    public long requiredSeconds() {
        try {
            return Math.multiplyExact(hours, 3600L);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }
}
