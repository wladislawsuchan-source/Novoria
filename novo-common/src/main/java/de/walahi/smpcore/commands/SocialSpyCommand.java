package de.walahi.smpcore.commands;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class SocialSpyCommand extends BaseCommand {
    private final PrivateMessageManager manager;

    public SocialSpyCommand(SMPCorePlugin plugin, PrivateMessageManager manager) {
        super(plugin);
        this.manager = manager;
    }

    @Override
    protected String permission() {
        return "smpcore.command.socialspy";
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) return message(sender, "staff.messages.player-only");
        if (args.length != 0) return message(sender, "chat.messages.socialspy-usage");
        return message(sender, manager.toggleSocialSpy(player)
                ? "chat.messages.socialspy-enabled"
                : "chat.messages.socialspy-disabled");
    }
}
