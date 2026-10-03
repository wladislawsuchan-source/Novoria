package de.walahi.novosmp.stats;

import de.walahi.smpcore.database.DatabaseManager;
import de.walahi.smpcore.storage.StorageDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

/** SQL persistence for player statistics. No Bukkit or GUI responsibilities. */
final class StatsRepository {
    private final DatabaseManager database;
    private final Logger logger;

    StatsRepository(DatabaseManager database, Logger logger) {
        this.database = Objects.requireNonNull(database, "database");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    List<StatsSnapshot> loadAll() throws SQLException {
        String sql = "SELECT player_uuid,player_name,kills,deaths,blocks_mined,mobs_killed," +
                "playtime_seconds,blocks_placed,sell_earnings,advancements,prestige,heads_collected,registered_at,last_seen FROM " +
                database.table("player_stats");
        List<StatsSnapshot> result = new ArrayList<>();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                try {
                    result.add(read(rows));
                } catch (IllegalArgumentException exception) {
                    logger.warning("Ungültige UUID in der Statistikdatenbank: "
                            + rows.getString("player_uuid"));
                }
            }
        }
        return result;
    }

    void saveAll(Collection<StatsSnapshot> entries) throws SQLException {
        if (entries == null || entries.isEmpty()) return;
        try (Connection connection = database.connection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(upsertSql())) {
                for (StatsSnapshot entry : entries) {
                    bind(statement, entry);
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
            } catch (SQLException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            } finally {
                try {
                    connection.setAutoCommit(previousAutoCommit);
                } catch (SQLException ignored) {
                }
            }
        }
    }

    private StatsSnapshot read(ResultSet row) throws SQLException {
        return new StatsSnapshot(
                UUID.fromString(row.getString("player_uuid")),
                row.getString("player_name"),
                row.getLong("kills"),
                row.getLong("deaths"),
                row.getLong("blocks_mined"),
                row.getLong("mobs_killed"),
                row.getLong("playtime_seconds"),
                row.getLong("blocks_placed"),
                row.getLong("sell_earnings"),
                row.getLong("advancements"),
                row.getLong("prestige"),
                row.getLong("heads_collected"),
                row.getLong("registered_at"),
                row.getLong("last_seen")
        );
    }

    private void bind(PreparedStatement statement, StatsSnapshot entry) throws SQLException {
        statement.setString(1, entry.uuid().toString());
        statement.setString(2, entry.name());
        statement.setLong(3, entry.kills());
        statement.setLong(4, entry.deaths());
        statement.setLong(5, entry.blocksMined());
        statement.setLong(6, entry.mobsKilled());
        statement.setLong(7, entry.playtimeSeconds());
        statement.setLong(8, entry.blocksPlaced());
        statement.setLong(9, entry.sellEarnings());
        statement.setLong(10, entry.advancements());
        statement.setLong(11, entry.prestige());
        statement.setLong(12, entry.headsCollected());
        statement.setLong(13, entry.registeredAt());
        statement.setLong(14, entry.lastSeen());
    }

    private String upsertSql() {
        String table = database.table("player_stats");
        String columns = "player_uuid,player_name,kills,deaths,blocks_mined,mobs_killed," +
                "playtime_seconds,blocks_placed,sell_earnings,advancements,prestige,heads_collected,registered_at,last_seen";
        String values = "?,?,?,?,?,?,?,?,?,?,?,?,?,?";
        if (database.dialect() == StorageDialect.SQLITE) {
            return "INSERT INTO " + table + " (" + columns + ") VALUES (" + values + ") " +
                    "ON CONFLICT(player_uuid) DO UPDATE SET " +
                    "player_name=excluded.player_name,kills=excluded.kills,deaths=excluded.deaths," +
                    "blocks_mined=excluded.blocks_mined,mobs_killed=excluded.mobs_killed," +
                    "playtime_seconds=excluded.playtime_seconds,blocks_placed=excluded.blocks_placed," +
                    "sell_earnings=excluded.sell_earnings,advancements=excluded.advancements," +
                    "prestige=excluded.prestige,heads_collected=excluded.heads_collected," +
                    "last_seen=excluded.last_seen";
        }
        return "INSERT INTO " + table + " (" + columns + ") VALUES (" + values + ") " +
                "ON DUPLICATE KEY UPDATE " +
                "player_name=VALUES(player_name),kills=VALUES(kills),deaths=VALUES(deaths)," +
                "blocks_mined=VALUES(blocks_mined),mobs_killed=VALUES(mobs_killed)," +
                "playtime_seconds=VALUES(playtime_seconds),blocks_placed=VALUES(blocks_placed)," +
                "sell_earnings=VALUES(sell_earnings),advancements=VALUES(advancements)," +
                "prestige=VALUES(prestige),heads_collected=VALUES(heads_collected)," +
                "last_seen=VALUES(last_seen)";
    }
}
