package de.walahi.novosmp.commands.gameplay;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public final class TpaCommand extends BaseCommand {
    public enum Mode { REQUEST, ACCEPT, DENY, CANCEL }
    private final Mode mode;
    public TpaCommand(SMPCorePlugin plugin, Mode mode) { super(plugin); this.mode = mode; }
    @Override protected String permission() {
        return switch (mode) {
            case REQUEST, CANCEL -> "smpcore.tpa.use";
            case ACCEPT -> "smpcore.tpa.accept";
            case DENY -> "smpcore.tpa.deny";
        };
    }
    @Override protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) return messageOrDefault(sender, "messages.player-only", "<red>Nur Spieler können diesen Befehl verwenden.</red>");
        switch (mode) {
            case REQUEST -> plugin.tpaAccess().request(player, args);
            case ACCEPT -> plugin.tpaAccess().accept(player, args);
            case DENY -> plugin.tpaAccess().deny(player, args);
            case CANCEL -> plugin.tpaAccess().cancel(player);
        }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || args.length != 1 || mode == Mode.CANCEL) return List.of();
        return plugin.tpaAccess().tabComplete(player, command.getName().toLowerCase(), args[0]);
    }
}
