package de.walahi.novosmp.combat;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** Rare-event YAML escrow with atomic replacement; normal PvP never touches disk. */
final class CombatLogStore {
    private final JavaPlugin plugin;
    private final File file;

    CombatLogStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "combat-loggers.yml");
    }

    Map<UUID, CombatLogRecord> load() {
        Map<UUID, CombatLogRecord> result = new LinkedHashMap<>();
        if (!file.isFile()) return result;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("players");
        if (root == null) return result;
        for (String rawId : root.getKeys(false)) {
            try {
                UUID id = UUID.fromString(rawId);
                ConfigurationSection section = root.getConfigurationSection(rawId);
                CombatLogRecord record = section == null ? null : read(id, section);
                if (record != null) result.put(id, record);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Ungültiger Combat-Logger-State für " + rawId + ": " + exception.getMessage());
            }
        }
        return result;
    }

    void save(Collection<CombatLogRecord> records) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", 1);
        for (CombatLogRecord record : records) write(yaml.createSection("players." + record.playerId), record);
        try {
            File parent = file.getParentFile();
            if (parent != null) Files.createDirectories(parent.toPath());
            File temporary = new File(parent, file.getName() + ".tmp");
            Files.writeString(temporary.toPath(), yaml.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Combat-Logger-State konnte nicht sicher gespeichert werden.", exception);
            throw new IllegalStateException("Combat-Logger-State konnte nicht gespeichert werden", exception);
        }
    }

    private void write(ConfigurationSection section, CombatLogRecord record) {
        section.set("name", record.playerName);
        section.set("created-at", record.createdAt);
        section.set("expires-at", record.expiresAt);
        section.set("phase", record.phase.name());
        section.set("location.world", record.worldName);
        section.set("location.x", record.x); section.set("location.y", record.y); section.set("location.z", record.z);
        section.set("location.yaw", record.yaw); section.set("location.pitch", record.pitch);
        section.set("health", record.health); section.set("max-health", record.maxHealth);
        section.set("absorption", record.absorption); section.set("food", record.food);
        section.set("saturation", record.saturation); section.set("exhaustion", record.exhaustion);
        section.set("fire-ticks", record.fireTicks); section.set("level", record.level);
        section.set("exp", record.exp); section.set("total-experience", record.totalExperience);
        section.set("held-slot", record.heldSlot);
        writeItems(section.createSection("inventory"), record.storage);
        writeItems(section.createSection("armor"), record.armor);
        writeItems(section.createSection("extra"), record.extra);
        section.set("cursor", record.cursor);
        ConfigurationSection effects = section.createSection("effects");
        for (int i = 0; i < record.effects.size(); i++) {
            CombatLogRecord.EffectState effect = record.effects.get(i);
            ConfigurationSection target = effects.createSection(Integer.toString(i));
            target.set("type", effect.type()); target.set("amplifier", effect.amplifier());
            target.set("expires-at", effect.expiresAt()); target.set("ambient", effect.ambient());
            target.set("particles", effect.particles()); target.set("icon", effect.icon());
        }
    }

    private CombatLogRecord read(UUID id, ConfigurationSection section) {
        String name = section.getString("name", id.toString());
        CombatLogRecord.Phase phase = CombatLogRecord.Phase.valueOf(section.getString("phase", "SURVIVED"));
        List<CombatLogRecord.EffectState> effects = readEffects(section.getConfigurationSection("effects"));
        return new CombatLogRecord(id, name, section.getLong("created-at"), section.getLong("expires-at"), phase,
                section.getString("location.world", "smp_world"), section.getDouble("location.x"),
                section.getDouble("location.y"), section.getDouble("location.z"),
                (float) section.getDouble("location.yaw"), (float) section.getDouble("location.pitch"),
                section.getDouble("health", 20D), section.getDouble("max-health", 20D),
                section.getDouble("absorption"), section.getInt("food", 20),
                (float) section.getDouble("saturation", 5D), (float) section.getDouble("exhaustion"),
                section.getInt("fire-ticks"), section.getInt("level"), (float) section.getDouble("exp"),
                section.getInt("total-experience"), section.getInt("held-slot"),
                readItems(section.getConfigurationSection("inventory")),
                readItems(section.getConfigurationSection("armor")), readItems(section.getConfigurationSection("extra")),
                section.getItemStack("cursor"), effects);
    }

    private void writeItems(ConfigurationSection section, ItemStack[] items) {
        section.set("size", items == null ? 0 : items.length);
        if (items == null) return;
        for (int i = 0; i < items.length; i++) if (items[i] != null) section.set("slots." + i, items[i]);
    }

    private ItemStack[] readItems(ConfigurationSection section) {
        if (section == null) return new ItemStack[0];
        ItemStack[] items = new ItemStack[Math.max(0, section.getInt("size"))];
        ConfigurationSection slots = section.getConfigurationSection("slots");
        if (slots == null) return items;
        for (String rawSlot : slots.getKeys(false)) {
            int slot = Integer.parseInt(rawSlot);
            if (slot >= 0 && slot < items.length) items[slot] = slots.getItemStack(rawSlot);
        }
        return items;
    }

    private List<CombatLogRecord.EffectState> readEffects(ConfigurationSection section) {
        if (section == null) return List.of();
        return section.getKeys(false).stream().sorted().map(key -> section.getConfigurationSection(key))
                .filter(java.util.Objects::nonNull)
                .map(effect -> new CombatLogRecord.EffectState(effect.getString("type", "minecraft:speed"),
                        effect.getInt("amplifier"), effect.getLong("expires-at"), effect.getBoolean("ambient"),
                        effect.getBoolean("particles", true), effect.getBoolean("icon", true))).toList();
    }
}
