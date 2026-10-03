package de.walahi.smpcore.punishments;

public record PunishmentResult(boolean success, String message, Punishment punishment) {

    public static PunishmentResult success(String message, Punishment punishment) {
        return new PunishmentResult(true, message, punishment);
    }

    public static PunishmentResult failure(String message) {
        return new PunishmentResult(false, message, null);
    }
}
