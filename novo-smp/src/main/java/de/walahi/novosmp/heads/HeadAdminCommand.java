package de.walahi.novosmp.heads;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Administrative Wiederherstellung regulaerer, signierter Novoria-Mobkoepfe. */
public final class HeadAdminCommand extends BaseCommand {
    private final HeadCollectionManager heads;

    public HeadAdminCommand(SMPCorePlugin plugin, HeadCollectionManager heads) {
        super(plugin);
        this.heads = heads;
    }

    @Override
    protected String permission() {
        return "smpcore.heads.admin";
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length != 3 || !args[0].equalsIgnoreCase("give")) {
            return messageOrDefault(sender, "staff.messages.head-give-usage",
                    "<gray>Benutzung: <yellow>/head give <Mob> <Spieler></yellow></gray>");
        }
        if (!heads.headProviderReady()) {
            return messageOrDefault(sender, "staff.messages.head-give-unavailable",
                    "<red>Die Novoria-Kopfsammlung ist momentan nicht verfügbar.</red>");
        }

        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            return messageOrDefault(sender, "staff.messages.head-give-player-not-found",
                    "<red>Der Spieler <yellow>%player%</yellow> ist nicht online.</red>",
                    "%player%", args[2]);
        }

        ItemStack head = heads.createAdminSignedHead(args[1], target);
        if (head == null) {
            return messageOrDefault(sender, "staff.messages.head-give-invalid-mob",
                    "<red>Für den Mob-Typ <yellow>%mob%</yellow> ist kein Kopf verfügbar.</red>",
                    "%mob%", args[1]);
        }

        Map<Integer, ItemStack> overflow = target.getInventory().addItem(head);
        overflow.values().forEach(item -> target.getWorld().dropItemNaturally(target.getLocation(), item));
        messageOrDefault(sender, "staff.messages.head-give-success",
                "<green>Du hast <yellow>%player%</yellow> den Kopf <yellow>%mob%</yellow> wiederhergestellt.</green>",
                "%player%", target.getName(), "%mob%", args[1].toUpperCase(Locale.ROOT));
        if (sender != target) {
            messageOrDefault(target, "staff.messages.head-give-received",
                    "<green>Ein Teammitglied hat dir den Kopf <yellow>%mob%</yellow> wiederhergestellt.</green>",
                    "%mob%", args[1].toUpperCase(Locale.ROOT));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(permission())) return List.of();
        if (args.length == 1) return filter(List.of("give"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return filter(heads.availableAdminMobTypes(), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) names.add(player.getName());
            return filter(names, args[2]);
        }
        return List.of();
    }

    private List<String> filter(List<String> values, String input) {
        String prefix = input == null ? "" : input.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }
}
