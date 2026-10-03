package de.walahi.novosmp.commands;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import de.walahi.novosmp.economy.sell.SellManager;
import de.walahi.smpcore.services.EconomyService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Objects;

public final class SellCommand extends BaseCommand {
    private final EconomyService economy;
    private final SellManager sellManager;

    public SellCommand(SMPCorePlugin plugin, EconomyService economy, SellManager sellManager) {
        super(plugin);
        this.economy = Objects.requireNonNull(economy);
        this.sellManager = Objects.requireNonNull(sellManager);
    }

    @Override protected String permission() { return "smpcore.economy.sell"; }
    @Override protected String noPermissionMessagePath() { return "economy.messages.no-permission"; }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            return messageOrDefault(sender, "economy.sell.messages.player-only",
                    "<dark_gray>[<green>SMP</green>]</dark_gray> <red>Dieser Befehl ist nur für Spieler verfügbar.</red>");
        }
        if (!plugin.isSmpServer()) {
            return messageOrDefault(sender, "staff.messages.smp-only",
                    "<dark_gray>[<green>SMP</green>]</dark_gray> <gray>Dieser Befehl ist nur im SMP verfügbar.</gray>");
        }
        if (args.length != 0) {
            return messageOrDefault(sender, "economy.sell.messages.usage",
                    "<dark_gray>[<green>SMP</green>]</dark_gray> <gray>Benutzung: <yellow>/sell</yellow></gray>");
        }
        if (!economy.available()) {
            return messageOrDefault(sender, "economy.messages.unavailable",
                    "<dark_gray>[<green>SMP</green>]</dark_gray> <red>Die Economy ist momentan nicht verfügbar.</red>");
        }
        sellManager.open(player);
        return true;
    }
}
