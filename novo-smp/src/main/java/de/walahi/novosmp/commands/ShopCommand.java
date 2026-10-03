package de.walahi.novosmp.commands;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import de.walahi.novosmp.shop.ShopMenu;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class ShopCommand extends BaseCommand {
    private final ShopMenu menu;
    public ShopCommand(SMPCorePlugin plugin, ShopMenu menu) { super(plugin); this.menu = menu; }
    @Override protected String permission() { return "smpcore.shop.use"; }
    @Override protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) return messageOrDefault(sender, "shop.messages.player-only", "<red>Nur für Spieler.</red>");
        menu.open(player);
        return true;
    }
}
