package de.walahi.smpcore.commands;

import de.walahi.smpcore.SMPCorePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import de.walahi.smpcore.messages.MessageChannel;
import de.walahi.smpcore.messages.MessageService;
import de.walahi.smpcore.ranks.RankManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Runtime state and delivery logic for private messages and SocialSpy. */
public final class PrivateMessageManager implements Listener {
    private final SMPCorePlugin plugin;
    private final MessageService messages;
    private final RankManager ranks;
    private final de.walahi.smpcore.sounds.SoundManager sounds;
    private final Map<UUID, UUID> lastPartner = new HashMap<>();
    private final Set<UUID> socialSpy = new HashSet<>();

    public PrivateMessageManager(SMPCorePlugin plugin, MessageService messages, RankManager ranks,
                                 de.walahi.smpcore.sounds.SoundManager sounds) {
        this.plugin = plugin;
        this.messages = messages;
        this.ranks = ranks;
        this.sounds = sounds;
    }

    public void send(Player sender, Player receiver, String text) {
        lastPartner.put(sender.getUniqueId(), receiver.getUniqueId());
        lastPartner.put(receiver.getUniqueId(), sender.getUniqueId());

        // Private Nachrichten respektieren dieselbe viewer-spezifische Identität wie der SMP-Chat:
        // Freunde bzw. per Wasserflasche aufgedeckte Spieler sehen den echten Namen, alle anderen "Anonym".
        Component senderMessage = privateMessagePrefix()
                .append(coloredLabel(sender, "Du"))
                .append(Component.text(" → ", NamedTextColor.DARK_GRAY))
                .append(visibleName(sender, receiver))
                .append(Component.text(": ", NamedTextColor.DARK_GRAY))
                .append(Component.text(text, plugin.shouldAnonymizeIdentity(sender, receiver)
                        ? NamedTextColor.GRAY : NamedTextColor.WHITE));

        boolean senderAnonymousForReceiver = plugin.shouldAnonymizeIdentity(receiver, sender);
        Component receiverMessage = privateMessagePrefix()
                .append(visibleName(receiver, sender))
                .append(Component.text(" → ", senderAnonymousForReceiver ? NamedTextColor.GRAY : NamedTextColor.DARK_GRAY))
                .append(senderAnonymousForReceiver
                        ? Component.text("Dir", NamedTextColor.GRAY)
                        : coloredLabel(receiver, "Dir"))
                .append(Component.text(": ", senderAnonymousForReceiver ? NamedTextColor.GRAY : NamedTextColor.DARK_GRAY))
                .append(Component.text(text, senderAnonymousForReceiver ? NamedTextColor.GRAY : NamedTextColor.WHITE));

        sender.sendMessage(senderMessage);
        receiver.sendMessage(receiverMessage);
        playMessageSound(receiver);

        // Der Team-Prefix reicht als Kennzeichnung aus; kein zusätzliches
        // hartcodiertes "SocialSpy" im Nachrichteninhalt.
        Component spyMessage = teamPrefix()
                .append(coloredName(sender))
                .append(Component.text(" → ", NamedTextColor.DARK_GRAY))
                .append(coloredName(receiver))
                .append(Component.text(": ", NamedTextColor.DARK_GRAY))
                .append(Component.text(text, NamedTextColor.WHITE));

        for (UUID uuid : Set.copyOf(socialSpy)) {
            Player spy = Bukkit.getPlayer(uuid);
            if (spy == null) {
                socialSpy.remove(uuid);
                continue;
            }
            if (spy.equals(sender) || spy.equals(receiver)) continue;
            spy.sendMessage(spyMessage);
        }
    }

    private void playMessageSound(Player receiver) {
        sounds.play(receiver, "private-message");
    }

    private Component privateMessagePrefix() {
        return messages.prefixComponent(MessageChannel.SMP);
    }

    private Component teamPrefix() {
        return messages.prefixComponent(MessageChannel.TEAM);
    }

    private Component coloredName(Player player) {
        return ranks.coloredName(player);
    }

    private Component visibleName(Player viewer, Player subject) {
        if (plugin.shouldAnonymizeIdentity(viewer, subject)) {
            return Component.text("Anonym", NamedTextColor.GRAY);
        }
        return coloredName(subject);
    }

    public String visiblePlainName(Player viewer, Player subject) {
        return plugin.shouldAnonymizeIdentity(viewer, subject) ? "Anonym" : subject.getName();
    }

    private Component coloredLabel(Player player, String label) {
        return ranks.coloredText(player, label);
    }

    public Player lastPartner(Player player) {
        UUID uuid = lastPartner.get(player.getUniqueId());
        return uuid == null ? null : Bukkit.getPlayer(uuid);
    }

    public boolean toggleSocialSpy(Player player) {
        if (socialSpy.remove(player.getUniqueId())) return false;
        socialSpy.add(player.getUniqueId());
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (ranks.resolve(player).key().equalsIgnoreCase("owner")) {
            socialSpy.add(player.getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        remove(event.getPlayer());
    }

    public void remove(Player player) {
        socialSpy.remove(player.getUniqueId());
    }
}
