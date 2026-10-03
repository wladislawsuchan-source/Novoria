package de.walahi.novosmp.commands;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Portable crafting table for Premium and Premium+. */
public final class CraftCommand extends BaseCommand {
    private final NovoSMPPlugin smp;

    public CraftCommand(NovoSMPPlugin plugin) {
        super(plugin);
        this.smp = plugin;
    }

    @Override
    protected String permission() {
        return null;
    }

    @Override
    @SuppressWarnings("deprecation") // Paper 1.21.11 keeps this stable compatibility entry point.
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendRichMessage("<red>Dieser Befehl ist nur für Spieler.</red>");
            return true;
        }
        if (args.length != 0) {
            player.sendRichMessage("<gray>Benutzung: <yellow>/craft</yellow></gray>");
            return true;
        }
        if (!plugin.getRankManager().hasPremium(player)) {
            player.sendRichMessage("<red>/craft ist ein Vorteil für Premium und Premium+.</red>");
            return true;
        }
        if (smp.isPlayerInDuel(player.getUniqueId())) {
            player.sendRichMessage("<red>Während eines Duells kannst du /craft nicht verwenden.</red>");
            return true;
        }
        player.openWorkbench(null, true);
        return true;
    }
}
