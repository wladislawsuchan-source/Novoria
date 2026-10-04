package de.walahi.novosmp.quests;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class QuestCommand extends BaseCommand {
    private final QuestMenu menu;
    public QuestCommand(SMPCorePlugin plugin, QuestMenu menu) { super(plugin); this.menu = menu; }
    @Override protected String permission() { return "smpcore.quests.use"; }
    @Override protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player))
            return messageOrDefault(sender, "messages.player-only", "<red>Nur Spieler können /quests verwenden.</red>");
        menu.open(player);
        return true;
    }
}
