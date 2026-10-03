package de.walahi.smpcore.commands;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Replaces Minecraft's /kill command so the vanilla "Killed ..." feedback
 * is not printed to the command sender. The normal PlayerDeathEvent still runs,
 * therefore the German death message is shown exactly once.
 */
public final class KillCommand extends BaseCommand {
    public KillCommand(SMPCorePlugin plugin) {
        super(plugin);
    }

    @Override
    protected String permission() {
        return "smpcore.command.kill";
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                return messageOrDefault(sender, "kill.messages.usage",
                        "<dark_gray>[<red>Team</red>]</dark_gray> <gray>Benutzung: <yellow>/kill <Spieler></yellow></gray>");
            }
            player.setHealth(0.0);
            return true;
        }

        if (args.length != 1) {
            return messageOrDefault(sender, "kill.messages.usage",
                    "<dark_gray>[<red>Team</red>]</dark_gray> <gray>Benutzung: <yellow>/kill [Spieler]</yellow></gray>");
        }

        if (!sender.hasPermission("smpcore.command.kill.others")) {
            return messageOrDefault(sender, "kill.messages.no-permission-others",
                    "<dark_gray>[<red>Team</red>]</dark_gray> <red>Du darfst keine anderen Spieler töten.</red>");
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            return messageOrDefault(sender, "kill.messages.player-not-found",
                    "<dark_gray>[<red>Team</red>]</dark_gray> <red>Dieser Spieler ist nicht online.</red>");
        }

        target.setHealth(0.0);
        // Bewusst keine Bestätigungsnachricht: Die Todesnachricht reicht aus.
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, org.bukkit.command.Command command, String alias, String[] args) {
        if (args.length != 1 || !sender.hasPermission("smpcore.command.kill.others")) {
            return Collections.emptyList();
        }
        String start = args[0].toLowerCase();
        List<String> result = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase().startsWith(start)) {
                result.add(player.getName());
            }
        }
        return result;
    }
}
