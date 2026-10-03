package de.walahi.novosmp.commands;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import de.walahi.novosmp.lumi.LumiShopMenu;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class LumiShopCommand extends BaseCommand {
    private final LumiShopMenu menu;

    public LumiShopCommand(SMPCorePlugin plugin, LumiShopMenu menu) {
        super(plugin);
        this.menu = menu;
    }

    @Override protected String permission() { return "smpcore.lumishop.use"; }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            return messageOrDefault(sender, "lumi.shop.messages.player-only", "<red>Nur für Spieler.</red>");
        }
        menu.open(player);
        return true;
    }
}
