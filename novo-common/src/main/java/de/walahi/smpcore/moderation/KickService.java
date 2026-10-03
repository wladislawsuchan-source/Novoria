package de.walahi.smpcore.moderation;

import de.walahi.smpcore.SMPCorePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class KickService {
    public enum Result { SUCCESS, SELF, HIERARCHY }
    private final SMPCorePlugin plugin;
    private final StaffHierarchy hierarchy;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public KickService(SMPCorePlugin plugin, StaffHierarchy hierarchy) {
        this.plugin = plugin;
        this.hierarchy = hierarchy;
    }

    public Result kick(CommandSender actor, Player target, String reason) {
        if (actor instanceof Player player && player.getUniqueId().equals(target.getUniqueId())) return Result.SELF;
        if (!hierarchy.mayActOn(actor, target)) return Result.HIERARCHY;
        String moderator = actor.getName();
        String template = plugin.configs().main().getString("moderation.kick.screen",
                "<red><bold>Du wurdest vom Server entfernt.</bold></red><newline><newline>" +
                "<gray>Grund:</gray><newline><white>%reason%</white>");
        template = stripLegacyFooter(template);
        Component screen = miniMessage.deserialize(template
                .replace("%reason%", escape(reason)).replace("%moderator%", escape(moderator))
                .replace("%player%", escape(target.getName())));
        plugin.getLogger().info("[Team] " + moderator + " hat " + target.getName() + " netzwerkweit gekickt. Grund: " + reason);
        String networkReason = template
                .replace("%reason%", escape(reason))
                .replace("%moderator%", escape(moderator))
                .replace("%player%", escape(target.getName()));
        boolean sentToProxy = plugin.getNetworkManager().kickFromNetwork(target, networkReason);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (target.isOnline()) target.kick(screen);
        }, sentToProxy ? 10L : 1L);
        return Result.SUCCESS;
    }

    private String stripLegacyFooter(String value) {
        return value.replace("<newline><newline><dark_gray>────────────────────</dark_gray><newline><gray>Hylonia SMP</gray>", "")
                .replace("\n\n<dark_gray>────────────────────</dark_gray>\n<gray>Hylonia SMP</gray>", "");
    }

    private String escape(String value) { return value == null ? "" : value.replace("<", "\\<").replace(">", "\\>"); }
}
