package de.walahi.smpcore.api.event;

/** Identifies the entry point that caused an SMPCore action. */
public enum ActionSource {
    COMMAND,
    GUI,
    ADMIN,
    SYSTEM,
    NETWORK,
    API
}
