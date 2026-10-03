package de.walahi.smpcore.api;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

/** Default event publisher backed by Bukkit's PluginManager. */
public final class BukkitEventPublisher implements EventPublisher {
    private final Plugin plugin;

    public BukkitEventPublisher(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public void publish(Event event) {
        Objects.requireNonNull(event, "event");
        if (!plugin.isEnabled()) return;
        Bukkit.getPluginManager().callEvent(event);
    }
}
