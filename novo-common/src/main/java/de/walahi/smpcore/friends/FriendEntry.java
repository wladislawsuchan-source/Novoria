package de.walahi.smpcore.friends;
import java.util.UUID;
public record FriendEntry(UUID uuid, String name, long createdAt) { }
