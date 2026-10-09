package de.walahi.novosmp.professions;

import de.walahi.smpcore.database.DatabaseManager;
import de.walahi.smpcore.storage.StorageDialect;
import org.bukkit.Material;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class ProfessionRepository {
    private final DatabaseManager database;

    public ProfessionRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public PlayerProfessionState load(UUID playerId) {
        PlayerProfessionState state = new PlayerProfessionState(playerId);
        try (Connection connection = database.connection()) {
            loadMeta(connection, state);
            List<PlayerProfessionState.SlotProjection> persistedSlots = loadActive(connection, state);
            loadProfiles(connection, state);
            state.progress(ProfessionManager.LUMBERJACK);
            state.progress(ProfessionManager.MINER);
            state.progress(ProfessionManager.HUNTER);
            state.progress(ProfessionManager.ANGLER);
            state.markMetaClean();
            state.progress().values().forEach(ProfessionProgress::markClean);
            state.loadedSlots(persistedSlots);
            return state;
        } catch (SQLException exception) {
            throw new IllegalStateException("Berufsdaten konnten nicht geladen werden", exception);
        }
    }

    public void save(PlayerProfessionState state) {
        try (Connection connection = database.connection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                boolean rewriteSlots = state.slotsNeedRewrite();
                if (state.metaDirty()) saveMeta(connection, state);
                if (rewriteSlots) saveActive(connection, state);
                for (ProfessionProgress progress : state.progress().values()) {
                    if (progress.dirty()) saveProgress(connection, state.playerId(), progress);
                }
                connection.commit();
                if (rewriteSlots) state.confirmPersistedSlots();
                state.markMetaClean();
                state.progress().values().forEach(ProfessionProgress::markClean);
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                restoreAutoCommit(connection, previous);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Berufsdaten konnten nicht gespeichert werden", exception);
        }
    }

    public Map<String, Long> contributions(UUID playerId, String professionId, int prestige, int milestone) {
        Map<String, Long> result = new HashMap<>();
        String sql = "SELECT requirement_id, amount FROM " + database.table("profession_contributions")
                + " WHERE player_uuid=? AND profession_id=? AND prestige=? AND milestone=?";
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, professionId);
            statement.setInt(3, prestige);
            statement.setInt(4, milestone);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.put(rows.getString(1), rows.getLong(2));
            }
            return result;
        } catch (SQLException exception) {
            throw new IllegalStateException("Berufsabgaben konnten nicht geladen werden", exception);
        }
    }

    public List<TrackedSapling> loadTrackedSaplings() {
        List<TrackedSapling> result = new ArrayList<>();
        String sql = "SELECT world_uuid,block_x,block_y,block_z,owner_uuid,prestige,milestone,"
                + "growth_group_id,sapling_material,planted_at FROM "
                + database.table("profession_planted_saplings") + " WHERE profession_id=?";
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ProfessionManager.LUMBERJACK);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    try {
                        Material material = Material.matchMaterial(rows.getString("sapling_material"));
                        if (material == null) continue;
                        result.add(new TrackedSapling(
                                UUID.fromString(rows.getString("world_uuid")),
                                rows.getInt("block_x"), rows.getInt("block_y"), rows.getInt("block_z"),
                                UUID.fromString(rows.getString("owner_uuid")),
                                rows.getInt("prestige"), rows.getInt("milestone"),
                                rows.getString("growth_group_id"), material, rows.getLong("planted_at")
                        ));
                    } catch (IllegalArgumentException ignored) { }
                }
            }
            return result;
        } catch (SQLException exception) {
            throw new IllegalStateException("Gespeicherte Setzlinge konnten nicht geladen werden", exception);
        }
    }

    public long trackedSaplingCount(UUID ownerId, int prestige, int milestone, String growthGroupId) {
        String sql = "SELECT COUNT(*) FROM " + database.table("profession_planted_saplings")
                + " WHERE owner_uuid=? AND profession_id=? AND prestige=? AND milestone=? AND growth_group_id=?";
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId.toString());
            statement.setString(2, ProfessionManager.LUMBERJACK);
            statement.setInt(3, prestige);
            statement.setInt(4, milestone);
            statement.setString(5, growthGroupId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Gesetzte Setzlinge konnten nicht gezählt werden", exception);
        }
    }

    public void saveTrackedSapling(TrackedSapling sapling) {
        String table = database.table("profession_planted_saplings");
        String update = "UPDATE " + table + " SET owner_uuid=?,profession_id=?,prestige=?,milestone=?,"
                + "growth_group_id=?,sapling_material=?,planted_at=? WHERE world_uuid=? AND block_x=? AND block_y=? AND block_z=?";
        try (Connection connection = database.connection()) {
            try (PreparedStatement statement = connection.prepareStatement(update)) {
                statement.setString(1, sapling.ownerId().toString());
                statement.setString(2, ProfessionManager.LUMBERJACK);
                statement.setInt(3, sapling.prestige());
                statement.setInt(4, sapling.milestone());
                statement.setString(5, sapling.growthGroupId());
                statement.setString(6, sapling.material().name());
                statement.setLong(7, sapling.plantedAt());
                statement.setString(8, sapling.worldId().toString());
                statement.setInt(9, sapling.x());
                statement.setInt(10, sapling.y());
                statement.setInt(11, sapling.z());
                if (statement.executeUpdate() == 1) return;
            }
            String insert = "INSERT INTO " + table
                    + " (world_uuid,block_x,block_y,block_z,owner_uuid,profession_id,prestige,milestone,"
                    + "growth_group_id,sapling_material,planted_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)";
            try (PreparedStatement statement = connection.prepareStatement(insert)) {
                statement.setString(1, sapling.worldId().toString());
                statement.setInt(2, sapling.x());
                statement.setInt(3, sapling.y());
                statement.setInt(4, sapling.z());
                statement.setString(5, sapling.ownerId().toString());
                statement.setString(6, ProfessionManager.LUMBERJACK);
                statement.setInt(7, sapling.prestige());
                statement.setInt(8, sapling.milestone());
                statement.setString(9, sapling.growthGroupId());
                statement.setString(10, sapling.material().name());
                statement.setLong(11, sapling.plantedAt());
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Gesetzter Setzling konnte nicht gespeichert werden", exception);
        }
    }

    public void deleteTrackedSapling(TrackedSapling sapling) {
        try (Connection connection = database.connection()) {
            deleteTrackedSapling(connection, sapling);
        } catch (SQLException exception) {
            throw new IllegalStateException("Gesetzter Setzling konnte nicht entfernt werden", exception);
        }
    }

    public long creditGrowthAndDelete(TrackedSapling sapling, String requirementId, long maximum) {
        try (Connection connection = database.connection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                long current = contribution(connection, sapling.ownerId(), ProfessionManager.LUMBERJACK,
                        sapling.prestige(), sapling.milestone(), requirementId);
                long updated = maximum <= 0L ? current : Math.min(maximum, Math.addExact(current, 1L));
                if (maximum > 0L) {
                    upsertContribution(connection, sapling.ownerId(), ProfessionManager.LUMBERJACK,
                            sapling.prestige(), sapling.milestone(), requirementId, updated);
                }
                deleteTrackedSapling(connection, sapling);
                connection.commit();
                return updated;
            } catch (SQLException | ArithmeticException exception) {
                if (exception instanceof SQLException sqlException) rollback(connection, sqlException);
                else try { connection.rollback(); } catch (SQLException ignored) { }
                throw exception;
            } finally {
                restoreAutoCommit(connection, previous);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Gewachsener Setzling konnte nicht gewertet werden", exception);
        }
    }

    public long addContribution(UUID playerId, String professionId, int prestige, int milestone,
                                String requirementId, long delta, long maximum) {
        if (delta <= 0L || maximum <= 0L) return contribution(playerId, professionId, prestige, milestone, requirementId);
        try (Connection connection = database.connection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                long current = contribution(connection, playerId, professionId, prestige, milestone, requirementId);
                long updated = Math.min(maximum, Math.addExact(current, delta));
                upsertContribution(connection, playerId, professionId, prestige, milestone, requirementId, updated);
                connection.commit();
                return updated;
            } catch (SQLException | ArithmeticException exception) {
                if (exception instanceof SQLException sqlException) rollback(connection, sqlException);
                else try { connection.rollback(); } catch (SQLException ignored) { }
                throw exception;
            } finally {
                restoreAutoCommit(connection, previous);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Berufsabgabe konnte nicht gespeichert werden", exception);
        }
    }

    /** Green hits belong only to the current stage; combo remains a prestige-wide best value. */
    static boolean shouldCreditGreenHit(int currentMilestone, int requirementLevel, boolean greenHit) {
        return greenHit && currentMilestone == requirementLevel;
    }

    public void recordAnglerSkills(UUID playerId, int prestige, Collection<MilestoneRequirement> milestones,
                                   int currentMilestone, boolean greenHit, int combo) {
        if (!greenHit && combo <= 0) return;
        try (Connection connection = database.connection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                for (MilestoneRequirement requirement : milestones) {
                    long greenTarget = requirement.skills().getOrDefault("green_hits", 0L);
                    if (shouldCreditGreenHit(currentMilestone, requirement.level(), greenHit) && greenTarget > 0L) {
                        long current = contribution(connection, playerId, ProfessionManager.ANGLER,
                                prestige, requirement.level(), "green_hits");
                        if (current < greenTarget) upsertContribution(connection, playerId, ProfessionManager.ANGLER,
                                prestige, requirement.level(), "green_hits", current + 1L);
                    }
                    long comboTarget = requirement.skills().getOrDefault("max_combo", 0L);
                    if (combo > 0 && comboTarget > 0L) {
                        long current = contribution(connection, playerId, ProfessionManager.ANGLER,
                                prestige, requirement.level(), "max_combo");
                        long reached = Math.min(comboTarget, combo);
                        if (reached > current) upsertContribution(connection, playerId, ProfessionManager.ANGLER,
                                prestige, requirement.level(), "max_combo", reached);
                    }
                }
                connection.commit();
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                restoreAutoCommit(connection, previous);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Angler-Skillfortschritt konnte nicht gespeichert werden", exception);
        }
    }

    public Map<String, Long> addContributions(UUID playerId, String professionId, int prestige, int milestone,
                                              Map<String, Long> deltas, Map<String, Long> maxima) {
        Map<String, Long> updated = new HashMap<>();
        if (deltas == null || deltas.isEmpty()) return updated;
        try (Connection connection = database.connection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                for (Map.Entry<String, Long> entry : deltas.entrySet()) {
                    String requirementId = entry.getKey();
                    long delta = Math.max(0L, entry.getValue());
                    long maximum = Math.max(0L, maxima.getOrDefault(requirementId, 0L));
                    if (delta <= 0L || maximum <= 0L) continue;
                    long current = contribution(connection, playerId, professionId, prestige, milestone, requirementId);
                    long value = Math.min(maximum, Math.addExact(current, delta));
                    upsertContribution(connection, playerId, professionId, prestige, milestone, requirementId, value);
                    updated.put(requirementId, value);
                }
                connection.commit();
                return updated;
            } catch (SQLException | ArithmeticException exception) {
                if (exception instanceof SQLException sqlException) rollback(connection, sqlException);
                else try { connection.rollback(); } catch (SQLException ignored) { }
                throw exception;
            } finally {
                restoreAutoCommit(connection, previous);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Berufsabgaben konnten nicht gespeichert werden", exception);
        }
    }

    public void saveProgressImmediate(UUID playerId, ProfessionProgress progress) {
        try (Connection connection = database.connection()) {
            saveProgress(connection, playerId, progress);
            progress.markClean();
        } catch (SQLException exception) {
            throw new IllegalStateException("Berufsfortschritt konnte nicht gespeichert werden", exception);
        }
    }

    /** Commit the completed Angler stage and start the next Green-hit stage at zero together. */
    public void completeAnglerMilestone(UUID playerId, ProfessionProgress progress, int nextMilestone) {
        try (Connection connection = database.connection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                saveProgress(connection, playerId, progress);
                resetGreenHitStage(connection, database.table("profession_contributions"),
                        playerId, progress.prestige(), nextMilestone);
                connection.commit();
                progress.markClean();
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                restoreAutoCommit(connection, previous);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Angler-Meilenstein konnte nicht gespeichert werden", exception);
        }
    }

    static void resetGreenHitStage(Connection connection, String table, UUID playerId,
                                   int prestige, int nextMilestone) throws SQLException {
        if (nextMilestone <= 0) return;
        String sql = "DELETE FROM " + table
                + " WHERE player_uuid=? AND profession_id=? AND prestige=? AND milestone=? AND requirement_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, ProfessionManager.ANGLER);
            statement.setInt(3, prestige);
            statement.setInt(4, nextMilestone);
            statement.setString(5, "green_hits");
            statement.executeUpdate();
        }
    }

    public void saveMetaAndActive(PlayerProfessionState state) {
        try (Connection connection = database.connection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                saveMeta(connection, state);
                saveActive(connection, state);
                connection.commit();
                state.confirmPersistedSlots();
                state.markMetaClean();
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                restoreAutoCommit(connection, previous);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Aktive Berufe konnten nicht gespeichert werden", exception);
        }
    }

    public void prestige(UUID playerId, ProfessionProgress progress, String oldSerial,
                         String newSerial, int newPrestige, boolean unlockSecondSlot,
                         PlayerProfessionState state) {
        try (Connection connection = database.connection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (oldSerial != null && !oldSerial.isBlank()) deactivateTool(connection, oldSerial);
                insertTool(connection, newSerial, playerId, progress.professionId(), newPrestige);

                progress.prestige(newPrestige);
                progress.level(1);
                progress.xp(0D);
                progress.completedMilestone(0);
                progress.activeToolSerial(newSerial);
                state.freeSwitch(true);
                if (unlockSecondSlot) state.secondSlotUnlocked(true);

                saveProgress(connection, playerId, progress);
                saveMeta(connection, state);
                connection.commit();
                progress.markClean();
                state.markMetaClean();
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                restoreAutoCommit(connection, previous);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Prestige konnte nicht gespeichert werden", exception);
        }
    }

    public void prestigeWithoutTool(UUID playerId, ProfessionProgress progress, int newPrestige,
                                    boolean unlockSecondSlot, PlayerProfessionState state) {
        try (Connection connection = database.connection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                progress.prestige(newPrestige);
                progress.level(1);
                progress.xp(0D);
                progress.completedMilestone(0);
                progress.activeToolSerial(null);
                state.freeSwitch(true);
                if (unlockSecondSlot) state.secondSlotUnlocked(true);

                saveProgress(connection, playerId, progress);
                saveMeta(connection, state);
                connection.commit();
                progress.markClean();
                state.markMetaClean();
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                restoreAutoCommit(connection, previous);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Prestige konnte nicht gespeichert werden", exception);
        }
    }

    public void prestigeWithCollectedTool(UUID playerId, ProfessionProgress progress, String newSerial,
                                          int newPrestige, boolean unlockSecondSlot,
                                          PlayerProfessionState state) {
        try (Connection connection = database.connection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                progress.prestige(newPrestige);
                progress.level(1);
                progress.xp(0D);
                progress.completedMilestone(0);
                progress.activeToolSerial(null);
                state.freeSwitch(true);
                if (unlockSecondSlot) state.secondSlotUnlocked(true);

                insertTool(connection, newSerial, playerId, progress.professionId(), newPrestige);
                saveProgress(connection, playerId, progress);
                saveMeta(connection, state);
                connection.commit();
                progress.markClean();
                state.markMetaClean();
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                restoreAutoCommit(connection, previous);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Prestige-Werkzeug konnte nicht gespeichert werden", exception);
        }
    }

    public List<String> replaceCollectedTool(UUID playerId, String professionId, int toolPrestige,
                                             String newSerial) {
        try (Connection connection = database.connection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                List<String> previousSerials = activeToolSerials(connection, playerId, professionId, toolPrestige);
                deactivateTools(connection, playerId, professionId, toolPrestige);
                insertTool(connection, newSerial, playerId, professionId, toolPrestige);
                connection.commit();
                return previousSerials;
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                restoreAutoCommit(connection, previous);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Bergarbeiter-Ersatzwerkzeug konnte nicht gespeichert werden", exception);
        }
    }

    public void replaceTool(UUID playerId, ProfessionProgress progress, String oldSerial, String newSerial) {
        try (Connection connection = database.connection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (oldSerial != null && !oldSerial.isBlank()) deactivateTool(connection, oldSerial);
                insertTool(connection, newSerial, playerId, progress.professionId(), progress.prestige());
                progress.activeToolSerial(newSerial);
                saveProgress(connection, playerId, progress);
                connection.commit();
                progress.markClean();
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                restoreAutoCommit(connection, previous);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Ersatzwerkzeug konnte nicht gespeichert werden", exception);
        }
    }

    public boolean claimLegacyCollectedTool(UUID playerId, String professionId, int toolPrestige,
                                            String serial) {
        try (Connection connection = database.connection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (!activeToolSerials(connection, playerId, professionId, toolPrestige).isEmpty()) {
                    connection.rollback();
                    return false;
                }
                insertTool(connection, serial, playerId, professionId, toolPrestige);
                connection.commit();
                return true;
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                restoreAutoCommit(connection, previous);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Altes Bergarbeiter-Werkzeug konnte nicht registriert werden", exception);
        }
    }

    public boolean isToolActive(String serial) {
        if (serial == null || serial.isBlank()) return false;
        String sql = "SELECT active FROM " + database.table("profession_tools") + " WHERE serial_id=?";
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, serial);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Werkzeugstatus konnte nicht geprüft werden", exception);
        }
    }

    public boolean claimReward(UUID playerId, String professionId, int prestige, int level) {
        String sql = database.dialect() == StorageDialect.SQLITE
                ? "INSERT OR IGNORE INTO " + database.table("profession_reward_claims")
                    + " (player_uuid,profession_id,prestige,reward_level,claimed_at) VALUES (?,?,?,?,?)"
                : "INSERT IGNORE INTO " + database.table("profession_reward_claims")
                    + " (player_uuid,profession_id,prestige,reward_level,claimed_at) VALUES (?,?,?,?,?)";
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, professionId);
            statement.setInt(3, prestige);
            statement.setInt(4, level);
            statement.setLong(5, System.currentTimeMillis());
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new IllegalStateException("Levelbelohnung konnte nicht reserviert werden", exception);
        }
    }

    public void unclaimReward(UUID playerId, String professionId, int prestige, int level) {
        String sql = "DELETE FROM " + database.table("profession_reward_claims")
                + " WHERE player_uuid=? AND profession_id=? AND prestige=? AND reward_level=?";
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, professionId);
            statement.setInt(3, prestige);
            statement.setInt(4, level);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Levelbelohnung konnte nicht freigegeben werden", exception);
        }
    }

    public List<Integer> claimedRewards(UUID playerId, String professionId, int prestige) {
        List<Integer> result = new ArrayList<>();
        String sql = "SELECT reward_level FROM " + database.table("profession_reward_claims")
                + " WHERE player_uuid=? AND profession_id=? AND prestige=?";
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, professionId);
            statement.setInt(3, prestige);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(rows.getInt(1));
            }
            return result;
        } catch (SQLException exception) {
            throw new IllegalStateException("Levelbelohnungen konnten nicht geladen werden", exception);
        }
    }

    public Map<BoosterCategory, ActiveBooster> loadBoosters(UUID playerId) {
        Map<BoosterCategory, ActiveBooster> result = new EnumMap<>(BoosterCategory.class);
        String sql = "SELECT category,multiplier,remaining_seconds FROM " + database.table("player_boosters")
                + " WHERE player_uuid=?";
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    try {
                        BoosterCategory category = BoosterCategory.valueOf(rows.getString(1));
                        int remaining = Math.max(0, rows.getInt(3));
                        if (remaining > 0) result.put(category,
                                new ActiveBooster(category, Math.max(1D, rows.getDouble(2)), remaining));
                    } catch (IllegalArgumentException ignored) { }
                }
            }
            return result;
        } catch (SQLException exception) {
            throw new IllegalStateException("Booster konnten nicht geladen werden", exception);
        }
    }

    public void saveBooster(UUID playerId, ActiveBooster booster) {
        if (booster.remainingSeconds() <= 0) {
            deleteBooster(playerId, booster.category());
            return;
        }
        try (Connection connection = database.connection()) {
            String update = "UPDATE " + database.table("player_boosters")
                    + " SET multiplier=?,remaining_seconds=?,updated_at=? WHERE player_uuid=? AND category=?";
            try (PreparedStatement statement = connection.prepareStatement(update)) {
                statement.setDouble(1, booster.multiplier());
                statement.setInt(2, booster.remainingSeconds());
                statement.setLong(3, System.currentTimeMillis());
                statement.setString(4, playerId.toString());
                statement.setString(5, booster.category().name());
                if (statement.executeUpdate() == 1) return;
            }
            String insert = "INSERT INTO " + database.table("player_boosters")
                    + " (player_uuid,category,multiplier,remaining_seconds,updated_at) VALUES (?,?,?,?,?)";
            try (PreparedStatement statement = connection.prepareStatement(insert)) {
                statement.setString(1, playerId.toString());
                statement.setString(2, booster.category().name());
                statement.setDouble(3, booster.multiplier());
                statement.setInt(4, booster.remainingSeconds());
                statement.setLong(5, System.currentTimeMillis());
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Booster konnte nicht gespeichert werden", exception);
        }
    }

    public void deleteBooster(UUID playerId, BoosterCategory category) {
        String sql = "DELETE FROM " + database.table("player_boosters") + " WHERE player_uuid=? AND category=?";
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, category.name());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Booster konnte nicht entfernt werden", exception);
        }
    }

    private void loadMeta(Connection connection, PlayerProfessionState state) throws SQLException {
        String sql = "SELECT free_switch,second_slot_unlocked FROM " + database.table("profession_meta")
                + " WHERE player_uuid=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, state.playerId().toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return;
                state.freeSwitch(rows.getBoolean(1));
                state.secondSlotUnlocked(rows.getBoolean(2));
            }
        }
    }

    private List<PlayerProfessionState.SlotProjection> loadActive(Connection connection, PlayerProfessionState state) throws SQLException {
        List<PlayerProfessionState.SlotProjection> persisted = new ArrayList<>();
        String sql = "SELECT slot_index,profession_id FROM " + database.table("profession_active")
                + " WHERE player_uuid=? ORDER BY slot_index";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, state.playerId().toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    int index = rows.getInt(1);
                    String professionId = rows.getString(2);
                    state.activeProfessions().add(professionId);
                    persisted.add(new PlayerProfessionState.SlotProjection(index, professionId));
                }
            }
        }
        return persisted;
    }

    private void loadProfiles(Connection connection, PlayerProfessionState state) throws SQLException {
        String sql = "SELECT profession_id,prestige,level,xp,completed_milestone,active_tool_serial FROM "
                + database.table("profession_profiles") + " WHERE player_uuid=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, state.playerId().toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ProfessionProgress progress = new ProfessionProgress(
                            rows.getString(1), rows.getInt(2), rows.getInt(3), rows.getDouble(4),
                            rows.getInt(5), rows.getString(6));
                    state.progress().put(progress.professionId(), progress);
                }
            }
        }
    }

    private void saveMeta(Connection connection, PlayerProfessionState state) throws SQLException {
        String update = "UPDATE " + database.table("profession_meta")
                + " SET free_switch=?,second_slot_unlocked=?,updated_at=? WHERE player_uuid=?";
        try (PreparedStatement statement = connection.prepareStatement(update)) {
            statement.setBoolean(1, state.freeSwitch());
            statement.setBoolean(2, state.secondSlotUnlocked());
            statement.setLong(3, System.currentTimeMillis());
            statement.setString(4, state.playerId().toString());
            if (statement.executeUpdate() == 1) return;
        }
        String insert = "INSERT INTO " + database.table("profession_meta")
                + " (player_uuid,free_switch,second_slot_unlocked,updated_at) VALUES (?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(insert)) {
            statement.setString(1, state.playerId().toString());
            statement.setBoolean(2, state.freeSwitch());
            statement.setBoolean(3, state.secondSlotUnlocked());
            statement.setLong(4, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private void saveActive(Connection connection, PlayerProfessionState state) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM " + database.table("profession_active") + " WHERE player_uuid=?")) {
            delete.setString(1, state.playerId().toString());
            delete.executeUpdate();
        }
        String insert = "INSERT INTO " + database.table("profession_active")
                + " (player_uuid,slot_index,profession_id,updated_at) VALUES (?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(insert)) {
            int maximum = Math.min(state.slotLimit(), state.activeProfessions().size());
            for (int index = 0; index < maximum; index++) {
                statement.setString(1, state.playerId().toString());
                statement.setInt(2, index);
                statement.setString(3, state.activeProfessions().get(index));
                statement.setLong(4, System.currentTimeMillis());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void saveProgress(Connection connection, UUID playerId, ProfessionProgress progress) throws SQLException {
        String update = "UPDATE " + database.table("profession_profiles")
                + " SET prestige=?,level=?,xp=?,completed_milestone=?,active_tool_serial=?,updated_at=?"
                + " WHERE player_uuid=? AND profession_id=?";
        try (PreparedStatement statement = connection.prepareStatement(update)) {
            statement.setInt(1, progress.prestige());
            statement.setInt(2, progress.level());
            statement.setDouble(3, progress.xp());
            statement.setInt(4, progress.completedMilestone());
            statement.setString(5, progress.activeToolSerial());
            statement.setLong(6, System.currentTimeMillis());
            statement.setString(7, playerId.toString());
            statement.setString(8, progress.professionId());
            if (statement.executeUpdate() == 1) return;
        }
        String insert = "INSERT INTO " + database.table("profession_profiles")
                + " (player_uuid,profession_id,prestige,level,xp,completed_milestone,active_tool_serial,updated_at)"
                + " VALUES (?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(insert)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, progress.professionId());
            statement.setInt(3, progress.prestige());
            statement.setInt(4, progress.level());
            statement.setDouble(5, progress.xp());
            statement.setInt(6, progress.completedMilestone());
            statement.setString(7, progress.activeToolSerial());
            statement.setLong(8, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private long contribution(UUID playerId, String professionId, int prestige, int milestone, String requirementId) {
        try (Connection connection = database.connection()) {
            return contribution(connection, playerId, professionId, prestige, milestone, requirementId);
        } catch (SQLException exception) {
            throw new IllegalStateException("Berufsabgabe konnte nicht geladen werden", exception);
        }
    }

    private long contribution(Connection connection, UUID playerId, String professionId, int prestige,
                              int milestone, String requirementId) throws SQLException {
        String sql = "SELECT amount FROM " + database.table("profession_contributions")
                + " WHERE player_uuid=? AND profession_id=? AND prestige=? AND milestone=? AND requirement_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, professionId);
            statement.setInt(3, prestige);
            statement.setInt(4, milestone);
            statement.setString(5, requirementId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        }
    }

    private void upsertContribution(Connection connection, UUID playerId, String professionId, int prestige,
                                    int milestone, String requirementId, long amount) throws SQLException {
        String update = "UPDATE " + database.table("profession_contributions")
                + " SET amount=?,updated_at=? WHERE player_uuid=? AND profession_id=? AND prestige=?"
                + " AND milestone=? AND requirement_id=?";
        try (PreparedStatement statement = connection.prepareStatement(update)) {
            statement.setLong(1, amount);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, playerId.toString());
            statement.setString(4, professionId);
            statement.setInt(5, prestige);
            statement.setInt(6, milestone);
            statement.setString(7, requirementId);
            if (statement.executeUpdate() == 1) return;
        }
        String insert = "INSERT INTO " + database.table("profession_contributions")
                + " (player_uuid,profession_id,prestige,milestone,requirement_id,amount,updated_at) VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(insert)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, professionId);
            statement.setInt(3, prestige);
            statement.setInt(4, milestone);
            statement.setString(5, requirementId);
            statement.setLong(6, amount);
            statement.setLong(7, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private void deleteTrackedSapling(Connection connection, TrackedSapling sapling) throws SQLException {
        String sql = "DELETE FROM " + database.table("profession_planted_saplings")
                + " WHERE world_uuid=? AND block_x=? AND block_y=? AND block_z=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sapling.worldId().toString());
            statement.setInt(2, sapling.x());
            statement.setInt(3, sapling.y());
            statement.setInt(4, sapling.z());
            statement.executeUpdate();
        }
    }

    private List<String> activeToolSerials(Connection connection, UUID owner, String professionId,
                                           int prestige) throws SQLException {
        List<String> serials = new ArrayList<>();
        String sql = "SELECT serial_id FROM " + database.table("profession_tools")
                + " WHERE owner_uuid=? AND profession_id=? AND prestige=? AND active=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, owner.toString());
            statement.setString(2, professionId);
            statement.setInt(3, prestige);
            statement.setBoolean(4, true);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) serials.add(rows.getString(1));
            }
        }
        return serials;
    }

    private void deactivateTools(Connection connection, UUID owner, String professionId,
                                 int prestige) throws SQLException {
        String sql = "UPDATE " + database.table("profession_tools")
                + " SET active=?,deactivated_at=? WHERE owner_uuid=? AND profession_id=? AND prestige=? AND active=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBoolean(1, false);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, owner.toString());
            statement.setString(4, professionId);
            statement.setInt(5, prestige);
            statement.setBoolean(6, true);
            statement.executeUpdate();
        }
    }

    private void insertTool(Connection connection, String serial, UUID owner, String professionId,
                            int prestige) throws SQLException {
        String sql = "INSERT INTO " + database.table("profession_tools")
                + " (serial_id,owner_uuid,profession_id,prestige,active,created_at,deactivated_at) VALUES (?,?,?,?,?,?,NULL)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, serial);
            statement.setString(2, owner.toString());
            statement.setString(3, professionId);
            statement.setInt(4, prestige);
            statement.setBoolean(5, true);
            statement.setLong(6, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private void deactivateTool(Connection connection, String serial) throws SQLException {
        String sql = "UPDATE " + database.table("profession_tools")
                + " SET active=?,deactivated_at=? WHERE serial_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBoolean(1, false);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, serial);
            statement.executeUpdate();
        }
    }

    private static void rollback(Connection connection, SQLException original) {
        try { connection.rollback(); }
        catch (SQLException rollbackFailure) { original.addSuppressed(rollbackFailure); }
    }

    private static void restoreAutoCommit(Connection connection, boolean previous) {
        try { connection.setAutoCommit(previous); }
        catch (SQLException ignored) { }
    }
}
