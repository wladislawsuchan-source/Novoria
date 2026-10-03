package de.walahi.novosmp.commands;

import de.walahi.novosmp.playtime.PlaytimeRewardMenu;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class PlaytimeCommand extends BaseCommand {
    private final PlaytimeRewardMenu menu;

    public PlaytimeCommand(SMPCorePlugin plugin, PlaytimeRewardMenu menu) {
        super(plugin);
        this.menu = menu;
    }

    @Override protected String permission() { return "smpcore.playtime.use"; }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            return messageOrDefault(sender, "messages.player-only", "<red>Nur Spieler können diesen Befehl verwenden.</red>");
        }
        menu.open(player, 0);
        return true;
    }
}
