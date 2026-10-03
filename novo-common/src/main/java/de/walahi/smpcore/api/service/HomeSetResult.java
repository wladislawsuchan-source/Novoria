package de.walahi.smpcore.api.service;

/** Result returned by {@link HomeApi#set}. */
public record HomeSetResult(Status status, String normalizedName, String displayName, int limit) {
    public enum Status { CREATED, ALREADY_EXISTS, INVALID_NAME, LIMIT_REACHED }
    public boolean success() { return status == Status.CREATED; }
}
