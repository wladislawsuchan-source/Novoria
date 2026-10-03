package de.walahi.smpcore.tablist;

import de.walahi.smpcore.SMPCorePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Schlanke Tablist im Hylonia-Stil.
 *
 * Die Breite wird über echte, nicht sichtbare Leerzeichen im Header erzeugt.
 * Damit bleibt der Spielername unverändert und es entstehen keine unnötigen
 * Abstände hinter einzelnen Namen. Höhe und Abstand werden ausschließlich über
 * konfigurierbare Leerzeilen im Header/Footer gesteuert.
 */
public final class TablistManager implements Listener {
    private static final char NON_BREAKING_SPACE = '\u00A0';

    private record PlaceholderUsage(boolean online, boolean hubOnline, boolean smpOnline, boolean lifestealOnline) {}
    private record TabSection(List<String> header, List<String> footer, PlaceholderUsage usage) {}
    private record OnlineCounts(int online, int hubOnline, int smpOnline, int lifestealOnline) {}
    private record GlobalTabSnapshot(OnlineCounts raw, Set<String> lifestealWorlds) {}
    private record RenderedTab(Component header, Component footer) {}

    private final SMPCorePlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final PlainTextComponentSerializer plainText = PlainTextComponentSerializer.plainText();
    private final Map<UUID, RenderedTab> appliedHeadersAndFooters = new HashMap<>();
    private BukkitTask updateTask;

    public TablistManager(SMPCorePlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void start() {
        stop();
        if (!plugin.configs().scoreboards().getBoolean("tablist.enabled", true)) {
            clearAll();
            return;
        }

        long updateTicks = Math.max(10L, plugin.configs().scoreboards().getLong("tablist.update-ticks", 20L));
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateAll, 1L, updateTicks);
    }

    public void reload() {
        start();
    }

    public void refreshNow() {
        if (plugin.configs().scoreboards().getBoolean("tablist.enabled", true)) updateAll();
    }

    public void stop() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        appliedHeadersAndFooters.clear();
    }

    private void updateAll() {
        List<Player> viewers = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (viewers.isEmpty()) return;

        boolean hasHubViewer = false;
        boolean hasSmpViewer = false;
        for (Player viewer : viewers) {
            if (isSmpWorld(viewer)) hasSmpViewer = true;
            else hasHubViewer = true;
        }

        TabSection hub = hasHubViewer ? configuredSection("tablist.hub") : null;
        TabSection smp = hasSmpViewer ? configuredSection("tablist.smp") : null;
        GlobalTabSnapshot snapshot = snapshot(viewers, hub, smp);
        int headerGap = clamp(plugin.configs().scoreboards().getInt("tablist.layout.header-gap-lines", 1), 0, 4);
        int footerGap = clamp(plugin.configs().scoreboards().getInt("tablist.layout.footer-gap-lines", 1), 0, 4);

        for (Player viewer : viewers) {
            update(viewer, isSmpWorld(viewer) ? smp : hub, snapshot, headerGap, footerGap);
        }
    }

    private TabSection configuredSection(String section) {
        List<String> headerLines = configuredLines(section + ".header",
                List.of("<gold><bold>ѕᴜʀᴠɪᴠᴀʟ</bold></gold>", "<gray>DEINSERVER.NET</gray>"));
        List<String> footerLines = configuredLines(section + ".footer",
                List.of("<gold><bold>ѕᴜʀᴠɪᴠᴀʟ:</bold></gold> <white>%smp_online%</white> <green><bold>♟♟</bold></green>"));
        return new TabSection(headerLines, footerLines, new PlaceholderUsage(
                containsPlaceholder(headerLines, footerLines, "%online%"),
                containsPlaceholder(headerLines, footerLines, "%hub_online%"),
                containsPlaceholder(headerLines, footerLines, "%smp_online%"),
                containsPlaceholder(headerLines, footerLines, "%lifesteal_online%")));
    }

    private boolean containsPlaceholder(List<String> header, List<String> footer, String placeholder) {
        for (String line : header) if (line.contains(placeholder)) return true;
        for (String line : footer) if (line.contains(placeholder)) return true;
        return false;
    }

    private GlobalTabSnapshot snapshot(List<Player> viewers, TabSection hub, TabSection smp) {
        boolean needHub = hub != null && hub.usage().hubOnline() || smp != null && smp.usage().hubOnline();
        boolean needSmp = hub != null && hub.usage().smpOnline() || smp != null && smp.usage().smpOnline();
        boolean needLifesteal = hub != null && hub.usage().lifestealOnline()
                || smp != null && smp.usage().lifestealOnline();
        Set<String> lifestealWorlds = needLifesteal
                ? new HashSet<>(plugin.configs().scoreboards().getStringList("tablist.lifesteal-worlds"))
                : Set.of();
        int hubOnline = 0;
        int smpOnline = 0;
        int lifestealOnline = 0;
        if (needHub || needSmp || needLifesteal && !lifestealWorlds.isEmpty()) {
            for (Player player : viewers) {
                if (needHub || needSmp) {
                    if (isSmpWorld(player)) {
                        if (needSmp) smpOnline++;
                    } else if (needHub) hubOnline++;
                }
                if (needLifesteal && lifestealWorlds.contains(player.getWorld().getName())) lifestealOnline++;
            }
        }
        return new GlobalTabSnapshot(new OnlineCounts(viewers.size(), hubOnline, smpOnline, lifestealOnline), lifestealWorlds);
    }

    private void update(Player viewer, TabSection section, GlobalTabSnapshot snapshot, int headerGap, int footerGap) {
        PlaceholderUsage usage = section.usage();
        OnlineCounts raw = snapshot.raw();
        OnlineCounts visible = new OnlineCounts(
                usage.online() ? plugin.visibleOnlineCount(viewer, raw.online(), ignored -> true) : 0,
                usage.hubOnline() ? plugin.visibleOnlineCount(viewer, raw.hubOnline(), player -> !isSmpWorld(player)) : 0,
                usage.smpOnline() ? plugin.visibleOnlineCount(viewer, raw.smpOnline(), this::isSmpWorld) : 0,
                usage.lifestealOnline() && !snapshot.lifestealWorlds().isEmpty()
                        ? plugin.visibleOnlineCount(viewer, raw.lifestealOnline(),
                                player -> snapshot.lifestealWorlds().contains(player.getWorld().getName()))
                        : 0);
        String playerName = escape(viewer.getName());
        List<Component> renderedHeader = renderLines(section.header(), playerName, visible);
        List<Component> renderedFooter = renderLines(section.footer(), playerName, visible);

        applyMinimumWidth(renderedHeader);
        addBlankLines(renderedHeader, headerGap, false);
        addBlankLines(renderedFooter, footerGap, true);

        RenderedTab rendered = new RenderedTab(join(renderedHeader), join(renderedFooter));
        UUID playerId = viewer.getUniqueId();
        if (rendered.equals(appliedHeadersAndFooters.get(playerId))) return;
        try {
            viewer.sendPlayerListHeaderAndFooter(rendered.header(), rendered.footer());
            appliedHeadersAndFooters.put(playerId, rendered);
        } catch (RuntimeException exception) {
            // A failed send must be retried by the next normal refresh.
            if (plugin.configs().main().getBoolean("debug.tablist", false)) {
                plugin.getLogger().warning("Tablist-Header/Footer konnte nicht gesetzt werden ("
                        + exception.getClass().getSimpleName() + ").");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        appliedHeadersAndFooters.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        appliedHeadersAndFooters.remove(event.getPlayer().getUniqueId());
    }

    private List<String> configuredLines(String path, List<String> fallback) {
        List<String> configured = plugin.configs().scoreboards().getStringList(path);
        return configured.isEmpty() ? fallback : configured;
    }

    private List<Component> renderLines(List<String> lines, String playerName, OnlineCounts counts) {
        List<Component> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            result.add(miniMessage.deserialize(replace(line, playerName, counts)));
        }
        return result;
    }

    /**
     * Minecraft bestimmt die Boxbreite anhand der breitesten sichtbaren Zeile.
     * Normale Farbcodes haben keine Breite. Geschützte Leerzeichen dagegen schon,
     * bleiben aber optisch leer. Die längste Headerzeile wird symmetrisch gepolstert.
     */
    private void applyMinimumWidth(List<Component> headerLines) {
        if (headerLines.isEmpty()) return;

        int minimumCharacters = clamp(
                plugin.configs().scoreboards().getInt("tablist.layout.minimum-width-characters", 42),
                0,
                100
        );
        if (minimumCharacters <= 0) return;

        int longestIndex = 0;
        int longestLength = -1;
        for (int i = 0; i < headerLines.size(); i++) {
            int length = plainText.serialize(headerLines.get(i)).length();
            if (length > longestLength) {
                longestLength = length;
                longestIndex = i;
            }
        }

        int missing = Math.max(0, minimumCharacters - longestLength);
        if (missing == 0) return;

        int left = missing / 2;
        int right = missing - left;
        Component padded = Component.text(String.valueOf(NON_BREAKING_SPACE).repeat(left))
                .append(headerLines.get(longestIndex))
                .append(Component.text(String.valueOf(NON_BREAKING_SPACE).repeat(right)));
        headerLines.set(longestIndex, padded);
    }

    private void addBlankLines(List<Component> lines, int count, boolean atStart) {
        for (int i = 0; i < count; i++) {
            if (atStart) lines.add(0, Component.empty());
            else lines.add(Component.empty());
        }
    }

    private Component join(List<Component> lines) {
        Component result = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) result = result.append(Component.newline());
            result = result.append(lines.get(i));
        }
        return result;
    }

    private String replace(String input, String playerName, OnlineCounts counts) {
        return input
                .replace("%player%", playerName)
                .replace("%online%", Integer.toString(counts.online()))
                .replace("%hub_online%", Integer.toString(counts.hubOnline()))
                .replace("%smp_online%", Integer.toString(counts.smpOnline()))
                .replace("%lifesteal_online%", Integer.toString(counts.lifestealOnline()));
    }

    private boolean isSmpWorld(Player player) {
        return plugin.isSmpGameplayWorld(player.getWorld());
    }

    private String escape(String text) {
        return text.replace("<", "\\<");
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private void clearAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
            player.playerListName(Component.text(player.getName()));
        }
    }
}
