package de.walahi.novosmp.commands;

import de.walahi.novosmp.feature.NightVisionManager;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class NightVisionCommand extends BaseCommand {
    private final NightVisionManager nightVision;

    public NightVisionCommand(SMPCorePlugin plugin, NightVisionManager nightVision) {
        super(plugin);
        this.nightVision = nightVision;
    }

    @Override protected String permission() { return "smpcore.nightvision"; }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            return messageOrDefault(sender, "night-vision.messages.player-only",
                    "<red>Dieser Befehl ist nur für Spieler verfügbar.</red>");
        }
        if (args.length != 0) {
            return messageOrDefault(sender, "night-vision.messages.usage",
                    "<gray>Benutzung: <yellow>/nachtsicht</yellow></gray>");
        }
        boolean enabled = nightVision.toggle(player);
        return messageOrDefault(player,
                enabled ? "night-vision.messages.enabled" : "night-vision.messages.disabled",
                enabled ? "<green>Nachtsicht wurde aktiviert.</green>" : "<yellow>Nachtsicht wurde deaktiviert.</yellow>");
    }
}
