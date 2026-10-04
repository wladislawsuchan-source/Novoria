package de.walahi.smpcore.database;

import de.walahi.smpcore.database.migration.CreatePunishmentsMigration;
import de.walahi.smpcore.database.migration.CreateEconomyMigration;
import de.walahi.smpcore.database.migration.CreateEconomyTransactionLogMigration;
import de.walahi.smpcore.database.migration.CreateAuctionHouseMigration;
import de.walahi.smpcore.database.migration.CreateOrdersMigration;
import de.walahi.smpcore.database.migration.CreateEnderChestUpgradeMigration;
import de.walahi.smpcore.database.migration.CreateLumiMigration;
import de.walahi.smpcore.database.migration.CreateDailyRewardsMigration;
import de.walahi.smpcore.database.migration.CreateHomesMigration;
import de.walahi.smpcore.database.migration.CreatePlayerPersistenceMigration;
import de.walahi.smpcore.database.migration.AddReadablePlayerNamesMigration;
import de.walahi.smpcore.database.migration.CreateDeathBackMigration;
import de.walahi.smpcore.database.migration.CreateFriendsMigration;
import de.walahi.smpcore.database.migration.AddFriendGlowPrivacyMigration;
import de.walahi.smpcore.database.migration.CreateProfessionsMigration;
import de.walahi.smpcore.database.migration.CreateProfessionSaplingsMigration;
import de.walahi.smpcore.database.migration.CreateHeadCollectionMigration;
import de.walahi.smpcore.database.migration.AddCollectionPrestigeStatsMigration;
import de.walahi.smpcore.database.migration.CreateHeadRewardsMigration;
import de.walahi.smpcore.database.migration.CreatePlaytimeRewardsMigration;
import de.walahi.smpcore.database.migration.CreateReferralMigration;
import de.walahi.smpcore.database.migration.AddReferralVerificationMigration;
import de.walahi.smpcore.database.migration.AddReferralCodeUnlockMigration;
import de.walahi.smpcore.database.migration.RepairHeadCollectionRewardsMigration;
import de.walahi.smpcore.database.migration.WidenPlayerNameColumnsMigration;
import de.walahi.smpcore.database.migration.CreateVoteSystemMigration;
import de.walahi.smpcore.database.migration.CreateVoteProgressMigration;
import de.walahi.smpcore.database.migration.CreatePlayerIpHistoryMigration;
import de.walahi.smpcore.database.migration.AddDailyTierClaimsMigration;
import de.walahi.smpcore.database.migration.CreateBountyMigration;
import de.walahi.smpcore.database.migration.CreateClanMigration;
import de.walahi.smpcore.database.migration.CreateDragonEggKingMigration;
import de.walahi.smpcore.database.migration.AddJumpRunHighscoreMigration;
import de.walahi.smpcore.database.migration.CreateAnglerCatchStorageMigration;
import de.walahi.smpcore.database.migration.CreateQuestSystemMigration;
import de.walahi.smpcore.database.migration.SchemaMigration;
import de.walahi.smpcore.storage.StorageDialect;
import de.walahi.smpcore.network.ServerType;
import de.walahi.smpcore.storage.StorageManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Initializes and migrates the SMPCore schema on SQLite, MySQL or MariaDB. */
public final class DatabaseManager implements AutoCloseable {
    private final JavaPlugin plugin;
    private final StorageManager storage;
    private final List<SchemaMigration> migrations;

    public DatabaseManager(JavaPlugin plugin, StorageManager storage, ServerType serverType) {
        this.plugin = Objects.requireNonNull(plugin);
        this.storage = Objects.requireNonNull(storage);
        Objects.requireNonNull(serverType);
        this.migrations = serverType == ServerType.HUB
                ? List.of(new CreatePunishmentsMigration())
                : List.of(
                        new CreatePunishmentsMigration(),
                        new CreateEconomyMigration(),
                        new CreateEconomyTransactionLogMigration(),
                        new CreateAuctionHouseMigration(),
                        new CreateOrdersMigration(),
                        new CreateEnderChestUpgradeMigration(),
                        new CreateLumiMigration(),
                        new CreateDailyRewardsMigration(),
                        new CreateHomesMigration(),
                        new CreatePlayerPersistenceMigration(),
                        new AddReadablePlayerNamesMigration(),
                        new CreateDeathBackMigration(),
                        new CreateFriendsMigration(),
                        new AddFriendGlowPrivacyMigration(),
                        new CreateProfessionsMigration(),
                        new CreateProfessionSaplingsMigration(),
                        new CreateHeadCollectionMigration(),
                        new AddCollectionPrestigeStatsMigration(),
                        new CreateHeadRewardsMigration(),
                        new CreatePlaytimeRewardsMigration(),
                        new CreateReferralMigration(),
                        new AddReferralVerificationMigration(),
                        new AddReferralCodeUnlockMigration(),
                        new RepairHeadCollectionRewardsMigration(),
                        new WidenPlayerNameColumnsMigration(),
                        new CreateVoteSystemMigration(),
                        new CreateVoteProgressMigration(),
                        new CreatePlayerIpHistoryMigration(),
                        new AddDailyTierClaimsMigration(),
                        new CreateBountyMigration(),
                        new CreateClanMigration(),
                        new CreateDragonEggKingMigration(),
                        new AddJumpRunHighscoreMigration(),
                        new CreateAnglerCatchStorageMigration(),
                        new CreateQuestSystemMigration()
                );
    }

    public void initialize() throws SQLException {
        createMigrationTable();
        runMigrations();
    }

    private void createMigrationTable() throws SQLException {
        String table = storage.table("schema_migrations");
        String descriptionType = storage.dialect() == StorageDialect.SQLITE ? "TEXT" : "VARCHAR(255)";
        String sql = "CREATE TABLE IF NOT EXISTS " + table + " (" +
                "version INTEGER NOT NULL PRIMARY KEY, " +
                "description " + descriptionType + " NOT NULL, " +
                "installed_at BIGINT NOT NULL" +
                ")";
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private void runMigrations() throws SQLException {
        List<SchemaMigration> ordered = migrations.stream()
                .sorted(Comparator.comparingInt(SchemaMigration::version))
                .toList();

        for (SchemaMigration migration : ordered) {
            if (isInstalled(migration.version())) continue;
            applyMigration(migration);
        }
    }

    private boolean isInstalled(int version) throws SQLException {
        String sql = "SELECT 1 FROM " + storage.table("schema_migrations") + " WHERE version = ?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, version);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void applyMigration(SchemaMigration migration) throws SQLException {
        try (Connection connection = connection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                migration.apply(connection, storage.dialect(), storage.tablePrefix());
                recordMigration(connection, migration);
                connection.commit();
                plugin.getLogger().info("Datenbank-Migration " + migration.version() + " ausgeführt: " + migration.description());
            } catch (SQLException exception) {
                try { connection.rollback(); } catch (SQLException rollbackFailure) { exception.addSuppressed(rollbackFailure); }
                throw new SQLException("Migration " + migration.version() + " fehlgeschlagen: " + migration.description(), exception);
            } finally {
                try { connection.setAutoCommit(previousAutoCommit); } catch (SQLException ignored) { }
            }
        }
    }

    private void recordMigration(Connection connection, SchemaMigration migration) throws SQLException {
        String sql = "INSERT INTO " + storage.table("schema_migrations") +
                " (version, description, installed_at) VALUES (?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, migration.version());
            statement.setString(2, migration.description());
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    public Connection connection() throws SQLException { return storage.connection(); }

    /** Kept for existing repositories; every provider now returns a pooled/borrowed connection. */
    public void closeIfPooled(Connection connection) {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException exception) {
            plugin.getLogger().warning("Datenbankverbindung konnte nicht freigegeben werden: " + exception.getMessage());
        }
    }

    public String table(String baseName) { return storage.table(baseName); }
    public StorageDialect dialect() { return storage.dialect(); }
    public boolean isAvailable() { return storage.isAvailable(); }
    @Override public void close() { /* StorageManager owns the provider/pool. */ }
}
