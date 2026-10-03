package de.walahi.smpcore.commands;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import de.walahi.smpcore.moderation.BanService;
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

public final class TempBanCommand extends BaseCommand {
    private final BanService banService;

    public TempBanCommand(SMPCorePlugin plugin, BanService banService) {
        super(plugin);
        this.banService = banService;
    }

    @Override protected String permission() { return "smpcore.command.tempban"; }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length < 2) return messageOrDefault(sender, "moderation.tempban.messages.usage",
                "<dark_gray>[<red>Team</red>]</dark_gray> <gray>Benutzung: <yellow>/tempban <Spieler> <Zeit> [Grund]</yellow></gray>");

        Optional<Duration> parsed = DurationParser.parse(args[1]);
        if (parsed.isEmpty()) return messageOrDefault(sender, "moderation.tempban.messages.invalid-duration",
                "<dark_gray>[<red>Team</red>]</dark_gray> <red>Ungültige Zeit. Beispiele: <yellow>30m, 2h, 7d, 3w, 6mo, 1y</yellow></red>");

        Player onlineTarget = Bukkit.getPlayerExact(args[0]);
        OfflinePlayer target = onlineTarget != null ? onlineTarget : Bukkit.getOfflinePlayer(args[0]);
        if (onlineTarget == null && !target.hasPlayedBefore()) {
            return messageOrDefault(sender, "moderation.tempban.messages.not-found",
                    "<dark_gray>[<red>Team</red>]</dark_gray> <red>Der Spieler <yellow>%player%</yellow> wurde nicht gefunden.</red>",
                    "%player%", args[0]);
        }

        String targetName = target.getName() == null ? args[0] : target.getName();
        String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                : plugin.configs().main().getString("moderation.tempban.default-reason", "Kein Grund angegeben");
        Duration duration = parsed.get();

        return switch (banService.tempBan(sender, target.getUniqueId(), targetName, onlineTarget, reason, duration)) {
            case SUCCESS -> messageOrDefault(sender, "moderation.tempban.messages.success",
                    "<dark_gray>[<red>Team</red>]</dark_gray> <green><yellow>%player%</yellow> wurde für <yellow>%duration%</yellow> gebannt.</green>",
                    "%player%", targetName, "%duration%", PunishmentFormatter.duration(duration), "%reason%", reason);
            case ALREADY_BANNED -> messageOrDefault(sender, "moderation.tempban.messages.already-banned",
                    "<dark_gray>[<red>Team</red>]</dark_gray> <red><yellow>%player%</yellow> ist bereits gebannt.</red>", "%player%", targetName);
            case SELF -> messageOrDefault(sender, "moderation.tempban.messages.self",
                    "<dark_gray>[<red>Team</red>]</dark_gray> <red>Du kannst dich nicht selbst bannen.</red>");
            case HIERARCHY -> messageOrDefault(sender, "moderation.tempban.messages.hierarchy",
                    "<dark_gray>[<red>Team</red>]</dark_gray> <red>Du kannst <yellow>%player%</yellow> aufgrund der Ranghierarchie nicht bannen.</red>", "%player%", targetName);
            case STORAGE_ERROR -> messageOrDefault(sender, "moderation.tempban.messages.storage-error",
                    "<dark_gray>[<red>Team</red>]</dark_gray> <red>Der Tempban konnte wegen eines Datenbankfehlers nicht gespeichert werden.</red>");
            case NOT_BANNED -> true;
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
