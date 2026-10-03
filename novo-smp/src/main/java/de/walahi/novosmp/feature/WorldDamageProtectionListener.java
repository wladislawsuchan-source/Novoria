package de.walahi.novosmp.feature;

import de.walahi.novosmp.NovoSMPPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Disables selected player damage causes per world through novo-smp's config.yml. */
public final class WorldDamageProtectionListener implements Listener {
    private static final String CONFIG_ROOT = "world-damage";

    private final NovoSMPPlugin plugin;
    private volatile Map<String, Set<EntityDamageEvent.DamageCause>> disabledCauses = Map.of();

    public WorldDamageProtectionListener(NovoSMPPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        ConfigurationSection worlds = plugin.configs().server().getConfigurationSection(CONFIG_ROOT);
        if (worlds == null) {
            disabledCauses = Map.of();
            return;
        }

        Map<String, Set<EntityDamageEvent.DamageCause>> loaded = new HashMap<>();
        for (String worldName : worlds.getKeys(false)) {
            ConfigurationSection causes = worlds.getConfigurationSection(worldName);
            if (causes == null) {
                plugin.getLogger().warning("Ungültiger Eintrag unter '" + CONFIG_ROOT + "." + worldName
                        + "': Erwartet wird eine Liste von Schadensarten mit true/false.");
                continue;
            }

            EnumSet<EntityDamageEvent.DamageCause> disabled = EnumSet.noneOf(EntityDamageEvent.DamageCause.class);
            for (String configuredCause : causes.getKeys(false)) {
                EntityDamageEvent.DamageCause cause;
                try {
                    cause = EntityDamageEvent.DamageCause.valueOf(configuredCause.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException exception) {
                    plugin.getLogger().warning("Unbekannte Schadensart in config.yml: '"
                            + configuredCause + "' (Welt: " + worldName + ").");
                    continue;
                }

                if (!causes.getBoolean(configuredCause, true)) disabled.add(cause);
            }

            if (!disabled.isEmpty()) {
                loaded.put(worldName.toLowerCase(Locale.ROOT), Collections.unmodifiableSet(disabled));
            }
        }
        disabledCauses = Collections.unmodifiableMap(loaded);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Set<EntityDamageEvent.DamageCause> worldCauses = disabledCauses.get(
                player.getWorld().getName().toLowerCase(Locale.ROOT));
        if (worldCauses != null && worldCauses.contains(event.getCause())) event.setCancelled(true);
    }
}
