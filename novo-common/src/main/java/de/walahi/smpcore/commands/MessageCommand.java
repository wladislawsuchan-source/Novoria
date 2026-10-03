package de.walahi.smpcore.commands;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class MessageCommand extends BaseCommand {
    private final PrivateMessageManager manager;

    public MessageCommand(SMPCorePlugin plugin, PrivateMessageManager manager) {
        super(plugin);
        this.manager = manager;
    }

    @Override
    protected String permission() {
        return "smpcore.command.msg";
    }

    @Override
    protected String noPermissionMessagePath() {
        return "chat.messages.no-permission";
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) return message(sender, "chat.messages.player-only");
        if (args.length < 2) return message(sender, "chat.messages.msg-usage");

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !plugin.isSamePlayerArea(player, target)) {
            return message(sender, "chat.messages.player-not-found", "%player%", args[0]);
        }
        if (target.equals(player)) return message(sender, "chat.messages.message-yourself");

        manager.send(player, target, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(permission()) || args.length != 1) return List.of();
        String lower = args[0].toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        if (!(sender instanceof Player source)) return List.of();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.equals(source)
                    && plugin.isSamePlayerArea(source, player)
                    && !plugin.shouldAnonymizeIdentity(source, player)
                    && player.getName().toLowerCase(Locale.ROOT).startsWith(lower)) {
                names.add(player.getName());
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }
}
