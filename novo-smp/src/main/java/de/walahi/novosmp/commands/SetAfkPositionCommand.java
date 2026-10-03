package de.walahi.novosmp.commands;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import de.walahi.novosmp.lumi.AfkZoneManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class SetAfkPositionCommand extends BaseCommand {
    private final AfkZoneManager manager;
    private final int position;

    public SetAfkPositionCommand(SMPCorePlugin plugin, AfkZoneManager manager, int position) {
        super(plugin);
        this.manager = manager;
        this.position = position;
    }

    @Override protected String permission() { return "smpcore.admin"; }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            return messageOrDefault(sender, "lumi.afk-zone.messages.player-only", "<red>Nur Spieler können die Position setzen.</red>");
        }
        String zone = args.length == 0 ? "default" : args[0];
        manager.setPosition(zone, position, player.getLocation());
        return messageOrDefault(player, "lumi.afk-zone.messages.position-set",
                "<gold>Novoria</gold> <dark_gray>»</dark_gray> <green>AFK-Position %position% wurde gesetzt.</green>",
                "%position%", Integer.toString(position) + " (Zone " + zone + ")");
    }
}
