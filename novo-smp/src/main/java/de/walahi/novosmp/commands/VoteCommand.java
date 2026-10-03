package de.walahi.novosmp.commands;

import de.walahi.novosmp.vote.VoteMenu;
import de.walahi.novosmp.vote.VoteManager;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class VoteCommand extends BaseCommand {
    private final VoteMenu menu;
    private final VoteManager manager;
    private static final List<String> ADMIN_ACTIONS = List.of("get", "set", "add", "remove");

    public VoteCommand(SMPCorePlugin plugin, VoteMenu menu, VoteManager manager) {
        super(plugin);
        this.menu = menu;
        this.manager = manager;
    }

    @Override protected String permission() { return "smpcore.vote.use"; }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length > 0) return executeAdmin(sender, args);
        if (!(sender instanceof Player player)) {
            return messageOrDefault(sender, "messages.player-only", "<red>Nur Spieler können diesen Befehl verwenden.</red>");
        }
        menu.open(player);
        return true;
    }

    private boolean executeAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("smpcore.vote.admin")) {
            return messageOrDefault(sender, "staff.messages.no-permission", "<red>Dafür hast du keine Berechtigung.</red>");
        }
        String actionName = args[0].toLowerCase(Locale.ROOT);
        if (!ADMIN_ACTIONS.contains(actionName)) return usage(sender);
        boolean get = actionName.equals("get");
        if (args.length != (get ? 2 : 3)) return usage(sender);

        UUID playerId = manager.knownPlayerId(args[1]).orElse(null);
        if (playerId == null) {
            return messageOrDefault(sender, "vote.admin.player-not-found",
                    "<dark_gray>[<red>TEAM</red>]</dark_gray> <red>Der Spieler <yellow>%player%</yellow> wurde nicht gefunden.</red>",
                    "%player%", args[1]);
        }

        long amount = 0L;
        if (!get) {
            try {
                amount = Long.parseLong(args[2]);
            } catch (NumberFormatException exception) {
                return invalidAmount(sender);
            }
            if ((actionName.equals("set") && amount < 0L) || (!actionName.equals("set") && amount <= 0L)) {
                return invalidAmount(sender);
            }
        }

        VoteManager.VoteAdminAction action = VoteManager.VoteAdminAction.valueOf(actionName.toUpperCase(Locale.ROOT));
        VoteManager.VoteAdminResult result = manager.adjustTotal(playerId, args[1], action, amount);
        if (!result.success()) {
            return messageOrDefault(sender, "vote.admin.storage-error",
                    "<dark_gray>[<red>TEAM</red>]</dark_gray> <red>%error%</red>", "%error%", result.error());
        }

        long progress = result.milestoneInterval() <= 0 ? 0 : result.after() % result.milestoneInterval();
        long next = result.after() - progress + result.milestoneInterval();
        if (get) {
            return messageOrDefault(sender, "vote.admin.get-success",
                    "<dark_gray>[<red>TEAM</red>]</dark_gray> <gray><yellow>%player%</yellow> hat <light_purple>%votes% Votes</light_purple>. Fortschritt: <white>%progress%/%interval%</white>, nächste Belohnung bei <yellow>%next%</yellow>.</gray>",
                    "%player%", args[1], "%votes%", Long.toString(result.after()), "%progress%", Long.toString(progress),
                    "%interval%", Integer.toString(result.milestoneInterval()), "%next%", Long.toString(next));
        }
        return messageOrDefault(sender, "vote.admin.change-success",
                "<dark_gray>[<red>TEAM</red>]</dark_gray> <green>Votes von <yellow>%player%</yellow>: <white>%before%</white> → <light_purple>%after%</light_purple>.</green>",
                "%player%", args[1], "%before%", Long.toString(result.before()), "%after%", Long.toString(result.after()));
    }

    private boolean usage(CommandSender sender) {
        return messageOrDefault(sender, "vote.admin.usage",
                "<gray>Benutzung: <yellow>/vote get &lt;Spieler&gt;</yellow> | <yellow>/vote set &lt;Spieler&gt; &lt;Anzahl&gt;</yellow> | <yellow>/vote add &lt;Spieler&gt; &lt;Anzahl&gt;</yellow> | <yellow>/vote remove &lt;Spieler&gt; &lt;Anzahl&gt;</yellow></gray>");
    }

    private boolean invalidAmount(CommandSender sender) {
        return messageOrDefault(sender, "vote.admin.invalid-amount",
                "<red>Die Anzahl muss eine gültige ganze Zahl sein; bei add/remove mindestens 1.</red>");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("smpcore.vote.admin")) return List.of();
        if (args.length == 1) return match(args[0], ADMIN_ACTIONS);
        if (args.length == 2 && ADMIN_ACTIONS.contains(args[0].toLowerCase(Locale.ROOT))) {
            return match(args[1], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        }
        return List.of();
    }

    private static List<String> match(String input, List<String> values) {
        String lower = input.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String value : values) if (value.toLowerCase(Locale.ROOT).startsWith(lower)) result.add(value);
        return result;
    }
}
