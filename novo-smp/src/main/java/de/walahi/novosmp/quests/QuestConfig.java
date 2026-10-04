package de.walahi.novosmp.quests;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

final class QuestConfig {
    final FileConfiguration yaml;
    final ZoneId zone;
    final int resetHour;
    final int resetMinute;
    final int keyAmount;
    final int dailyCount;
    final int cooldownSeconds;
    final List<Integer> thresholds;
    final List<QuestDefinition> daily;
    final List<QuestDefinition> global;
    final Map<String, QuestDefinition> byId;
    final Set<Material> ores;

    QuestConfig(FileConfiguration yaml, Logger logger) {
        this.yaml = yaml;
        ZoneId parsed;
        try { parsed = ZoneId.of(yaml.getString("timezone", "Europe/Berlin")); }
        catch (DateTimeException error) { logger.warning("quests.yml: Ungültige timezone; Europe/Berlin wird verwendet."); parsed = ZoneId.of("Europe/Berlin"); }
        zone = parsed;
        resetHour = Math.max(0, Math.min(23, yaml.getInt("daily.reset-hour", 0)));
        resetMinute = Math.max(0, Math.min(59, yaml.getInt("daily.reset-minute", 0)));
        keyAmount = Math.max(1, Math.min(64, yaml.getInt("daily.key-amount", 1)));
        dailyCount = Math.max(1, Math.min(9, yaml.getInt("daily.count", 5)));
        cooldownSeconds = Math.max(1, yaml.getInt("global.cooldown-seconds", 900));
        List<Integer> limits = yaml.getIntegerList("global.row-thresholds");
        thresholds = limits.size() == 5 && limits.get(0) > 0 && strictlyIncreasing(limits)
                ? List.copyOf(limits) : List.of(1, 5, 10, 15, 20);
        Map<String, QuestDefinition> found = new HashMap<>();
        daily = parsePool("daily.pool", logger, found);
        global = parsePool("global.pool", logger, found);
        byId = Map.copyOf(found);
        Set<Material> oreSet = EnumSet.noneOf(Material.class);
        for (String name : yaml.getStringList("ore-materials")) {
            Material material = Material.getMaterial(name);
            if (material != null && material.isBlock()) oreSet.add(material);
            else logger.warning("quests.yml: Ungültiges Erzmaterial " + name);
        }
        ores = Collections.unmodifiableSet(oreSet);
        if (daily.isEmpty()) logger.warning("quests.yml: Daily-Pool ist leer oder ungültig.");
        if (global.isEmpty()) logger.warning("quests.yml: Global-Pool ist leer oder ungültig.");
    }

    int rows(int online) {
        return rows(thresholds, online);
    }

    static int rows(List<Integer> thresholds, int online) {
        int rows = 0;
        for (int threshold : thresholds) if (online >= threshold) rows++;
        return rows;
    }

    private List<QuestDefinition> parsePool(String path, Logger logger, Map<String, QuestDefinition> found) {
        List<QuestDefinition> parsed = new ArrayList<>();
        int index = 0;
        for (Map<?, ?> raw : yaml.getMapList(path)) {
            index++;
            try {
                String id = required(raw, "id");
                QuestType type = QuestType.valueOf(required(raw, "type"));
                double target = Double.parseDouble(required(raw, "target"));
                int lumis = Integer.parseInt(required(raw, "lumis"));
                Material item = Material.valueOf(required(raw, "item"));
                String name = required(raw, "name");
                Material material = raw.containsKey("material") ? Material.valueOf(required(raw, "material")) : null;
                EntityType entity = raw.containsKey("entity") ? EntityType.valueOf(required(raw, "entity")) : null;
                if (!Double.isFinite(target) || target <= 0 || lumis < 0 || item.isAir()
                        || (type == QuestType.MATERIAL_BREAK && (material == null || !material.isBlock()))
                        || ((type == QuestType.ENTITY_KILL || type == QuestType.BOSS_KILL) && entity == null))
                    throw new IllegalArgumentException("Ziel, Reward, Item oder Target ungültig");
                QuestDefinition quest = new QuestDefinition(id, type, target, lumis, item, name, material, entity);
                QuestDefinition previous = found.putIfAbsent(id, quest);
                if (previous != null && !previous.equals(quest)) throw new IllegalArgumentException("ID mehrfach mit anderen Werten: " + id);
                parsed.add(quest);
            } catch (RuntimeException error) {
                logger.warning("quests.yml: Ungültige Definition " + path + "[" + index + "]: " + error.getMessage());
            }
        }
        return List.copyOf(parsed);
    }

    private static String required(Map<?, ?> raw, String key) {
        Object value = raw.get(key);
        if (value == null || value.toString().isBlank()) throw new IllegalArgumentException(key + " fehlt");
        return value.toString();
    }

    private static boolean strictlyIncreasing(List<Integer> values) {
        for (int i = 1; i < values.size(); i++) if (values.get(i) <= values.get(i - 1)) return false;
        return true;
    }
}
