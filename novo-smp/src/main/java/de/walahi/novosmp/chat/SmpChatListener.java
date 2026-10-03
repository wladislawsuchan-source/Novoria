package de.walahi.novosmp.chat;

import de.walahi.novosmp.NovoSMPPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SMP chat formatting, mentions, selectable message colors and safe [item] snapshots. */
public final class SmpChatListener implements Listener {
    private static final Pattern ITEM_TOKEN = Pattern.compile("(?i)\\[item]");

    private final NovoSMPPlugin plugin;
    private final ChatColorService colors;
    private final Map<UUID, Component> heldItemSnapshots = new ConcurrentHashMap<>();
    private final Map<UUID, ItemStack> lastHeldItems = new ConcurrentHashMap<>();

    public SmpChatListener(NovoSMPPlugin plugin, ChatColorService colors) {
        this.plugin = plugin;
        this.colors = colors;
        Bukkit.getScheduler().runTaskTimer(plugin,
                () -> Bukkit.getOnlinePlayers().forEach(this::snapshotHeldItem), 1L, 5L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        event.renderer((source, sourceDisplayName, message, viewer) -> {
            Component name = plugin.getRankManager().coloredName(source)
                    .clickEvent(ClickEvent.suggestCommand("/msg " + source.getName() + " "))
                    .hoverEvent(HoverEvent.showText(Component.text(
                            "Klicke, um " + source.getName() + " zu schreiben.", NamedTextColor.GRAY)));
            if (viewer instanceof Player recipient && plugin.friendManager() != null
                    && plugin.friendManager().shouldMarkChat(recipient, source)) {
                name = Component.text("❤ ", NamedTextColor.LIGHT_PURPLE).append(name);
            }
            return name
                    .append(Component.text(": ", NamedTextColor.DARK_GRAY))
                    .append(formatMessage(message, viewer, source));
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        heldItemSnapshots.remove(event.getPlayer().getUniqueId());
        lastHeldItems.remove(event.getPlayer().getUniqueId());
    }

    private Component formatMessage(Component message, Audience viewer, Player sender) {
        String plain = PlainTextComponentSerializer.plainText().serialize(message);
        TextColor messageColor = colors.color(sender);
        boolean itemEnabled = plugin.configs().server().getBoolean("premium-features.chat-item.enabled", true);
        boolean mentionEnabled = plugin.configs().main().getBoolean("gameplay.chat-mention.enabled", true)
                && plugin.configs().main().getBoolean("gameplay.chat-mention.highlight.enabled", true)
                && viewer instanceof Player recipient && !recipient.equals(sender);

        String recipientName = mentionEnabled ? ((Player) viewer).getName() : null;
        Pattern pattern;
        if (itemEnabled && recipientName != null) {
            pattern = Pattern.compile("(?i)(\\[item])|(?<![A-Za-z0-9_])(" + Pattern.quote(recipientName)
                    + ")(?![A-Za-z0-9_])");
        } else if (recipientName != null) {
            pattern = Pattern.compile("(?i)(?<![A-Za-z0-9_])(" + Pattern.quote(recipientName)
                    + ")(?![A-Za-z0-9_])");
        } else if (itemEnabled) {
            pattern = ITEM_TOKEN;
        } else {
            return message.color(messageColor);
        }

        Matcher matcher = pattern.matcher(plain);
        // Preserve components supplied by other chat integrations whenever no replacement
        // is required. Only messages containing [item] or a viewer-specific mention are split.
        if (!matcher.find()) return message.color(messageColor);
        Component result = Component.empty();
        int cursor = 0;
        do {
            if (matcher.start() > cursor) {
                result = result.append(Component.text(plain.substring(cursor, matcher.start()), messageColor));
            }
            String matched = matcher.group();
            if (matched.equalsIgnoreCase("[item]")) {
                result = result.append(heldItemSnapshots.getOrDefault(sender.getUniqueId(), emptyHand()));
            } else {
                String prefix = plugin.configs().main().getString("gameplay.chat-mention.highlight.prefix", "@");
                result = result.append(Component.text(prefix + recipientName, NamedTextColor.YELLOW));
            }
            cursor = matcher.end();
        } while (matcher.find());
        if (cursor < plain.length()) {
            result = result.append(Component.text(plain.substring(cursor), messageColor));
        }
        return result;
    }

    /** Runs only on the server thread; AsyncChatEvent reads the immutable Component snapshot. */
    private void snapshotHeldItem(Player player) {
        UUID playerId = player.getUniqueId();
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            if (!lastHeldItems.containsKey(playerId) && heldItemSnapshots.containsKey(playerId)) return;
            lastHeldItems.remove(playerId);
            heldItemSnapshots.put(playerId, emptyHand());
            return;
        }
        ItemStack previous = lastHeldItems.get(playerId);
        if (previous != null && previous.equals(held)) return;
        ItemStack snapshot = held.clone();
        lastHeldItems.put(playerId, snapshot);
        String readable = readable(snapshot.getType());
        String amount = snapshot.getAmount() > 1 ? snapshot.getAmount() + "× " : "";
        Component component = Component.text("[" + amount + readable + "]", NamedTextColor.AQUA)
                .hoverEvent(snapshot.asHoverEvent());
        heldItemSnapshots.put(playerId, component);
    }

    private Component emptyHand() {
        return Component.text("[Leere Hand]", NamedTextColor.GRAY)
                .hoverEvent(HoverEvent.showText(Component.text("Du hältst kein Item.", NamedTextColor.DARK_GRAY)));
    }

    private String readable(Material material) {
        String[] parts = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return result.toString();
    }
}
