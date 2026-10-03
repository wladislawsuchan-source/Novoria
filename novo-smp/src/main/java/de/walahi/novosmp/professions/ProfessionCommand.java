package de.walahi.novosmp.professions;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class ProfessionCommand extends BaseCommand {
    private final ProfessionManager manager;

    public ProfessionCommand(SMPCorePlugin plugin, ProfessionManager manager) {
        super(plugin);
        this.manager = manager;
    }

    @Override
    protected String permission() {
        return "smpcore.professions.use";
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            return plugin.messages().sendConfiguredAuto(
                    sender, plugin.configs().professions(), "messages.player-only",
                    "<red>Dieser Befehl ist nur für Spieler.</red>");
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("holzfaeller")) {
            manager.openLumberjack(player);
        } else if (args.length > 0 && (args[0].equalsIgnoreCase("bergarbeiter")
                || args[0].equalsIgnoreCase("miner"))) {
            manager.openMiner(player);
        } else if (args.length > 0 && (args[0].equalsIgnoreCase("jaeger")
                || args[0].equalsIgnoreCase("jäger") || args[0].equalsIgnoreCase("hunter"))) {
            manager.openHunter(player);
        } else if (args.length > 0 && (args[0].equalsIgnoreCase("angler")
                || args[0].equalsIgnoreCase("fischer"))) {
            manager.openAngler(player);
        } else {
            manager.open(player);
        }
        return true;
    }
}
