package de.walahi.smpcore.services;

/** Lightweight lifecycle contract for centrally managed SMPCore services. */
public interface Service {
    default void start() { }
    default void stop() { }
}
