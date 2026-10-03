package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Creates the durable auction-house foundation: listings, collect, history and statistics. */
public final class CreateAuctionHouseMigration implements SchemaMigration {
    @Override public int version() { return 4; }
    @Override public String description() { return "Create auction house foundation"; }

    @Override
    public void apply(Connection connection, StorageDialect dialect, String tablePrefix) throws SQLException {
        String id = dialect == StorageDialect.SQLITE
                ? "INTEGER PRIMARY KEY AUTOINCREMENT"
                : "BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY";
        String bool = dialect == StorageDialect.SQLITE ? "INTEGER" : "TINYINT";

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + tablePrefix + "ah_listings (" +
                    "listing_uuid VARCHAR(36) NOT NULL PRIMARY KEY, " +
                    "seller_uuid VARCHAR(36) NOT NULL, " +
                    "seller_name VARCHAR(16) NOT NULL, " +
                    "item_data TEXT NOT NULL, " +
                    "item_type VARCHAR(64) NOT NULL, " +
                    "item_amount INTEGER NOT NULL, " +
                    "price BIGINT NOT NULL, " +
                    "status VARCHAR(16) NOT NULL, " +
                    "created_at BIGINT NOT NULL, " +
                    "expires_at BIGINT NOT NULL, " +
                    "closed_at BIGINT, " +
                    "buyer_uuid VARCHAR(36), " +
                    "buyer_name VARCHAR(16)" +
                    ")");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + tablePrefix + "ah_collect (" +
                    "id " + id + ", " +
                    "owner_uuid VARCHAR(36) NOT NULL, " +
                    "listing_uuid VARCHAR(36), " +
                    "item_data TEXT NOT NULL, " +
                    "reason VARCHAR(24) NOT NULL, " +
                    "created_at BIGINT NOT NULL, " +
                    "collected " + bool + " NOT NULL DEFAULT 0, " +
                    "collected_at BIGINT" +
                    ")");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + tablePrefix + "ah_history (" +
                    "id " + id + ", " +
                    "player_uuid VARCHAR(36) NOT NULL, " +
                    "event_type VARCHAR(24) NOT NULL, " +
                    "listing_uuid VARCHAR(36), " +
                    "item_data TEXT NOT NULL, " +
                    "item_type VARCHAR(64) NOT NULL, " +
                    "item_amount INTEGER NOT NULL, " +
                    "counterpart_uuid VARCHAR(36), " +
                    "counterpart_name VARCHAR(16), " +
                    "price BIGINT NOT NULL DEFAULT 0, " +
                    "created_at BIGINT NOT NULL" +
                    ")");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + tablePrefix + "ah_stats (" +
                    "player_uuid VARCHAR(36) NOT NULL PRIMARY KEY, " +
                    "coins_earned BIGINT NOT NULL DEFAULT 0, " +
                    "coins_spent BIGINT NOT NULL DEFAULT 0, " +
                    "items_sold BIGINT NOT NULL DEFAULT 0, " +
                    "items_bought BIGINT NOT NULL DEFAULT 0, " +
                    "listings_created BIGINT NOT NULL DEFAULT 0, " +
                    "updated_at BIGINT NOT NULL" +
                    ")");

            createIndex(statement, dialect, tablePrefix + "idx_ah_listings_status_expiry", tablePrefix + "ah_listings", "status, expires_at");
            createIndex(statement, dialect, tablePrefix + "idx_ah_listings_seller", tablePrefix + "ah_listings", "seller_uuid, status");
            createIndex(statement, dialect, tablePrefix + "idx_ah_collect_owner", tablePrefix + "ah_collect", "owner_uuid, collected");
            createIndex(statement, dialect, tablePrefix + "idx_ah_history_player", tablePrefix + "ah_history", "player_uuid, created_at");
        }
    }

    private static void createIndex(Statement statement, StorageDialect dialect, String index, String table, String columns) throws SQLException {
        if (dialect == StorageDialect.SQLITE) {
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS " + index + " ON " + table + "(" + columns + ")");
            return;
        }
        try {
            statement.executeUpdate("CREATE INDEX " + index + " ON " + table + "(" + columns + ")");
        } catch (SQLException exception) {
            if (exception.getErrorCode() != 1061) throw exception;
        }
    }
}
