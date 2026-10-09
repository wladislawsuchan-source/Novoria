package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Old releases prefilled every future Angler stage with lifetime Green hits. */
public final class ResetAnglerGreenHitStagesMigration implements SchemaMigration {
    @Override public int version() { return 36; }
    @Override public String description() { return "Angler Green-Hits auf stufenbezogenen Fortschritt umstellen"; }

    @Override
    public void apply(Connection connection, StorageDialect dialect, String prefix) throws SQLException {
        String contributions = prefix + "profession_contributions";
        String profiles = prefix + "profession_profiles";
        String sql = "DELETE FROM " + contributions + " WHERE profession_id='angler' AND requirement_id='green_hits'"
                + " AND EXISTS (SELECT 1 FROM " + profiles + " p WHERE p.player_uuid=" + contributions + ".player_uuid"
                + " AND p.profession_id='angler' AND p.prestige=" + contributions + ".prestige"
                + " AND " + contributions + ".milestone>p.completed_milestone)";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }
}
