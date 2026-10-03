package de.walahi.smpcore.config;

import de.walahi.smpcore.network.ServerType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;
import java.util.Objects;

/**
 * Owns every configuration folder and loaded YAML object of one server.
 * Feature code receives the explicit {@link PluginConfigurations} facade and
 * never has to know how the shared/global/server folders are assembled.
 */
public final class CoreConfigurationSystem {
    private final JavaPlugin plugin;
    private final String localResourceName;
    private final ServerType serverType;

    private ConfigurationLoader loader;
    private PluginConfigurations configurations;
    private File sharedFolder;
    private File globalFolder;
    private File serverFolder;

    public CoreConfigurationSystem(JavaPlugin plugin, String localResourceName, ServerType serverType) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.localResourceName = Objects.requireNonNull(localResourceName, "localResourceName");
        this.serverType = Objects.requireNonNull(serverType, "serverType");
    }

    public void initialize() {
        loader = new ConfigurationLoader(plugin);
        ensureFolder(plugin.getDataFolder(), "Plugin-Ordner");

        File localConfig = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration local = loader.load(localConfig, localResourceName);
        String configuredPath = local.getString("shared-folder", "../shared/NovoCore");

        // Bukkit may expose the plugin data folder as a relative path (for example
        // "plugins/NovoSMP"). Calling getParentFile() on that relative path can lose the
        // actual server root and would resolve ../shared below the plugin directory.
        // Make it absolute first, then resolve every relative shared-folder path from the
        // backend server root (/hub or /smp). This guarantees that Hub and SMP both reach
        // the same network-level ../shared/NovoCore directory.
        File absoluteDataFolder = plugin.getDataFolder().getAbsoluteFile();
        File pluginsFolder = absoluteDataFolder.getParentFile();
        File serverRoot = pluginsFolder == null ? null : pluginsFolder.getParentFile();
        if (serverRoot == null) serverRoot = new File(".").getAbsoluteFile();

        File configuredSharedFolder = new File(configuredPath);
        File resolvedSharedFolder = configuredSharedFolder.isAbsolute()
                ? configuredSharedFolder
                : new File(serverRoot, configuredPath);
        sharedFolder = resolvedSharedFolder.toPath().toAbsolutePath().normalize().toFile();
        globalFolder = new File(sharedFolder, "global").toPath().toAbsolutePath().normalize().toFile();
        serverFolder = absoluteDataFolder.toPath().toAbsolutePath().normalize().toFile();

        for (File folder : List.of(sharedFolder, globalFolder, serverFolder)) {
            ensureFolder(folder, "NovoCore-Konfigurationsordner");
        }

        configurations = new PluginConfigurations(
                plugin,
                loader,
                globalFolder,
                serverFolder,
                localResourceName,
                serverType
        );

        plugin.getLogger().info("Globale Konfiguration: " + globalFolder.getAbsolutePath());
        plugin.getLogger().info("Server-Konfiguration: " + serverFolder.getAbsolutePath());
    }

    public PluginConfigurations configurations() {
        if (configurations == null) {
            throw new IllegalStateException("Konfigurationen wurden noch nicht initialisiert.");
        }
        return configurations;
    }

    public void reloadAll() {
        configurations().reloadAll();
    }

    public File sharedFolder() {
        return sharedFolder != null ? sharedFolder : plugin.getDataFolder();
    }

    public File globalFolder() {
        return globalFolder != null ? globalFolder : sharedFolder();
    }

    public File serverFolder() {
        return serverFolder != null ? serverFolder : plugin.getDataFolder();
    }

    public File globalFile(String name) {
        return new File(globalFolder(), name);
    }

    public File serverFile(String name) {
        return new File(serverFolder(), name);
    }

    public void ensureGlobalResource(String resourceName) {
        loader().ensureExists(globalFile(resourceName), resourceName);
    }

    public void ensureServerResource(String resourceName) {
        loader().ensureExists(serverFile(resourceName), resourceName);
    }

    private ConfigurationLoader loader() {
        if (loader == null) loader = new ConfigurationLoader(plugin);
        return loader;
    }

    private void ensureFolder(File folder, String label) {
        if (folder.exists() || folder.mkdirs()) return;
        plugin.getLogger().warning(label + " konnte nicht erstellt werden: " + folder);
    }
}
