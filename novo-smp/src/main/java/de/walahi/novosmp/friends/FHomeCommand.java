package de.walahi.novosmp.friends;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import de.walahi.smpcore.friends.FriendEntry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class FHomeCommand extends BaseCommand {
    private final FriendManager manager;

    public FHomeCommand(NovoSMPPlugin plugin, FriendManager manager) {
        super(plugin);
        this.manager = manager;
    }

    @Override
    protected String permission() { return FriendPermissions.HOME; }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length != 2) {
            manager.send(player, "messages.usage-fhome", "<red>Verwendung: /fhome \\<Spieler\\> \\<Home\\></red>");
            return true;
        }

        UUID owner = manager.resolvePlayer(args[0]);
        if (owner == null || !manager.areFriends(player.getUniqueId(), owner)) {
            manager.send(player, "messages.not-your-friend", "<red>Dieser Spieler ist nicht dein Freund.</red>");
            return true;
        }
        if (!manager.repository().isHomeAllowed(owner, player.getUniqueId(), args[1])) {
            manager.send(player, "messages.home-not-shared", "<red>Dieses Home wurde dir nicht freigegeben.</red>");
            return true;
        }

        var location = manager.loadHome(owner, args[1]);
        if (location == null) {
            manager.repository().deleteHomePermission(owner, args[1]);
            manager.send(player, "messages.friend-home-missing",
                    "<red>Dieses Home existiert nicht mehr oder seine Welt ist nicht geladen.</red>");
            return true;
        }

        plugin.teleportFriendHome(player, location, args[0], args[1]);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || args.length == 0) return List.of();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            return manager.repository().list(player.getUniqueId()).stream()
                    .map(FriendEntry::name)
                    .filter(name -> name != null && name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }
        if (args.length == 2) {
            UUID owner = manager.resolvePlayer(args[0]);
            if (owner == null || !manager.areFriends(player.getUniqueId(), owner)) return List.of();
            return manager.allowedHomeNames(owner, player.getUniqueId()).stream()
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
