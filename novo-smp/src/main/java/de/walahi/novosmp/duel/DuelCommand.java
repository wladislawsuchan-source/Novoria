package de.walahi.novosmp.duel;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DuelCommand extends BaseCommand {
    private final DuelManager manager;
    private final DuelMenu menu;

    public DuelCommand(NovoSMPPlugin plugin, DuelManager manager, DuelMenu menu) {
        super(plugin);
        this.manager = manager;
        this.menu = menu;
    }

    @Override protected String permission() { return DuelPermissions.USE; }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            DuelMessages.send(manager.config(), sender, manager.config().message("command.player-only", "<red>Dieser Befehl kann nur von Spielern verwendet werden.</red>"));
            return true;
        }
        if (args.length == 0) {
            menu.openKitEditorList(player);
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("leave")) {
            manager.leave(player);
            return true;
        }
        if (action.equals("open") || action.equals("anzeigen")) {
            DuelRequest request = manager.findRequest(player.getUniqueId(), args.length >= 2 ? args[1] : null);
            manager.showRequest(player, request);
            return true;
        }
        if (action.equals("accept") || action.equals("annehmen")) {
            DuelRequest request = manager.findRequest(player.getUniqueId(), args.length >= 2 ? args[1] : null);
            manager.accept(player, request);
            return true;
        }
        if (action.equals("deny") || action.equals("ablehnen")) {
            DuelRequest request = manager.findRequest(player.getUniqueId(), args.length >= 2 ? args[1] : null);
            manager.deny(player, request);
            return true;
        }
        if (args.length == 1) {
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null || !player.canSee(target)) {
                DuelMessages.send(manager.config(), player, manager.config().message("command.player-offline", "§cDieser Spieler ist nicht online."));
                manager.sounds().play(player, "error");
                return true;
            }
            manager.openDraft(player, target);
            return true;
        }
        DuelMessages.send(manager.config(), player, manager.config().message("command.usage", "§eBenutzung: /duel [Spieler|open|accept|deny|leave]"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) return List.of();
        if (args.length == 1) {
            List<String> values = new ArrayList<>(List.of("open", "accept", "deny", "leave"));
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!online.equals(player) && player.canSee(online)) values.add(online.getName());
            }
            return filter(values, args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("open")
                || args[0].equalsIgnoreCase("accept") || args[0].equalsIgnoreCase("deny"))) {
            return filter(manager.pendingChallengerNames(player.getUniqueId()), args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).distinct().sorted().toList();
    }
}
