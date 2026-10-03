package de.walahi.novosmp.commands.gameplay;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

public final class HomeCommand extends BaseCommand {
    public enum Mode { SET, TELEPORT, DELETE, LIST }
    private final Mode mode;
    public HomeCommand(SMPCorePlugin plugin, Mode mode) { super(plugin); this.mode = mode; }
    @Override protected String permission() {
        return switch (mode) {
            case SET -> "smpcore.home.set";
            case TELEPORT -> "smpcore.home.use";
            case DELETE -> "smpcore.home.delete";
            case LIST -> "smpcore.home.list";
        };
    }
    @Override protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) return messageOrDefault(sender, "messages.player-only", "<red>Nur Spieler können diesen Befehl verwenden.</red>");
        switch (mode) {
            case SET -> plugin.setHome(player, args);
            case TELEPORT -> plugin.teleportHome(player, args);
            case DELETE -> plugin.deleteHome(player, args);
            case LIST -> plugin.listHomes(player);
        }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || args.length != 1 || (mode != Mode.TELEPORT && mode != Mode.DELETE)) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return plugin.homeAccess().getDisplayNames(player).stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }
}
