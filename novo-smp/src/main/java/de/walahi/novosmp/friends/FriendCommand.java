package de.walahi.novosmp.friends;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;

public final class FriendCommand extends BaseCommand {
    private final FriendManager manager;

    public FriendCommand(NovoSMPPlugin plugin, FriendManager manager) {
        super(plugin);
        this.manager = manager;
    }

    @Override
    protected String permission() { return FriendPermissions.USE; }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Nur Spieler.");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            manager.openList(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("requests")) {
            manager.openRequests(player);
            return true;
        }
        if (args.length < 2) {
            manager.send(player, "messages.usage-friend", "<red>/friend \\<add|accept|deny|remove\\> \\<Spieler\\></red>");
            return true;
        }

        String sub = args[0].toLowerCase(java.util.Locale.ROOT);
        UUID other = manager.resolvePlayer(args[1]);
        if (other == null || other.equals(player.getUniqueId())) {
            manager.send(player, "messages.player-not-found", "<red>Spieler nicht gefunden.</red>");
            return true;
        }

        String otherName = manager.friendName(other);
        switch (sub) {
            case "add" -> handleAdd(player, other, otherName);
            case "accept" -> handleAccept(player, other, otherName);
            case "deny" -> {
                if (manager.deny(other, player.getUniqueId())) {
                    manager.send(player, "messages.request-denied", "<gray>Freundesanfrage abgelehnt.</gray>");
                } else {
                    manager.send(player, "messages.no-request", "<red>Keine Anfrage von diesem Spieler.</red>");
                }
            }
            case "remove" -> handleRemove(player, other, otherName);
            default -> manager.send(player, "messages.usage-friend", "<red>/friend \\<add|accept|deny|remove\\> \\<Spieler\\></red>");
        }
        return true;
    }

    private void handleAdd(Player player, UUID other, String otherName) {
        var result = manager.sendRequest(player.getUniqueId(), player.getName(), other, otherName);
        switch (result) {
            case SENT -> {
                manager.send(player, "messages.request-sent", "<green>Freundesanfrage an <player> gesendet.</green>",
                        Map.of("player", otherName));
                Player online = Bukkit.getPlayer(other);
                if (online != null && !manager.isHiddenFromPlayers(online)) manager.notifyRequestsNow(online);
            }
            case AUTO_ACCEPTED -> {
                manager.send(player, "messages.request-auto-accepted",
                        "<green>Ihr seid jetzt Freunde, weil bereits eine Anfrage von <player> offen war.</green>",
                        Map.of("player", otherName));
                Player online = Bukkit.getPlayer(other);
                if (online != null && !manager.isHiddenFromPlayers(online)) {
                    manager.send(online, "messages.request-accepted-other",
                            "<green><player> hat deine Freundesanfrage angenommen.</green>",
                            Map.of("player", player.getName()));
                }
            }
            case ALREADY_FRIENDS -> manager.send(player, "messages.already-friends", "<red>Ihr seid bereits Freunde.</red>");
            case ALREADY_SENT -> manager.send(player, "messages.request-already-sent",
                    "<red>Du hast diesem Spieler bereits eine Freundesanfrage gesendet.</red>");
            case FAILED -> manager.send(player, "messages.request-save-failed",
                    "<red>Die Freundesanfrage konnte nicht gespeichert werden.</red>");
        }
    }

    private void handleAccept(Player player, UUID other, String otherName) {
        if (!manager.repository().hasRequest(other, player.getUniqueId())) {
            manager.send(player, "messages.no-request", "<red>Keine Anfrage von diesem Spieler.</red>");
            return;
        }
        manager.accept(other, otherName, player.getUniqueId(), player.getName());
        manager.send(player, "messages.now-friends", "<green>Ihr seid jetzt Freunde.</green>");
        Player online = Bukkit.getPlayer(other);
        if (online != null) {
            manager.send(online, "messages.request-accepted-other",
                    "<green><player> hat deine Freundesanfrage angenommen.</green>",
                    Map.of("player", player.getName()));
        }
    }

    private void handleRemove(Player player, UUID other, String otherName) {
        if (!manager.areFriends(player.getUniqueId(), other)) {
            manager.send(player, "messages.not-in-list", "<red>Dieser Spieler ist nicht in deiner Freundesliste.</red>");
            return;
        }
        manager.remove(player.getUniqueId(), other);
        manager.send(player, "messages.removed-player", "<red><player> wurde aus deiner Freundesliste entfernt.</red>",
                Map.of("player", otherName));
        Player online = Bukkit.getPlayer(other);
        if (online != null && !manager.isHiddenFromPlayers(online)) {
            manager.send(online, "messages.removed-by-player", "<gray><player> hat dich aus der Freundesliste entfernt.</gray>",
                    Map.of("player", player.getName()));
        }
    }
}
