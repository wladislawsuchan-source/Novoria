package de.walahi.smpcore.bootstrap;

import de.walahi.smpcore.SMPCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Performs readable startup checks before gameplay systems are exposed.
 * Critical failures prevent SMPCore from starting; warnings are reported but do not stop startup.
 */
public final class StartupValidator {

    private final SMPCorePlugin plugin;
    private final List<String> passed = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();

    public StartupValidator(SMPCorePlugin plugin) {
        this.plugin = plugin;
    }

    /** Runs all startup checks and prints one compact validation report. */
    public boolean validate() {
        validateServerType();
        validateWorldConfiguration();
        validateStorage();
        validateDependencies();
        validateNetworkConfiguration();
        printReport();
        return failures.isEmpty();
    }

    private void validateServerType() {
        String configured = plugin.isHubServer() ? "HUB" : "SMP";
        passed.add("Server-Typ: " + configured);
    }

    private void validateWorldConfiguration() {
        FileConfiguration config = plugin.configs().server();
        Set<String> requiredWorlds = new LinkedHashSet<>();

        if (plugin.isHubServer()) {
            String hubWorld = config.getString("world", "world");
            addConfiguredWorld(requiredWorlds, hubWorld, "world");
        }

        if (plugin.isSmpServer()) {
            String spawnWorld = config.getString("spawn-world", "smp_spawn");
            addConfiguredWorld(requiredWorlds, spawnWorld, "spawn-world");
            List<String> smpWorlds = config.getStringList("survival-worlds");
            if (smpWorlds.isEmpty()) smpWorlds = config.getStringList("worlds.smp");
            if (smpWorlds.isEmpty()) {
                warnings.add("survival-worlds enthält keine Welten.");
            }
            for (String world : smpWorlds) {
                addConfiguredWorld(requiredWorlds, world, "smp.worlds");
            }
        }

        for (String worldName : requiredWorlds) {
            World world = Bukkit.getWorld(worldName);
            if (world != null) {
                passed.add("Welt geladen: " + worldName);
                continue;
            }

            boolean folderExists = Bukkit.getWorldContainer().toPath().resolve(worldName).toFile().isDirectory();
            if (folderExists) {
                warnings.add("Welt '" + worldName + "' existiert, ist beim Start aber noch nicht geladen.");
            } else {
                warnings.add("Konfigurierte Welt '" + worldName + "' wurde nicht gefunden.");
            }
        }
    }

    private void addConfiguredWorld(Set<String> worlds, String value, String path) {
        if (value == null || value.isBlank()) {
            failures.add(path + " darf nicht leer sein.");
            return;
        }
        worlds.add(value.trim());
    }

    private void validateStorage() {
        if (plugin.storageManager() == null || !plugin.storageManager().isAvailable()) {
            failures.add("StorageProvider ist nicht verfügbar.");
            return;
        }

        try (Connection connection = plugin.storageManager().connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 1")) {
            if (result.next()) {
                passed.add("Storage erreichbar: " + plugin.storageManager().type());
            } else {
                failures.add("Storage-Testabfrage lieferte kein Ergebnis.");
            }
        } catch (SQLException exception) {
            failures.add("Storage-Test fehlgeschlagen: " + exception.getMessage());
        }
    }

    private void validateDependencies() {
        Plugin luckPerms = Bukkit.getPluginManager().getPlugin("LuckPerms");
        if (luckPerms == null || !luckPerms.isEnabled()) {
            failures.add("LuckPerms fehlt oder ist deaktiviert.");
        } else {
            passed.add("Abhängigkeit aktiv: LuckPerms");
        }

        validateOptionalPlugin("Citizens");
        validateOptionalPlugin("Multiverse-Core");
        validateOptionalPlugin("ProtocolLib");
    }

    private void validateOptionalPlugin(String name) {
        Plugin dependency = Bukkit.getPluginManager().getPlugin(name);
        if (dependency == null) {
            plugin.getLogger().info("[OPTIONAL] " + name + " nicht installiert.");
        } else if (!dependency.isEnabled()) {
            warnings.add("Optionale Abhängigkeit " + name + " ist installiert, aber deaktiviert.");
        } else {
            passed.add("Optionale Abhängigkeit aktiv: " + name);
        }
    }

    private void validateNetworkConfiguration() {
        String hubName = plugin.isHubServer()
                ? plugin.configs().server().getString("velocity.this-server", "hub")
                : plugin.configs().server().getString("velocity.hub-server", "hub");
        String smpName = plugin.isSmpServer()
                ? plugin.configs().server().getString("velocity.this-server", "smp")
                : plugin.configs().server().getString("velocity.smp-server", "smp");

        if (hubName.isEmpty()) {
            failures.add("velocity.hub-server darf nicht leer sein.");
        }
        if (smpName.isEmpty()) {
            failures.add("velocity.smp-server darf nicht leer sein.");
        }
        if (!hubName.isEmpty() && !smpName.isEmpty()) {
            passed.add("Velocity-Ziele konfiguriert: " + hubName + " / " + smpName);
        }

        if (plugin.getNetworkManager() == null) {
            failures.add("NetworkManager wurde nicht initialisiert.");
        } else {
            passed.add("Plugin-Messaging-Kanäle registriert");
        }
    }


    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }
    private void printReport() {
        plugin.getLogger().info("---------- Startup Validation ----------");
        for (String entry : passed) {
            plugin.getLogger().info("[OK] " + entry);
        }
        for (String warning : warnings) {
            plugin.getLogger().warning("[WARN] " + warning);
        }
        for (String failure : failures) {
            plugin.getLogger().severe("[ERROR] " + failure);
        }
        plugin.getLogger().info("Ergebnis: " + failures.size() + " Fehler, " + warnings.size() + " Warnungen");
        plugin.getLogger().info("----------------------------------------");
    }
}
