package de.walahi.smpcore.commands;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import de.walahi.smpcore.moderation.MuteService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

public final class UnmuteCommand extends BaseCommand {
    private final MuteService muteService;

    public UnmuteCommand(SMPCorePlugin plugin, MuteService muteService) {
        super(plugin);
        this.muteService = muteService;
    }

    @Override protected String permission() { return "smpcore.command.unmute"; }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length != 1) return messageOrDefault(sender, "moderation.unmute.messages.usage",
                "<dark_gray>[<red>Team</red>]</dark_gray> <gray>Benutzung: <yellow>/unmute <Spieler></yellow></gray>");

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            return messageOrDefault(sender, "moderation.unmute.messages.not-found",
                    "<dark_gray>[<red>Team</red>]</dark_gray> <red>Der Spieler <yellow>%player%</yellow> wurde nicht gefunden.</red>",
                    "%player%", args[0]);
        }
        String targetName = target.getName() == null ? args[0] : target.getName();
        return switch (muteService.unmute(sender, target.getUniqueId(), targetName)) {
            case SUCCESS -> messageOrDefault(sender, "moderation.unmute.messages.success",
                    "<dark_gray>[<red>Team</red>]</dark_gray> <green><yellow>%player%</yellow> wurde erfolgreich entmutet.</green>",
                    "%player%", targetName);
            case NOT_MUTED -> messageOrDefault(sender, "moderation.unmute.messages.not-muted",
                    "<dark_gray>[<red>Team</red>]</dark_gray> <red><yellow>%player%</yellow> ist nicht gemutet.</red>",
                    "%player%", targetName);
            case STORAGE_ERROR -> messageOrDefault(sender, "moderation.unmute.messages.storage-error",
                    "<dark_gray>[<red>Team</red>]</dark_gray> <red>Der Mute konnte wegen eines Datenbankfehlers nicht aufgehoben werden.</red>");
            default -> true;
        };
    }
}
