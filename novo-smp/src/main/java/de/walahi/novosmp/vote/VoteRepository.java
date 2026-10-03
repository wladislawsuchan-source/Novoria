package de.walahi.novosmp.vote;

import de.walahi.smpcore.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** SQL persistence for received votes and pending rewards. */
final class VoteRepository {
    private final DatabaseManager database;

    VoteRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    boolean insert(VotePayload vote, UUID knownPlayer) throws SQLException {
        if (exists(vote.id())) return false;
        String sql = "INSERT INTO " + database.table("vote_events") +
                " (vote_id,service_name,player_name,player_uuid,vote_address,vote_timestamp,received_at,coins_rewarded,key_rewarded,rewarded_at)" +
                " VALUES (?,?,?,?,?,?,?,?,?,?)";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, vote.id());
            statement.setString(2, vote.serviceName());
            statement.setString(3, vote.playerName());
            if (knownPlayer == null) statement.setNull(4, Types.VARCHAR);
            else statement.setString(4, knownPlayer.toString());
            statement.setString(5, vote.address());
            statement.setString(6, vote.timestamp());
            statement.setLong(7, vote.receivedAt());
            statement.setInt(8, 0);
            statement.setInt(9, 0);
            statement.setNull(10, Types.BIGINT);
            statement.executeUpdate();
            return true;
        }
    }

    Optional<UUID> findKnownUuid(String playerName) throws SQLException {
        String sql = "SELECT player_uuid FROM " + database.table("player_stats") +
                " WHERE LOWER(player_name)=LOWER(?) ORDER BY last_seen DESC LIMIT 1";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerName);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                try {
                    return Optional.of(UUID.fromString(rows.getString("player_uuid")));
                } catch (IllegalArgumentException ignored) {
                    return Optional.empty();
                }
            }
        }
    }

    void attachPendingByName(UUID playerId, String currentName) throws SQLException {
        String sql = "UPDATE " + database.table("vote_events") +
                " SET player_uuid=?, player_name=? WHERE player_uuid IS NULL AND LOWER(player_name)=LOWER(?)";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, currentName);
            statement.setString(3, currentName);
            statement.executeUpdate();
        }
    }

    List<VoteRewardRecord> pending(UUID playerId) throws SQLException {
        String sql = "SELECT vote_id,service_name,coins_rewarded,key_rewarded FROM " + database.table("vote_events") +
                " WHERE player_uuid=? AND (coins_rewarded=0 OR key_rewarded=0) ORDER BY received_at ASC";
        List<VoteRewardRecord> result = new ArrayList<>();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new VoteRewardRecord(
                            rows.getString("vote_id"),
                            rows.getString("service_name"),
                            rows.getInt("coins_rewarded") != 0,
                            rows.getInt("key_rewarded") != 0
                    ));
                }
            }
        }
        return result;
    }

    void markCoinsRewarded(String voteId, long now) throws SQLException {
        markPart(voteId, "coins_rewarded", "key_rewarded", now);
    }

    void markKeyRewarded(String voteId, long now) throws SQLException {
        markPart(voteId, "key_rewarded", "coins_rewarded", now);
    }

    long totalVotes(UUID playerId) throws SQLException {
        String sql = "SELECT COUNT(*) AS total FROM " + database.table("vote_events") + " WHERE player_uuid=?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong("total") : 0L;
            }
        }
    }

    /**
     * Corrects the lifetime counter without creating payable vote rewards. When reducing the
     * counter, the oldest events are removed first so today's per-site GUI state stays intact.
     */
    long setTotalVotes(UUID playerId, String playerName, long targetTotal) throws SQLException {
        long safeTarget = Math.max(0L, targetTotal);
        String table = database.table("vote_events");
        try (Connection connection = database.connection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                long current;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) AS total FROM " + table + " WHERE player_uuid=?")) {
                    statement.setString(1, playerId.toString());
                    try (ResultSet rows = statement.executeQuery()) {
                        current = rows.next() ? rows.getLong("total") : 0L;
                    }
                }

                if (safeTarget < current) {
                    long remove = current - safeTarget;
                    List<String> ids = new ArrayList<>();
                    try (PreparedStatement statement = connection.prepareStatement(
                            "SELECT vote_id FROM " + table + " WHERE player_uuid=? ORDER BY received_at ASC")) {
                        statement.setString(1, playerId.toString());
                        try (ResultSet rows = statement.executeQuery()) {
                            while (rows.next() && ids.size() < remove) ids.add(rows.getString("vote_id"));
                        }
                    }
                    try (PreparedStatement statement = connection.prepareStatement(
                            "DELETE FROM " + table + " WHERE vote_id=?")) {
                        for (String id : ids) {
                            statement.setString(1, id);
                            statement.addBatch();
                        }
                        statement.executeBatch();
                    }
                } else if (safeTarget > current) {
                    long add = safeTarget - current;
                    String sql = "INSERT INTO " + table +
                            " (vote_id,service_name,player_name,player_uuid,vote_address,vote_timestamp,received_at,coins_rewarded,key_rewarded,rewarded_at)" +
                            " VALUES (?,?,?,?,?,?,?,?,?,?)";
                    long now = System.currentTimeMillis();
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        for (long index = 0; index < add; index++) {
                            statement.setString(1, "admin-adjust-" + UUID.randomUUID());
                            statement.setString(2, "AdminAdjust");
                            statement.setString(3, playerName);
                            statement.setString(4, playerId.toString());
                            statement.setString(5, "admin");
                            statement.setString(6, "admin-adjustment");
                            statement.setLong(7, Math.max(0L, now - add + index));
                            statement.setInt(8, 1);
                            statement.setInt(9, 1);
                            statement.setLong(10, now);
                            statement.addBatch();
                        }
                        statement.executeBatch();
                    }
                }
                connection.commit();
                connection.setAutoCommit(previousAutoCommit);
                return safeTarget;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                try { connection.setAutoCommit(previousAutoCommit); } catch (SQLException ignored) { }
                throw exception;
            }
        }
    }

    long voteCountSince(UUID playerId, long since) throws SQLException {
        String sql = "SELECT COUNT(*) AS total FROM " + database.table("vote_events") +
                " WHERE player_uuid=? AND received_at>=?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setLong(2, Math.max(0L, since));
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong("total") : 0L;
            }
        }
    }

    List<String> serviceNamesSince(UUID playerId, long since) throws SQLException {
        String sql = "SELECT DISTINCT service_name FROM " + database.table("vote_events") +
                " WHERE player_uuid=? AND received_at>=?";
        List<String> result = new ArrayList<>();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setLong(2, Math.max(0L, since));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String service = rows.getString("service_name");
                    if (service != null && !service.isBlank()) result.add(service);
                }
            }
        }
        return result;
    }

    List<String> serviceNamesSince(String playerName, long since) throws SQLException {
        String sql = "SELECT DISTINCT service_name FROM " + database.table("vote_events") +
                " WHERE LOWER(player_name)=LOWER(?) AND received_at>=?";
        List<String> result = new ArrayList<>();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerName);
            statement.setLong(2, Math.max(0L, since));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String service = rows.getString("service_name");
                    if (service != null && !service.isBlank()) result.add(service);
                }
            }
        }
        return result;
    }


    long milestonesRewarded(UUID playerId) throws SQLException {
        ensureProgress(playerId);
        String sql = "SELECT milestones_rewarded FROM " + database.table("vote_progress") + " WHERE player_uuid=?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Math.max(0L, rows.getLong("milestones_rewarded")) : 0L;
            }
        }
    }

    void setMilestonesRewarded(UUID playerId, long rewarded) throws SQLException {
        ensureProgress(playerId);
        String sql = "UPDATE " + database.table("vote_progress") + " SET milestones_rewarded=? WHERE player_uuid=?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, Math.max(0L, rewarded));
            statement.setString(2, playerId.toString());
            statement.executeUpdate();
        }
    }

    private void ensureProgress(UUID playerId) throws SQLException {
        String table = database.table("vote_progress");
        String sql = database.dialect() == de.walahi.smpcore.storage.StorageDialect.SQLITE
                ? "INSERT OR IGNORE INTO " + table + " (player_uuid,milestones_rewarded) VALUES (?,0)"
                : "INSERT IGNORE INTO " + table + " (player_uuid,milestones_rewarded) VALUES (?,0)";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.executeUpdate();
        }
    }

    private void markPart(String voteId, String completedColumn, String otherColumn, long now) throws SQLException {
        String sql = "UPDATE " + database.table("vote_events") + " SET " + completedColumn + "=1, " +
                "rewarded_at=CASE WHEN " + otherColumn + "=1 THEN ? ELSE rewarded_at END WHERE vote_id=?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, now);
            statement.setString(2, voteId);
            statement.executeUpdate();
        }
    }

    private boolean exists(String voteId) throws SQLException {
        String sql = "SELECT 1 FROM " + database.table("vote_events") + " WHERE vote_id=?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, voteId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    record VoteRewardRecord(String voteId, String serviceName, boolean coinsRewarded, boolean keyRewarded) { }
}
