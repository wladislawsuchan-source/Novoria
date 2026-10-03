package de.walahi.smpcore.moderation;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.punishments.Punishment;
import de.walahi.smpcore.punishments.PunishmentFormatter;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import de.walahi.smpcore.messages.MessageChannel;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

public final class MuteListener implements Listener {
    private final SMPCorePlugin plugin;
    private final MuteService muteService;

    public MuteListener(SMPCorePlugin plugin, MuteService muteService) {
        this.plugin = plugin;
        this.muteService = muteService;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!plugin.configs().main().getBoolean(
                "moderation.mute.communication.block-public-chat", true)) return;
        Optional<Punishment> active = muteService.activeMute(event.getPlayer().getUniqueId());
        if (active.isEmpty()) return;
        event.setCancelled(true);
        sendBlocked(event.getPlayer(), active.get());
    }

    /** Blocks every configured communication command through the same normal MUTE punishment. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.configs().main().getBoolean(
                "moderation.mute.communication.block-commands", true)) return;
        String command = commandLabel(event.getMessage());
        if (command.isEmpty() || plugin.configs().main()
                .getStringList("moderation.mute.communication.commands").stream()
                .map(this::commandLabel)
                .noneMatch(command::equals)) return;

        Optional<Punishment> active = muteService.activeMute(event.getPlayer().getUniqueId());
        if (active.isEmpty()) return;
        event.setCancelled(true);
        sendBlocked(event.getPlayer(), active.get());
    }

    private void sendBlocked(org.bukkit.entity.Player player, Punishment punishment) {
        String fallback = punishment.permanent()
                ? "<red>Du bist permanent gemutet.</red> <gray>Grund: <white>%reason%</white></gray>"
                : "<red>Du bist noch <yellow>%remaining%</yellow> gemutet.</red> <gray>Grund: <white>%reason%</white></gray>";
        plugin.messages().sendConfigured(player, "moderation.mute.blocked-message", MessageChannel.SMP, fallback,
                "%reason%", escape(punishment.reason()),
                "%remaining%", escape(PunishmentFormatter.remaining(punishment, Instant.now())),
                "%moderator%", escape(punishment.staffName() == null ? "Konsole" : punishment.staffName()));
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("<", "\\<").replace(">", "\\>");
    }

    private String commandLabel(String input) {
        if (input == null) return "";
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("/")) normalized = normalized.substring(1);
        int arguments = normalized.indexOf(' ');
        if (arguments >= 0) normalized = normalized.substring(0, arguments);
        int namespace = normalized.lastIndexOf(':');
        return namespace >= 0 ? normalized.substring(namespace + 1) : normalized;
    }
}
