package de.walahi.smpcore.commands;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class PingCommand extends BaseCommand {
    public PingCommand(SMPCorePlugin plugin) { super(plugin); }

    @Override protected String permission() { return "smpcore.command.ping"; }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            return messageOrDefault(sender, "ping.messages.player-only", "<dark_gray>[<green>SMP</green>]</dark_gray> <red>Dieser Befehl ist nur für Spieler verfügbar.</red>");
        }
        if (args.length != 0) {
            return messageOrDefault(sender, "ping.messages.usage", "<dark_gray>[<green>SMP</green>]</dark_gray> <gray>Benutzung: <yellow>/ping</yellow></gray>");
        }
        return messageOrDefault(sender, "ping.messages.result", "<dark_gray>[<green>SMP</green>]</dark_gray> <gray>Dein Ping beträgt <green>%ping% ms</green>.</gray>", "%ping%", Integer.toString(player.getPing()));
    }
}
