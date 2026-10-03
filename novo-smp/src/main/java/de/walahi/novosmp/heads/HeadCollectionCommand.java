package de.walahi.novosmp.heads;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class HeadCollectionCommand extends BaseCommand {
    private final HeadCollectionManager heads;

    public HeadCollectionCommand(SMPCorePlugin plugin, HeadCollectionManager heads) {
        super(plugin);
        this.heads = heads;
    }

    @Override protected String permission() { return "smpcore.heads.use"; }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendRichMessage("<red>Dieser Befehl ist nur für Spieler.</red>");
            return true;
        }
        heads.open(player);
        return true;
    }
}
