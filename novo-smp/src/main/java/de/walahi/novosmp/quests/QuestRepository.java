package de.walahi.novosmp.quests;

import de.walahi.novosmp.lumi.LumiRepository;
import de.walahi.smpcore.storage.StorageDialect;
import de.walahi.smpcore.storage.StorageManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Uses the existing SMP StorageManager and Lumi account inside one SQL transaction per reward. */
final class QuestRepository {
    record DailyProgress(UUID id, double progress) { }
    record GlobalProgress(UUID instance, UUID player, String name, double progress, long reachedAt) { }
    record CooldownProgress(int slot, UUID instance, long remaining) { }

    private final StorageManager storage;
    private final LumiRepository lumis;
    QuestRepository(StorageManager storage, LumiRepository lumis) { this.storage = storage; this.lumis = lumis; }

    List<QuestState.Daily> loadDaily(UUID player) throws SQLException {
        List<QuestState.Daily> result = new ArrayList<>();
        try (Connection connection = storage.connection(); PreparedStatement sql = connection.prepareStatement(
                "SELECT instance_id,quest_day,slot_index,definition_id,progress,completed,key_delivered FROM "
                        + storage.table("quest_daily") + " WHERE player_uuid=? ORDER BY slot_index")) {
            sql.setString(1, player.toString());
            try (ResultSet rows = sql.executeQuery()) {
                while (rows.next()) result.add(new QuestState.Daily(UUID.fromString(rows.getString(1)), player,
                        LocalDate.parse(rows.getString(2)), rows.getInt(3), rows.getString(4),
                        rows.getDouble(5), rows.getInt(6) != 0, rows.getInt(7) != 0));
            }
        }
        return result;
    }

    void replaceDaily(UUID player, List<QuestState.Daily> quests) throws SQLException {
        try (Connection connection = storage.connection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM " + storage.table("quest_daily")
                                + " WHERE player_uuid=? AND (completed=0 OR key_delivered=1)")) {
                    delete.setString(1, player.toString()); delete.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO " + storage.table("quest_daily")
                        + " (instance_id,player_uuid,quest_day,slot_index,definition_id,progress,completed,key_delivered)"
                        + " VALUES (?,?,?,?,?,0,0,0)")) {
                    for (QuestState.Daily quest : quests) {
                        insert.setString(1, quest.id.toString()); insert.setString(2, player.toString());
                        insert.setString(3, quest.day.toString()); insert.setInt(4, quest.slot);
                        insert.setString(5, quest.definitionId); insert.addBatch();
                    }
                    insert.executeBatch();
                }
                connection.commit();
            } catch (SQLException error) { connection.rollback(); throw error; }
        }
    }

    List<QuestState.Global> loadGlobal() throws SQLException {
        List<QuestState.Global> slots = new ArrayList<>();
        try (Connection connection = storage.connection(); PreparedStatement sql = connection.prepareStatement(
                "SELECT slot_index,instance_id,definition_id,cooldown_left,winner_uuid FROM "
                        + storage.table("quest_global_slot") + " ORDER BY slot_index")) {
            try (ResultSet rows = sql.executeQuery()) {
                while (rows.next()) {
                    String id = rows.getString(2), winner = rows.getString(5);
                    slots.add(new QuestState.Global(rows.getInt(1), id == null ? null : UUID.fromString(id),
                            rows.getString(3), rows.getLong(4), 0L,
                            winner == null ? null : UUID.fromString(winner)));
                }
            }
            try (PreparedStatement progress = connection.prepareStatement("SELECT player_uuid,player_name,progress,reached_at FROM "
                    + storage.table("quest_global_progress") + " WHERE instance_id=?")) {
                for (QuestState.Global slot : slots) {
                    if (slot.id == null || slot.cooling()) continue;
                    progress.setString(1, slot.id.toString());
                    try (ResultSet rows = progress.executeQuery()) {
                        while (rows.next()) {
                            UUID player = UUID.fromString(rows.getString(1));
                            slot.participants.put(player, new QuestState.Participant(player, rows.getString(2),
                                    rows.getDouble(3), rows.getLong(4)));
                        }
                    }
                }
            }
        }
        return slots;
    }

    void saveSlot(QuestState.Global slot, UUID oldInstance) throws SQLException {
        try (Connection connection = storage.connection()) {
            connection.setAutoCommit(false);
            try {
                if (oldInstance != null && !oldInstance.equals(slot.id)) {
                    try (PreparedStatement delete = connection.prepareStatement("DELETE FROM "
                            + storage.table("quest_global_progress") + " WHERE instance_id=?")) {
                        delete.setString(1, oldInstance.toString()); delete.executeUpdate();
                    }
                }
                int updated;
                try (PreparedStatement sql = connection.prepareStatement("UPDATE " + storage.table("quest_global_slot")
                        + " SET instance_id=?,definition_id=?,cooldown_left=?,active_since=?,winner_uuid=? WHERE slot_index=?")) {
                    bindSlot(sql, slot); sql.setInt(6, slot.slot); updated = sql.executeUpdate();
                }
                if (updated == 0) try (PreparedStatement sql = connection.prepareStatement("INSERT INTO "
                        + storage.table("quest_global_slot")
                        + " (instance_id,definition_id,cooldown_left,active_since,winner_uuid,slot_index) VALUES (?,?,?,?,?,?)")) {
                    bindSlot(sql, slot); sql.setInt(6, slot.slot); sql.executeUpdate();
                }
                connection.commit();
            } catch (SQLException error) { connection.rollback(); throw error; }
        }
    }

    private static void bindSlot(PreparedStatement sql, QuestState.Global slot) throws SQLException {
        sql.setString(1, slot.id == null ? null : slot.id.toString());
        sql.setString(2, slot.definitionId);
        sql.setLong(3, slot.cooldownLeft);
        sql.setLong(4, slot.activeSince);
        sql.setString(5, slot.winner == null ? null : slot.winner.toString());
    }

    boolean completeDaily(QuestState.Daily quest, int lumisAmount, int keyAmount) throws SQLException {
        try (Connection connection = storage.connection()) {
            connection.setAutoCommit(false);
            try {
                int updated;
                try (PreparedStatement sql = connection.prepareStatement("UPDATE " + storage.table("quest_daily")
                        + " SET progress=?,completed=1 WHERE instance_id=? AND completed=0")) {
                    sql.setDouble(1, quest.progress); sql.setString(2, quest.id.toString()); updated = sql.executeUpdate();
                }
                if (updated != 1) { connection.rollback(); return false; }
                insertReward(connection, quest.id, quest.player, lumisAmount, keyAmount);
                connection.commit(); return true;
            } catch (SQLException error) { connection.rollback(); throw error; }
        }
    }

    boolean completeGlobal(QuestState.Global slot, UUID winner, int lumisAmount, long cooldown) throws SQLException {
        try (Connection connection = storage.connection()) {
            connection.setAutoCommit(false);
            try {
                int updated;
                try (PreparedStatement sql = connection.prepareStatement("UPDATE " + storage.table("quest_global_slot")
                        + " SET definition_id=NULL,cooldown_left=?,active_since=?,winner_uuid=?"
                        + " WHERE slot_index=? AND instance_id=? AND definition_id IS NOT NULL AND winner_uuid IS NULL")) {
                    sql.setLong(1, cooldown); sql.setLong(2, System.currentTimeMillis());
                    sql.setString(3, winner.toString()); sql.setInt(4, slot.slot);
                    sql.setString(5, slot.id.toString()); updated = sql.executeUpdate();
                }
                if (updated != 1) { connection.rollback(); return false; }
                insertReward(connection, slot.id, winner, lumisAmount, 0);
                connection.commit(); return true;
            } catch (SQLException error) { connection.rollback(); throw error; }
        }
    }

    private void insertReward(Connection connection, UUID id, UUID player, int amount, int keyAmount) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement("INSERT INTO " + storage.table("quest_reward_ledger")
                + " (instance_id,player_uuid,lumi_amount,daily_key,awarded_at) VALUES (?,?,?,?,?)")) {
            sql.setString(1, id.toString()); sql.setString(2, player.toString());
            sql.setLong(3, amount); sql.setInt(4, keyAmount);
            sql.setLong(5, System.currentTimeMillis()); sql.executeUpdate();
        }
        if (amount > 0 && !lumis.add(connection, player, amount)) throw new SQLException("Lumi-Gutschrift abgelehnt");
    }

    void markKeyDelivered(UUID id) throws SQLException {
        try (Connection connection = storage.connection(); PreparedStatement sql = connection.prepareStatement(
                "UPDATE " + storage.table("quest_daily") + " SET key_delivered=1 WHERE instance_id=?")) {
            sql.setString(1, id.toString()); sql.executeUpdate();
        }
    }

    int keyAmount(UUID id) throws SQLException {
        try (Connection connection = storage.connection(); PreparedStatement sql = connection.prepareStatement(
                "SELECT daily_key FROM " + storage.table("quest_reward_ledger") + " WHERE instance_id=?")) {
            sql.setString(1, id.toString());
            try (ResultSet rows = sql.executeQuery()) { return rows.next() ? rows.getInt(1) : 0; }
        }
    }

    void saveProgress(List<DailyProgress> daily, List<GlobalProgress> global,
                      List<CooldownProgress> cooldowns) throws SQLException {
        if (daily.isEmpty() && global.isEmpty() && cooldowns.isEmpty()) return;
        try (Connection connection = storage.connection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement sql = connection.prepareStatement("UPDATE " + storage.table("quest_daily")
                        + " SET progress=? WHERE instance_id=? AND completed=0 AND progress<?")) {
                    for (DailyProgress row : daily) {
                        sql.setDouble(1, row.progress); sql.setString(2, row.id.toString());
                        sql.setDouble(3, row.progress); sql.addBatch();
                    }
                    sql.executeBatch();
                }
                String table = storage.table("quest_global_progress");
                String insert = storage.dialect() == StorageDialect.SQLITE
                        ? "INSERT INTO " + table + " (instance_id,player_uuid,player_name,progress,reached_at) VALUES (?,?,?,?,?)"
                            + " ON CONFLICT(instance_id,player_uuid) DO UPDATE SET player_name=excluded.player_name,"
                            + " progress=excluded.progress,reached_at=excluded.reached_at WHERE excluded.progress>progress"
                        : "INSERT INTO " + table + " (instance_id,player_uuid,player_name,progress,reached_at) VALUES (?,?,?,?,?)"
                            + " ON DUPLICATE KEY UPDATE player_name=IF(VALUES(progress)>progress,VALUES(player_name),player_name),"
                            + " reached_at=IF(VALUES(progress)>progress,VALUES(reached_at),reached_at),"
                            + " progress=GREATEST(progress,VALUES(progress))";
                try (PreparedStatement sql = connection.prepareStatement(insert)) {
                    for (GlobalProgress row : global) {
                        sql.setString(1, row.instance.toString()); sql.setString(2, row.player.toString());
                        sql.setString(3, row.name); sql.setDouble(4, row.progress);
                        sql.setLong(5, row.reachedAt); sql.addBatch();
                    }
                    sql.executeBatch();
                }
                String minimum = storage.dialect() == StorageDialect.SQLITE ? "MIN" : "LEAST";
                try (PreparedStatement sql = connection.prepareStatement("UPDATE " + storage.table("quest_global_slot")
                        + " SET cooldown_left=" + minimum + "(cooldown_left,?)"
                        + " WHERE slot_index=? AND instance_id=? AND definition_id IS NULL AND active_since>0")) {
                    for (CooldownProgress row : cooldowns) {
                        sql.setLong(1, row.remaining); sql.setInt(2, row.slot);
                        sql.setString(3, row.instance.toString()); sql.addBatch();
                    }
                    sql.executeBatch();
                }
                connection.commit();
            } catch (SQLException error) { connection.rollback(); throw error; }
        }
    }

    void cleanup(LocalDate oldestDay, long oldestRewardTime) throws SQLException {
        try (Connection connection = storage.connection()) {
            try (PreparedStatement sql = connection.prepareStatement("DELETE FROM " + storage.table("quest_global_progress")
                    + " WHERE instance_id NOT IN (SELECT instance_id FROM " + storage.table("quest_global_slot")
                    + " WHERE instance_id IS NOT NULL)")) { sql.executeUpdate(); }
            try (PreparedStatement sql = connection.prepareStatement("DELETE FROM " + storage.table("quest_daily")
                    + " WHERE quest_day<? AND (completed=0 OR key_delivered=1)")) {
                sql.setString(1, oldestDay.toString()); sql.executeUpdate();
            }
            try (PreparedStatement sql = connection.prepareStatement("DELETE FROM " + storage.table("quest_reward_ledger")
                    + " WHERE awarded_at<? AND (daily_key=0 OR instance_id NOT IN (SELECT instance_id FROM "
                    + storage.table("quest_daily") + " WHERE key_delivered=0))")) {
                sql.setLong(1, oldestRewardTime); sql.executeUpdate();
            }
        }
    }
}
