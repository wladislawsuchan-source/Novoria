package de.walahi.smpcore.messages;

import de.walahi.smpcore.SMPCorePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;

/**
 * Central entry point for every formatted SMPCore chat message.
 * Prefixes, MiniMessage parsing and placeholder replacement live here so
 * feature classes do not need their own formatting logic.
 */
public final class MessageService {
    private final SMPCorePlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public MessageService(SMPCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void smp(CommandSender target, String body, String... replacements) {
        send(target, MessageChannel.SMP, body, replacements);
    }

    public void team(CommandSender target, String body, String... replacements) {
        send(target, MessageChannel.TEAM, body, replacements);
    }

    public void spawn(CommandSender target, String body, String... replacements) {
        send(target, MessageChannel.SPAWN, body, replacements);
    }

    public void plain(CommandSender target, String body, String... replacements) {
        send(target, MessageChannel.NONE, body, replacements);
    }

    public void send(CommandSender target, MessageChannel channel, String body, String... replacements) {
        if (target == null || body == null || body.isBlank()) return;
        String resolved = replace(body, replacements);
        resolved = resolved.replace("<prefix>", prefix(channel));
        resolved = stripLegacyFeaturePrefix(resolved);
        if (channel != MessageChannel.NONE && !containsKnownPrefix(resolved)) {
            resolved = prefix(channel) + resolved;
        }
        target.sendMessage(miniMessage.deserialize(resolved));
    }

    /** Resolves the one YAML file that owns this message path. */
    public boolean sendConfigured(CommandSender target, String path, MessageChannel channel,
                                  String fallbackBody, String... replacements) {
        FileConfiguration source = plugin.configs().findOwner(path).orElse(null);
        return sendConfigured(target, source, path, channel, fallbackBody, replacements);
    }

    /** Reads a message from the explicitly supplied owning YAML file. */
    public boolean sendConfigured(CommandSender target, FileConfiguration source, String path,
                                  MessageChannel channel, String fallbackBody, String... replacements) {
        String raw = source == null ? null : source.getString(path);
        if (raw == null || raw.isBlank()) raw = fallbackBody;
        send(target, channel, raw, replacements);
        return true;
    }

    /** Resolves the one YAML file that owns this path and chooses the channel automatically. */
    public boolean sendConfiguredAuto(CommandSender target, String path, String fallback,
                                      String... replacements) {
        return sendConfigured(target, path, inferChannel(path, fallback), fallback, replacements);
    }

    /** Uses an explicitly supplied owning YAML file and chooses the channel automatically. */
    public boolean sendConfiguredAuto(CommandSender target, FileConfiguration source, String path,
                                      String fallback, String... replacements) {
        return sendConfigured(target, source, path, inferChannel(path, fallback), fallback, replacements);
    }

    public Component prefixComponent(MessageChannel channel) {
        return miniMessage.deserialize(prefix(channel));
    }

    public Component component(String miniMessageText, String... replacements) {
        if (miniMessageText == null || miniMessageText.isBlank()) return Component.empty();
        return miniMessage.deserialize(replace(miniMessageText, replacements));
    }

    public String prefix(MessageChannel channel) {
        if (channel == null || channel == MessageChannel.NONE) return "";
        String configured = plugin.configs().messages().getString(channel.configPath());
        if (configured != null && !configured.isBlank()) return configured;

        // Compatibility with the existing config until every old key is removed.
        if (channel == MessageChannel.SMP) {
            return plugin.configs().messages().getString("messages.prefix", channel.fallbackPrefix());
        }
        if (channel == MessageChannel.SPAWN) {
            return plugin.configs().messages().getString("messages.prefix_spawn", channel.fallbackPrefix());
        }
        return channel.fallbackPrefix();
    }

    private MessageChannel inferChannel(String path, String fallback) {
        String p = path == null ? "" : path.toLowerCase(Locale.ROOT);
        String f = fallback == null ? "" : fallback.toLowerCase(Locale.ROOT);
        if (p.startsWith("auction-house.") || p.startsWith("ah.")) return MessageChannel.AUCTION_HOUSE;
        if (p.startsWith("orders.") || p.startsWith("order.")) return MessageChannel.ORDER;
        if (p.startsWith("shop.")) return MessageChannel.SHOP;
        if (p.startsWith("duel.") || p.startsWith("duels.")) return MessageChannel.DUEL;
        if (p.startsWith("friends.") || p.startsWith("friend.")) return MessageChannel.FRIENDS;
        if (p.startsWith("crates.") || p.startsWith("crate.")) return MessageChannel.CRATE;
        if (p.startsWith("enderchest.") || p.startsWith("ec.")) return MessageChannel.ENDER_CHEST;
        if (p.startsWith("discord.")) return MessageChannel.NOVORIA;
        if (f.contains("[<red>team") || f.contains("<red>team</red>")
                || p.startsWith("staff.") || p.startsWith("moderation.")
                || p.startsWith("build-mode.")) {
            return MessageChannel.TEAM;
        }
        if (f.contains("[<gold>spawn") || f.contains("<gold>spawn</gold>")
                || p.contains("teleported-to-hub") || p.contains("spawn.")) {
            return MessageChannel.SPAWN;
        }
        return MessageChannel.SMP;
    }

    private String replace(String raw, String... replacements) {
        String result = raw;
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            result = result.replace(replacements[i], replacements[i + 1]);
        }
        return result;
    }

    private String stripLegacyFeaturePrefix(String raw) {
        String result = raw.stripLeading();
        String[] legacyPrefixes = {
                "<dark_gray>[<green>Home</green>]</dark_gray>",
                "<dark_gray>[<gold>RTP</gold>]</dark_gray>",
                "<gold><dark_gray>[<gold>RTP</gold><dark_gray>]",
                "[HOME]", "[Home]", "[RTP]"
        };
        for (String legacyPrefix : legacyPrefixes) {
            if (result.regionMatches(true, 0, legacyPrefix, 0, legacyPrefix.length())) {
                return result.substring(legacyPrefix.length()).stripLeading();
            }
        }
        return result;
    }

    private boolean containsKnownPrefix(String raw) {
        String value = raw == null ? "" : raw.stripLeading();
        String compact = value.replace(" ", "").toLowerCase(Locale.ROOT);

        for (MessageChannel channel : MessageChannel.values()) {
            if (channel == MessageChannel.NONE) continue;
            String configured = prefix(channel).strip();
            if (!configured.isBlank() && value.regionMatches(true, 0, configured, 0, configured.length())) {
                return true;
            }
            String fallback = channel.fallbackPrefix().strip();
            if (!fallback.isBlank() && value.regionMatches(true, 0, fallback, 0, fallback.length())) {
                return true;
            }
        }

        // Compatibility for old feature messages that already contain their own
        // [Feature] prefix. This avoids duplicate prefixes while those messages
        // are gradually moved to body-only entries.
        if (compact.startsWith("<dark_gray>[") || compact.startsWith("<gray>[")
                || compact.startsWith("<black>[") || compact.startsWith("[")) {
            int closingBracket = compact.indexOf(']');
            return closingBracket > 1 && closingBracket < 80;
        }
        return false;
    }
}
