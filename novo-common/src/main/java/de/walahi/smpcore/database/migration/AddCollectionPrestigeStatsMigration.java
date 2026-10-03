package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Adds durable aggregate counters for prestige levels and personally collected mob heads. */
public final class AddCollectionPrestigeStatsMigration implements SchemaMigration {
    @Override public int version() { return 18; }
    @Override public String description() { return "Prestige- und Kopf-Statistiken"; }

    @Override
    public void apply(Connection connection, StorageDialect dialect, String prefix) throws SQLException {
        String stats = prefix + "player_stats";
        try (Statement statement = connection.createStatement()) {
            if (!hasColumn(statement, stats, "prestige")) {
                statement.executeUpdate("ALTER TABLE " + stats + " ADD COLUMN prestige BIGINT NOT NULL DEFAULT 0");
            }
            if (!hasColumn(statement, stats, "heads_collected")) {
                statement.executeUpdate("ALTER TABLE " + stats + " ADD COLUMN heads_collected BIGINT NOT NULL DEFAULT 0");
            }

            // Backfill existing players from the actual source-of-truth tables. Afterwards the counters
            // are incremented transactionally by the gameplay systems whenever new progress is earned.
            statement.executeUpdate("UPDATE " + stats + " SET prestige=COALESCE((SELECT SUM(p.prestige) FROM "
                    + prefix + "profession_profiles p WHERE p.player_uuid=" + stats + ".player_uuid),0)");
            statement.executeUpdate("UPDATE " + stats + " SET heads_collected=COALESCE((SELECT COUNT(*) FROM "
                    + prefix + "head_collection h WHERE h.player_uuid=" + stats + ".player_uuid),0)");
        }
    }

    private boolean hasColumn(Statement statement, String table, String column) {
        try (ResultSet ignored = statement.executeQuery("SELECT " + column + " FROM " + table + " WHERE 1=0")) {
            return true;
        } catch (SQLException ignored) {
            return false;
        }
    }
}
