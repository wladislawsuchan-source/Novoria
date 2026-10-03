package de.walahi.smpcore.commands;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class ReplyCommand extends BaseCommand {
    private final PrivateMessageManager manager;

    public ReplyCommand(SMPCorePlugin plugin, PrivateMessageManager manager) {
        super(plugin);
        this.manager = manager;
    }

    @Override
    protected String permission() {
        return "smpcore.command.reply";
    }

    @Override
    protected String noPermissionMessagePath() {
        return "chat.messages.no-permission";
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) return message(sender, "chat.messages.player-only");
        if (args.length < 1) return message(sender, "chat.messages.reply-usage");

        Player target = manager.lastPartner(player);
        if (target == null) return message(sender, "chat.messages.no-reply-target");
        if (!plugin.isSamePlayerArea(player, target)) {
            return message(sender, "chat.messages.player-not-found", "%player%", manager.visiblePlainName(player, target));
        }
        manager.send(player, target, String.join(" ", args));
        return true;
    }
}
