package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Widens readable player-name columns on existing MySQL/MariaDB installations.
 *
 * <p>Older Novoria schemas used VARCHAR(16) or VARCHAR(32). That can reject
 * proxy/Bedrock names even though newer code no longer relies on names as IDs.</p>
 */
public final class WidenPlayerNameColumnsMigration implements SchemaMigration {
    private static final int TARGET_LENGTH = 64;

    @Override public int version() { return 25; }
    @Override public String description() { return "Spielernamen-Spalten auf 64 Zeichen erweitern"; }

    @Override
    public void apply(Connection connection, StorageDialect dialect, String tablePrefix) throws SQLException {
        // SQLite does not enforce VARCHAR(n) length and therefore needs no schema rewrite.
        if (dialect == StorageDialect.SQLITE) return;

        DatabaseMetaData metadata = connection.getMetaData();
        String catalog = connection.getCatalog();
        List<String> tablesToCheck = new ArrayList<>();
        try (ResultSet tables = metadata.getTables(catalog, null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String table = tables.getString("TABLE_NAME");
                if (table != null && table.startsWith(tablePrefix)) tablesToCheck.add(table);
            }
        }
        for (String table : tablesToCheck) {
            widenPlayerNameColumn(connection, metadata, catalog, table);
        }
    }

    private void widenPlayerNameColumn(Connection connection, DatabaseMetaData metadata,
                                       String catalog, String table) throws SQLException {
        try (ResultSet columns = metadata.getColumns(catalog, null, table, "player_name")) {
            if (!columns.next()) return;

            int jdbcType = columns.getInt("DATA_TYPE");
            int currentSize = columns.getInt("COLUMN_SIZE");
            if (!isTextColumn(jdbcType) || currentSize >= TARGET_LENGTH) return;

            boolean nullable = columns.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
            String sql = "ALTER TABLE " + quote(table)
                    + " MODIFY COLUMN `player_name` VARCHAR(" + TARGET_LENGTH + ") "
                    + (nullable ? "NULL" : "NOT NULL");
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(sql);
            }
        }
    }

    private boolean isTextColumn(int jdbcType) {
        return jdbcType == Types.CHAR
                || jdbcType == Types.VARCHAR
                || jdbcType == Types.LONGVARCHAR
                || jdbcType == Types.NCHAR
                || jdbcType == Types.NVARCHAR
                || jdbcType == Types.LONGNVARCHAR;
    }

    private String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }
}
