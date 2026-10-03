package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Adds a readable, automatically refreshed player name beside UUID ownership columns. */
public final class AddReadablePlayerNamesMigration implements SchemaMigration {
    @Override public int version() { return 11; }
    @Override public String description() { return "Add readable player names to SMP account tables"; }

    @Override
    public void apply(Connection connection, StorageDialect dialect, String tablePrefix) throws SQLException {
        String[] tables = {"economy_accounts", "lumi_accounts", "daily_rewards", "enderchest_upgrades", "homes", "ah_stats"};
        for (String base : tables) {
            String table = tablePrefix + base;
            if (!columnExists(connection, table, "player_name")) {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN player_name VARCHAR(32) NULL");
                }
            }
        }
        if (!columnExists(connection, tablePrefix + "ah_collect", "owner_name")) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE " + tablePrefix + "ah_collect ADD COLUMN owner_name VARCHAR(32) NULL");
            }
        }
        if (!columnExists(connection, tablePrefix + "ah_history", "player_name")) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE " + tablePrefix + "ah_history ADD COLUMN player_name VARCHAR(32) NULL");
            }
        }
    }

    private boolean columnExists(Connection connection, String table, String column) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        for (String candidate : new String[]{table, table.toUpperCase(), table.toLowerCase()}) {
            try (ResultSet result = metadata.getColumns(connection.getCatalog(), null, candidate, column)) {
                if (result.next()) return true;
            }
        }
        return false;
    }
}
