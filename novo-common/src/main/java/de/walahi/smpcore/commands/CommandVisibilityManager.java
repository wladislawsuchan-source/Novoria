package de.walahi.smpcore.commands;

import de.walahi.smpcore.SMPCorePlugin;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.World;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Liest ausschließlich LuckPerms-Gruppen und entscheidet, welche Befehlsnamen
 * für einen Spieler sichtbar sein dürfen. Es werden keine Rangrechte gespeichert.
 */
public final class CommandVisibilityManager {
    private final SMPCorePlugin plugin;
    private final LuckPerms luckPerms;
    private YamlConfiguration configuration;

    public CommandVisibilityManager(SMPCorePlugin plugin) {
        this.plugin = plugin;
        this.luckPerms = LuckPermsProvider.get();
        reload();
    }

    public void reload() {
        plugin.configs().commandsFile().reload();
        configuration = plugin.configs().commandsFile().yaml();
    }







    public YamlConfiguration configuration() {
        return configuration;
    }

    public boolean isEnabled() {
        return configuration.getBoolean("command-filter.enabled", true);
    }

    public boolean hideTabCompletion() {
        return configuration.getBoolean("command-filter.hide-tab-completion", true);
    }

    public boolean removeNamespacedCommands() {
        return configuration.getBoolean("command-filter.remove-namespaced-commands", true);
    }

    public boolean refreshOnWorldChange() {
        return configuration.getBoolean("world-restrictions.refresh-commands-on-world-change", false);
    }

    public String crossWorldMessage() {
        return configuration.getString(
                "private-messages.cross-world-message",
                "<gray>Dieser Spieler befindet sich nicht im selben Bereich.</gray>"
        );
    }

    public boolean isPrivateMessageRestrictionEnabled() {
        return configuration.getBoolean("private-messages.enabled", true);
    }

    public Set<String> directMessageCommands() {
        return lower(configuration.getStringList("private-messages.direct-commands"));
    }

    public Set<String> replyCommands() {
        return lower(configuration.getStringList("private-messages.reply-commands"));
    }

    public String blockedMessage() {
        return configuration.getString(
                "command-filter.blocked-message",
                "<gray>Dieser Befehl konnte nicht gefunden werden.</gray>"
        );
    }

    public boolean canUse(Player player, String rawCommand) {
        if (!isEnabled()) return true;

        String command = normalize(rawCommand);
        if (command.isBlank()) return true;

        // This debug command is permission-gated (OP by default), even if the OP has no staff rank.
        if (plugin.isSmpServer() && command.equals("exposeore")
                && player.hasPermission("novosmp.admin.exposeore")) return true;
        if (plugin.isSmpServer() && command.equals("dnd")
                && player.hasPermission("smpcore.command.dnd")) return true;
        // /list is now filtered per viewer, including the vanilla namespaced forms.
        if (plugin.isSmpServer()) {
            String baseCommand = command.substring(command.lastIndexOf(':') + 1);
            if (baseCommand.equals("list") || baseCommand.equals("players")) return true;
        }

        Set<String> groups = inheritedGroups(player);

        for (String bypass : lower(configuration.getStringList("command-filter.bypass-groups"))) {
            if (groups.contains(bypass)) return true;
        }

        Set<String> allowed = new LinkedHashSet<>();
        Set<String> visited = new HashSet<>();
        String groupRoot = activeGroupRoot();
        for (String group : groups) collectAllowed(groupRoot, group, allowed, visited);
        collectAllowed(groupRoot, "default", allowed, visited);

        if (allowed.contains("*")) return true;
        if (allowed.contains(command)) return true;

        // Ein erlaubter Hauptbefehl schaltet auch seine echten Bukkit-Aliase frei. Dadurch
        // funktioniert z. B. das neue /spielzeit sofort auf Servern, deren bestehende
        // commands.yml bereits /playtime erlaubt, ohne die Admin-Datei überschreiben zu müssen.
        String canonical = canonicalCommandName(command);
        if (!canonical.equals(command) && allowed.contains(canonical)) return true;

        if (player.hasPermission("smpcore.buildmode.active") && isAllowedBuildPluginCommand(command)) return true;

        int namespaceIndex = command.indexOf(':');
        if (namespaceIndex >= 0 && allowed.contains(command.substring(namespaceIndex + 1))) return true;

        return false;
    }

    public Set<String> inheritedGroups(Player player) {
        Set<String> result = new HashSet<>();
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            result.add("default");
            return result;
        }
        for (Group group : user.getInheritedGroups(user.getQueryOptions())) {
            result.add(group.getName().toLowerCase(Locale.ROOT));
        }
        result.add(user.getPrimaryGroup().toLowerCase(Locale.ROOT));
        return result;
    }

    public boolean hasAnyGroup(Player player, List<String> configuredGroups) {
        Set<String> groups = inheritedGroups(player);
        for (String group : configuredGroups) {
            if (groups.contains(group.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }


    private boolean isAllowedInCurrentWorld(Player player, String command) {
        if (!configuration.getBoolean("world-restrictions.enabled", true)) return true;

        String baseCommand = command;
        int namespaceIndex = baseCommand.indexOf(':');
        if (namespaceIndex >= 0) baseCommand = baseCommand.substring(namespaceIndex + 1);

        Set<String> smpOnly = lower(configuration.getStringList("world-restrictions.smp-only-commands"));
        if (smpOnly.contains(baseCommand)) {
            // In einem Velocity-Netzwerk entscheidet das Backend, nicht der Weltname.
            // So funktionieren SMP-Befehle auch dann, wenn die Hauptwelt anders heißt.
            if (!plugin.isSmpServer()) return false;
        }

        Set<String> hubOnly = lower(configuration.getStringList("world-restrictions.hub-only-commands"));
        if (hubOnly.contains(baseCommand)) {
            // /smp muss auf dem HUB-Backend immer erlaubt sein – unabhängig davon,
            // ob die geladene Hub-Welt "hub", "world" oder anders heißt.
            if (!plugin.isHubServer()) return false;
        }

        return true;
    }

    public boolean isSmpWorld(World world) {
        return plugin.isSmpGameplayWorld(world);
    }

    public boolean isHubWorld(World world) {
        if (world == null) return false;
        String hubWorld = plugin.configs().server().getString("world", "world");
        return world.getName().equalsIgnoreCase(hubWorld);
    }

    public boolean samePrivateMessageArea(Player first, Player second) {
        return isSmpWorld(first.getWorld()) && isSmpWorld(second.getWorld());
    }

    private String canonicalCommandName(String commandName) {
        Command command = Bukkit.getCommandMap().getCommand(commandName);
        if (command == null && commandName.contains(":")) {
            command = Bukkit.getCommandMap().getCommand(commandName.substring(commandName.indexOf(':') + 1));
        }
        return command == null ? commandName : normalize(command.getName());
    }

    private boolean isAllowedBuildPluginCommand(String commandName) {
        Command command = Bukkit.getCommandMap().getCommand(commandName);
        if (command == null && commandName.contains(":")) {
            command = Bukkit.getCommandMap().getCommand(commandName.substring(commandName.indexOf(':') + 1));
        }
        if (!(command instanceof PluginIdentifiableCommand identifiable)) return false;
        String pluginName = identifiable.getPlugin().getName();
        for (String allowedPlugin : configuration.getStringList("build-mode.allowed-command-plugins")) {
            if (pluginName.equalsIgnoreCase(allowedPlugin)) return true;
        }
        return false;
    }

    private String activeGroupRoot() {
        if (plugin.isHubServer() && configuration.isConfigurationSection("server-groups.hub")) {
            return "server-groups.hub";
        }
        if (plugin.isSmpServer() && configuration.isConfigurationSection("server-groups.smp")) {
            return "server-groups.smp";
        }
        return "groups";
    }

    private void collectAllowed(String root, String group, Set<String> allowed, Set<String> visited) {
        String normalizedGroup = group.toLowerCase(Locale.ROOT);
        String visitKey = root + ":" + normalizedGroup;
        if (!visited.add(visitKey)) return;

        ConfigurationSection section = configuration.getConfigurationSection(root + "." + normalizedGroup);
        if (section == null) return;

        Object inheritance = section.get("inherit");
        if (inheritance instanceof String parent) {
            collectAllowed(root, parent, allowed, visited);
        } else if (inheritance instanceof List<?> parents) {
            for (Object parent : parents) {
                if (parent != null) collectAllowed(root, parent.toString(), allowed, visited);
            }
        }

        for (String command : section.getStringList("allowed")) {
            allowed.add(normalize(command));
        }
    }

    private Set<String> lower(List<String> values) {
        Set<String> result = new HashSet<>();
        for (String value : values) result.add(value.toLowerCase(Locale.ROOT));
        return result;
    }

    public static String normalize(String raw) {
        if (raw == null) return "";
        String command = raw.trim().toLowerCase(Locale.ROOT);
        while (command.startsWith("/")) command = command.substring(1);
        int space = command.indexOf(' ');
        if (space >= 0) command = command.substring(0, space);
        return command;
    }
}
