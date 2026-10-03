package de.walahi.smpcore.moderation;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.punishments.Punishment;
import de.walahi.smpcore.punishments.PunishmentResult;
import de.walahi.smpcore.punishments.PunishmentType;
import de.walahi.smpcore.services.PunishmentService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public final class MuteService {
    public enum Result { SUCCESS, ALREADY_MUTED, NOT_MUTED, SELF, HIERARCHY, STORAGE_ERROR }

    private final SMPCorePlugin plugin;
    private final StaffHierarchy hierarchy;
    private final PunishmentService punishments;

    public MuteService(SMPCorePlugin plugin, StaffHierarchy hierarchy, PunishmentService punishments) {
        this.plugin = plugin;
        this.hierarchy = hierarchy;
        this.punishments = punishments;
    }

    public Result mute(CommandSender actor, UUID targetUuid, String targetName, Player onlineTarget, String reason) {
        return createMute(actor, targetUuid, targetName, onlineTarget, reason, null);
    }

    public Result tempMute(CommandSender actor, UUID targetUuid, String targetName, Player onlineTarget,
                           String reason, Duration duration) {
        return createMute(actor, targetUuid, targetName, onlineTarget, reason, duration);
    }

    private Result createMute(CommandSender actor, UUID targetUuid, String targetName, Player onlineTarget,
                              String reason, Duration duration) {
        if (actor instanceof Player player && player.getUniqueId().equals(targetUuid)) return Result.SELF;
        if (onlineTarget != null && !hierarchy.mayActOn(actor, onlineTarget)) return Result.HIERARCHY;
        if (punishments.active(targetUuid, PunishmentType.MUTE).isPresent()) return Result.ALREADY_MUTED;

        UUID staffUuid = actor instanceof Player player ? player.getUniqueId() : null;
        PunishmentResult result = punishments.punish(
                targetUuid, targetName, staffUuid, actor.getName(), PunishmentType.MUTE, reason, duration
        );
        if (!result.success()) return Result.STORAGE_ERROR;

        plugin.getLogger().info("[Team] " + actor.getName() + " hat " + targetName
                + (duration == null ? " permanent" : " temporär") + " gemutet. Grund: " + reason);
        return Result.SUCCESS;
    }

    public Result unmute(CommandSender actor, UUID targetUuid, String targetName) {
        if (punishments.active(targetUuid, PunishmentType.MUTE).isEmpty()) return Result.NOT_MUTED;
        UUID staffUuid = actor instanceof Player player ? player.getUniqueId() : null;
        boolean revoked = punishments.revoke(
                targetUuid, PunishmentType.MUTE, staffUuid, actor.getName()
        );
        if (!revoked) return Result.STORAGE_ERROR;
        plugin.getLogger().info("[Team] " + actor.getName() + " hat " + targetName + " entmutet.");
        return Result.SUCCESS;
    }

    public Optional<Punishment> activeMute(UUID playerUuid) {
        return punishments.active(playerUuid, PunishmentType.MUTE);
    }
}
