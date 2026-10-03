package de.walahi.smpcore.commands.framework;

import de.walahi.smpcore.SMPCorePlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Collections;
import java.util.List;

public abstract class BaseCommand implements CommandExecutor, TabCompleter {
    protected final SMPCorePlugin plugin;

    protected BaseCommand(SMPCorePlugin plugin) { this.plugin = plugin; }
    protected abstract String permission();
    protected abstract boolean execute(CommandSender sender, String label, String[] args);
    protected String noPermissionMessagePath() { return "staff.messages.no-permission"; }

    @Override
    public final boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String permission = permission();
        if (permission != null && !permission.isBlank() && !sender.hasPermission(permission)) {
            return messageOrDefault(sender, noPermissionMessagePath(), "<dark_gray>[<red>Team</red>]</dark_gray> <red>Dafür hast du keine Berechtigung.</red>");
        }
        return execute(sender, label, args);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }

    protected boolean message(CommandSender sender, String path, String... replacements) {
        return messageOrDefault(sender, path, "<red>Nachricht fehlt: " + path + "</red>", replacements);
    }

    protected boolean messageOrDefault(CommandSender sender, String path, String fallback, String... replacements) {
        return plugin.messages().sendConfiguredAuto(sender, path, fallback, replacements);
    }
}
