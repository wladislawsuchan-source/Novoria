package de.walahi.smpcore.api.service;

/** Result returned by {@link HomeApi#delete}. */
public record HomeDeleteResult(boolean deleted, String displayName) {}
