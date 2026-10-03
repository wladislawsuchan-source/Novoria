package de.walahi.novosmp.commands.gameplay;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class RtpCommand extends BaseCommand {
    public RtpCommand(SMPCorePlugin plugin) { super(plugin); }
    @Override protected String permission() { return "smpcore.rtp"; }
    @Override protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) return messageOrDefault(sender, "messages.player-only", "<red>Nur Spieler können diesen Befehl verwenden.</red>");
        plugin.openRtpMenu(player);
        return true;
    }
}
