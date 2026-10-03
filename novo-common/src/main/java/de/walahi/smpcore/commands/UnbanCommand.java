package de.walahi.smpcore.commands;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import de.walahi.smpcore.moderation.BanService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

public final class UnbanCommand extends BaseCommand {
    private final BanService banService;

    public UnbanCommand(SMPCorePlugin plugin, BanService banService) {
        super(plugin);
        this.banService = banService;
    }

    @Override protected String permission() { return "smpcore.command.unban"; }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length != 1) return messageOrDefault(sender, "moderation.unban.messages.usage",
                "<dark_gray>[<red>Team</red>]</dark_gray> <gray>Benutzung: <yellow>/unban <Spieler></yellow></gray>");

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            return messageOrDefault(sender, "moderation.unban.messages.not-found",
                    "<dark_gray>[<red>Team</red>]</dark_gray> <red>Der Spieler <yellow>%player%</yellow> wurde nicht gefunden.</red>",
                    "%player%", args[0]);
        }
        String targetName = target.getName() == null ? args[0] : target.getName();
        return switch (banService.unban(sender, target.getUniqueId(), targetName)) {
            case SUCCESS -> messageOrDefault(sender, "moderation.unban.messages.success",
                    "<dark_gray>[<red>Team</red>]</dark_gray> <green><yellow>%player%</yellow> wurde erfolgreich entbannt.</green>",
                    "%player%", targetName);
            case NOT_BANNED -> messageOrDefault(sender, "moderation.unban.messages.not-banned",
                    "<dark_gray>[<red>Team</red>]</dark_gray> <red><yellow>%player%</yellow> ist nicht gebannt.</red>",
                    "%player%", targetName);
            case STORAGE_ERROR -> messageOrDefault(sender, "moderation.unban.messages.storage-error",
                    "<dark_gray>[<red>Team</red>]</dark_gray> <red>Der Ban konnte wegen eines Datenbankfehlers nicht aufgehoben werden.</red>");
            default -> true;
        };
    }
}
