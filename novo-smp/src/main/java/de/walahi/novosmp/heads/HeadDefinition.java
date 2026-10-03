package de.walahi.novosmp.heads;

public record HeadDefinition(String id, String familyId, String entityType, String variant,
                             boolean baby, String displayName, String textureQuery,
                             HeadCategory category) { }
