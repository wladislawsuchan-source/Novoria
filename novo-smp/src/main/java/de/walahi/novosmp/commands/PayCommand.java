package de.walahi.novosmp.commands;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.api.event.ActionContext;
import de.walahi.smpcore.api.event.ActionSource;
import de.walahi.smpcore.commands.framework.BaseCommand;
import de.walahi.smpcore.economy.EconomyOperationResult;
import de.walahi.smpcore.services.EconomyService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Transfers whole coins between two online players. */
public final class PayCommand extends BaseCommand {
    private static final NumberFormat COIN_FORMAT = NumberFormat.getIntegerInstance(Locale.GERMANY);
    private final EconomyService economy;

    public PayCommand(SMPCorePlugin plugin, EconomyService economy) {
        super(plugin);
        this.economy = Objects.requireNonNull(economy, "economy");
        COIN_FORMAT.setGroupingUsed(true);
        COIN_FORMAT.setMaximumFractionDigits(0);
    }

    @Override
    protected String permission() {
        return "smpcore.economy.pay";
    }

    @Override
    protected String noPermissionMessagePath() {
        return "economy.messages.no-permission";
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            return messageOrDefault(sender, "economy.pay.messages.player-only",
                    "<dark_gray>[<green>SMP</green>]</dark_gray> <red>Dieser Befehl ist nur für Spieler verfügbar.</red>");
        }
        if (args.length != 2) return usage(sender);
        if (!economy.available()) {
            return messageOrDefault(sender, "economy.messages.unavailable",
                    "<dark_gray>[<green>SMP</green>]</dark_gray> <red>Die Economy ist momentan nicht verfügbar.</red>");
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            return messageOrDefault(sender, "economy.pay.messages.player-not-found",
                    "<dark_gray>[<green>SMP</green>]</dark_gray> <red>Dieser Spieler ist nicht online.</red>");
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            return messageOrDefault(sender, "economy.pay.messages.self",
                    "<dark_gray>[<green>SMP</green>]</dark_gray> <red>Du kannst dir nicht selbst Coins senden.</red>");
        }

        final long amount;
        try {
            amount = Long.parseLong(args[1]);
        } catch (NumberFormatException exception) {
            return invalidAmount(sender);
        }
        if (amount <= 0L) return invalidAmount(sender);

        EconomyOperationResult result = economy.transfer(
                player.getUniqueId(),
                target.getUniqueId(),
                amount,
                "PLAYER_PAY",
                ActionContext.actorTarget(ActionSource.COMMAND, player.getUniqueId(), target.getUniqueId())
        );

        if (result == EconomyOperationResult.INSUFFICIENT_FUNDS) {
            return messageOrDefault(sender, "economy.pay.messages.insufficient-funds",
                    "<dark_gray>[<green>SMP</green>]</dark_gray> <red>Du besitzt nicht genügend Coins.</red>");
        }
        if (result == EconomyOperationResult.INVALID_AMOUNT) return invalidAmount(sender);
        if (result == EconomyOperationResult.SAME_ACCOUNT) {
            return messageOrDefault(sender, "economy.pay.messages.self",
                    "<dark_gray>[<green>SMP</green>]</dark_gray> <red>Du kannst dir nicht selbst Coins senden.</red>");
        }
        if (result != EconomyOperationResult.SUCCESS) {
            return messageOrDefault(sender, "economy.pay.messages.storage-error",
                    "<dark_gray>[<green>SMP</green>]</dark_gray> <red>Die Überweisung konnte nicht gespeichert werden.</red>");
        }

        String formattedAmount = COIN_FORMAT.format(amount);
        messageOrDefault(player, "economy.pay.messages.sent",
                "<dark_gray>[<green>SMP</green>]</dark_gray> <gray>Du hast <yellow>%player%</yellow> <green>%amount% Coins</green> gesendet.</gray>",
                "%player%", target.getName(), "%amount%", formattedAmount);
        messageOrDefault(target, "economy.pay.messages.received",
                "<dark_gray>[<green>SMP</green>]</dark_gray> <gray>Du hast von <yellow>%player%</yellow> <green>%amount% Coins</green> erhalten.</gray>",
                "%player%", player.getName(), "%amount%", formattedAmount);
        plugin.sounds().play(player, "economy.coins-sent");
        plugin.sounds().play(target, "economy.coins-received");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || !sender.hasPermission(permission())) return List.of();
        if (args.length != 1) return List.of();
        String input = args[0].toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream()
                .filter(target -> !target.getUniqueId().equals(player.getUniqueId()))
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(input))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private boolean usage(CommandSender sender) {
        return messageOrDefault(sender, "economy.pay.messages.usage",
                "<dark_gray>[<green>SMP</green>]</dark_gray> <gray>Benutzung: <yellow>/pay <Spieler> <Betrag></yellow></gray>");
    }

    private boolean invalidAmount(CommandSender sender) {
        return messageOrDefault(sender, "economy.pay.messages.invalid-amount",
                "<dark_gray>[<green>SMP</green>]</dark_gray> <red>Der Betrag muss eine positive ganze Zahl sein.</red>");
    }
}
