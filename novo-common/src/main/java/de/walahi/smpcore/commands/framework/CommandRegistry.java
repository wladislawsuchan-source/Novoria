package de.walahi.smpcore.commands.framework;

import de.walahi.smpcore.SMPCorePlugin;
import org.bukkit.command.PluginCommand;

public final class CommandRegistry {
    private final SMPCorePlugin plugin;

    public CommandRegistry(SMPCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void register(String name, BaseCommand command) {
        PluginCommand pluginCommand = plugin.getCommand(name);
        if (pluginCommand == null) {
            plugin.getLogger().severe("Command '" + name + "' fehlt in plugin.yml.");
            return;
        }
        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);
    }
}
