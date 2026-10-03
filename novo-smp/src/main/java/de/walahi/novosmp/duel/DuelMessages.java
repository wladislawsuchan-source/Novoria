package de.walahi.novosmp.duel;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;

/** Einheitliche [Duel]-Formatierung für sämtliche Duell-Chatnachrichten. */
public final class DuelMessages {
    private static final String FALLBACK_PREFIX = "<dark_gray>[<light_purple>Duel</light_purple>]</dark_gray> ";
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private DuelMessages() {}

    public static void send(DuelConfig config, CommandSender target, String body) {
        if (target == null || body == null || body.isBlank()) return;
        target.sendMessage(decorate(config, deserializeBody(body)));
    }

    public static Component component(DuelConfig config, String body) {
        if (body == null || body.isBlank()) return prefix(config);
        return decorate(config, deserializeBody(body));
    }

    /** Actionbars bleiben bewusst ohne [Duel]-Präfix. */
    public static Component actionBar(String body) {
        if (body == null || body.isBlank()) return Component.empty();
        return deserializeBody(body);
    }

    public static Component actionBar(DuelConfig config, String body) {
        if (body == null || body.isBlank()) return Component.empty();
        return deserializeBody(body);
    }

    public static Component decorate(DuelConfig config, Component body) {
        return prefix(config).append(body == null ? Component.empty() : body);
    }

    public static Component prefix(DuelConfig config) {
        String raw = config == null ? FALLBACK_PREFIX : config.messagePrefix();
        try {
            return MM.deserialize(raw == null || raw.isBlank() ? FALLBACK_PREFIX : raw);
        } catch (RuntimeException ignored) {
            return MM.deserialize(FALLBACK_PREFIX);
        }
    }

    private static Component deserializeBody(String body) {
        if (body.indexOf('§') >= 0) return LEGACY.deserialize(body);
        try {
            return MM.deserialize(body);
        } catch (RuntimeException ignored) {
            return Component.text(body);
        }
    }
}
