package de.walahi.novosmp.angler;

import de.walahi.smpcore.storage.StorageManager;
import de.walahi.smpcore.storage.StorageDialect;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public final class AnglerStorageRepository {
    private final StorageManager storage;
    private final AnglerStorageCodec codec = new AnglerStorageCodec();

    public AnglerStorageRepository(StorageManager storage) { this.storage = storage; }

    public ItemStack[] load(UUID playerId) throws SQLException, IOException {
        String sql = "SELECT contents FROM " + storage.table("angler_catch_storage") + " WHERE player_uuid=?";
        try (Connection connection = storage.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? codec.deserialize(rows.getBytes(1)) : new ItemStack[54];
            }
        }
    }

    public void save(UUID playerId, ItemStack[] contents) throws SQLException, IOException {
        byte[] bytes = codec.serialize(contents);
        String table = storage.table("angler_catch_storage");
        String sql = "INSERT INTO " + table + " (player_uuid,contents,updated_at) VALUES (?,?,?) "
                + (storage.dialect() == StorageDialect.SQLITE
                ? "ON CONFLICT(player_uuid) DO UPDATE SET contents=excluded.contents,updated_at=excluded.updated_at"
                : "ON DUPLICATE KEY UPDATE contents=VALUES(contents),updated_at=VALUES(updated_at)");
        try (Connection connection = storage.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setBytes(2, bytes);
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }
}
