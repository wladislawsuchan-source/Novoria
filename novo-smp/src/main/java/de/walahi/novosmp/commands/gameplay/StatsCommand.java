package de.walahi.novosmp.commands.gameplay;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public final class StatsCommand extends BaseCommand {
    private final boolean leaderboards;
    public StatsCommand(SMPCorePlugin plugin, boolean leaderboards) { super(plugin); this.leaderboards = leaderboards; }
    @Override protected String permission() { return leaderboards ? "smpcore.leaderboards" : "smpcore.stats"; }
    @Override protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) return messageOrDefault(sender, "messages.player-only", "<red>Nur Spieler können diesen Befehl verwenden.</red>");
        if (leaderboards) {
            plugin.statsAccess().openLeaderboards(player);
            return true;
        }
        if (args.length == 0) {
            plugin.statsAccess().openStats(player);
        } else if (args.length == 1) {
            if (!plugin.statsAccess().openStats(player, args[0])) player.sendMessage("§cDer Spieler §f" + args[0] + " §cwurde nicht gefunden.");
        } else {
            player.sendMessage("§eBenutzung: /stats [Spieler]");
        }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (leaderboards || args.length != 1) return List.of();
        return plugin.statsAccess().registeredPlayerNames(args[0]);
    }
}
