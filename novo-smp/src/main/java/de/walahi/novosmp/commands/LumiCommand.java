package de.walahi.novosmp.commands;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import de.walahi.novosmp.lumi.LumiRepository;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class LumiCommand extends BaseCommand {
    private static final List<String> ACTIONS = List.of("pay", "give", "take", "set", "balance");
    private static final List<String> ADMIN_ACTIONS = List.of("give", "take", "set", "balance");
    private static final String ADMIN_PERMISSION = "smpcore.command.lumi.admin";
    private static final NumberFormat FORMAT = NumberFormat.getIntegerInstance(Locale.GERMANY);

    private final LumiRepository lumis;

    public LumiCommand(SMPCorePlugin plugin, LumiRepository lumis) {
        super(plugin);
        this.lumis = Objects.requireNonNull(lumis, "lumis");
        FORMAT.setGroupingUsed(true);
        FORMAT.setMaximumFractionDigits(0);
    }

    @Override
    protected String permission() {
        return "smpcore.command.lumi";
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length == 0) return usage(sender);

        String action = args[0].toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) return usage(sender);

        if (action.equals("pay")) return pay(sender, args);
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            return messageOrDefault(sender, "lumi.admin.messages.no-permission",
                    "<dark_gray>[<gold>Novoria</gold>]</dark_gray> <red>Dafür hast du keine Berechtigung.</red>");
        }

        if (action.equals("balance")) {
            OfflinePlayer target;
            if (args.length == 1 && sender instanceof Player player) {
                target = player;
            } else if (args.length == 2) {
                target = findKnownPlayer(args[1]);
            } else {
                return usage(sender);
            }

            if (target == null) return playerNotFound(sender, args.length >= 2 ? args[1] : "");
            long balance = lumis.balance(target.getUniqueId());
            String name = displayName(target);
            return messageOrDefault(sender, "lumi.admin.messages.balance",
                    "<dark_gray>[<gold>Novoria</gold>]</dark_gray> <gray>Lumi-Kontostand von <yellow>%player%</yellow>: <yellow>%balance% Lumis</yellow>.</gray>",
                    "%player%", name,
                    "%balance%", FORMAT.format(balance));
        }

        if (args.length != 3) return usage(sender);
        OfflinePlayer target = findKnownPlayer(args[1]);
        if (target == null) return playerNotFound(sender, args[1]);

        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException exception) {
            return invalidAmount(sender);
        }

        if ((action.equals("set") && amount < 0L) || (!action.equals("set") && amount <= 0L)) {
            return invalidAmount(sender);
        }

        boolean success = switch (action) {
            case "give" -> lumis.add(target.getUniqueId(), amount);
            case "take" -> lumis.withdraw(target.getUniqueId(), amount);
            case "set" -> lumis.set(target.getUniqueId(), amount);
            default -> false;
        };

        if (!success) {
            if (action.equals("take")) {
                return messageOrDefault(sender, "lumi.admin.messages.insufficient-funds",
                        "<dark_gray>[<gold>Novoria</gold>]</dark_gray> <red><yellow>%player%</yellow> besitzt nicht genügend Lumis.</red>",
                        "%player%", displayName(target));
            }
            return messageOrDefault(sender, "lumi.admin.messages.storage-error",
                    "<dark_gray>[<gold>Novoria</gold>]</dark_gray> <red>Die Lumi-Änderung konnte nicht gespeichert werden.</red>");
        }

        long balance = lumis.balance(target.getUniqueId());
        String name = displayName(target);
        String amountText = FORMAT.format(amount);
        String balanceText = FORMAT.format(balance);

        String fallback = switch (action) {
            case "give" -> "<dark_gray>[<gold>Novoria</gold>]</dark_gray> <green>Du hast <yellow>%player%</yellow> <yellow>%amount% Lumis</yellow> gegeben. Neuer Kontostand: <yellow>%balance% Lumis</yellow>.</green>";
            case "take" -> "<dark_gray>[<gold>Novoria</gold>]</dark_gray> <green>Du hast <yellow>%player%</yellow> <yellow>%amount% Lumis</yellow> entfernt. Neuer Kontostand: <yellow>%balance% Lumis</yellow>.</green>";
            default -> "<dark_gray>[<gold>Novoria</gold>]</dark_gray> <green>Der Lumi-Kontostand von <yellow>%player%</yellow> wurde auf <yellow>%balance% Lumis</yellow> gesetzt.</green>";
        };

        messageOrDefault(sender, "lumi.admin.messages." + action + "-success", fallback,
                "%player%", name,
                "%amount%", amountText,
                "%balance%", balanceText);

        Player onlineTarget = target.getPlayer();
        if (onlineTarget != null && !onlineTarget.equals(sender)) {
            String targetFallback = switch (action) {
                case "give" -> "<dark_gray>[<gold>Novoria</gold>]</dark_gray> <gray>Dir wurden <yellow>%amount% Lumis</yellow> gutgeschrieben. Kontostand: <yellow>%balance% Lumis</yellow>.</gray>";
                case "take" -> "<dark_gray>[<gold>Novoria</gold>]</dark_gray> <gray>Dir wurden <red>%amount% Lumis</red> entfernt. Kontostand: <yellow>%balance% Lumis</yellow>.</gray>";
                default -> "<dark_gray>[<gold>Novoria</gold>]</dark_gray> <gray>Dein Lumi-Kontostand wurde auf <yellow>%balance% Lumis</yellow> gesetzt.</gray>";
            };
            messageOrDefault(onlineTarget, "lumi.admin.messages.target-" + action, targetFallback,
                    "%amount%", amountText,
                    "%balance%", balanceText);
        }
        return true;
    }

    private boolean pay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            return messageOrDefault(sender, "lumi.pay.messages.player-only",
                    "<dark_gray>[<gold>Novoria</gold>]</dark_gray> <red>Dieser Befehl ist nur für Spieler verfügbar.</red>");
        }
        if (args.length != 3) return payUsage(sender);

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            return messageOrDefault(sender, "lumi.pay.messages.player-not-found",
                    "<dark_gray>[<gold>Novoria</gold>]</dark_gray> <red>Dieser Spieler ist nicht online.</red>");
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            return messageOrDefault(sender, "lumi.pay.messages.self",
                    "<dark_gray>[<gold>Novoria</gold>]</dark_gray> <red>Du kannst dir nicht selbst Lumis senden.</red>");
        }

        final long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException exception) {
            return payInvalidAmount(sender);
        }
        if (amount <= 0L) return payInvalidAmount(sender);

        LumiRepository.TransferResult result = lumis.transfer(player.getUniqueId(), target.getUniqueId(), amount);
        if (result == LumiRepository.TransferResult.INSUFFICIENT_FUNDS) {
            return messageOrDefault(sender, "lumi.pay.messages.insufficient-funds",
                    "<dark_gray>[<gold>Novoria</gold>]</dark_gray> <red>Du besitzt nicht genügend Lumis.</red>");
        }
        if (result == LumiRepository.TransferResult.INVALID_AMOUNT) return payInvalidAmount(sender);
        if (result == LumiRepository.TransferResult.SAME_ACCOUNT) {
            return messageOrDefault(sender, "lumi.pay.messages.self",
                    "<dark_gray>[<gold>Novoria</gold>]</dark_gray> <red>Du kannst dir nicht selbst Lumis senden.</red>");
        }
        if (result != LumiRepository.TransferResult.SUCCESS) {
            return messageOrDefault(sender, "lumi.pay.messages.storage-error",
                    "<dark_gray>[<gold>Novoria</gold>]</dark_gray> <red>Die Lumi-Überweisung konnte nicht gespeichert werden.</red>");
        }

        String formattedAmount = FORMAT.format(amount);
        messageOrDefault(player, "lumi.pay.messages.sent",
                "<dark_gray>[<gold>Novoria</gold>]</dark_gray> <gray>Du hast <yellow>%player%</yellow> <gold>%amount% Lumis</gold> gesendet.</gray>",
                "%player%", target.getName(), "%amount%", formattedAmount);
        messageOrDefault(target, "lumi.pay.messages.received",
                "<dark_gray>[<gold>Novoria</gold>]</dark_gray> <gray>Du hast von <yellow>%player%</yellow> <gold>%amount% Lumis</gold> erhalten.</gray>",
                "%player%", player.getName(), "%amount%", formattedAmount);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(permission())) return List.of();
        if (args.length == 1) {
            List<String> actions = sender.hasPermission(ADMIN_PERMISSION) ? ACTIONS : List.of("pay");
            return match(args[0], actions);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("pay") && sender instanceof Player player) {
            return match(args[1], Bukkit.getOnlinePlayers().stream()
                    .filter(target -> !target.getUniqueId().equals(player.getUniqueId()))
                    .map(Player::getName)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList());
        }
        if (args.length == 2 && sender.hasPermission(ADMIN_PERMISSION)
                && ADMIN_ACTIONS.contains(args[0].toLowerCase(Locale.ROOT))) {
            return match(args[1], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        }
        return List.of();
    }

    private OfflinePlayer findKnownPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        return Arrays.stream(Bukkit.getOfflinePlayers())
                .filter(player -> player.getName() != null && player.getName().equalsIgnoreCase(name))
                .findFirst().orElse(null);
    }

    private boolean usage(CommandSender sender) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) return payUsage(sender);
        return messageOrDefault(sender, "lumi.admin.messages.usage",
                "<dark_gray>[<gold>Novoria</gold>]</dark_gray> <gray>Benutzung: <yellow>/lumi <pay|give|take|set|balance> [Spieler] [Anzahl]</yellow></gray>");
    }

    private boolean payUsage(CommandSender sender) {
        return messageOrDefault(sender, "lumi.pay.messages.usage",
                "<dark_gray>[<gold>Novoria</gold>]</dark_gray> <gray>Benutzung: <yellow>/lumi pay <Spieler> <Menge></yellow></gray>");
    }

    private boolean payInvalidAmount(CommandSender sender) {
        return messageOrDefault(sender, "lumi.pay.messages.invalid-amount",
                "<dark_gray>[<gold>Novoria</gold>]</dark_gray> <red>Die Menge muss eine positive ganze Zahl sein.</red>");
    }

    private boolean invalidAmount(CommandSender sender) {
        return messageOrDefault(sender, "lumi.admin.messages.invalid-amount",
                "<dark_gray>[<gold>Novoria</gold>]</dark_gray> <red>Die Anzahl muss eine gültige ganze Zahl sein.</red>");
    }

    private boolean playerNotFound(CommandSender sender, String name) {
        return messageOrDefault(sender, "lumi.admin.messages.player-not-found",
                "<dark_gray>[<gold>Novoria</gold>]</dark_gray> <red>Der Spieler <yellow>%player%</yellow> wurde nicht gefunden.</red>",
                "%player%", name);
    }

    private static String displayName(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString() : player.getName();
    }

    private static List<String> match(String input, List<String> values) {
        String lower = input.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lower)) matches.add(value);
        }
        return matches;
    }
}
