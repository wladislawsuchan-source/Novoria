package de.walahi.smpcore.commands;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import de.walahi.smpcore.moderation.MuteService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class MuteCommand extends BaseCommand {
    private final MuteService muteService;

    public MuteCommand(SMPCorePlugin plugin, MuteService muteService) {
        super(plugin);
        this.muteService = muteService;
    }

    @Override protected String permission() { return "smpcore.command.mute"; }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length < 1) return messageOrDefault(sender, "moderation.mute.messages.usage",
                "<dark_gray>[<red>Team</red>]</dark_gray> <gray>Benutzung: <yellow>/mute <Spieler> [Grund]</yellow></gray>");

        Player onlineTarget = Bukkit.getPlayerExact(args[0]);
        OfflinePlayer target = onlineTarget != null ? onlineTarget : Bukkit.getOfflinePlayer(args[0]);
        if (onlineTarget == null && !target.hasPlayedBefore()) {
            return messageOrDefault(sender, "moderation.mute.messages.not-found",
                    "<dark_gray>[<red>Team</red>]</dark_gray> <red>Der Spieler <yellow>%player%</yellow> wurde nicht gefunden.</red>",
                    "%player%", args[0]);
        }

        String targetName = target.getName() == null ? args[0] : target.getName();
        String reason = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                : plugin.configs().main().getString("moderation.mute.default-reason", "Kein Grund angegeben");

        return switch (muteService.mute(sender, target.getUniqueId(), targetName, onlineTarget, reason)) {
            case SUCCESS -> messageOrDefault(sender, "moderation.mute.messages.success",
                    "<dark_gray>[<red>Team</red>]</dark_gray> <green><yellow>%player%</yellow> wurde permanent gemutet.</green>",
                    "%player%", targetName, "%reason%", reason);
            case ALREADY_MUTED -> messageOrDefault(sender, "moderation.mute.messages.already-muted",
                    "<dark_gray>[<red>Team</red>]</dark_gray> <red><yellow>%player%</yellow> ist bereits gemutet.</red>", "%player%", targetName);
            case SELF -> messageOrDefault(sender, "moderation.mute.messages.self",
                    "<dark_gray>[<red>Team</red>]</dark_gray> <red>Du kannst dich nicht selbst muten.</red>");
            case HIERARCHY -> messageOrDefault(sender, "moderation.mute.messages.hierarchy",
                    "<dark_gray>[<red>Team</red>]</dark_gray> <red>Du kannst <yellow>%player%</yellow> aufgrund der Ranghierarchie nicht muten.</red>", "%player%", targetName);
            case STORAGE_ERROR -> messageOrDefault(sender, "moderation.mute.messages.storage-error",
                    "<dark_gray>[<red>Team</red>]</dark_gray> <red>Der Mute konnte wegen eines Datenbankfehlers nicht gespeichert werden.</red>");
            case NOT_MUTED -> true;
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(permission()) || args.length != 1) return List.of();
        String input = args[0].toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (sender instanceof Player viewer && !viewer.canSee(player)) continue;
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(input)) names.add(player.getName());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }
}
