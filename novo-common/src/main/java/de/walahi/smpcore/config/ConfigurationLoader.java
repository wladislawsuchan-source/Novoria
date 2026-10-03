package de.walahi.smpcore.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.logging.Level;

/**
 * Loads configuration resources without migrations, version ledgers or backup files.
 * Existing files are never rewritten. Missing values are supplied in memory from the
 * bundled resource, while administrator values always take precedence.
 */
public final class ConfigurationLoader {
    private final JavaPlugin plugin;

    public ConfigurationLoader(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public void ensureExists(File target, String resourceName) {
        if (target.isFile()) return;
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().warning("Konfigurationsordner konnte nicht erstellt werden: " + parent.getAbsolutePath());
            return;
        }
        try (InputStream input = plugin.getResource(resourceName)) {
            if (input == null) {
                plugin.getLogger().warning("Resource fehlt in der JAR: " + resourceName);
                return;
            }
            Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, resourceName + " konnte nicht erstellt werden.", exception);
        }
    }

    public YamlConfiguration load(File target, String resourceName) {
        ensureExists(target, resourceName);
        YamlConfiguration merged = new YamlConfiguration();
        YamlConfiguration defaults = loadResource(resourceName);
        if (defaults != null) copy(defaults, merged);
        if (target.isFile()) copy(YamlConfiguration.loadConfiguration(target), merged);
        return merged;
    }

    private YamlConfiguration loadResource(String resourceName) {
        try (InputStream input = plugin.getResource(resourceName)) {
            if (input == null) return null;
            return YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Resource konnte nicht gelesen werden: " + resourceName, exception);
            return null;
        }
    }

    private void copy(YamlConfiguration source, YamlConfiguration target) {
        for (String key : source.getKeys(true)) {
            if (!source.isConfigurationSection(key)) target.set(key, source.get(key));
        }
    }
}
