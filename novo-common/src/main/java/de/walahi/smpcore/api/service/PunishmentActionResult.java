package de.walahi.smpcore.api.service;

import java.util.Optional;

/** Public result of creating a punishment. */
public record PunishmentActionResult(boolean success, String message, Optional<PunishmentData> punishment) {
    public PunishmentActionResult {
        punishment = punishment == null ? Optional.empty() : punishment;
    }
}
