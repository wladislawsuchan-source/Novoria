package de.walahi.novosmp.enderchest;

import de.walahi.smpcore.SMPCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.plugin.EventExecutor;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Öffnet das EC-Erweiterungsmenü ausschließlich über den konfigurierten Citizens-NPC. */
public final class EnderChestUpgradeNpcListener implements Listener {
    private final SMPCorePlugin plugin;
    private final EnderChestUpgradeMenu menu;
    private final Set<UUID> opening = new HashSet<>();

    public EnderChestUpgradeNpcListener(SMPCorePlugin plugin, EnderChestUpgradeMenu menu) {
        this.plugin = plugin;
        this.menu = menu;
    }

    /**
     * Bukkit-Fallback. Einige Citizens-Versionen reichen den normalen Entity-Klick nicht zuverlässig weiter,
     * deshalb wird zusätzlich direkt NPCRightClickEvent registriert.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!enabled()) return;
        Entity entity = event.getRightClicked();
        if (!entity.hasMetadata("NPC") || !matches(entity)) return;
        event.setCancelled(true);
        openNextTick(event.getPlayer());
    }

    /** Registriert Citizens' NPCRightClickEvent ohne harte Maven-Abhängigkeit. */
    @SuppressWarnings("unchecked")
    public void registerCitizensHook() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            plugin.getLogger().warning("Citizens ist nicht aktiv; der EC-Erweiterungs-NPC kann nicht reagieren.");
            return;
        }
        try {
            Class<?> rawEventClass = Class.forName("net.citizensnpcs.api.event.NPCRightClickEvent");
            if (!Event.class.isAssignableFrom(rawEventClass)) {
                plugin.getLogger().warning("Citizens NPCRightClickEvent ist kein Bukkit-Event.");
                return;
            }

            Class<? extends Event> eventClass = (Class<? extends Event>) rawEventClass;
            EventExecutor executor = (listener, event) -> handleCitizensClick(event);
            Bukkit.getPluginManager().registerEvent(
                    eventClass,
                    this,
                    EventPriority.MONITOR,
                    executor,
                    plugin,
                    false
            );
            plugin.getLogger().info("EC-Erweiterungs-NPC: direkter Citizens-Klick-Hook aktiviert.");
        } catch (ClassNotFoundException ex) {
            plugin.getLogger().warning("Citizens NPCRightClickEvent wurde nicht gefunden; Bukkit-Fallback bleibt aktiv.");
        }
    }

    private void handleCitizensClick(Event event) {
        if (!enabled()) return;
        try {
            Object npc = event.getClass().getMethod("getNPC").invoke(event);
            Object clicker = event.getClass().getMethod("getClicker").invoke(event);
            if (!(clicker instanceof Player player) || npc == null) return;

            int actualId = ((Number) npc.getClass().getMethod("getId").invoke(npc)).intValue();
            int configuredId = plugin.configs().main().getInt("enderchest-upgrades.npc.id", -1);
            if (configuredId >= 0 && actualId != configuredId) return;

            // Falls keine ID konfiguriert ist, weiterhin UUID/Name-Fallback verwenden.
            if (configuredId < 0) {
                Object entityObject = npc.getClass().getMethod("getEntity").invoke(npc);
                if (!(entityObject instanceof Entity entity) || !matches(entity)) return;
            }

            openNextTick(player);
        } catch (ReflectiveOperationException | ClassCastException ex) {
            plugin.getLogger().warning("Citizens-NPC-Klick konnte nicht verarbeitet werden: " + ex.getMessage());
        }
    }

    private boolean enabled() {
        return plugin.configs().main().getBoolean("enderchest-upgrades.npc.enabled", true);
    }

    private void openNextTick(Player player) {
        if (!opening.add(player.getUniqueId())) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                if (player.isOnline()) menu.open(player);
            } finally {
                Bukkit.getScheduler().runTaskLater(plugin, () -> opening.remove(player.getUniqueId()), 2L);
            }
        });
    }

    private boolean matches(Entity entity) {
        int configuredNpcId = plugin.configs().main().getInt("enderchest-upgrades.npc.id", -1);
        if (configuredNpcId >= 0) {
            Integer actualNpcId = citizensNpcId(entity);
            return actualNpcId != null && actualNpcId == configuredNpcId;
        }

        String configuredUuid = plugin.configs().main().getString("enderchest-upgrades.npc.entity-uuid", "").trim();
        if (!configuredUuid.isEmpty()) {
            try {
                return entity.getUniqueId().equals(UUID.fromString(configuredUuid));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Ungültige UUID unter enderchest-upgrades.npc.entity-uuid.");
                return false;
            }
        }

        String configuredName = plugin.configs().main().getString("enderchest-upgrades.npc.name", "EC Erweiterung");
        return !configuredName.isBlank() && entity.getName().equalsIgnoreCase(configuredName);
    }

    /** Citizens bleibt eine optionale Abhängigkeit; deshalb wird die API reflektiv aufgerufen. */
    private Integer citizensNpcId(Entity entity) {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("Citizens")) return null;
        try {
            Class<?> api = Class.forName("net.citizensnpcs.api.CitizensAPI");
            Method getRegistry = api.getMethod("getNPCRegistry");
            Object registry = getRegistry.invoke(null);
            Method getNpc = registry.getClass().getMethod("getNPC", Entity.class);
            Object npc = getNpc.invoke(registry, entity);
            if (npc == null) return null;
            Method getId = npc.getClass().getMethod("getId");
            return ((Number) getId.invoke(npc)).intValue();
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().fine("Citizens-NPC-ID konnte nicht ausgelesen werden: " + ex.getMessage());
            return null;
        }
    }
}
