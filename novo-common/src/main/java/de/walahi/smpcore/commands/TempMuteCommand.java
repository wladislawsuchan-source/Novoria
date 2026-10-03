package de.walahi.smpcore.commands;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import de.walahi.smpcore.moderation.MuteService;
import de.walahi.smpcore.punishments.DurationParser;
import de.walahi.smpcore.punishments.PunishmentFormatter;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class TempMuteCommand extends BaseCommand {
    private final MuteService muteService;

    public TempMuteCommand(SMPCorePlugin plugin, MuteService muteService) {
        super(plugin);
        this.muteService = muteService;
    }

    @Override protected String permission() { return "smpcore.command.tempmute"; }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length < 2) return messageOrDefault(sender, "moderation.tempmute.messages.usage",
                "<dark_gray>[<red>Team</red>]</dark_gray> <gray>Benutzung: <yellow>/tempmute <Spieler> <Zeit> [Grund]</yellow></gray>");

        Optional<Duration> parsed = DurationParser.parse(args[1]);
        if (parsed.isEmpty()) return messageOrDefault(sender, "moderation.tempmute.messages.invalid-duration",
                "<dark_gray>[<red>Team</red>]</dark_gray> <red>Ungültige Zeit. Beispiele: <yellow>30m, 2h, 7d, 3w, 6mo, 1y</yellow></red>");

        Player onlineTarget = Bukkit.getPlayerExact(args[0]);
        OfflinePlayer target = onlineTarget != null ? onlineTarget : Bukkit.getOfflinePlayer(args[0]);
        if (onlineTarget == null && !target.hasPlayedBefore()) {
            return messageOrDefault(sender, "moderation.tempmute.messages.not-found",
                    "<dark_gray>[<red>Team</red>]</dark_gray> <red>Der Spieler <yellow>%player%</yellow> wurde nicht gefunden.</red>",
                    "%player%", args[0]);
        }

        String targetName = target.getName() == null ? args[0] : target.getName();
        String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                : plugin.configs().main().getString("moderation.tempmute.default-reason", "Kein Grund angegeben");
        Duration duration = parsed.get();

        return switch (muteService.tempMute(sender, target.getUniqueId(), targetName, onlineTarget, reason, duration)) {
            case SUCCESS -> messageOrDefault(sender, "moderation.tempmute.messages.success",
                    "<dark_gray>[<red>Team</red>]</dark_gray> <green><yellow>%player%</yellow> wurde für <yellow>%duration%</yellow> gemutet.</green>",
                    "%player%", targetName, "%duration%", PunishmentFormatter.duration(duration), "%reason%", reason);
            case ALREADY_MUTED -> messageOrDefault(sender, "moderation.tempmute.messages.already-muted",
                    "<dark_gray>[<red>Team</red>]</dark_gray> <red><yellow>%player%</yellow> ist bereits gemutet.</red>", "%player%", targetName);
            case SELF -> messageOrDefault(sender, "moderation.tempmute.messages.self",
                    "<dark_gray>[<red>Team</red>]</dark_gray> <red>Du kannst dich nicht selbst muten.</red>");
            case HIERARCHY -> messageOrDefault(sender, "moderation.tempmute.messages.hierarchy",
                    "<dark_gray>[<red>Team</red>]</dark_gray> <red>Du kannst <yellow>%player%</yellow> aufgrund der Ranghierarchie nicht muten.</red>", "%player%", targetName);
            case STORAGE_ERROR -> messageOrDefault(sender, "moderation.tempmute.messages.storage-error",
                    "<dark_gray>[<red>Team</red>]</dark_gray> <red>Der Tempmute konnte wegen eines Datenbankfehlers nicht gespeichert werden.</red>");
            case NOT_MUTED -> true;
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(permission())) return List.of();
        if (args.length == 1) {
            String input = args[0].toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (sender instanceof Player viewer && !viewer.canSee(player)) continue;
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(input)) names.add(player.getName());
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            return names;
        }
        if (args.length == 2) return List.of("30m", "2h", "1d", "7d", "3w", "6mo", "1y").stream()
                .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }
}
