package de.walahi.novosmp.commands;

import de.walahi.novosmp.orders.OrderManager;
import de.walahi.novosmp.orders.OrderMenu;
import de.walahi.novosmp.orders.OrderPermissions;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Opens the order overview or the player's collect inventory. */
public final class OrderCommand extends BaseCommand {
    private final OrderMenu menu;

    public OrderCommand(SMPCorePlugin plugin, OrderManager manager) {
        super(plugin);
        this.menu = new OrderMenu(plugin, manager);
    }

    @Override
    protected String permission() {
        return OrderPermissions.USE;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            return messageOrDefault(sender, "orders.messages.player-only",
                    "<red>Nur für Spieler.</red>");
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("open")) {
            menu.open(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("collect")) {
            menu.openCollect(player);
            return true;
        }
        return messageOrDefault(player, "orders.messages.usage",
                "<gray>Benutzung: <yellow>/order</yellow> oder "
                        + "<yellow>/order collect</yellow></gray>");
    }
}
