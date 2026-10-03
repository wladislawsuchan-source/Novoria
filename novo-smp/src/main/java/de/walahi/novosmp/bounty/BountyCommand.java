package de.walahi.novosmp.bounty;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** /bounty GUI, funded placement and compact administration facade. */
public final class BountyCommand extends BaseCommand {
    private static final String USE = "smpcore.bounty.use";
    private static final String SET = "smpcore.bounty.set";
    private static final String ADMIN = "smpcore.bounty.admin";
    private final BountyManager manager;

    public BountyCommand(NovoSMPPlugin plugin, BountyManager manager) {
        super(plugin);
        this.manager = manager;
    }

    @Override protected String permission() { return ""; }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            if (!sender.hasPermission(USE)) return noPermission(sender);
            if (!(sender instanceof Player player)) return playerOnly(sender);
            if (!manager.enabled()) return disabled(sender);
            manager.open(player);
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("admin")) {
            return admin(sender, args);
        }
        if (args.length != 2) return usage(sender);
        if (!sender.hasPermission(SET)) return noPermission(sender);
        if (!(sender instanceof Player player)) return playerOnly(sender);

        Long amount = parse(args[1]);
        if (amount == null) return invalidAmount(sender);
        BountyManager.PlaceResult result = manager.place(player, args[0], amount);
        return handlePlace(sender, result);
    }

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN)) return noPermission(sender);
        if (args.length < 3) return adminUsage(sender);
        String action = args[1].toLowerCase(Locale.ROOT);
        BountyRepository.KnownPlayer target = manager.resolveAnyKnown(args[2]);
        if (target == null) return messageOrDefault(sender, "bounty.command.target-not-found",
                "<dark_gray>[<dark_red>Bounty</dark_red>]</dark_gray> <red>Dieser Spieler hat Novoria noch nie betreten.</red>");

        if (action.equals("get") && args.length == 3) {
            BountyEntry entry = manager.get(target.id());
            return messageOrDefault(sender, "bounty.command.admin-get",
                    "<dark_gray>[<dark_red>Bounty</dark_red>]</dark_gray> <gray>Kopfgeld auf <yellow>%player%</yellow>: <gold>%amount% Coins</gold>.</gray>",
                    "%player%", target.name(), "%amount%", BountyManager.format(entry == null ? 0L : entry.amount()));
        }
        if (action.equals("remove") && args.length == 3) {
            return handleAdmin(sender, target, manager.adminRemove(target), "entfernt");
        }
        if ((action.equals("set") || action.equals("add")) && args.length == 4) {
            Long amount = parse(args[3]);
            if (amount == null || (action.equals("add") ? amount <= 0L : amount < 0L)) return invalidAmount(sender);
            BountyRepository.Mutation result = action.equals("set")
                    ? manager.adminSet(target, amount) : manager.adminAdd(target, amount);
            return handleAdmin(sender, target, result, action.equals("set") ? "gesetzt" : "erhöht");
        }
        return adminUsage(sender);
    }

    private boolean handlePlace(CommandSender sender, BountyManager.PlaceResult result) {
        return switch (result.status()) {
            case NEW_BOUNTY, INCREASED -> messageOrDefault(sender, "bounty.command.success",
                    "<dark_gray>[<dark_red>Bounty</dark_red>]</dark_gray> <green>Das Kopfgeld auf <yellow>%player%</yellow> beträgt jetzt <gold>%amount% Coins</gold>.</green>",
                    "%player%", result.entry().targetName(), "%amount%", BountyManager.format(result.entry().amount()));
            case DISABLED -> disabled(sender);
            case TARGET_NOT_FOUND -> messageOrDefault(sender, "bounty.command.target-not-found",
                    "<dark_gray>[<dark_red>Bounty</dark_red>]</dark_gray> <red>Dieser Spieler hat Novoria noch nie betreten.</red>");
            case OFFLINE_NOT_ALLOWED -> messageOrDefault(sender, "bounty.command.offline-not-allowed",
                    "<dark_gray>[<dark_red>Bounty</dark_red>]</dark_gray> <red>Das Ziel muss momentan online sein.</red>");
            case SELF_NOT_ALLOWED -> messageOrDefault(sender, "bounty.command.self-not-allowed",
                    "<dark_gray>[<dark_red>Bounty</dark_red>]</dark_gray> <red>Du kannst kein Kopfgeld auf dich selbst setzen.</red>");
            case BELOW_MINIMUM -> messageOrDefault(sender, "bounty.command.below-minimum",
                    "<dark_gray>[<dark_red>Bounty</dark_red>]</dark_gray> <red>Der Mindestbetrag beträgt <gold>%amount% Coins</gold>.</red>",
                    "%amount%", BountyManager.format(manager.minimumAmount()));
            case ABOVE_MAXIMUM -> messageOrDefault(sender, "bounty.command.above-maximum",
                    "<dark_gray>[<dark_red>Bounty</dark_red>]</dark_gray> <red>Der maximale Einzelbetrag beträgt <gold>%amount% Coins</gold>.</red>",
                    "%amount%", BountyManager.format(manager.maximumAmount()));
            case INSUFFICIENT_FUNDS -> messageOrDefault(sender, "bounty.command.insufficient-funds",
                    "<dark_gray>[<dark_red>Bounty</dark_red>]</dark_gray> <red>Du besitzt nicht genügend Coins.</red>");
            case INVALID_AMOUNT -> invalidAmount(sender);
            case STORAGE_ERROR -> storageError(sender);
        };
    }

    private boolean handleAdmin(CommandSender sender, BountyRepository.KnownPlayer target,
                                BountyRepository.Mutation result, String action) {
        if (result.status() == BountyRepository.Status.NOT_FOUND) {
            return messageOrDefault(sender, "bounty.command.admin-not-found",
                    "<dark_gray>[<dark_red>Bounty</dark_red>]</dark_gray> <red>Auf diesem Spieler liegt kein Kopfgeld.</red>");
        }
        if (result.status() == BountyRepository.Status.INVALID_AMOUNT) return invalidAmount(sender);
        if (result.status() != BountyRepository.Status.SUCCESS) return storageError(sender);
        long total = result.entry() == null ? 0L : result.entry().amount();
        return messageOrDefault(sender, "bounty.command.admin-success",
                "<dark_gray>[<dark_red>Bounty</dark_red>]</dark_gray> <green>Kopfgeld auf <yellow>%player%</yellow> %action%: <gold>%amount% Coins</gold>.</green>",
                "%player%", target.name(), "%action%", action, "%amount%", BountyManager.format(total));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            Set<String> values = new LinkedHashSet<>();
            if (sender.hasPermission(ADMIN)) values.add("admin");
            Bukkit.getOnlinePlayers().forEach(player -> values.add(player.getName()));
            manager.entries().forEach(entry -> values.add(entry.targetName()));
            return match(args[0], values);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin") && sender.hasPermission(ADMIN)) {
            return match(args[1], List.of("set", "add", "remove", "get"));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && sender.hasPermission(ADMIN)) {
            Set<String> values = new LinkedHashSet<>();
            Bukkit.getOnlinePlayers().forEach(player -> values.add(player.getName()));
            manager.entries().forEach(entry -> values.add(entry.targetName()));
            return match(args[2], values);
        }
        return List.of();
    }

    private boolean usage(CommandSender sender) {
        return messageOrDefault(sender, "bounty.command.usage",
                "<dark_gray>[<dark_red>Bounty</dark_red>]</dark_gray> <gray>Benutzung: <yellow>/bounty [Spieler Betrag]</yellow></gray>");
    }
    private boolean adminUsage(CommandSender sender) {
        return messageOrDefault(sender, "bounty.command.admin-usage",
                "<dark_gray>[<dark_red>Bounty</dark_red>]</dark_gray> <gray>Benutzung: <yellow>/bounty admin <set|add|remove|get> <Spieler> [Betrag]</yellow></gray>");
    }
    private boolean noPermission(CommandSender sender) {
        return messageOrDefault(sender, "staff.messages.no-permission", "<red>Dafür hast du keine Berechtigung.</red>");
    }
    private boolean playerOnly(CommandSender sender) {
        return messageOrDefault(sender, "bounty.command.player-only", "<red>Dieser Befehl ist nur für Spieler verfügbar.</red>");
    }
    private boolean disabled(CommandSender sender) {
        return messageOrDefault(sender, "bounty.command.disabled", "<red>Das Bounty-System ist momentan deaktiviert.</red>");
    }
    private boolean invalidAmount(CommandSender sender) {
        return messageOrDefault(sender, "bounty.command.invalid-amount", "<red>Der Betrag muss eine positive ganze Zahl sein.</red>");
    }
    private boolean storageError(CommandSender sender) {
        return messageOrDefault(sender, "bounty.command.storage-error", "<red>Die Bounty konnte wegen eines Datenbankfehlers nicht geändert werden.</red>");
    }
    private Long parse(String value) {
        try { return Long.parseLong(value); }
        catch (NumberFormatException exception) { return null; }
    }
    private List<String> match(String input, Iterable<String> values) {
        String prefix = input == null ? "" : input.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String value : values) if (value.toLowerCase(Locale.ROOT).startsWith(prefix)) result.add(value);
        return result;
    }
}
