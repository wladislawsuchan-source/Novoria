package de.walahi.novosmp.commands.admin;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;

public final class ChatClearCommand extends BaseCommand {
    public ChatClearCommand(SMPCorePlugin plugin) { super(plugin); }

    @Override protected String permission() { return "smpcore.admin.chatclear"; }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length != 0) {
            sender.sendRichMessage("<dark_gray>[<red>Team</red>]</dark_gray> <gray>Benutzung: <yellow>/chatclear</yellow></gray>");
            return true;
        }
        int lines = Math.max(100, plugin.configs().main().getInt("chat-clear.lines", 200));
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (int i = 0; i < lines; i++) {
                player.sendMessage(Component.empty());
            }
            player.sendRichMessage("<dark_gray>[<green>SMP</green>]</dark_gray> <gray>Der Chat wurde von <yellow>" + sender.getName() + "</yellow> geleert.</gray>");
        }
        return true;
    }
}
