package de.walahi.novosmp.quests;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

public record QuestDefinition(String id, QuestType type, double target, int lumis,
                              Material item, String name, Material material, EntityType entity) {
    boolean matches(QuestType eventType, Material eventMaterial, EntityType eventEntity, boolean ore) {
        if (type == QuestType.MOB_KILL && eventType == QuestType.ENTITY_KILL) return true;
        if (type == QuestType.BOSS_KILL && eventType == QuestType.ENTITY_KILL) return entity == eventEntity;
        if (type == QuestType.BREED && eventType == QuestType.BREED) return entity == null || entity == eventEntity;
        if (type == QuestType.BLOCK_BREAK && eventType == QuestType.MATERIAL_BREAK) return true;
        if (type == QuestType.ORE_BREAK && eventType == QuestType.MATERIAL_BREAK) return ore;
        if (type == QuestType.MATERIAL_BREAK && eventType == QuestType.MATERIAL_BREAK) return material == eventMaterial;
        if (type == QuestType.ENTITY_KILL && eventType == QuestType.ENTITY_KILL) return entity == eventEntity;
        return type == eventType;
    }

    boolean ranking() { return type != QuestType.BOSS_KILL; }
}
