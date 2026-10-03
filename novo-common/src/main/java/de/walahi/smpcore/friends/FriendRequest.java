package de.walahi.smpcore.friends;

import java.util.UUID;

public record FriendRequest(UUID senderUuid, String senderName, long createdAt) {}
