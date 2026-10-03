package de.walahi.novosmp.bounty;

import java.util.UUID;

public record BountyEntry(UUID targetId, String targetName, long amount, long updatedAt) { }
