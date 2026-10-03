package de.walahi.novosmp.commands;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import de.walahi.smpcore.services.EconomyService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.Objects;

/** Shows only the executing player's own whole-coin balance. */
public final class BalanceCommand extends BaseCommand {
    private static final NumberFormat COIN_FORMAT = NumberFormat.getIntegerInstance(Locale.GERMANY);
    private final EconomyService economy;

    public BalanceCommand(SMPCorePlugin plugin, EconomyService economy) {
        super(plugin);
        this.economy = Objects.requireNonNull(economy, "economy");
        COIN_FORMAT.setGroupingUsed(true);
        COIN_FORMAT.setMaximumFractionDigits(0);
    }

    @Override
    protected String permission() {
        return "smpcore.economy.balance";
    }

    @Override
    protected String noPermissionMessagePath() {
        return "economy.messages.no-permission";
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            return messageOrDefault(sender, "economy.balance.messages.player-only",
                    "<dark_gray>[<green>SMP</green>]</dark_gray> <red>Dieser Befehl ist nur für Spieler verfügbar.</red>");
        }
        if (args.length != 0) {
            return messageOrDefault(sender, "economy.balance.messages.usage",
                    "<dark_gray>[<green>SMP</green>]</dark_gray> <gray>Benutzung: <yellow>/balance</yellow></gray>");
        }
        if (!economy.available()) {
            return messageOrDefault(sender, "economy.messages.unavailable",
                    "<dark_gray>[<green>SMP</green>]</dark_gray> <red>Die Economy ist momentan nicht verfügbar.</red>");
        }

        long balance = economy.balance(player.getUniqueId());
        return messageOrDefault(sender, "economy.balance.messages.result",
                "<dark_gray>[<green>SMP</green>]</dark_gray> <gray>Du besitzt <green>%balance% Coins</green>.</gray>",
                "%balance%", COIN_FORMAT.format(balance));
    }
}
