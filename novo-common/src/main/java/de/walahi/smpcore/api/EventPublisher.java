package de.walahi.smpcore.api;

import org.bukkit.event.Event;

/** Publishes SMPCore API events without coupling services to Bukkit internals. */
@FunctionalInterface
public interface EventPublisher {
    void publish(Event event);
}
