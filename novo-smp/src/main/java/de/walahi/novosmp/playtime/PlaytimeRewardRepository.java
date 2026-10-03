package de.walahi.novosmp.playtime;

import de.walahi.smpcore.storage.StorageDialect;
import de.walahi.smpcore.storage.StorageManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Persistente Claims für /spielzeit. */
public final class PlaytimeRewardRepository {
    private final StorageManager storage;

    public PlaytimeRewardRepository(StorageManager storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public Set<Integer> claimed(UUID playerId) throws SQLException {
        Set<Integer> result = new LinkedHashSet<>();
        String sql = "SELECT milestone_id FROM " + storage.table("playtime_rewards")
                + " WHERE player_uuid=? ORDER BY milestone_id";
        try (Connection connection = storage.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(rows.getInt("milestone_id"));
            }
        }
        return result;
    }

    /** Reserviert einen Claim atomar. false bedeutet: bereits abgeholt. */
    public boolean reserve(UUID playerId, int milestoneId) throws SQLException {
        String sql = storage.dialect() == StorageDialect.SQLITE
                ? "INSERT OR IGNORE INTO " + storage.table("playtime_rewards")
                    + " (player_uuid,milestone_id,claimed_at) VALUES (?,?,?)"
                : "INSERT IGNORE INTO " + storage.table("playtime_rewards")
                    + " (player_uuid,milestone_id,claimed_at) VALUES (?,?,?)";
        try (Connection connection = storage.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setInt(2, milestoneId);
            statement.setLong(3, System.currentTimeMillis());
            return statement.executeUpdate() == 1;
        }
    }

    public void release(UUID playerId, int milestoneId) throws SQLException {
        String sql = "DELETE FROM " + storage.table("playtime_rewards") + " WHERE player_uuid=? AND milestone_id=?";
        try (Connection connection = storage.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setInt(2, milestoneId);
            statement.executeUpdate();
        }
    }
}
