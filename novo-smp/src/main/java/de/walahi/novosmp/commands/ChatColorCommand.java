package de.walahi.novosmp.commands;

import de.walahi.novosmp.chat.ChatColorService;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class ChatColorCommand extends BaseCommand {
    private final ChatColorService colors;

    public ChatColorCommand(SMPCorePlugin plugin, ChatColorService colors) {
        super(plugin);
        this.colors = colors;
    }

    @Override
    protected String permission() {
        return null;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendRichMessage("<red>Dieser Befehl ist nur für Spieler.</red>");
            return true;
        }
        if (args.length == 0) {
            colors.showOptions(player);
            return true;
        }
        if (args.length == 1) {
            colors.select(player, args[0]);
            return true;
        }
        player.sendRichMessage("<gray>Benutzung: <yellow>/chatfarbe [Code]</yellow></gray>");
        return true;
    }
}
