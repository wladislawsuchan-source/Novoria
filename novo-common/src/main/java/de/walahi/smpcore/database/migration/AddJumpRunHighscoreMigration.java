package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Adds the durable personal Jump'n'Run best score to the existing player statistics. */
public final class AddJumpRunHighscoreMigration implements SchemaMigration {
    @Override public int version() { return 33; }
    @Override public String description() { return "Jump'n'Run-Highscore in Spielerstatistiken"; }

    @Override
    public void apply(Connection connection, StorageDialect dialect, String prefix) throws SQLException {
        String table = prefix + "player_stats";
        try (Statement statement = connection.createStatement()) {
            if (!hasColumn(statement, table, "jumpnrun_highscore")) {
                statement.executeUpdate("ALTER TABLE " + table
                        + " ADD COLUMN jumpnrun_highscore BIGINT NOT NULL DEFAULT 0");
            }
        }
    }

    private boolean hasColumn(Statement statement, String table, String column) {
        try (ResultSet ignored = statement.executeQuery(
                "SELECT " + column + " FROM " + table + " WHERE 1=0")) {
            return true;
        } catch (SQLException ignored) {
            return false;
        }
    }
}
