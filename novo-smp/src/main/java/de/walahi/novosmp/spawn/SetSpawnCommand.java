package de.walahi.novosmp.spawn;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** NovoSMP-owned /setspawn command. */
public final class SetSpawnCommand extends BaseCommand {
    public SetSpawnCommand(SMPCorePlugin plugin) {
        super(plugin);
    }

    @Override
    protected String permission() {
        return "smpcore.admin";
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Dieser Befehl kann nur von Spielern verwendet werden.");
            return true;
        }
        if (args.length != 0) {
            player.sendMessage("§eBenutzung: /setspawn");
            return true;
        }
        plugin.setSmpSpawn(player.getLocation());
        plugin.messages().sendConfiguredAuto(player, plugin.configs().messages(), "messages.location-set-smp", "<green>SMP-Spawn gesetzt.</green>");
        return true;
    }
}
