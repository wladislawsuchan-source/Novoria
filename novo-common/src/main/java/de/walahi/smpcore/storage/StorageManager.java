package de.walahi.smpcore.storage;

import de.walahi.smpcore.SMPCorePlugin;
import org.bukkit.configuration.file.FileConfiguration;
import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

public final class StorageManager implements AutoCloseable {
    private final SMPCorePlugin plugin;
    private StorageProvider provider;
    private String tablePrefix = "";

    public StorageManager(SMPCorePlugin plugin) { this.plugin = plugin; }

    public void initialize() throws SQLException {
        close();
        FileConfiguration config = plugin.configs().storage();
        String type = config.getString("storage.type", "sqlite").trim().toLowerCase(Locale.ROOT);
        String configuredPrefix = config.getString("storage.table-prefix", "");
        if ((configuredPrefix == null || configuredPrefix.isBlank()) && !type.equals("sqlite")) {
            configuredPrefix = plugin.isSmpServer() ? "novosmp_" : "novohub_";
        }
        tablePrefix = sanitizeIdentifier(configuredPrefix, true);

        provider = switch (type) {
            case "sqlite" -> {
                String fileName = config.getString("storage.sqlite.file", "database.db");
                if (fileName == null || fileName.isBlank()) throw new SQLException("storage.sqlite.file darf nicht leer sein.");
                yield new SQLiteStorageProvider(plugin, new File(plugin.getDataFolder(), fileName));
            }
            case "mysql", "mariadb" -> new MySqlStorageProvider(
                    plugin,
                    type,
                    required(config, "storage.mysql.host"),
                    checkedPort(config.getInt("storage.mysql.port", 3306)),
                    required(config, "storage.mysql.database"),
                    required(config, "storage.mysql.username"),
                    config.getString("storage.mysql.password", ""),
                    config.getInt("storage.mysql.pool-size", 10),
                    config.getBoolean("storage.mysql.use-ssl", false)
            );
            default -> throw new SQLException("Unbekannter Storage-Typ '" + type + "'. Erlaubt: sqlite, mysql, mariadb.");
        };

        provider.initialize();
        plugin.getLogger().info("Storage aktiv: " + provider.type() + " | Tabellenpräfix: " + (tablePrefix.isEmpty() ? "<keins>" : tablePrefix));
    }

    /** The returned pooled connection must always be closed after use. */
    public Connection connection() throws SQLException {
        if (provider == null) throw new SQLException("Storage nicht initialisiert.");
        return provider.connection();
    }

    public StorageDialect dialect() { return provider == null ? StorageDialect.SQLITE : provider.dialect(); }
    public String tablePrefix() { return tablePrefix; }
    public String table(String baseName) {
        try {
            return tablePrefix + sanitizeIdentifier(baseName, false);
        } catch (SQLException exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        }
    }
    public boolean isAvailable() { return provider != null && provider.isAvailable(); }
    public String type() { return provider == null ? "none" : provider.type(); }

    @Override
    public void close() {
        if (provider != null) {
            provider.close();
            provider = null;
        }
    }

    private String required(FileConfiguration config, String path) throws SQLException {
        String value = config.getString(path, "");
        if (value == null || value.isBlank()) throw new SQLException(path + " darf nicht leer sein.");
        return value.trim();
    }

    private static int checkedPort(int port) throws SQLException {
        if (port < 1 || port > 65535) throw new SQLException("storage.mysql.port muss zwischen 1 und 65535 liegen.");
        return port;
    }

    private static String sanitizeIdentifier(String value, boolean allowEmpty) throws SQLException {
        String identifier = value == null ? "" : value.trim();
        if (allowEmpty && identifier.isEmpty()) return "";
        if (!identifier.matches("[A-Za-z0-9_]+")) {
            throw new SQLException("SQL-Bezeichner dürfen nur Buchstaben, Zahlen und Unterstriche enthalten: " + identifier);
        }
        return identifier;
    }
}
