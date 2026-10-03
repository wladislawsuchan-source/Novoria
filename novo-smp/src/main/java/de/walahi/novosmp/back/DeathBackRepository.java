package de.walahi.novosmp.back;

import de.walahi.smpcore.storage.StorageDialect;
import de.walahi.smpcore.storage.StorageManager;
import org.bukkit.Location;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

public final class DeathBackRepository {
    private final StorageManager storage;

    public DeathBackRepository(StorageManager storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public void save(UUID uuid, String playerName, Location location, long createdAt, long expiresAt) throws SQLException {
        Objects.requireNonNull(location.getWorld(), "death world");
        String table = storage.table("death_back");
        String sql = storage.dialect() == StorageDialect.SQLITE
                ? "INSERT INTO " + table + " (player_uuid,player_name,world,x,y,z,yaw,pitch,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?) " +
                  "ON CONFLICT(player_uuid) DO UPDATE SET player_name=excluded.player_name,world=excluded.world,x=excluded.x,y=excluded.y,z=excluded.z,yaw=excluded.yaw,pitch=excluded.pitch,created_at=excluded.created_at,expires_at=excluded.expires_at"
                : "INSERT INTO " + table + " (player_uuid,player_name,world,x,y,z,yaw,pitch,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?) " +
                  "ON DUPLICATE KEY UPDATE player_name=VALUES(player_name),world=VALUES(world),x=VALUES(x),y=VALUES(y),z=VALUES(z),yaw=VALUES(yaw),pitch=VALUES(pitch),created_at=VALUES(created_at),expires_at=VALUES(expires_at)";
        try (Connection connection = storage.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, playerName);
            statement.setString(3, location.getWorld().getName());
            statement.setDouble(4, location.getX());
            statement.setDouble(5, location.getY());
            statement.setDouble(6, location.getZ());
            statement.setFloat(7, location.getYaw());
            statement.setFloat(8, location.getPitch());
            statement.setLong(9, createdAt);
            statement.setLong(10, expiresAt);
            statement.executeUpdate();
        }
    }

    public DeathBackPoint load(UUID uuid) throws SQLException {
        String sql = "SELECT world,x,y,z,yaw,pitch,created_at,expires_at FROM " + storage.table("death_back") + " WHERE player_uuid=?";
        try (Connection connection = storage.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return new DeathBackPoint(
                        result.getString("world"), result.getDouble("x"), result.getDouble("y"), result.getDouble("z"),
                        result.getFloat("yaw"), result.getFloat("pitch"), result.getLong("created_at"), result.getLong("expires_at")
                );
            }
        }
    }

    public boolean deleteIfUnchanged(UUID uuid, long createdAt) throws SQLException {
        String sql = "DELETE FROM " + storage.table("death_back") + " WHERE player_uuid=? AND created_at=?";
        try (Connection connection = storage.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setLong(2, createdAt);
            return statement.executeUpdate() == 1;
        }
    }

    public void delete(UUID uuid) throws SQLException {
        try (Connection connection = storage.connection(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + storage.table("death_back") + " WHERE player_uuid=?")) {
            statement.setString(1, uuid.toString());
            statement.executeUpdate();
        }
    }

    public int deleteExpired(long now) throws SQLException {
        try (Connection connection = storage.connection(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + storage.table("death_back") + " WHERE expires_at<=?")) {
            statement.setLong(1, now);
            return statement.executeUpdate();
        }
    }
}
