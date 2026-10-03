package de.walahi.novosmp.spawn;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** NovoSMP-owned /spawn command. */
public final class SpawnCommand extends BaseCommand {
    public SpawnCommand(SMPCorePlugin plugin) {
        super(plugin);
    }

    @Override
    protected String permission() {
        return "smpcore.spawn";
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Dieser Befehl kann nur von Spielern verwendet werden.");
            return true;
        }
        if (args.length != 0) {
            player.sendMessage("§eBenutzung: /spawn");
            return true;
        }
        plugin.teleportToSmpSpawn(player);
        return true;
    }
}
