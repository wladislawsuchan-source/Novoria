package de.walahi.novosmp.commands.gameplay;

import de.walahi.novosmp.back.DeathBackManager;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class BackCommand extends BaseCommand {
    private final DeathBackManager manager;
    public BackCommand(SMPCorePlugin plugin, DeathBackManager manager) { super(plugin); this.manager = manager; }
    @Override protected String permission() { return "smpcore.back"; }
    @Override protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) return messageOrDefault(sender, "messages.player-only", "<red>Nur Spieler können diesen Befehl verwenden.</red>");
        if (args.length != 0) { player.sendRichMessage("<gray>Benutzung: <yellow>/back</yellow></gray>"); return true; }
        manager.useBack(player);
        return true;
    }
}
