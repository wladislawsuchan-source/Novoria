package de.walahi.smpcore.commands;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Staff teleport command with player and coordinate destinations. */
public final class TeleportCommand extends BaseCommand {
    public TeleportCommand(SMPCorePlugin plugin) {
        super(plugin);
    }

    @Override
    protected String permission() {
        return "smpcore.command.tp";
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!hasTeleportAccess(sender)) {
            return message(sender, "staff.messages.no-permission");
        }

        if (args.length == 1) {
            if (!(sender instanceof Player player)) return usage(sender);
            Player destination = findPlayer(sender, args[0]);
            if (destination == null) return playerNotFound(sender, args[0]);
            return teleport(sender, player, destination.getLocation(), destination.getName());
        }

        if (args.length == 2) {
            if (!hasTeleportOthersAccess(sender)) {
                return message(sender, "staff.messages.no-permission");
            }
            Player target = findPlayer(sender, args[0]);
            if (target == null) return playerNotFound(sender, args[0]);
            Player destination = findPlayer(sender, args[1]);
            if (destination == null) return playerNotFound(sender, args[1]);
            return teleport(sender, target, destination.getLocation(), destination.getName());
        }

        if (args.length == 3) {
            if (!(sender instanceof Player player)) return usage(sender);
            Location destination = parseLocation(player.getLocation(), args[0], args[1], args[2]);
            if (destination == null) return invalidCoordinates(sender);
            return teleport(sender, player, destination, formatCoordinates(destination));
        }

        if (args.length == 4) {
            World world = Bukkit.getWorld(args[0]);
            if (world != null) {
                if (!(sender instanceof Player player)) return usage(sender);
                Location destination = parseLocation(new Location(world, player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch()), args[1], args[2], args[3]);
                if (destination == null) return invalidCoordinates(sender);
                return teleport(sender, player, destination, world.getName() + " " + formatCoordinates(destination));
            }

            if (!hasTeleportOthersAccess(sender)) {
                return message(sender, "staff.messages.no-permission");
            }
            Player target = findPlayer(sender, args[0]);
            if (target == null) return playerNotFound(sender, args[0]);
            Location destination = parseLocation(target.getLocation(), args[1], args[2], args[3]);
            if (destination == null) return invalidCoordinates(sender);
            return teleport(sender, target, destination, formatCoordinates(destination));
        }

        if (args.length == 5) {
            if (!hasTeleportOthersAccess(sender)) {
                return message(sender, "staff.messages.no-permission");
            }
            Player target = findPlayer(sender, args[0]);
            if (target == null) return playerNotFound(sender, args[0]);
            World world = Bukkit.getWorld(args[1]);
            if (world == null) return message(sender, "staff.messages.tp-invalid-world", "%world%", args[1]);
            Location base = new Location(world, target.getX(), target.getY(), target.getZ(), target.getYaw(), target.getPitch());
            Location destination = parseLocation(base, args[2], args[3], args[4]);
            if (destination == null) return invalidCoordinates(sender);
            return teleport(sender, target, destination, world.getName() + " " + formatCoordinates(destination));
        }

        return usage(sender);
    }

    private boolean teleport(CommandSender sender, Player target, Location destination, String destinationName) {
        target.teleportAsync(destination).thenAccept(success -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!success || !target.isOnline()) {
                message(sender, "staff.messages.tp-failed");
                return;
            }
            target.setFallDistance(0.0F);
            if (sender == target) {
                message(sender, "staff.messages.tp-success", "%destination%", destinationName);
            } else {
                message(sender, "staff.messages.tp-success-other", "%player%", target.getName(), "%destination%", destinationName);
            }
        }));
        return true;
    }

    private Location parseLocation(Location base, String xRaw, String yRaw, String zRaw) {
        try {
            double x = parseCoordinate(base.getX(), xRaw);
            double y = parseCoordinate(base.getY(), yRaw);
            double z = parseCoordinate(base.getZ(), zRaw);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return null;
            if (y < base.getWorld().getMinHeight() || y > base.getWorld().getMaxHeight()) return null;
            return new Location(base.getWorld(), x, y, z, base.getYaw(), base.getPitch());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private double parseCoordinate(double base, String raw) {
        if (!raw.startsWith("~")) return Double.parseDouble(raw);
        if (raw.length() == 1) return base;
        return base + Double.parseDouble(raw.substring(1));
    }

    private String formatCoordinates(Location location) {
        return String.format(Locale.ROOT, "%.2f %.2f %.2f", location.getX(), location.getY(), location.getZ());
    }

    private Player findPlayer(CommandSender sender, String name) {
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) return null;
        if (sender instanceof Player source && !plugin.isSamePlayerArea(source, target)) return null;
        return target;
    }

    private boolean playerNotFound(CommandSender sender, String name) {
        return message(sender, "staff.messages.player-not-found", "%player%", name);
    }

    private boolean invalidCoordinates(CommandSender sender) {
        return message(sender, "staff.messages.tp-invalid-coordinates");
    }

    private boolean usage(CommandSender sender) {
        return message(sender, "staff.messages.tp-usage");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!hasTeleportAccess(sender)) return List.of();
        List<String> players = onlinePlayerNames(sender);
        List<String> worlds = Bukkit.getWorlds().stream().map(World::getName).sorted().toList();
        if (args.length == 1) {
            List<String> values = new ArrayList<>(players);
            values.addAll(worlds);
            return filter(values, args[0]);
        }
        if (args.length == 2 && hasTeleportOthersAccess(sender)) {
            List<String> values = new ArrayList<>(players);
            values.addAll(worlds);
            return filter(values, args[1]);
        }
        return List.of();
    }

    private boolean hasTeleportAccess(CommandSender sender) {
        return !(sender instanceof Player) || sender.hasPermission("smpcore.command.tp");
    }

    private boolean hasTeleportOthersAccess(CommandSender sender) {
        return !(sender instanceof Player) || sender.hasPermission("smpcore.command.tp.others");
    }

    private List<String> onlinePlayerNames(CommandSender sender) {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!(sender instanceof Player source) || plugin.isSamePlayerArea(source, player)) {
                names.add(player.getName());
            }
        }
        return names;
    }

    private List<String> filter(List<String> values, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower))
                .sorted()
                .toList();
    }
}
