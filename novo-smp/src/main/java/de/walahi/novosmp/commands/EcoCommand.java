package de.walahi.novosmp.commands;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.api.event.ActionContext;
import de.walahi.smpcore.api.event.ActionSource;
import de.walahi.smpcore.commands.framework.BaseCommand;
import de.walahi.smpcore.economy.EconomyOperationResult;
import de.walahi.smpcore.services.EconomyService;
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
import java.util.UUID;

/** Owner/admin command for whole-coin account administration. */
public final class EcoCommand extends BaseCommand {
    private static final NumberFormat COIN_FORMAT = NumberFormat.getIntegerInstance(Locale.GERMANY);
    private static final List<String> ACTIONS = List.of("give", "take", "set");
    private final EconomyService economy;

    public EcoCommand(SMPCorePlugin plugin, EconomyService economy) {
        super(plugin);
        this.economy = Objects.requireNonNull(economy, "economy");
        COIN_FORMAT.setGroupingUsed(true);
        COIN_FORMAT.setMaximumFractionDigits(0);
    }

    @Override
    protected String permission() {
        return "smpcore.economy.admin";
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length != 3 || !ACTIONS.contains(args[0].toLowerCase(Locale.ROOT))) {
            return usage(sender);
        }
        if (!economy.available()) {
            return messageOrDefault(sender, "economy.messages.unavailable",
                    "<dark_gray>[<green>SMP</green>]</dark_gray> <red>Die Economy ist momentan nicht verfügbar.</red>");
        }

        OfflinePlayer target = findKnownPlayer(args[1]);
        if (target == null) {
            return messageOrDefault(sender, "economy.admin.messages.player-not-found",
                    "<dark_gray>[<red>TEAM</red>]</dark_gray> <red>Der Spieler <yellow>%player%</yellow> wurde nicht gefunden.</red>",
                    "%player%", args[1]);
        }

        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException exception) {
            return invalidAmount(sender);
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        if ((action.equals("set") && amount < 0L) || (!action.equals("set") && amount <= 0L)) {
            return invalidAmount(sender);
        }

        UUID actorUuid = sender instanceof Player player ? player.getUniqueId() : null;
        ActionContext context = ActionContext.actorTarget(ActionSource.ADMIN, actorUuid, target.getUniqueId());
        EconomyOperationResult result = switch (action) {
            case "give" -> economy.deposit(target.getUniqueId(), amount, "ADMIN_GIVE", context);
            case "take" -> economy.withdraw(target.getUniqueId(), amount, "ADMIN_TAKE", context);
            case "set" -> economy.setBalance(target.getUniqueId(), amount, "ADMIN_SET", context);
            default -> EconomyOperationResult.INVALID_AMOUNT;
        };

        if (result == EconomyOperationResult.INSUFFICIENT_FUNDS) {
            return messageOrDefault(sender, "economy.admin.messages.insufficient-funds",
                    "<dark_gray>[<red>TEAM</red>]</dark_gray> <red><yellow>%player%</yellow> besitzt nicht genügend Coins.</red>",
                    "%player%", displayName(target));
        }
        if (result == EconomyOperationResult.INVALID_AMOUNT) return invalidAmount(sender);
        if (result != EconomyOperationResult.SUCCESS) {
            return messageOrDefault(sender, "economy.admin.messages.storage-error",
                    "<dark_gray>[<red>TEAM</red>]</dark_gray> <red>Die Änderung konnte wegen eines Datenbankfehlers nicht gespeichert werden.</red>");
        }

        long balance = economy.balance(target.getUniqueId());
        String amountText = COIN_FORMAT.format(amount);
        String balanceText = COIN_FORMAT.format(balance);
        String name = displayName(target);
        messageOrDefault(sender, "economy.admin.messages." + action + "-success", successFallback(action),
                "%player%", name, "%amount%", amountText, "%balance%", balanceText);

        Player onlineTarget = target.getPlayer();
        if (onlineTarget != null && !onlineTarget.equals(sender)) {
            messageOrDefault(onlineTarget, "economy.admin.messages.target-" + action, targetFallback(action),
                    "%amount%", amountText, "%balance%", balanceText);
            if (action.equals("give")) plugin.sounds().play(onlineTarget, "economy.coins-received");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(permission())) return List.of();
        if (args.length == 1) return match(args[0], ACTIONS);
        if (args.length == 2) {
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
        return messageOrDefault(sender, "economy.admin.messages.usage",
                "<dark_gray>[<red>TEAM</red>]</dark_gray> <gray>Benutzung: <yellow>/eco <give|take|set> <Spieler> <Betrag></yellow></gray>");
    }

    private boolean invalidAmount(CommandSender sender) {
        return messageOrDefault(sender, "economy.admin.messages.invalid-amount",
                "<dark_gray>[<red>TEAM</red>]</dark_gray> <red>Der Betrag muss eine gültige ganze Zahl sein.</red>");
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

    private static String successFallback(String action) {
        return switch (action) {
            case "give" -> "<dark_gray>[<red>TEAM</red>]</dark_gray> <green>Du hast <yellow>%player%</yellow> <yellow>%amount% Coins</yellow> gegeben. Neuer Kontostand: <yellow>%balance% Coins</yellow>.</green>";
            case "take" -> "<dark_gray>[<red>TEAM</red>]</dark_gray> <green>Du hast <yellow>%player%</yellow> <yellow>%amount% Coins</yellow> abgezogen. Neuer Kontostand: <yellow>%balance% Coins</yellow>.</green>";
            default -> "<dark_gray>[<red>TEAM</red>]</dark_gray> <green>Du hast den Kontostand von <yellow>%player%</yellow> auf <yellow>%balance% Coins</yellow> gesetzt.</green>";
        };
    }

    private static String targetFallback(String action) {
        return switch (action) {
            case "give" -> "<dark_gray>[<green>SMP</green>]</dark_gray> <gray>Dir wurden <green>%amount% Coins</green> gutgeschrieben.</gray>";
            case "take" -> "<dark_gray>[<green>SMP</green>]</dark_gray> <gray>Dir wurden <red>%amount% Coins</red> abgezogen.</gray>";
            default -> "<dark_gray>[<green>SMP</green>]</dark_gray> <gray>Dein Kontostand wurde auf <green>%balance% Coins</green> gesetzt.</gray>";
        };
    }
}
