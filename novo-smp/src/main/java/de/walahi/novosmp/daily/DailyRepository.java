package de.walahi.novosmp.daily;

import de.walahi.smpcore.storage.StorageDialect;
import de.walahi.smpcore.storage.StorageManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class DailyRepository {
    private static final int CLAIM_BATCH_SIZE = 200;
    private final StorageManager storage;

    public DailyRepository(StorageManager storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public DailyProgress load(UUID uuid) throws SQLException {
        try (Connection connection = storage.connection()) {
            ensure(connection, uuid);
            return load(connection, uuid);
        }
    }

    /** Bulk equivalent of load(uuid).totalClaims(), including missing-row initialization. */
    public Map<UUID, Long> totalClaims(Collection<UUID> playerIds) throws SQLException {
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(playerIds));
        if (ids.isEmpty()) return Map.of();
        Map<UUID, Long> claims = new HashMap<>(ids.size());
        String table = storage.table("daily_rewards");
        String insert = storage.dialect() == StorageDialect.SQLITE
                ? "INSERT OR IGNORE INTO " : "INSERT IGNORE INTO ";
        try (Connection connection = storage.connection()) {
            for (int start = 0; start < ids.size(); start += CLAIM_BATCH_SIZE) {
                List<UUID> batch = ids.subList(start, Math.min(start + CLAIM_BATCH_SIZE, ids.size()));
                String rows = String.join(",", java.util.Collections.nCopies(batch.size(), "(?,1,NULL,0,?)"));
                try (PreparedStatement statement = connection.prepareStatement(insert + table
                        + " (player_uuid,current_day,last_claim_date,total_claims,updated_at) VALUES " + rows)) {
                    long now = System.currentTimeMillis();
                    for (int index = 0; index < batch.size(); index++) {
                        statement.setString(index * 2 + 1, batch.get(index).toString());
                        statement.setLong(index * 2 + 2, now);
                    }
                    statement.executeUpdate();
                }
                String placeholders = String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT player_uuid,total_claims FROM " + table + " WHERE player_uuid IN (" + placeholders + ")")) {
                    for (int index = 0; index < batch.size(); index++) {
                        statement.setString(index + 1, batch.get(index).toString());
                    }
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            claims.put(UUID.fromString(result.getString(1)), result.getLong(2));
                        }
                    }
                }
            }
        }
        return claims;
    }

    public DailyClaimResult claim(UUID uuid, LocalDate today, DailyRewardType type, int requiredMask) throws SQLException {
        Objects.requireNonNull(type, "type");
        try (Connection connection = storage.connection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                ensure(connection, uuid);
                DailyProgress progress = load(connection, uuid);
                if (progress.claimedOn(today) || progress.tierClaimedOn(today, type)) {
                    connection.rollback();
                    return DailyClaimResult.ALREADY_CLAIMED;
                }

                boolean sameDate = today.equals(progress.lastClaimDate());
                int oldMask = sameDate ? progress.claimMask() : 0;
                int newMask = oldMask | type.bit();
                boolean completed = (newMask & requiredMask) == requiredMask;
                int storedMask = completed ? newMask | DailyRewardType.COMPLETE_BIT : newMask;
                int nextDay = completed
                        ? (progress.currentDay() >= 7 ? 1 : progress.currentDay() + 1)
                        : progress.currentDay();
                String sql = "UPDATE " + storage.table("daily_rewards") +
                        " SET current_day=?, last_claim_date=?, claim_mask=?, total_claims=total_claims+?, updated_at=?" +
                        " WHERE player_uuid=? AND claim_mask=? AND " +
                        "((last_claim_date IS NULL AND ? IS NULL) OR last_claim_date=?)";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setInt(1, nextDay);
                    statement.setString(2, today.toString());
                    statement.setInt(3, storedMask);
                    statement.setInt(4, completed ? 1 : 0);
                    statement.setLong(5, System.currentTimeMillis());
                    statement.setString(6, uuid.toString());
                    statement.setInt(7, progress.claimMask());
                    if (progress.lastClaimDate() == null) statement.setNull(8, java.sql.Types.VARCHAR);
                    else statement.setString(8, progress.lastClaimDate().toString());
                    if (progress.lastClaimDate() == null) statement.setNull(9, java.sql.Types.VARCHAR);
                    else statement.setString(9, progress.lastClaimDate().toString());
                    if (statement.executeUpdate() != 1) {
                        connection.rollback();
                        return DailyClaimResult.ALREADY_CLAIMED;
                    }
                }
                connection.commit();
                return DailyClaimResult.SUCCESS;
            } catch (SQLException exception) {
                try { connection.rollback(); } catch (SQLException rollbackFailure) { exception.addSuppressed(rollbackFailure); }
                throw exception;
            } finally {
                try { connection.setAutoCommit(previousAutoCommit); } catch (SQLException ignored) { }
            }
        }
    }

    public void restore(DailyProgress progress) throws SQLException {
        Objects.requireNonNull(progress, "progress");
        try (Connection connection = storage.connection()) {
            ensure(connection, progress.playerUuid());
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE " + storage.table("daily_rewards") +
                            " SET current_day=?, last_claim_date=?, total_claims=?, claim_mask=?, updated_at=? WHERE player_uuid=?")) {
                statement.setInt(1, progress.currentDay());
                if (progress.lastClaimDate() == null) statement.setNull(2, java.sql.Types.VARCHAR);
                else statement.setString(2, progress.lastClaimDate().toString());
                statement.setLong(3, progress.totalClaims());
                statement.setInt(4, progress.claimMask());
                statement.setLong(5, System.currentTimeMillis());
                statement.setString(6, progress.playerUuid().toString());
                statement.executeUpdate();
            }
        }
    }

    public void reset(UUID uuid) throws SQLException {
        try (Connection connection = storage.connection()) {
            ensure(connection, uuid);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE " + storage.table("daily_rewards") +
                            " SET current_day=1, last_claim_date=NULL, total_claims=0, claim_mask=0, updated_at=? WHERE player_uuid=?")) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setString(2, uuid.toString());
                statement.executeUpdate();
            }
        }
    }

    public void setDay(UUID uuid, int day) throws SQLException {
        if (day < 1 || day > 7) throw new IllegalArgumentException("day must be between 1 and 7");
        try (Connection connection = storage.connection()) {
            ensure(connection, uuid);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE " + storage.table("daily_rewards") +
                            " SET current_day=?, last_claim_date=NULL, claim_mask=0, updated_at=? WHERE player_uuid=?")) {
                statement.setInt(1, day);
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, uuid.toString());
                statement.executeUpdate();
            }
        }
    }

    private DailyProgress load(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT current_day,last_claim_date,total_claims,claim_mask FROM " + storage.table("daily_rewards") + " WHERE player_uuid=?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return new DailyProgress(uuid, 1, null, 0L, 0);
                String rawDate = result.getString("last_claim_date");
                LocalDate date = rawDate == null || rawDate.isBlank() ? null : LocalDate.parse(rawDate);
                return new DailyProgress(uuid, result.getInt("current_day"), date,
                        result.getLong("total_claims"), result.getInt("claim_mask"));
            }
        }
    }

    private void ensure(Connection connection, UUID uuid) throws SQLException {
        String sql = storage.dialect() == StorageDialect.SQLITE
                ? "INSERT OR IGNORE INTO " + storage.table("daily_rewards") + " (player_uuid,current_day,last_claim_date,total_claims,updated_at) VALUES (?,1,NULL,0,?)"
                : "INSERT IGNORE INTO " + storage.table("daily_rewards") + " (player_uuid,current_day,last_claim_date,total_claims,updated_at) VALUES (?,1,NULL,0,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setLong(2, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }
}
