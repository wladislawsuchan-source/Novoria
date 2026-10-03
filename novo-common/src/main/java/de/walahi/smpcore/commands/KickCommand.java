package de.walahi.smpcore.commands;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import de.walahi.smpcore.moderation.KickService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class KickCommand extends BaseCommand {
    private final KickService kickService;

    public KickCommand(SMPCorePlugin plugin, KickService kickService) {
        super(plugin);
        this.kickService = kickService;
    }

    @Override
    protected String permission() {
        return "smpcore.command.kick";
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length < 1) return messageOrDefault(sender, "moderation.kick.messages.usage", "<dark_gray>[<red>Team</red>]</dark_gray> <gray>Benutzung: <yellow>/kick <Spieler> [Grund]</yellow></gray>");

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            return messageOrDefault(sender, "moderation.kick.messages.not-online", "<dark_gray>[<red>Team</red>]</dark_gray> <red>Der Spieler <yellow>%player%</yellow> ist nicht online.</red>", "%player%", args[0]);
        }

        String reason = args.length > 1
                ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length))
                : plugin.configs().main().getString("moderation.kick.default-reason", "Kein Grund angegeben");

        return switch (kickService.kick(sender, target, reason)) {
            case SUCCESS -> messageOrDefault(sender, "moderation.kick.messages.success", "<dark_gray>[<red>Team</red>]</dark_gray> <green><yellow>%player%</yellow> wurde erfolgreich gekickt.</green>", "%player%", target.getName(), "%reason%", reason);
            case SELF -> messageOrDefault(sender, "moderation.kick.messages.self", "<dark_gray>[<red>Team</red>]</dark_gray> <red>Du kannst dich nicht selbst kicken.</red>");
            case HIERARCHY -> messageOrDefault(sender, "moderation.kick.messages.hierarchy", "<dark_gray>[<red>Team</red>]</dark_gray> <red>Du kannst <yellow>%player%</yellow> aufgrund der Ranghierarchie nicht kicken.</red>", "%player%", target.getName());
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
