package de.walahi.smpcore.commands;

import de.walahi.smpcore.SMPCorePlugin;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Verwaltet flüchtige Build-Sessions. Keine Rechte werden in LuckPerms geschrieben. */
public final class BuildModeManager {
    private final SMPCorePlugin plugin;
    private final CommandVisibilityManager visibility;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, BuildSession> sessions = new HashMap<>();
    private final Set<UUID> warned = new HashSet<>();
    private BukkitTask timeoutTask;

    public BuildModeManager(SMPCorePlugin plugin, CommandVisibilityManager visibility) {
        this.plugin = plugin;
        this.visibility = visibility;
        startTimeoutTask();
    }

    public boolean isActive(Player player) { return sessions.containsKey(player.getUniqueId()); }
    public boolean canManage(Player player) { return config().getBoolean("build-mode.enabled", true) && visibility.hasAnyGroup(player, config().getStringList("build-mode.managers")); }
    public boolean canBeBuilder(Player player) { return visibility.hasAnyGroup(player, config().getStringList("build-mode.builders")); }
    public boolean isWorldAllowed(Player player) {
        List<String> worlds = config().getStringList("build-mode.allowed-worlds");
        if (worlds.isEmpty() || worlds.contains("*")) return true;
        return worlds.stream().anyMatch(w -> player.getWorld().getName().equalsIgnoreCase(w));
    }

    public boolean toggle(Player manager, Player target) {
        if (isActive(target)) { disable(target, manager, true, "manual"); return false; }
        enable(target, manager); return true;
    }

    public void enable(Player target, Player manager) {
        if (isActive(target)) return;
        PermissionAttachment attachment = target.addAttachment(plugin);
        attachment.setPermission("smpcore.buildmode.active", true);
        for (String permission : config().getStringList("build-mode.temporary-permissions")) if (!permission.isBlank()) attachment.setPermission(permission, true);
        target.recalculatePermissions();
        long now = System.currentTimeMillis();
        sessions.put(target.getUniqueId(), new BuildSession(target.getGameMode(), target.getAllowFlight(), target.isFlying(), target.getFlySpeed(), attachment, now, now));
        target.setGameMode(parseGameMode(config().getString("build-mode.gamemode", "CREATIVE")));
        if (config().getBoolean("build-mode.fly", true)) { target.setAllowFlight(true); target.setFlying(true); }
        send(target, "build-mode.messages.enabled-target", "%manager%", manager.getName(), "%player%", target.getName());
        if (!manager.getUniqueId().equals(target.getUniqueId())) send(manager, "build-mode.messages.enabled-manager", "%manager%", manager.getName(), "%player%", target.getName());
        play(target, "build-mode.sounds.enabled");
        log("ENABLE", manager.getName(), target, "/bau " + target.getName());
        refreshDisplay();
        target.updateCommands();
    }

    public void disable(Player target, Player manager, boolean messages) { disable(target, manager, messages, "manual"); }
    public void disable(Player target, Player manager, boolean messages, String reason) {
        BuildSession session = sessions.remove(target.getUniqueId());
        warned.remove(target.getUniqueId());
        if (session == null) return;
        try { target.removeAttachment(session.permissionAttachment()); } catch (IllegalArgumentException ignored) {}
        target.recalculatePermissions();
        if (config().getBoolean("build-mode.restore-gamemode", true)) target.setGameMode(session.previousGameMode());
        if (config().getBoolean("build-mode.restore-flight", true)) {
            target.setAllowFlight(session.previousAllowFlight());
            if (session.previousAllowFlight()) target.setFlying(session.previousFlying());
            target.setFlySpeed(session.previousFlySpeed());
        }
        String managerName = manager == null ? "Server" : manager.getName();
        if (messages) {
            String path = reason.equals("inactivity") ? "build-mode.messages.inactivity-disabled" : "build-mode.messages.disabled-target";
            send(target, path, "%manager%", managerName, "%player%", target.getName());
            if (manager != null && !manager.getUniqueId().equals(target.getUniqueId())) send(manager, "build-mode.messages.disabled-manager", "%manager%", managerName, "%player%", target.getName());
            play(target, "build-mode.sounds.disabled");
        }
        log("DISABLE", managerName, target, reason);
        refreshDisplay();
        target.updateCommands();
    }

    public void ensureBuildState(Player player) {
        if (!isActive(player) || !isWorldAllowed(player)) return;
        GameMode configured = parseGameMode(config().getString("build-mode.gamemode", "CREATIVE"));
        if (player.getGameMode() != configured) player.setGameMode(configured);
        if (config().getBoolean("build-mode.fly", true)) {
            if (!player.getAllowFlight()) player.setAllowFlight(true);
        }
        player.recalculatePermissions();
    }

    public void markActivity(Player player) {
        BuildSession current = sessions.get(player.getUniqueId());
        if (current != null) { sessions.put(player.getUniqueId(), current.withActivity(System.currentTimeMillis())); warned.remove(player.getUniqueId()); }
    }

    public void logBuildCommand(Player player, String command) {
        if (!isActive(player)) return;
        markActivity(player);
        if (config().getBoolean("build-mode.logging.log-commands", true)) log("COMMAND", player.getName(), player, command);
    }

    public void disableOnQuit(Player player) { disable(player, null, false, "quit"); }
    public void disableAll() {
        if (timeoutTask != null) { timeoutTask.cancel(); timeoutTask = null; }
        for (UUID id : List.copyOf(sessions.keySet())) { Player p = Bukkit.getPlayer(id); if (p != null) disable(p, null, false, "shutdown"); else sessions.remove(id); }
    }
    public void reload() { disableAll(); startTimeoutTask(); }

    private void startTimeoutTask() {
        if (!config().getBoolean("build-mode.inactivity.enabled", true)) return;
        timeoutTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long timeout = Math.max(1, config().getLong("build-mode.inactivity.timeout-minutes", 30)) * 60_000L;
            long warning = Math.max(0, config().getLong("build-mode.inactivity.warning-minutes-before", 5)) * 60_000L;
            long now = System.currentTimeMillis();
            for (UUID id : List.copyOf(sessions.keySet())) {
                Player p = Bukkit.getPlayer(id); BuildSession s = sessions.get(id);
                if (p == null || s == null) continue;
                long idle = now - s.lastActivityAt();
                if (idle >= timeout) disable(p, null, true, "inactivity");
                else if (warning > 0 && idle >= timeout - warning && warned.add(id)) send(p, "build-mode.messages.inactivity-warning", "%minutes%", Long.toString(Math.max(1, warning / 60_000L)));
            }
        }, 20L * 30, 20L * 30);
    }

    private void log(String action, String actor, Player target, String detail) {
        if (!config().getBoolean("build-mode.logging.enabled", true)) return;
        File file = new File(plugin.getDataFolder(), config().getString("build-mode.logging.file", "build-log.yml"));
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String key = "entries." + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
        yaml.set(key + ".time", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        yaml.set(key + ".action", action); yaml.set(key + ".actor", actor); yaml.set(key + ".player", target.getName());
        yaml.set(key + ".world", target.getWorld().getName()); yaml.set(key + ".detail", detail);
        try { yaml.save(file); } catch (IOException e) { plugin.getLogger().warning("build-log.yml konnte nicht gespeichert werden: " + e.getMessage()); }
    }

    private void refreshDisplay() { Bukkit.getScheduler().runTask(plugin, () -> { if (plugin.getRankManager() != null) plugin.getRankManager().applyAll(); }); }
    private YamlConfiguration config() { return visibility.configuration(); }
    private GameMode parseGameMode(String value) { try { return GameMode.valueOf(value.toUpperCase(Locale.ROOT)); } catch (Exception e) { return GameMode.CREATIVE; } }
    private void send(Player p, String path, String... repl) { String m = config().getString(path, ""); if (m == null || m.isBlank()) return; for (int i=0;i+1<repl.length;i+=2) m=m.replace(repl[i], repl[i+1]); p.sendMessage(miniMessage.deserialize(m)); }
    private void play(Player p, String path) { if (!config().getBoolean(path+".enabled", true)) return; try { Sound s=Sound.valueOf(config().getString(path+".sound","BLOCK_NOTE_BLOCK_PLING").toUpperCase(Locale.ROOT)); p.playSound(p.getLocation(),s,(float)config().getDouble(path+".volume",1),(float)config().getDouble(path+".pitch",1)); } catch(Exception e){ plugin.getLogger().warning("Ungültiger Baumodus-Sound."); } }
}
