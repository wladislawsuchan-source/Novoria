package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Durable singleton state and challenger cooldowns for the canonical dragon egg. */
public final class CreateDragonEggKingMigration implements SchemaMigration {
    @Override public int version() { return 32; }
    @Override public String description() { return "Create dragon egg king state and cooldowns"; }

    @Override
    public void apply(Connection connection, StorageDialect dialect, String p) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + p + "dragon_egg_king (" +
                    "singleton_id INTEGER NOT NULL PRIMARY KEY,token_uuid VARCHAR(36) NOT NULL," +
                    "location_kind VARCHAR(24) NOT NULL,holder_uuid VARCHAR(36),holder_name VARCHAR(32)," +
                    "world_name VARCHAR(128),block_x INTEGER,block_y INTEGER,block_z INTEGER,detail VARCHAR(128)," +
                    "reign_uuid VARCHAR(36),reign_started_at BIGINT NOT NULL,duel_date VARCHAR(16)," +
                    "mandatory_used INTEGER NOT NULL,updated_at BIGINT NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + p + "dragon_egg_king_cooldowns (" +
                    "player_uuid VARCHAR(36) NOT NULL PRIMARY KEY,expires_at BIGINT NOT NULL)");
        }
    }
}
