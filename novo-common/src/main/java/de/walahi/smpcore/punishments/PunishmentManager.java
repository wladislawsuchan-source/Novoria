package de.walahi.smpcore.punishments;

import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

public final class PunishmentManager {

    private final JavaPlugin plugin;
    private final PunishmentRepository repository;

    public PunishmentManager(JavaPlugin plugin, PunishmentRepository repository) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public PunishmentResult punish(UUID playerUuid, String playerName, UUID staffUuid, String staffName,
                                   PunishmentType type, String reason, Duration duration) {
        if (reason == null || reason.isBlank()) return PunishmentResult.failure("Ein Grund muss angegeben werden.");
        Instant expiresAt = duration == null ? null : Instant.now().plus(duration);
        try {
            repository.revokeActive(playerUuid, type, staffUuid, staffName, Instant.now());
            Punishment punishment = repository.create(playerUuid, playerName, staffUuid, staffName,
                    type, reason.trim(), expiresAt);
            return PunishmentResult.success("Punishment wurde gespeichert.", punishment);
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "Punishment konnte nicht gespeichert werden.", exception);
            return PunishmentResult.failure("Das Punishment konnte nicht gespeichert werden.");
        }
    }

    public Optional<Punishment> active(UUID playerUuid, PunishmentType type) {
        try {
            return repository.findActive(playerUuid, type, Instant.now());
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "Aktives Punishment konnte nicht geladen werden.", exception);
            return Optional.empty();
        }
    }

    public boolean revoke(UUID playerUuid, PunishmentType type, UUID staffUuid, String staffName) {
        try {
            return repository.revokeActive(playerUuid, type, staffUuid, staffName, Instant.now());
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "Punishment konnte nicht aufgehoben werden.", exception);
            return false;
        }
    }

    public List<Punishment> history(UUID playerUuid) {
        try {
            return repository.findHistory(playerUuid);
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "Punishment-Historie konnte nicht geladen werden.", exception);
            return List.of();
        }
    }

    public void cleanupExpired() {
        try {
            int count = repository.deactivateExpired(Instant.now());
            if (count > 0) plugin.getLogger().info(count + " abgelaufene Punishment(s) wurden deaktiviert.");
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.WARNING, "Abgelaufene Punishments konnten nicht bereinigt werden.", exception);
        }
    }
}
