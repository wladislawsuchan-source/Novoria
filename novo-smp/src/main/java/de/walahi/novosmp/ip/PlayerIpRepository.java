package de.walahi.novosmp.ip;

import de.walahi.smpcore.database.DatabaseManager;
import de.walahi.smpcore.storage.StorageDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class PlayerIpRepository {
    private final DatabaseManager database;

    public PlayerIpRepository(DatabaseManager database) {
        this.database = database;
    }

    public void record(UUID playerId, String playerName, String ipAddress) throws SQLException {
        long now = System.currentTimeMillis();
        String table = database.table("player_ip_history");
        String sql;
        if (database.dialect() == StorageDialect.SQLITE) {
            sql = "INSERT INTO " + table +
                    " (player_uuid, player_name, ip_address, first_seen, last_seen) VALUES (?, ?, ?, ?, ?) " +
                    "ON CONFLICT(player_uuid, ip_address) DO UPDATE SET player_name = excluded.player_name, last_seen = excluded.last_seen";
        } else {
            sql = "INSERT INTO " + table +
                    " (player_uuid, player_name, ip_address, first_seen, last_seen) VALUES (?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE player_name = VALUES(player_name), last_seen = VALUES(last_seen)";
        }
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, playerName);
            statement.setString(3, ipAddress);
            statement.setLong(4, now);
            statement.setLong(5, now);
            statement.executeUpdate();
        }
    }

    public Optional<String> latestIpByName(String playerName) throws SQLException {
        String sql = "SELECT ip_address FROM " + database.table("player_ip_history") +
                " WHERE LOWER(player_name) = LOWER(?) ORDER BY last_seen DESC";
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerName);
            statement.setMaxRows(1);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getString("ip_address")) : Optional.empty();
            }
        }
    }

    public List<String> playerNamesByIp(String ipAddress) throws SQLException {
        String sql = "SELECT player_name, MAX(last_seen) AS latest FROM " + database.table("player_ip_history") +
                " WHERE ip_address = ? GROUP BY player_uuid, player_name ORDER BY latest DESC";
        List<String> names = new ArrayList<>();
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ipAddress);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) names.add(result.getString("player_name"));
            }
        }
        return names;
    }
}
