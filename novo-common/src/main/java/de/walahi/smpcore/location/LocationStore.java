package de.walahi.smpcore.location;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.logging.Level;

/**
 * Owns the persistent fixed locations of one server instance.
 *
 * <p>The store deliberately does not migrate, rename or rewrite unrelated
 * configuration files. It only reads and writes {@code locations.yml} when a
 * location is explicitly changed.</p>
 */
public final class LocationStore {
    private final JavaPlugin plugin;
    private final File file;
    private YamlConfiguration data;

    public LocationStore(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.file = new File(plugin.getDataFolder(), "locations.yml");
    }

    public void load() {
        data = YamlConfiguration.loadConfiguration(file);
        if (!file.isFile()) {
            save();
        }
    }

    public void reload() {
        data = YamlConfiguration.loadConfiguration(file);
    }

    public void save() {
        if (data == null) return;
        try {
            data.save(file);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "locations.yml konnte nicht gespeichert werden.", exception);
        }
    }

    public void set(String path, Location location) {
        if (location == null || location.getWorld() == null) return;
        ensureLoaded();
        data.set(path + ".world", location.getWorld().getName());
        data.set(path + ".x", location.getX());
        data.set(path + ".y", location.getY());
        data.set(path + ".z", location.getZ());
        data.set(path + ".yaw", location.getYaw());
        data.set(path + ".pitch", location.getPitch());
        save();
    }

    public Location get(String path) {
        ensureLoaded();
        ConfigurationSection section = data.getConfigurationSection(path);
        if (section == null) return null;

        String worldName = section.getString("world");
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        if (world == null) return null;

        return new Location(
                world,
                section.getDouble("x"),
                section.getDouble("y"),
                section.getDouble("z"),
                (float) section.getDouble("yaw"),
                (float) section.getDouble("pitch")
        );
    }

    public Location worldSpawn(String worldName) {
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        return world == null ? null : world.getSpawnLocation();
    }

    private void ensureLoaded() {
        if (data == null) reload();
    }
}
