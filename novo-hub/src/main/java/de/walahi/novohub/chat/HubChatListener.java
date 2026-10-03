package de.walahi.novohub.chat;

import de.walahi.novohub.NovoHubPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Hub-eigene Chatdarstellung ohne SMP-Abhängigkeiten. */
public final class HubChatListener implements Listener {
    private final NovoHubPlugin plugin;

    public HubChatListener(NovoHubPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        event.renderer((source, sourceDisplayName, message, viewer) -> {
            Component name = plugin.getRankManager().coloredName(source);
            return name
                    .append(Component.text(": ", NamedTextColor.DARK_GRAY))
                    .append(highlightMention(message, viewer, source).color(NamedTextColor.WHITE));
        });
    }

    private Component highlightMention(Component message, Audience viewer, Player sender) {
        if (!plugin.configs().main().getBoolean("gameplay.chat-mention.enabled", true)
                || !plugin.configs().main().getBoolean("gameplay.chat-mention.highlight.enabled", true)
                || !(viewer instanceof Player recipient)
                || recipient.equals(sender)) {
            return message;
        }

        String plain = PlainTextComponentSerializer.plainText().serialize(message);
        Pattern namePattern = Pattern.compile("(?i)(?<![A-Za-z0-9_])"
                + Pattern.quote(recipient.getName()) + "(?![A-Za-z0-9_])");
        Matcher matcher = namePattern.matcher(plain);
        if (!matcher.find()) return message;

        String prefix = plugin.configs().main().getString("gameplay.chat-mention.highlight.prefix", "@");
        Component result = Component.empty();
        int cursor = 0;
        do {
            if (matcher.start() > cursor) {
                result = result.append(Component.text(plain.substring(cursor, matcher.start()), NamedTextColor.WHITE));
            }
            result = result.append(Component.text(prefix + recipient.getName(), NamedTextColor.YELLOW));
            cursor = matcher.end();
        } while (matcher.find());
        if (cursor < plain.length()) {
            result = result.append(Component.text(plain.substring(cursor), NamedTextColor.WHITE));
        }
        return result;
    }
}
