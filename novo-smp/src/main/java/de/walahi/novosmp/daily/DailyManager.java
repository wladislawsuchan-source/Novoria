package de.walahi.novosmp.daily;

import de.walahi.smpcore.SMPCorePlugin;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class DailyManager {
    private final SMPCorePlugin plugin;
    private final DailyRepository repository;
    private final Map<Integer, DailyReward> rewards = new LinkedHashMap<>();
    private ZoneId zoneId = ZoneId.of("Europe/Berlin");

    public DailyManager(SMPCorePlugin plugin, DailyRepository repository) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.repository = Objects.requireNonNull(repository, "repository");
        reload();
    }

    public void reload() {
        plugin.configs().dailyFile().reload();
        org.bukkit.configuration.file.FileConfiguration config = plugin.configs().daily();
        String configuredZone = config.getString("timezone", "Europe/Berlin");
        try {
            zoneId = ZoneId.of(configuredZone);
        } catch (ZoneRulesException exception) {
            zoneId = ZoneId.of("Europe/Berlin");
            plugin.getLogger().warning("Ungültige Daily-Zeitzone '" + configuredZone + "'. Europe/Berlin wird verwendet.");
        }

        rewards.clear();
        ConfigurationSection days = config.getConfigurationSection("days");
        for (int day = 1; day <= 7; day++) {
            String path = Integer.toString(day);
            ConfigurationSection section = days == null ? null : days.getConfigurationSection(path);
            Material material = Material.matchMaterial(section == null
                    ? "CHEST" : section.getString("material", "CHEST"));
            int slot = section == null ? 9 + day : section.getInt("slot", 9 + day);
            DailyRewardTier base = loadTier(section);
            DailyRewardTier premium = loadTier(section == null ? null : section.getConfigurationSection("premium"));
            DailyRewardTier premiumPlus = loadTier(section == null ? null : section.getConfigurationSection("premium-plus"));
            rewards.put(day, new DailyReward(day, slot, material, base, premium, premiumPlus));
        }
    }

    private DailyRewardTier loadTier(ConfigurationSection section) {
        if (section == null) return DailyRewardTier.EMPTY;
        long coins = Math.max(0L, section.getLong("coins", 0L));
        long lumis = Math.max(0L, section.getLong("lumis", 0L));
        Map<String, Integer> keys = new LinkedHashMap<>();
        ConfigurationSection keySection = section.getConfigurationSection("keys");
        if (keySection != null) {
            for (String crateId : keySection.getKeys(false)) {
                int amount = Math.max(0, keySection.getInt(crateId, 0));
                if (amount > 0) keys.merge(crateId.toLowerCase(Locale.ROOT), amount, Integer::sum);
            }
        }

        List<String> externalCommands = new ArrayList<>();
        for (String command : section.getStringList("commands")) {
            KeyCommand keyCommand = parseKeyCommand(command);
            if (keyCommand == null) externalCommands.add(command);
            else keys.merge(keyCommand.crateId(), keyCommand.amount(), Integer::sum);
        }
        return new DailyRewardTier(coins, lumis, keys, externalCommands);
    }

    /** Keeps old daily.yml files safe without dispatching their crate commands through the console. */
    private KeyCommand parseKeyCommand(String rawCommand) {
        if (rawCommand == null) return null;
        String command = rawCommand.trim();
        if (command.startsWith("/")) command = command.substring(1);
        String[] parts = command.split("\\s+");
        if (parts.length != 5 || !parts[0].equalsIgnoreCase("crate")
                || !parts[1].equalsIgnoreCase("givekey")) return null;

        String crateId;
        if (parts[2].equalsIgnoreCase("%player%")) crateId = parts[3];
        else if (parts[3].equalsIgnoreCase("%player%")) crateId = parts[2];
        else return null;
        try {
            int amount = Integer.parseInt(parts[4]);
            return amount > 0 && !crateId.isBlank()
                    ? new KeyCommand(crateId.toLowerCase(Locale.ROOT), amount) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public DailyProgress progress(UUID uuid) {
        try {
            return repository.load(uuid);
        } catch (SQLException exception) {
            throw new IllegalStateException("Daily-Fortschritt konnte nicht geladen werden", exception);
        }
    }

    public Map<UUID, Long> totalClaims(Collection<UUID> playerIds) {
        try {
            return repository.totalClaims(playerIds);
        } catch (SQLException exception) {
            throw new IllegalStateException("Daily-Fortschritte konnten nicht geladen werden", exception);
        }
    }

    public DailyClaimResult markClaimed(UUID uuid, DailyRewardType type, int requiredMask) {
        try {
            return repository.claim(uuid, today(), type, requiredMask);
        } catch (SQLException exception) {
            plugin.getLogger().severe("Daily konnte nicht gespeichert werden: " + exception.getMessage());
            return DailyClaimResult.STORAGE_ERROR;
        }
    }

    public boolean canClaim(UUID uuid) {
        return !progress(uuid).claimedOn(today());
    }

    public DailyReward currentReward(UUID uuid) {
        return reward(progress(uuid).currentDay());
    }

    public DailyReward reward(int day) {
        return rewards.getOrDefault(day, rewards.get(1));
    }

    public Map<Integer, DailyReward> rewards() {
        return Collections.unmodifiableMap(rewards);
    }

    public LocalDate today() {
        return LocalDate.now(zoneId);
    }

    public ZoneId zoneId() {
        return zoneId;
    }

    public void restore(DailyProgress progress) throws SQLException {
        repository.restore(progress);
    }

    public void reset(UUID uuid) throws SQLException {
        repository.reset(uuid);
    }

    public void setDay(UUID uuid, int day) throws SQLException {
        repository.setDay(uuid, day);
    }

    private record KeyCommand(String crateId, int amount) { }
}
