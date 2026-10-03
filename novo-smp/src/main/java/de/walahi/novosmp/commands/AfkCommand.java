package de.walahi.novosmp.commands;

import de.walahi.smpcore.afk.AfkAccess;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class AfkCommand extends BaseCommand {
    private final AfkAccess afkManager;

    public AfkCommand(SMPCorePlugin plugin, AfkAccess afkManager) {
        super(plugin);
        this.afkManager = afkManager;
    }

    @Override protected String permission() { return "smpcore.command.afk"; }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            return messageOrDefault(sender, "afk.messages.player-only", "<dark_gray>[<green>SMP</green>]</dark_gray> <red>Dieser Befehl ist nur für Spieler verfügbar.</red>");
        }
        if (args.length != 0) {
            return messageOrDefault(sender, "afk.messages.usage", "<dark_gray>[<green>SMP</green>]</dark_gray> <gray>Benutzung: <yellow>/afk</yellow></gray>");
        }
        afkManager.toggle(player);
        return true;
    }
}
