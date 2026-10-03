package de.walahi.smpcore.commands;

import de.walahi.smpcore.SMPCorePlugin;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Versteckt und blockiert nicht freigegebene Befehle für Spieler. */
public final class CommandFilter implements Listener {
    private final SMPCorePlugin plugin;
    private final CommandVisibilityManager visibility;
    private final BuildModeManager buildModeManager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, UUID> lastPrivatePartner = new HashMap<>();

    public CommandFilter(SMPCorePlugin plugin, CommandVisibilityManager visibility, BuildModeManager buildModeManager) {
        this.plugin = plugin;
        this.visibility = visibility;
        this.buildModeManager = buildModeManager;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String lower = event.getMessage().trim().toLowerCase(Locale.ROOT);
        if (isBuildCommand(lower)) {
            if (!buildModeManager.isActive(event.getPlayer())) {
                event.setCancelled(true);
                sendConfigured(event.getPlayer(), visibility.blockedMessage());
                return;
            }
            buildModeManager.logBuildCommand(event.getPlayer(), event.getMessage());
        }
        if (!visibility.canUse(event.getPlayer(), event.getMessage())) {
            event.setCancelled(true);
            sendConfigured(event.getPlayer(), visibility.blockedMessage());
            return;
        }

        if (visibility.isPrivateMessageRestrictionEnabled()) {
            validatePrivateMessage(event);
        }
    }

    private void validatePrivateMessage(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage().trim();
        while (raw.startsWith("/")) raw = raw.substring(1);
        if (raw.isBlank()) return;

        String[] parts = raw.split("\\s+", 3);
        String command = parts[0].toLowerCase(Locale.ROOT);
        int namespaceIndex = command.indexOf(':');
        if (namespaceIndex >= 0) command = command.substring(namespaceIndex + 1);

        Player sender = event.getPlayer();
        if (visibility.directMessageCommands().contains(command)) {
            if (parts.length < 2) return;
            Player target = Bukkit.getPlayerExact(parts[1]);
            if (target == null) return;
            if (!visibility.samePrivateMessageArea(sender, target)) {
                event.setCancelled(true);
                sendConfigured(sender, visibility.crossWorldMessage());
                return;
            }
            lastPrivatePartner.put(sender.getUniqueId(), target.getUniqueId());
            lastPrivatePartner.put(target.getUniqueId(), sender.getUniqueId());
            return;
        }

        if (visibility.replyCommands().contains(command)) {
            UUID partnerId = lastPrivatePartner.get(sender.getUniqueId());
            if (partnerId == null) return;
            Player target = Bukkit.getPlayer(partnerId);
            if (target != null && !visibility.samePrivateMessageArea(sender, target)) {
                event.setCancelled(true);
                sendConfigured(sender, visibility.crossWorldMessage());
            }
        }
    }

    private boolean isBuildCommand(String command) {
        for (String configured : visibility.configuration().getStringList("build-mode.blocked-commands-outside-mode")) {
            String prefix = configured.toLowerCase(Locale.ROOT).trim();
            if (!prefix.startsWith("/")) prefix = "/" + prefix;
            if (command.equals(prefix) || command.startsWith(prefix + " ") || (prefix.equals("//") && command.startsWith("//"))) return true;
        }
        return false;
    }

    private void sendConfigured(Player player, String message) {
        if (message != null && !message.isBlank()) {
            player.sendMessage(miniMessage.deserialize(message));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommandSend(PlayerCommandSendEvent event) {
        if (!visibility.isEnabled() || !visibility.hideTabCompletion()) return;

        for (String command : new HashSet<>(event.getCommands())) {
            String plain = "/" + command.toLowerCase(Locale.ROOT);
            if ((visibility.removeNamespacedCommands() && command.contains(":"))
                    || (!buildModeManager.isActive(event.getPlayer()) && isBuildCommand(plain))
                    || !visibility.canUse(event.getPlayer(), command)) {
                event.getCommands().remove(command);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (!visibility.refreshOnWorldChange()) return;
        Bukkit.getScheduler().runTask(plugin, event.getPlayer()::updateCommands);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        UUID partnerId = lastPrivatePartner.remove(playerId);
        if (partnerId != null && playerId.equals(lastPrivatePartner.get(partnerId))) {
            lastPrivatePartner.remove(partnerId);
        }
    }
}
