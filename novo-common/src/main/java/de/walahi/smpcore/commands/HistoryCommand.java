package de.walahi.smpcore.commands;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import de.walahi.smpcore.punishments.Punishment;
import de.walahi.smpcore.punishments.PunishmentFormatter;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import de.walahi.smpcore.messages.MessageChannel;
import de.walahi.smpcore.services.PunishmentService;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class HistoryCommand extends BaseCommand {
    private static final int PAGE_SIZE = 5;
    private final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());
    private final PunishmentService punishmentService;

    public HistoryCommand(SMPCorePlugin plugin, PunishmentService punishmentService) {
        super(plugin);
        this.punishmentService = punishmentService;
    }

    @Override protected String permission() { return "smpcore.command.history"; }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length < 1 || args.length > 2) {
            return messageOrDefault(sender, "moderation.history.messages.usage", "<dark_gray>[<red>Team</red>]</dark_gray> <gray>Benutzung: <yellow>/history <Spieler> [Seite]</yellow></gray>");
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        if (!target.isOnline() && !target.hasPlayedBefore()) {
            return messageOrDefault(sender, "moderation.history.messages.not-found", "<dark_gray>[<red>Team</red>]</dark_gray> <red>Der Spieler <yellow>%player%</yellow> wurde nicht gefunden.</red>", "%player%", args[0]);
        }

        int page = 1;
        if (args.length == 2) {
            try { page = Integer.parseInt(args[1]); }
            catch (NumberFormatException ignored) { page = -1; }
            if (page < 1) return messageOrDefault(sender, "moderation.history.messages.invalid-page", "<dark_gray>[<red>Team</red>]</dark_gray> <red>Diese Seite ist ungültig.</red>");
        }

        List<Punishment> history = punishmentService.history(target.getUniqueId());
        String targetName = target.getName() == null ? args[0] : target.getName();
        if (history.isEmpty()) {
            return messageOrDefault(sender, "moderation.history.messages.empty", "<dark_gray>[<red>Team</red>]</dark_gray> <gray>Für <yellow>%player%</yellow> wurden keine Einträge gefunden.</gray>", "%player%", targetName);
        }

        int pages = (history.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        if (page > pages) return messageOrDefault(sender, "moderation.history.messages.invalid-page", "<dark_gray>[<red>Team</red>]</dark_gray> <red>Diese Seite ist ungültig.</red>");

        plugin.messages().team(sender, "<gray>Verlauf von <yellow>%player%</yellow> <dark_gray>(%page%/%pages%)</dark_gray></gray>", "%player%", escape(targetName), "%page%", Integer.toString(page), "%pages%", Integer.toString(pages));
        int from = (page - 1) * PAGE_SIZE;
        int to = Math.min(history.size(), from + PAGE_SIZE);
        Instant now = Instant.now();
        for (int index = from; index < to; index++) {
            Punishment punishment = history.get(index);
            String type = switch (punishment.type()) {
                case BAN -> punishment.permanent() ? "Ban" : "Tempban";
                case MUTE -> punishment.permanent() ? "Mute" : "Tempmute";
                case WARN -> "Warnung";
            };
            String status = punishment.currentlyActive(now) ? "<green>aktiv</green>" : "<gray>inaktiv</gray>";
            String duration = punishment.permanent() ? "Permanent" : PunishmentFormatter.duration(java.time.Duration.between(punishment.createdAt(), punishment.expiresAt()));
            String moderator = punishment.staffName() == null ? "Konsole" : punishment.staffName();
            String line = "<dark_gray>#" + punishment.id() + "</dark_gray> <red>" + type + "</red> <dark_gray>•</dark_gray> " + status
                    + "<newline><gray>Grund: <white>" + escape(punishment.reason()) + "</white></gray>"
                    + "<newline><gray>Von: <white>" + escape(moderator) + "</white> <dark_gray>•</dark_gray> " + escape(dateFormat.format(punishment.createdAt())) + "</gray>"
                    + "<newline><gray>Dauer: <white>" + escape(duration) + "</white></gray>";
            plugin.messages().plain(sender, line);
            if (index + 1 < to) plugin.messages().plain(sender, "<dark_gray>────────────────────</dark_gray>");
        }
        return true;
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
        return List.of();
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("<", "\\<").replace(">", "\\>");
    }
}
