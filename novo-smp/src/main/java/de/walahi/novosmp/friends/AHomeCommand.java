package de.walahi.novosmp.friends;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class AHomeCommand extends BaseCommand {
    private final FriendManager manager;

    public AHomeCommand(NovoSMPPlugin plugin, FriendManager manager) {
        super(plugin);
        this.manager = manager;
    }

    @Override
    protected String permission() { return FriendPermissions.ADMIN_HOME; }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length == 0) {
            manager.openAdminPlayers(player, 0);
            return true;
        }
        if (args.length > 2) {
            manager.send(player, "messages.usage-ahome", "<red>Verwendung: /ahome [Spieler] [Home]</red>");
            return true;
        }

        UUID owner = manager.resolvePlayer(args[0]);
        if (owner == null) {
            manager.send(player, "messages.player-not-found", "<red>Spieler nicht gefunden.</red>");
            return true;
        }
        String ownerName = manager.friendName(owner);

        if (args.length == 1) {
            manager.openAdminHomes(player, owner, ownerName);
            return true;
        }

        var location = manager.loadHome(owner, args[1]);
        if (location == null) {
            manager.send(player, "messages.admin-home-missing",
                    "<red>Dieses Home existiert nicht oder seine Welt ist nicht geladen.</red>");
            return true;
        }

        player.teleportAsync(location).thenAccept(success -> Bukkit.getScheduler().runTask(plugin, () ->
                manager.send(player, success ? "messages.admin-home-success" : "messages.teleport-failed",
                        success ? "<green>Du wurdest zum Admin-Home teleportiert.</green>"
                                : "<red>Der Teleport ist fehlgeschlagen.</red>")));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0) return List.of();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }
        if (args.length == 2) {
            UUID owner = manager.resolvePlayer(args[0]);
            if (owner == null) return List.of();
            return manager.homeNames(owner).stream()
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
