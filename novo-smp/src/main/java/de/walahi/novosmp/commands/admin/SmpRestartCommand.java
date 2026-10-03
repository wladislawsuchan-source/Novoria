package de.walahi.novosmp.commands.admin;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;

/**
 * Performs a planned restart of the SMP backend only.
 * Players are first moved to the configured Velocity hub through the existing /hub flow,
 * then Paper is shut down so the external SMP process supervisor can start it again.
 */
public final class SmpRestartCommand extends BaseCommand {
    private boolean restartScheduled;

    public SmpRestartCommand(SMPCorePlugin plugin) {
        super(plugin);
    }

    @Override
    protected String permission() {
        return "smpcore.admin";
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length != 0) {
            sender.sendRichMessage("<dark_gray>[<red>Team</red>]</dark_gray> <gray>Benutzung: <yellow>/smprestart</yellow></gray>");
            return true;
        }

        if (restartScheduled) {
            sender.sendRichMessage("<dark_gray>[<red>Team</red>]</dark_gray> <yellow>Der SMP-Neustart läuft bereits.</yellow>");
            return true;
        }
        restartScheduled = true;

        String fallback = "<dark_gray>[<green>SMP</green>]</dark_gray> <yellow>Der SMP wird neugestartet...</yellow>";
        for (Player player : Bukkit.getOnlinePlayers()) {
            plugin.messages().sendConfiguredAuto(player, "messages.smp-restart", fallback);
        }
        if (!(sender instanceof Player)) {
            plugin.messages().sendConfiguredAuto(sender, "messages.smp-restart", fallback);
        }
        plugin.getLogger().warning("SMP-Neustart durch " + sender.getName() + " angefordert.");

        // Give the announcement one second to reach clients, then reuse the existing /hub path.
        // That path also saves the last SMP location before Velocity transfers the player.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) {
                if (player.isOnline()) {
                    player.performCommand("hub");
                }
            }
        }, 20L);

        // Leave two more seconds for the Velocity transfers before stopping this backend only.
        Bukkit.getScheduler().runTaskLater(plugin, Bukkit::shutdown, 60L);
        return true;
    }
}
