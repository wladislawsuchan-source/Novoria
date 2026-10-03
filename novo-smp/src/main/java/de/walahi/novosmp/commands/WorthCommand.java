package de.walahi.novosmp.commands;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import de.walahi.novosmp.economy.worth.WorthMenu;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class WorthCommand extends BaseCommand {
    private final WorthMenu menu;
    public WorthCommand(SMPCorePlugin plugin, WorthMenu menu) { super(plugin); this.menu = menu; }
    @Override protected String permission() { return "smpcore.economy.worth"; }
    @Override protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) return messageOrDefault(sender, "economy.worth.messages.player-only", "<red>Nur für Spieler.</red>");
        menu.open(player);
        return true;
    }
}
