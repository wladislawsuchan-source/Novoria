package de.walahi.smpcore.commands;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BuildCommand implements CommandExecutor, TabCompleter {
    private final BuildModeManager manager;
    private final CommandVisibilityManager visibility;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public BuildCommand(BuildModeManager manager, CommandVisibilityManager visibility) {
        this.manager = manager;
        this.visibility = visibility;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Dieser Befehl kann nur im Spiel benutzt werden.");
            return true;
        }
        if (!manager.canManage(player)) {
            send(player, "build-mode.messages.no-permission");
            return true;
        }
        if (args.length == 0) {
            manager.toggle(player, player);
            return true;
        }
        if (args.length != 1) {
            send(player, "build-mode.messages.usage");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            send(player, "build-mode.messages.player-not-found", "%player%", args[0]);
            return true;
        }
        if (!manager.isActive(target) && !manager.canBeBuilder(target)) {
            send(player, "build-mode.messages.not-a-builder", "%player%", target.getName());
            return true;
        }
        if (!manager.isActive(target) && !manager.isWorldAllowed(target)) {
            send(player, "build-mode.messages.wrong-world", "%player%", target.getName(), "%world%", target.getWorld().getName());
            return true;
        }

        manager.toggle(player, target);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || !manager.canManage(player) || args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if ((manager.canBeBuilder(online) || manager.isActive(online))
                    && online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                matches.add(online.getName());
            }
        }
        return matches;
    }

    private void send(Player player, String path, String... replacements) {
        String message = visibility.configuration().getString(path, "");
        if (message == null || message.isBlank()) return;
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            message = message.replace(replacements[i], replacements[i + 1]);
        }
        player.sendMessage(miniMessage.deserialize(message));
    }
}
