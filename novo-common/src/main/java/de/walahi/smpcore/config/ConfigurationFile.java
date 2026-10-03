package de.walahi.smpcore.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;

/**
 * One physical YAML file with its own bundled defaults.
 *
 * <p>The administrator file is never rewritten during loading. Bundled values are attached
 * only as in-memory defaults, so newly introduced settings work without migrations or backup
 * files. Explicit save operations persist only values that belong to this file.</p>
 */
public final class ConfigurationFile {
    private final JavaPlugin plugin;
    private final ConfigurationLoader loader;
    private final File file;
    private final String resourceName;
    private YamlConfiguration configuration;

    public ConfigurationFile(JavaPlugin plugin, ConfigurationLoader loader, File file, String resourceName) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.file = Objects.requireNonNull(file, "file");
        this.resourceName = Objects.requireNonNull(resourceName, "resourceName");
        reload();
    }

    public synchronized void reload() {
        loader.ensureExists(file, resourceName);
        YamlConfiguration loaded = file.isFile()
                ? YamlConfiguration.loadConfiguration(file)
                : new YamlConfiguration();
        YamlConfiguration defaults = loadDefaults();

        if (configuration == null) {
            configuration = loaded;
        } else {
            // Keep the same object so services that received this configuration during
            // startup immediately see /smpcore reload changes as well.
            for (String key : Set.copyOf(configuration.getKeys(false))) {
                configuration.set(key, null);
            }
            copyValues(loaded, configuration);
        }

        configuration.setDefaults(defaults);
        configuration.options().copyDefaults(false);
    }

    public synchronized FileConfiguration configuration() {
        return configuration;
    }

    public synchronized YamlConfiguration yaml() {
        return configuration;
    }

    public synchronized void save() {
        try {
            configuration.save(file);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE,
                    file.getName() + " konnte nicht gespeichert werden.", exception);
        }
    }

    /**
     * Saves a complete, self-contained configuration while preserving all explicit runtime
     * changes. This is intended for configuration files that also contain persistent setup
     * values (for example a configured Jump'n'Run trigger). Empty local sections cannot shadow
     * bundled child defaults after the next restart because the bundled file is materialized
     * before the runtime values are overlaid.
     */
    public synchronized void saveWithDefaults() {
        YamlConfiguration complete = loadDefaults();
        if (complete == null) {
            save();
            return;
        }
        copyValues(configuration, complete);
        try {
            complete.save(file);
            reload();
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE,
                    file.getName() + " konnte nicht vollständig gespeichert werden.", exception);
        }
    }

    public File file() {
        return file;
    }

    public String resourceName() {
        return resourceName;
    }


    private void copyValues(YamlConfiguration source, YamlConfiguration target) {
        for (String key : source.getKeys(true)) {
            if (!source.isConfigurationSection(key)) {
                target.set(key, source.get(key));
            }
        }
    }

    private YamlConfiguration loadDefaults() {
        try (InputStream input = plugin.getResource(resourceName)) {
            if (input == null) {
                plugin.getLogger().warning("Resource fehlt in der JAR: " + resourceName);
                return null;
            }
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "Resource konnte nicht gelesen werden: " + resourceName, exception);
            return null;
        }
    }
}
