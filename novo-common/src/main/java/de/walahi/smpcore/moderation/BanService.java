package de.walahi.smpcore.moderation;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.punishments.Punishment;
import de.walahi.smpcore.punishments.PunishmentFormatter;
import de.walahi.smpcore.punishments.PunishmentResult;
import de.walahi.smpcore.punishments.PunishmentType;
import de.walahi.smpcore.services.PunishmentService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class BanService {
    public enum Result { SUCCESS, ALREADY_BANNED, NOT_BANNED, SELF, HIERARCHY, STORAGE_ERROR }
    private final SMPCorePlugin plugin;
    private final StaffHierarchy hierarchy;
    private final PunishmentService punishments;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public BanService(SMPCorePlugin plugin, StaffHierarchy hierarchy, PunishmentService punishments) {
        this.plugin = plugin;
        this.hierarchy = hierarchy;
        this.punishments = punishments;
    }

    public Result ban(CommandSender actor, UUID targetUuid, String targetName, Player onlineTarget, String reason) {
        return createBan(actor, targetUuid, targetName, onlineTarget, reason, null);
    }

    public Result tempBan(CommandSender actor, UUID targetUuid, String targetName, Player onlineTarget, String reason, Duration duration) {
        return createBan(actor, targetUuid, targetName, onlineTarget, reason, duration);
    }

    private Result createBan(CommandSender actor, UUID targetUuid, String targetName, Player onlineTarget, String reason, Duration duration) {
        if (actor instanceof Player actorPlayer && actorPlayer.getUniqueId().equals(targetUuid)) return Result.SELF;
        if (onlineTarget != null && !hierarchy.mayActOn(actor, onlineTarget)) return Result.HIERARCHY;
        Optional<Punishment> activeBan = punishments.active(targetUuid, PunishmentType.BAN);
        if (activeBan.isPresent()) return Result.ALREADY_BANNED;

        UUID staffUuid = actor instanceof Player player ? player.getUniqueId() : null;
        String staffName = actor.getName();
        PunishmentResult result = punishments.punish(targetUuid, targetName, staffUuid, staffName,
                PunishmentType.BAN, reason, duration);
        if (!result.success()) return Result.STORAGE_ERROR;

        plugin.getLogger().info("[Team] " + staffName + " hat " + targetName +
                (duration == null ? " permanent" : " für " + PunishmentFormatter.duration(duration)) +
                " gebannt. Grund: " + reason);
        if (onlineTarget != null && onlineTarget.isOnline()) {
            Component screen = buildBanScreen(result.punishment());
            String networkMessage = buildBanMiniMessage(result.punishment());
            boolean sentToProxy = plugin.getNetworkManager().addNetworkBan(
                    onlineTarget, targetUuid, result.punishment().expiresAt(), networkMessage);

            // Fallback only. A normal backend kick would otherwise send the player to the Hub,
            // therefore the proxy receives and stores BAN_ADD before disconnecting the connection.
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (onlineTarget.isOnline()) onlineTarget.kick(screen);
            }, sentToProxy ? 10L : 1L);
        }
        return Result.SUCCESS;
    }

    public Result unban(CommandSender actor, UUID targetUuid, String targetName) {
        if (punishments.active(targetUuid, PunishmentType.BAN).isEmpty()) return Result.NOT_BANNED;
        UUID staffUuid = actor instanceof Player player ? player.getUniqueId() : null;
        boolean revoked = punishments.revoke(targetUuid, PunishmentType.BAN, staffUuid, actor.getName());
        if (!revoked) return Result.STORAGE_ERROR;
        Player preferredCarrier = actor instanceof Player player ? player : null;
        if (!plugin.getNetworkManager().removeNetworkBan(preferredCarrier, targetUuid)) {
            plugin.getLogger().warning("Der Ban wurde lokal aufgehoben, konnte aber nicht sofort an Velocity gemeldet werden, weil kein Spieler als Plugin-Message-Carrier online war.");
        }
        plugin.getLogger().info("[Team] " + actor.getName() + " hat " + targetName + " entbannt.");
        return Result.SUCCESS;
    }

    public Component buildBanScreen(Punishment punishment) {
        boolean temporary = !punishment.permanent();
        String path = temporary ? "moderation.tempban.screen" : "moderation.ban.screen";
        String fallback = temporary
                ? "<red><bold>Du bist temporär von diesem Server ausgeschlossen.</bold></red><newline><newline>" +
                  "<gray>Grund:</gray><newline><white>%reason%</white><newline><newline>" +
                  "<gray>Gebannt von:</gray><newline><white>%moderator%</white><newline><newline>" +
                  "<gray>Verbleibende Zeit:</gray><newline><white>%remaining%</white>"
                : "<red><bold>Du bist von diesem Server ausgeschlossen.</bold></red><newline><newline>" +
                  "<gray>Grund:</gray><newline><white>%reason%</white><newline><newline>" +
                  "<gray>Gebannt von:</gray><newline><white>%moderator%</white>";
        String template = stripLegacyFooter(plugin.configs().main().getString(path, fallback));
        return miniMessage.deserialize(template
                .replace("%reason%", escape(punishment.reason()))
                .replace("%moderator%", escape(punishment.staffName() == null ? "Konsole" : punishment.staffName()))
                .replace("%player%", escape(punishment.playerName()))
                .replace("%remaining%", escape(PunishmentFormatter.remaining(punishment, Instant.now()))));
    }


    public String buildBanMiniMessage(Punishment punishment) {
        boolean temporary = !punishment.permanent();
        String path = temporary ? "moderation.tempban.screen" : "moderation.ban.screen";
        String fallback = temporary
                ? "<red><bold>Du bist temporär vom Netzwerk ausgeschlossen.</bold></red><newline><newline>" +
                  "<gray>Grund:</gray><newline><white>%reason%</white><newline><newline>" +
                  "<gray>Gebannt von:</gray><newline><white>%moderator%</white><newline><newline>" +
                  "<gray>Verbleibende Zeit:</gray><newline><white>%remaining%</white>"
                : "<red><bold>Du bist vom Netzwerk ausgeschlossen.</bold></red><newline><newline>" +
                  "<gray>Grund:</gray><newline><white>%reason%</white><newline><newline>" +
                  "<gray>Gebannt von:</gray><newline><white>%moderator%</white>";
        String template = stripLegacyFooter(plugin.configs().main().getString(path, fallback));
        return template
                .replace("%reason%", escape(punishment.reason()))
                .replace("%moderator%", escape(punishment.staffName() == null ? "Konsole" : punishment.staffName()))
                .replace("%player%", escape(punishment.playerName()))
                .replace("%remaining%", escape(PunishmentFormatter.remaining(punishment, Instant.now())));
    }

    private String stripLegacyFooter(String value) {
        return value.replace("<newline><newline><dark_gray>────────────────────</dark_gray><newline><gray>Hylonia SMP</gray>", "")
                .replace("\n\n<dark_gray>────────────────────</dark_gray>\n<gray>Hylonia SMP</gray>", "");
    }

    private String escape(String value) { return value == null ? "" : value.replace("<", "\\<").replace(">", "\\>"); }
}
