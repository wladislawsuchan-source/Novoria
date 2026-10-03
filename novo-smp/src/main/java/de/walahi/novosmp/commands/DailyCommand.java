package de.walahi.novosmp.commands;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import de.walahi.novosmp.daily.DailyManager;
import de.walahi.novosmp.daily.DailyMenu;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class DailyCommand extends BaseCommand {
    private static final List<String> ADMIN_ACTIONS = List.of("reset", "setday", "reload");

    private final DailyMenu menu;
    private final DailyManager manager;

    public DailyCommand(SMPCorePlugin plugin, DailyMenu menu, DailyManager manager) {
        super(plugin);
        this.menu = menu;
        this.manager = manager;
    }

    @Override
    protected String permission() {
        return "smpcore.daily.use";
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                return messageOrDefault(sender, "daily.messages.player-only", "<red>Dieser Befehl ist nur für Spieler.</red>");
            }
            menu.open(player);
            return true;
        }

        if (!sender.hasPermission("smpcore.daily.admin")) {
            return messageOrDefault(sender, "staff.messages.no-permission", "<red>Dafür hast du keine Berechtigung.</red>");
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        try {
            return switch (action) {
                case "reload" -> reload(sender, args);
                case "reset" -> reset(sender, args);
                case "setday" -> setDay(sender, args);
                default -> usage(sender);
            };
        } catch (SQLException exception) {
            plugin.getLogger().severe("Daily-Adminaktion fehlgeschlagen: " + exception.getMessage());
            return messageOrDefault(sender, "daily.admin.storage-error", "<red>Die Daily-Daten konnten nicht gespeichert werden.</red>");
        }
    }

    private boolean reload(CommandSender sender, String[] args) {
        if (args.length != 1) return usage(sender);
        manager.reload();
        return messageOrDefault(sender, "daily.admin.reload-success", "<green>Daily-Konfiguration neu geladen.</green>");
    }

    private boolean reset(CommandSender sender, String[] args) throws SQLException {
        if (args.length != 2) return usage(sender);
        OfflinePlayer target = findKnownPlayer(args[1]);
        if (target == null) return playerNotFound(sender, args[1]);
        manager.reset(target.getUniqueId());
        return messageOrDefault(sender, "daily.admin.reset-success", "<green>Daily-Fortschritt von <yellow>%player%</yellow> wurde zurückgesetzt.</green>",
                "%player%", displayName(target));
    }

    private boolean setDay(CommandSender sender, String[] args) throws SQLException {
        if (args.length != 3) return usage(sender);
        OfflinePlayer target = findKnownPlayer(args[1]);
        if (target == null) return playerNotFound(sender, args[1]);
        int day;
        try {
            day = Integer.parseInt(args[2]);
        } catch (NumberFormatException exception) {
            return invalidDay(sender);
        }
        if (day < 1 || day > 7) return invalidDay(sender);
        manager.setDay(target.getUniqueId(), day);
        return messageOrDefault(sender, "daily.admin.setday-success", "<green>Daily-Tag von <yellow>%player%</yellow> wurde auf <yellow>%day%</yellow> gesetzt.</green>",
                "%player%", displayName(target), "%day%", Integer.toString(day));
    }

    private boolean usage(CommandSender sender) {
        return messageOrDefault(sender, "daily.admin.usage", "<gray>Benutzung: <yellow>/daily</yellow>, <yellow>/daily reset &lt;Spieler&gt;</yellow>, <yellow>/daily setday &lt;Spieler&gt; &lt;1-7&gt;</yellow>, <yellow>/daily reload</yellow></gray>");
    }

    private boolean invalidDay(CommandSender sender) {
        return messageOrDefault(sender, "daily.admin.invalid-day", "<red>Der Daily-Tag muss zwischen 1 und 7 liegen.</red>");
    }

    private boolean playerNotFound(CommandSender sender, String name) {
        return messageOrDefault(sender, "daily.admin.player-not-found", "<red>Der Spieler <yellow>%player%</yellow> wurde nicht gefunden.</red>", "%player%", name);
    }

    private OfflinePlayer findKnownPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        return Arrays.stream(Bukkit.getOfflinePlayers())
                .filter(player -> player.getName() != null && player.getName().equalsIgnoreCase(name))
                .findFirst().orElse(null);
    }

    private static String displayName(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString() : player.getName();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("smpcore.daily.admin")) return List.of();
        if (args.length == 1) return match(args[0], ADMIN_ACTIONS);
        if (args.length == 2 && (args[0].equalsIgnoreCase("reset") || args[0].equalsIgnoreCase("setday"))) {
            return match(args[1], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("setday")) {
            return match(args[2], List.of("1", "2", "3", "4", "5", "6", "7"));
        }
        return List.of();
    }

    private static List<String> match(String input, List<String> values) {
        String lower = input.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String value : values) if (value.toLowerCase(Locale.ROOT).startsWith(lower)) matches.add(value);
        return matches;
    }
}
