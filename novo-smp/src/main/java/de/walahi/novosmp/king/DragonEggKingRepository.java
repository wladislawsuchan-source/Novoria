package de.walahi.novosmp.king;

import de.walahi.smpcore.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class DragonEggKingRepository {
    private final DatabaseManager database;
    public DragonEggKingRepository(DatabaseManager database) { this.database = database; }

    public DragonEggKingState load() throws SQLException {
        String sql = "SELECT * FROM " + database.table("dragon_egg_king") + " WHERE singleton_id=1";
        try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql); ResultSet r = p.executeQuery()) {
            if (!r.next()) return null;
            return new DragonEggKingState(UUID.fromString(r.getString("token_uuid")),
                    KingLocationKind.valueOf(r.getString("location_kind")), uuid(r.getString("holder_uuid")),
                    r.getString("holder_name"), r.getString("world_name"), integer(r, "block_x"),
                    integer(r, "block_y"), integer(r, "block_z"), r.getString("detail"),
                    uuid(r.getString("reign_uuid")), r.getLong("reign_started_at"),
                    date(r.getString("duel_date")), r.getInt("mandatory_used"), r.getLong("updated_at"));
        }
    }

    public synchronized void save(DragonEggKingState s) throws SQLException {
        String delete = "DELETE FROM " + database.table("dragon_egg_king") + " WHERE singleton_id=1";
        String insert = "INSERT INTO " + database.table("dragon_egg_king") +
                " (singleton_id,token_uuid,location_kind,holder_uuid,holder_name,world_name,block_x,block_y,block_z,detail,reign_uuid,reign_started_at,duel_date,mandatory_used,updated_at) VALUES (1,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = database.connection()) {
            c.setAutoCommit(false);
            try (PreparedStatement d = c.prepareStatement(delete)) { d.executeUpdate(); }
            try (PreparedStatement p = c.prepareStatement(insert)) {
                p.setString(1, s.token().toString()); p.setString(2, s.kind().name());
                nullable(p, 3, s.holder() == null ? null : s.holder().toString(), Types.VARCHAR);
                nullable(p, 4, s.holderName(), Types.VARCHAR); nullable(p, 5, s.world(), Types.VARCHAR);
                nullable(p, 6, s.x(), Types.INTEGER); nullable(p, 7, s.y(), Types.INTEGER);
                nullable(p, 8, s.z(), Types.INTEGER); nullable(p, 9, s.detail(), Types.VARCHAR);
                nullable(p, 10, s.reign() == null ? null : s.reign().toString(), Types.VARCHAR);
                p.setLong(11, s.reignStartedAt()); nullable(p, 12, s.duelDate() == null ? null : s.duelDate().toString(), Types.VARCHAR);
                p.setInt(13, s.mandatoryUsed()); p.setLong(14, s.updatedAt()); p.executeUpdate();
            }
            c.commit();
        }
    }

    public Map<UUID, Long> loadCooldowns(long now) throws SQLException {
        Map<UUID, Long> out = new HashMap<>();
        String sql = "SELECT player_uuid,expires_at FROM " + database.table("dragon_egg_king_cooldowns") + " WHERE expires_at>?";
        try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setLong(1, now); try (ResultSet r = p.executeQuery()) { while (r.next()) out.put(UUID.fromString(r.getString(1)), r.getLong(2)); }
        }
        return out;
    }

    public void saveCooldown(UUID player, long expires) throws SQLException {
        try (Connection c = database.connection()) {
            try (PreparedStatement d = c.prepareStatement("DELETE FROM " + database.table("dragon_egg_king_cooldowns") + " WHERE player_uuid=?")) {
                d.setString(1, player.toString()); d.executeUpdate();
            }
            try (PreparedStatement p = c.prepareStatement("INSERT INTO " + database.table("dragon_egg_king_cooldowns") + " (player_uuid,expires_at) VALUES (?,?)")) {
                p.setString(1, player.toString()); p.setLong(2, expires); p.executeUpdate();
            }
        }
    }

    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
    private static LocalDate date(String value) { return value == null ? null : LocalDate.parse(value); }
    private static Integer integer(ResultSet r, String column) throws SQLException { int value = r.getInt(column); return r.wasNull() ? null : value; }
    private static void nullable(PreparedStatement p, int index, Object value, int type) throws SQLException {
        if (value == null) p.setNull(index, type); else p.setObject(index, value);
    }
}
