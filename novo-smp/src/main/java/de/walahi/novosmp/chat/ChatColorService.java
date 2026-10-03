package de.walahi.novosmp.chat;

import de.walahi.novosmp.NovoSMPPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Persistent SMP message color; Premium and Premium+ intentionally share one palette. */
public final class ChatColorService implements Listener {
    private static final Pattern HEX = Pattern.compile("^#?[0-9a-fA-F]{6}$");
    private final NovoSMPPlugin plugin;
    private final NamespacedKey selectedColorKey;
    private final Map<UUID, TextColor> selected = new ConcurrentHashMap<>();
    private final Set<UUID> eligible = ConcurrentHashMap.newKeySet();

    public ChatColorService(NovoSMPPlugin plugin) {
        this.plugin = plugin;
        this.selectedColorKey = new NamespacedKey(plugin, "chat_color");
        LuckPermsProvider.get().getEventBus().subscribe(plugin, UserDataRecalculateEvent.class,
                event -> Bukkit.getScheduler().runTask(plugin, () -> {
                    Player player = Bukkit.getPlayer(event.getUser().getUniqueId());
                    if (player != null) refresh(player);
                }));
        Bukkit.getOnlinePlayers().forEach(this::refresh);
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getOnlinePlayers().forEach(this::refresh));
    }

    public TextColor color(Player player) {
        if (player == null || !eligible.contains(player.getUniqueId())) return NamedTextColor.WHITE;
        return selected.getOrDefault(player.getUniqueId(), NamedTextColor.WHITE);
    }

    public void refreshAll() {
        Bukkit.getOnlinePlayers().forEach(this::refresh);
    }

    public void showOptions(Player player) {
        refresh(player);
        if (!checkEligible(player)) return;

        player.sendRichMessage("<dark_gray>──────── <light_purple><bold>Chatfarben</bold></light_purple> ────────</dark_gray>");
        player.sendRichMessage("<gray>Klicke eine Farbe an oder nutze <yellow>/chatfarbe &lt;Code&gt;</yellow>.</gray>");
        player.sendRichMessage("<gray>Eigene Farbe: <yellow>/chatfarbe #A855F7</yellow></gray>");
        Component row = Component.empty();
        int entriesInRow = 0;
        for (ColorChoice choice : configuredColors()) {
            boolean active = color(player).equals(choice.color());
            Component option = Component.text("[&" + choice.code() + " " + choice.display() + "]", choice.color())
                    .append(active ? Component.text("✓", NamedTextColor.GREEN) : Component.empty())
                    .clickEvent(ClickEvent.runCommand("/chatfarbe &" + choice.code()))
                    .hoverEvent(HoverEvent.showText(Component.text(
                            active ? choice.display() + " – bereits ausgewählt" : choice.display() + " auswählen",
                            NamedTextColor.GRAY)));
            row = row.append(option).append(Component.text("   "));
            entriesInRow++;
            if (entriesInRow == 2) {
                player.sendMessage(row);
                row = Component.empty();
                entriesInRow = 0;
            }
        }
        if (entriesInRow != 0) player.sendMessage(row);
    }

    public boolean select(Player player, String rawCode) {
        refresh(player);
        if (!checkEligible(player)) return false;

        String normalized = rawCode == null ? "" : rawCode.trim().toLowerCase(Locale.ROOT);
        if (HEX.matcher(normalized).matches()) {
            String hex = normalized.startsWith("#") ? normalized : "#" + normalized;
            TextColor custom = TextColor.fromHexString(hex);
            player.getPersistentDataContainer().set(selectedColorKey, PersistentDataType.STRING, "hex:" + hex);
            selected.put(player.getUniqueId(), custom);
            player.sendMessage(Component.text("Deine Chatfarbe ist jetzt ", NamedTextColor.GREEN)
                    .append(Component.text(hex.toUpperCase(Locale.ROOT), custom))
                    .append(Component.text(".", NamedTextColor.GREEN)));
            return true;
        }
        if (normalized.startsWith("&") || normalized.startsWith("§")) normalized = normalized.substring(1);
        ColorChoice choice = null;
        for (ColorChoice candidate : configuredColors()) {
            if (candidate.code().equalsIgnoreCase(normalized)
                    || candidate.id().equalsIgnoreCase(normalized)
                    || candidate.display().equalsIgnoreCase(normalized)) {
                choice = candidate;
                break;
            }
        }
        if (choice == null) {
            player.sendRichMessage("<red>Ungültiger Farbcode.</red> <gray>Nutze <yellow>/chatfarbe</yellow> für die Liste.</gray>");
            return false;
        }

        player.getPersistentDataContainer().set(selectedColorKey, PersistentDataType.STRING, choice.id());
        selected.put(player.getUniqueId(), choice.color());
        player.sendMessage(Component.text("Deine Chatfarbe ist jetzt ", NamedTextColor.GREEN)
                .append(Component.text(choice.display(), choice.color()))
                .append(Component.text(".", NamedTextColor.GREEN)));
        return true;
    }

    private boolean checkEligible(Player player) {
        if (plugin.getRankManager() != null && plugin.getRankManager().hasPremium(player)) return true;
        eligible.remove(player.getUniqueId());
        player.sendRichMessage("<red>/chatfarbe ist ein Vorteil für Premium und Premium+.</red>");
        return false;
    }

    private List<ColorChoice> configuredColors() {
        List<ColorChoice> result = new ArrayList<>();
        var config = plugin.configs().chatColors();
        for (Map<?, ?> entry : config.getMapList("colors")) {
            String id = value(entry, "id");
            String code = normalizeCode(value(entry, "code"));
            String display = value(entry, "name");
            TextColor color = TextColor.fromHexString(value(entry, "color"));
            if (id.isBlank() || code.isBlank() || display.isBlank() || color == null) {
                plugin.getLogger().warning("Ungültiger Eintrag in chat-colors.yml: " + entry);
                continue;
            }
            boolean duplicate = result.stream().anyMatch(choice ->
                    choice.id().equalsIgnoreCase(id) || choice.code().equalsIgnoreCase(code));
            if (!duplicate) result.add(new ColorChoice(id, code, display, color));
        }
        return result;
    }

    private String value(Map<?, ?> entry, String key) {
        Object raw = entry.get(key);
        return raw == null ? "" : raw.toString().trim();
    }

    private String normalizeCode(String raw) {
        String result = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (result.startsWith("&") || result.startsWith("§")) result = result.substring(1);
        return result;
    }

    private void refresh(Player player) {
        String stored = player.getPersistentDataContainer().get(selectedColorKey, PersistentDataType.STRING);
        ColorChoice storedChoice = null;
        TextColor storedHex = null;
        if (stored != null) {
            if (stored.toLowerCase(Locale.ROOT).startsWith("hex:")) {
                storedHex = TextColor.fromHexString(stored.substring(4));
            }
            for (ColorChoice choice : configuredColors()) {
                if (choice.id().equalsIgnoreCase(stored) || choice.code().equalsIgnoreCase(stored)) {
                    storedChoice = choice;
                    break;
                }
            }
        }
        selected.put(player.getUniqueId(), storedHex != null ? storedHex
                : storedChoice == null ? NamedTextColor.WHITE : storedChoice.color());
        if (plugin.getRankManager() != null && plugin.getRankManager().hasPremium(player)) {
            eligible.add(player.getUniqueId());
        } else {
            eligible.remove(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        eligible.remove(event.getPlayer().getUniqueId());
        selected.remove(event.getPlayer().getUniqueId());
    }

    private record ColorChoice(String id, String code, String display, TextColor color) { }
}
