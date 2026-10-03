package de.walahi.smpcore.commands;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class GamemodeCommand extends BaseCommand {
    private static final List<String> MODES = List.of("survival", "creative", "adventure", "spectator");

    public GamemodeCommand(SMPCorePlugin plugin) {
        super(plugin);
    }

    @Override
    protected String permission() {
        return "smpcore.command.gamemode";
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length < 1 || args.length > 2) {
            return message(sender, "staff.messages.gamemode-usage");
        }

        GameMode mode = parseMode(args[0]);
        if (mode == null) {
            return message(sender, "staff.messages.gamemode-invalid");
        }

        Player target;
        if (args.length == 2) {
            if (!sender.hasPermission("smpcore.command.gamemode.others")) {
                return message(sender, "staff.messages.no-permission");
            }
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                return message(sender, "staff.messages.player-not-found", "%player%", args[1]);
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            return message(sender, "staff.messages.gamemode-usage");
        }

        target.setGameMode(mode);
        message(target, "staff.messages.gamemode-changed", "%mode%", displayMode(mode), "%player%", target.getName());
        if (sender != target) {
            message(sender, "staff.messages.gamemode-changed-other", "%mode%", displayMode(mode), "%player%", target.getName());
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(permission())) return List.of();
        if (args.length == 1) return filter(MODES, args[0]);
        if (args.length == 2 && sender.hasPermission("smpcore.command.gamemode.others")) {
            List<String> players = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) players.add(player.getName());
            return filter(players, args[1]);
        }
        return List.of();
    }

    private GameMode parseMode(String input) {
        return switch (input.toLowerCase(Locale.ROOT)) {
            case "survival" -> GameMode.SURVIVAL;
            case "creative" -> GameMode.CREATIVE;
            case "adventure" -> GameMode.ADVENTURE;
            case "spectator" -> GameMode.SPECTATOR;
            default -> null;
        };
    }

    private String displayMode(GameMode mode) {
        return switch (mode) {
            case SURVIVAL -> "Survival";
            case CREATIVE -> "Creative";
            case ADVENTURE -> "Adventure";
            case SPECTATOR -> "Spectator";
        };
    }

    private List<String> filter(List<String> values, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower))
                .sorted()
                .toList();
    }
}
