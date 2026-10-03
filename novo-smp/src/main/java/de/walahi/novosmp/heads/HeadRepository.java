package de.walahi.novosmp.heads;

import de.walahi.smpcore.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class HeadRepository {
    private final DatabaseManager database;

    public HeadRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database);
    }

    public Set<String> collected(UUID playerId) {
        Set<String> result = new LinkedHashSet<>();
        String sql = "SELECT head_id FROM " + database.table("head_collection")
                + " WHERE player_uuid=? ORDER BY unlocked_at, head_id";
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(rows.getString(1));
            }
            return result;
        } catch (SQLException exception) {
            throw new IllegalStateException("Kopfsammlung konnte nicht geladen werden", exception);
        }
    }

    /** Counts only currently valid catalogue IDs for every player, used to keep leaderboard stats exact. */
    public Map<UUID, Integer> collectedCounts(Set<String> validHeadIds) {
        Map<UUID, Integer> result = new LinkedHashMap<>();
        if (validHeadIds == null || validHeadIds.isEmpty()) return result;
        String sql = "SELECT player_uuid,head_id FROM " + database.table("head_collection");
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                try {
                    UUID uuid = UUID.fromString(rows.getString("player_uuid"));
                    result.putIfAbsent(uuid, 0);
                    if (validHeadIds.contains(rows.getString("head_id"))) result.merge(uuid, 1, Integer::sum);
                } catch (IllegalArgumentException ignored) { }
            }
            return result;
        } catch (SQLException exception) {
            throw new IllegalStateException("Kopfsammlungs-Statistiken konnten nicht geladen werden", exception);
        }
    }

    /** @return true only when this head was newly unlocked. */
    public boolean unlock(UUID playerId, String headId) {
        if (headId == null || headId.isBlank()) return false;
        try (Connection connection = database.connection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                String check = "SELECT 1 FROM " + database.table("head_collection")
                        + " WHERE player_uuid=? AND head_id=?";
                try (PreparedStatement statement = connection.prepareStatement(check)) {
                    statement.setString(1, playerId.toString());
                    statement.setString(2, headId);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (rows.next()) {
                            connection.rollback();
                            return false;
                        }
                    }
                }
                String insert = "INSERT INTO " + database.table("head_collection")
                        + " (player_uuid,head_id,unlocked_at) VALUES (?,?,?)";
                try (PreparedStatement statement = connection.prepareStatement(insert)) {
                    statement.setString(1, playerId.toString());
                    statement.setString(2, headId);
                    statement.setLong(3, System.currentTimeMillis());
                    statement.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException exception) {
                try { connection.rollback(); } catch (SQLException ignored) { }
                throw exception;
            } finally {
                connection.setAutoCommit(previous);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Kopf konnte nicht zur Sammlung hinzugefügt werden", exception);
        }
    }
    /** Persists the moment a collection milestone was reached, independently of later claiming. */
    public boolean unlockReward(UUID playerId, String rewardId) {
        if (rewardId == null || rewardId.isBlank()) return false;
        try (Connection connection = database.connection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                String check = "SELECT 1 FROM " + database.table("head_collection_rewards")
                        + " WHERE player_uuid=? AND reward_id=?";
                try (PreparedStatement statement = connection.prepareStatement(check)) {
                    statement.setString(1, playerId.toString());
                    statement.setString(2, rewardId);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (rows.next()) {
                            connection.rollback();
                            return false;
                        }
                    }
                }
                String insert = "INSERT INTO " + database.table("head_collection_rewards")
                        + " (player_uuid,reward_id,unlocked_at,claimed_at) VALUES (?,?,?,NULL)";
                try (PreparedStatement statement = connection.prepareStatement(insert)) {
                    statement.setString(1, playerId.toString());
                    statement.setString(2, rewardId);
                    statement.setLong(3, System.currentTimeMillis());
                    statement.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException exception) {
                try { connection.rollback(); } catch (SQLException ignored) { }
                throw exception;
            } finally {
                connection.setAutoCommit(previous);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Kopfsammlungs-Belohnung konnte nicht freigeschaltet werden", exception);
        }
    }

    /** Marks an already unlocked reward as physically claimed exactly once. */
    public boolean claimReward(UUID playerId, String rewardId) {
        if (rewardId == null || rewardId.isBlank()) return false;
        String sql = "UPDATE " + database.table("head_collection_rewards")
                + " SET claimed_at=? WHERE player_uuid=? AND reward_id=? AND claimed_at IS NULL";
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, playerId.toString());
            statement.setString(3, rewardId);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new IllegalStateException("Kopfsammlungs-Belohnung konnte nicht als abgeholt gespeichert werden", exception);
        }
    }

    /** Rolls back only the physical claim; the milestone unlock itself stays permanent. */
    public void unclaimReward(UUID playerId, String rewardId) {
        String sql = "UPDATE " + database.table("head_collection_rewards")
                + " SET claimed_at=NULL WHERE player_uuid=? AND reward_id=?";
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, rewardId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Kopfsammlungs-Belohnung konnte nicht zurückgesetzt werden", exception);
        }
    }

    public Set<String> unlockedRewards(UUID playerId) {
        Set<String> result = new LinkedHashSet<>();
        String sql = "SELECT reward_id FROM " + database.table("head_collection_rewards")
                + " WHERE player_uuid=? ORDER BY unlocked_at,reward_id";
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(rows.getString(1));
            }
            return result;
        } catch (SQLException exception) {
            throw new IllegalStateException("Freigeschaltete Kopfsammlungs-Belohnungen konnten nicht geladen werden", exception);
        }
    }

    public Set<String> claimedRewards(UUID playerId) {
        Set<String> result = new LinkedHashSet<>();
        String sql = "SELECT reward_id FROM " + database.table("head_collection_rewards")
                + " WHERE player_uuid=? AND claimed_at IS NOT NULL ORDER BY claimed_at,reward_id";
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(rows.getString(1));
            }
            return result;
        } catch (SQLException exception) {
            throw new IllegalStateException("Abgeholte Kopfsammlungs-Belohnungen konnten nicht geladen werden", exception);
        }
    }

    /**
     * Repairs the 1.53.4 reward-unlock bug exactly once for this player. Only unclaimed rewards
     * supplied in {@code rewardIdsToRemove} are removed; claimed rewards are never touched.
     */
    public void repairUnclaimedRewardsOnce(UUID playerId, String repairId, Set<String> rewardIdsToRemove) {
        if (playerId == null || repairId == null || repairId.isBlank()) return;
        try (Connection connection = database.connection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                String check = "SELECT 1 FROM " + database.table("head_collection_reward_repairs")
                        + " WHERE player_uuid=? AND repair_id=?";
                try (PreparedStatement statement = connection.prepareStatement(check)) {
                    statement.setString(1, playerId.toString());
                    statement.setString(2, repairId);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (rows.next()) {
                            connection.rollback();
                            return;
                        }
                    }
                }

                if (rewardIdsToRemove != null && !rewardIdsToRemove.isEmpty()) {
                    String delete = "DELETE FROM " + database.table("head_collection_rewards")
                            + " WHERE player_uuid=? AND reward_id=? AND claimed_at IS NULL";
                    try (PreparedStatement statement = connection.prepareStatement(delete)) {
                        for (String rewardId : rewardIdsToRemove) {
                            if (rewardId == null || rewardId.isBlank()) continue;
                            statement.setString(1, playerId.toString());
                            statement.setString(2, rewardId);
                            statement.addBatch();
                        }
                        statement.executeBatch();
                    }
                }

                String mark = "INSERT INTO " + database.table("head_collection_reward_repairs")
                        + " (player_uuid,repair_id,repaired_at) VALUES (?,?,?)";
                try (PreparedStatement statement = connection.prepareStatement(mark)) {
                    statement.setString(1, playerId.toString());
                    statement.setString(2, repairId);
                    statement.setLong(3, System.currentTimeMillis());
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException exception) {
                try { connection.rollback(); } catch (SQLException ignored) { }
                throw exception;
            } finally {
                connection.setAutoCommit(previous);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Kopfsammlungs-Rewards konnten nicht einmalig repariert werden", exception);
        }
    }

}
