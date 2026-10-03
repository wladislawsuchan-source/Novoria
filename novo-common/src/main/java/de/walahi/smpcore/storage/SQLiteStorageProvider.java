package de.walahi.smpcore.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;

/** Local SQLite storage. A one-connection pool serializes access safely across async tasks. */
public final class SQLiteStorageProvider implements StorageProvider {
    private final JavaPlugin plugin;
    private final File file;
    private HikariDataSource dataSource;

    public SQLiteStorageProvider(JavaPlugin plugin, File file) {
        this.plugin = plugin;
        this.file = file;
    }

    @Override
    public void initialize() throws SQLException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new SQLException("Datenbankordner konnte nicht erstellt werden: " + parent);
        }

        HikariConfig config = new HikariConfig();
        config.setPoolName("SMPCore-SQLite");
        config.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10_000L);
        config.setValidationTimeout(5_000L);
        config.setMaxLifetime(0L);
        config.setConnectionInitSql("PRAGMA foreign_keys = ON");

        dataSource = new HikariDataSource(config);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = NORMAL");
            statement.execute("PRAGMA busy_timeout = 5000");
        } catch (SQLException exception) {
            close();
            throw exception;
        }
    }

    @Override
    public Connection connection() throws SQLException {
        if (!isAvailable()) throw new SQLException("SQLite ist nicht verbunden.");
        return dataSource.getConnection();
    }

    @Override public boolean isAvailable() { return dataSource != null && !dataSource.isClosed(); }
    @Override public String type() { return "sqlite"; }
    @Override public StorageDialect dialect() { return StorageDialect.SQLITE; }

    @Override
    public void close() {
        if (dataSource == null) return;
        try {
            dataSource.close();
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "SQLite-Pool konnte nicht geschlossen werden.", exception);
        } finally {
            dataSource = null;
        }
    }
}
