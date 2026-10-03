package de.walahi.smpcore.moderation;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.punishments.Punishment;
import de.walahi.smpcore.punishments.PunishmentType;
import de.walahi.smpcore.services.PunishmentService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.util.Optional;

public final class BanLoginListener implements Listener {
    private final PunishmentService punishments;
    private final BanService banService;

    public BanLoginListener(PunishmentService punishments, BanService banService) {
        this.punishments = punishments;
        this.banService = banService;
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        Optional<Punishment> activeBan = punishments.active(event.getUniqueId(), PunishmentType.BAN);
        activeBan.ifPresent(punishment -> event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                banService.buildBanScreen(punishment)
        ));
    }
}
