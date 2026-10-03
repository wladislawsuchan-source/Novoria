package de.walahi.smpcore.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Level;

/** Shared network storage for MariaDB and MySQL. */
public final class MySqlStorageProvider implements StorageProvider {
    private final JavaPlugin plugin;
    private final String type;
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final int poolSize;
    private final boolean useSsl;
    private HikariDataSource dataSource;

    public MySqlStorageProvider(JavaPlugin plugin, String type, String host, int port, String database,
                                String username, String password, int poolSize, boolean useSsl) {
        this.plugin = plugin;
        this.type = type;
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.poolSize = Math.max(2, poolSize);
        this.useSsl = useSsl;
    }

    @Override
    public void initialize() throws SQLException {
        boolean mariaDb = type.equalsIgnoreCase("mariadb");
        HikariConfig config = new HikariConfig();
        config.setPoolName("SMPCore-" + (mariaDb ? "MariaDB" : "MySQL"));
        config.setJdbcUrl((mariaDb ? "jdbc:mariadb" : "jdbc:mysql") + "://" + host + ":" + port + "/" + database);
        config.setDriverClassName(mariaDb ? "org.mariadb.jdbc.Driver" : "com.mysql.cj.jdbc.Driver");
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(Math.min(2, poolSize));
        config.setConnectionTimeout(10_000L);
        config.setValidationTimeout(5_000L);
        config.setIdleTimeout(600_000L);
        config.setMaxLifetime(1_740_000L);
        config.setKeepaliveTime(300_000L);

        Properties properties = new Properties();
        properties.setProperty("useUnicode", "true");
        properties.setProperty("characterEncoding", "utf8");
        properties.setProperty("useSSL", Boolean.toString(useSsl));
        properties.setProperty("tcpKeepAlive", "true");
        if (!mariaDb) {
            properties.setProperty("serverTimezone", "UTC");
            properties.setProperty("cachePrepStmts", "true");
            properties.setProperty("prepStmtCacheSize", "250");
            properties.setProperty("prepStmtCacheSqlLimit", "2048");
            properties.setProperty("useServerPrepStmts", "true");
            properties.setProperty("rewriteBatchedStatements", "true");
        }
        config.setDataSourceProperties(properties);

        dataSource = new HikariDataSource(config);
        try (Connection ignored = dataSource.getConnection()) {
            // Fail during startup instead of on the first player action.
        } catch (SQLException exception) {
            close();
            throw exception;
        }
    }

    @Override
    public Connection connection() throws SQLException {
        if (!isAvailable()) throw new SQLException(type + " ist nicht verbunden.");
        return dataSource.getConnection();
    }

    @Override public boolean isAvailable() { return dataSource != null && !dataSource.isClosed(); }
    @Override public String type() { return type; }
    @Override public StorageDialect dialect() { return StorageDialect.MYSQL; }

    @Override
    public void close() {
        if (dataSource == null) return;
        try {
            dataSource.close();
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, type + "-Pool konnte nicht geschlossen werden.", exception);
        } finally {
            dataSource = null;
        }
    }
}
