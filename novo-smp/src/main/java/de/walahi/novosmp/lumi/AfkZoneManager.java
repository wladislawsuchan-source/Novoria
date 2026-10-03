package de.walahi.novosmp.lumi;

import de.walahi.smpcore.SMPCorePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;
import java.util.function.ToDoubleFunction;
import java.util.function.Function;
import java.util.logging.Level;

public final class AfkZoneManager {
    private final SMPCorePlugin plugin;
    private final LumiRepository lumis;
    private final File file;
    private final YamlConfiguration config;
    private final Map<UUID, Integer> elapsedSeconds = new HashMap<>();
    private final Map<UUID, Double> fractionalRewards = new HashMap<>();
    private final Map<UUID, SharedProgress> sharedProgress = new HashMap<>();
    private final ToDoubleFunction<UUID> lumiMultiplier;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private Function<Player, Component> actionbarOverlay = ignored -> null;
    private BukkitTask task;

    public AfkZoneManager(SMPCorePlugin plugin, LumiRepository lumis) {
        this(plugin, lumis, ignored -> 1.0D);
    }

    public AfkZoneManager(SMPCorePlugin plugin, LumiRepository lumis,
                          ToDoubleFunction<UUID> lumiMultiplier) {
        this.plugin = plugin;
        this.lumis = lumis;
        this.lumiMultiplier = lumiMultiplier == null ? ignored -> 1.0D : lumiMultiplier;
        this.file = new File(plugin.getDataFolder(), "afk.yml");
        this.config = YamlConfiguration.loadConfiguration(file);
        warnAboutIncomplete3dZones();
    }

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    /** One HUD clock for Lumi and fishing. Null means no fishing overlay;
     * an empty component reserves the bar for the active minigame. */
    public void actionbarOverlay(Function<Player, Component> overlay) {
        actionbarOverlay = overlay == null ? ignored -> null : overlay;
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
        elapsedSeconds.clear();
        fractionalRewards.clear();
        sharedProgress.clear();
    }

    public void setPosition(int number, Location location) {
        config.set("world", location.getWorld() == null ? null : location.getWorld().getName());
        config.set("pos" + number + ".x", location.getBlockX());
        config.set("pos" + number + ".y", location.getBlockY());
        config.set("pos" + number + ".z", location.getBlockZ());
        try { config.save(file); }
        catch (IOException exception) { plugin.getLogger().log(Level.SEVERE, "afk.yml konnte nicht gespeichert werden", exception); }
    }

    public void setPosition(String rawZone, int number, Location location) {
        String zone = rawZone == null ? "default" : rawZone.toLowerCase(Locale.ROOT);
        if (!zone.matches("[a-z0-9_-]{1,24}")) throw new IllegalArgumentException("Ungültiger Zonenname");
        if (zone.equals("default") && config.contains("world")) { setPosition(number, location); return; }
        String root = "zones." + zone + ".";
        config.set(root + "world", location.getWorld() == null ? null : location.getWorld().getName());
        config.set(root + "pos" + number + ".x", location.getBlockX());
        config.set(root + "pos" + number + ".y", location.getBlockY());
        config.set(root + "pos" + number + ".z", location.getBlockZ());
        try { config.save(file); }
        catch (IOException exception) { plugin.getLogger().log(Level.SEVERE, "afk.yml konnte nicht gespeichert werden", exception); }
    }

    public boolean hasPosition(int number) { return hasCompletePosition("", number); }

    private void tick() {
        if (!plugin.configs().main().getBoolean("lumi.afk-zone.enabled", true)) {
            for (Player player : Bukkit.getOnlinePlayers()) showOverlay(player, actionbarOverlay.apply(player));
            return;
        }
        int interval = Math.max(1, plugin.configs().main().getInt("lumi.afk-zone.reward-interval-seconds", 60));
        long amount = Math.max(1L, plugin.configs().main().getLong("lumi.afk-zone.reward-amount", 1L));
        var onlinePlayers = Bukkit.getOnlinePlayers();
        var onlineIds = onlinePlayers.stream().map(Player::getUniqueId).collect(java.util.stream.Collectors.toSet());
        elapsedSeconds.keySet().removeIf(playerId -> !onlineIds.contains(playerId));

        for (Player player : onlinePlayers) {
            Component overlay = actionbarOverlay.apply(player);
            if (!inside(player) || player.getGameMode() == GameMode.SPECTATOR) {
                elapsedSeconds.remove(player.getUniqueId());
                sharedProgress.remove(player.getUniqueId());
                // Angefangene Bruchteile (z. B. 0,5 Lumi bei einem 1,5×-Booster)
                // bleiben während der Serverlaufzeit erhalten, damit kurzes Verlassen
                // der Zone den bereits verdienten Anteil nicht vernichtet.
                showOverlay(player, overlay);
                continue;
            }
            tickSharedPartyReward(player, overlay == null);
            int elapsed = elapsedSeconds.merge(player.getUniqueId(), 1, Integer::sum);
            int remaining = Math.max(0, interval - elapsed);
            if (elapsed >= interval) {
                elapsedSeconds.put(player.getUniqueId(), 0);
                try {
                    double multiplier = Math.max(1.0D, lumiMultiplier.applyAsDouble(player.getUniqueId()));
                    double rewardRate = amount * multiplier;
                    double exact = rewardRate + fractionalRewards.getOrDefault(player.getUniqueId(), 0.0D);
                    long granted = Math.max(1L, (long) Math.floor(exact + 0.0000001D));
                    fractionalRewards.put(player.getUniqueId(), Math.max(0.0D, exact - granted));
                    lumis.add(player.getUniqueId(), granted);
                    long balance = lumis.balance(player.getUniqueId());
                    String raw = plugin.configs().main().getString("lumi.afk-zone.reward-actionbar",
                            "<gold>+%amount% Lumi</gold> <dark_gray>•</dark_gray> <yellow>%balance% Lumis</yellow>");
                    if (overlay == null) player.sendActionBar(mm.deserialize(raw
                            .replace("%amount%", Long.toString(granted))
                            .replace("%reward%", formatReward(rewardRate))
                            .replace("%multiplier%", formatReward(multiplier))
                            .replace("%balance%", Long.toString(balance))));
                } catch (RuntimeException exception) {
                    plugin.getLogger().log(Level.WARNING, "AFK-Lumi konnte nicht vergeben werden", exception);
                }
            } else if (overlay == null && plugin.configs().main().getBoolean("lumi.afk-zone.countdown-actionbar", true)) {
                double multiplier = Math.max(1.0D, lumiMultiplier.applyAsDouble(player.getUniqueId()));
                double rewardRate = amount * multiplier;
                String raw = plugin.configs().main().getString("lumi.afk-zone.countdown",
                        "<gold>%reward% Lumi</gold> <gray>in</gray> <yellow>%seconds%s</yellow>");
                // Alte config.yml-Dateien enthielten noch keinen %reward%-Platzhalter.
                // In diesem Fall verwenden wir automatisch die neue Anzeige, damit
                // der Lumi-Booster auch ohne manuelles Config-Update sichtbar ist.
                if (raw == null || !raw.contains("%reward%")) {
                    raw = "<gold>%reward% Lumi</gold> <gray>in</gray> <yellow>%seconds%s</yellow>";
                }
                player.sendActionBar(mm.deserialize(raw
                        .replace("%reward%", formatReward(rewardRate))
                        .replace("%multiplier%", formatReward(multiplier))
                        .replace("%seconds%", Integer.toString(remaining))));
            }
            showOverlay(player, overlay);
        }
    }

    private void showOverlay(Player player, Component overlay) {
        if (overlay != null && !overlay.equals(Component.empty())) player.sendActionBar(overlay);
    }

    private String formatReward(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.000001D) {
            return Long.toString(Math.round(value));
        }
        return String.format(Locale.GERMANY, "%.1f", value);
    }

    private boolean inside(Player player) {
        if (insideLegacy(player)) return true;
        var zones = config.getConfigurationSection("zones");
        if (zones == null) return false;
        for (String zone : zones.getKeys(false)) if (insideRoot(player, "zones." + zone + ".")) return true;
        return false;
    }

    private boolean insideLegacy(Player player) {
        String worldName = config.getString("world");
        if (worldName == null || !hasPosition(1) || !hasPosition(2)) return false;
        return insideCoordinates(player, "", worldName);
    }

    private boolean insideRoot(Player player, String root) {
        String worldName = config.getString(root + "world");
        if (worldName == null || !hasCompletePosition(root, 1) || !hasCompletePosition(root, 2)) return false;
        return insideCoordinates(player, root, worldName);
    }

    private boolean insideCoordinates(Player player, String root, String worldName) {
        World world = player.getWorld();
        if (!world.getName().equalsIgnoreCase(worldName)) return false;
        int x = player.getLocation().getBlockX();
        int y = player.getLocation().getBlockY();
        int z = player.getLocation().getBlockZ();
        int minX = Math.min(config.getInt(root + "pos1.x"), config.getInt(root + "pos2.x"));
        int maxX = Math.max(config.getInt(root + "pos1.x"), config.getInt(root + "pos2.x"));
        int minZ = Math.min(config.getInt(root + "pos1.z"), config.getInt(root + "pos2.z"));
        int maxZ = Math.max(config.getInt(root + "pos1.z"), config.getInt(root + "pos2.z"));
        int minY = Math.min(config.getInt(root + "pos1.y"), config.getInt(root + "pos2.y"));
        int maxY = Math.max(config.getInt(root + "pos1.y"), config.getInt(root + "pos2.y"));
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    private boolean hasCompletePosition(String root, int number) {
        String position = root + "pos" + number + ".";
        return config.contains(position + "x")
                && config.contains(position + "y")
                && config.contains(position + "z");
    }

    private void warnAboutIncomplete3dZones() {
        warnIfIncomplete("", "default");
        var zones = config.getConfigurationSection("zones");
        if (zones == null) return;
        for (String zone : zones.getKeys(false)) warnIfIncomplete("zones." + zone + ".", zone);
    }

    private void warnIfIncomplete(String root, String displayName) {
        boolean hasAnyCoordinate = config.contains(root + "pos1.x") || config.contains(root + "pos1.z")
                || config.contains(root + "pos2.x") || config.contains(root + "pos2.z");
        if (!hasAnyCoordinate || hasCompletePosition(root, 1) && hasCompletePosition(root, 2)) return;
        plugin.getLogger().warning("AFK-/Lumi-Zone '" + displayName
                + "' ist deaktiviert, weil in afk.yml vollständige Y-Werte fehlen. "
                + "Bitte beide Ecken mit /setafkpos1 " + displayName
                + " und /setafkpos2 " + displayName + " neu setzen.");
    }

    private void tickSharedPartyReward(Player player, boolean showActionbar) {
        if (!(plugin instanceof de.walahi.novosmp.NovoSMPPlugin smp) || smp.clanManager() == null) return;
        double tier = smp.clanManager().sharedLumiTier(player.getUniqueId());
        if (tier <= 1D) { sharedProgress.remove(player.getUniqueId()); return; }
        int effective = tier >= 1.99D ? 2 : 1;
        SharedProgress old = sharedProgress.get(player.getUniqueId());
        int elapsed = old == null || old.tier() != effective ? 1 : old.elapsed() + 1;
        int interval = smp.clanManager().lumiInterval(tier);
        if (elapsed >= interval) {
            elapsed = 0;
            long reward = java.util.concurrent.ThreadLocalRandom.current().nextLong(
                    smp.clanManager().lumiRewardMin(), (long) smp.clanManager().lumiRewardMax() + 1L);
            lumis.add(player.getUniqueId(), reward);
            if (showActionbar)
                player.sendActionBar(mm.deserialize("<light_purple>Party-Bonus: +" + reward + " Lumis</light_purple>"));
        }
        sharedProgress.put(player.getUniqueId(), new SharedProgress(effective, elapsed));
    }

    private record SharedProgress(int tier, int elapsed) { }
}
