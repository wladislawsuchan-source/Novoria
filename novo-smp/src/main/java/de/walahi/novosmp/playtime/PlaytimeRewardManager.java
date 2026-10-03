package de.walahi.novosmp.playtime;

import de.walahi.novosmp.crates.CrateDefinition;
import de.walahi.novosmp.crates.CrateManager;
import de.walahi.smpcore.stats.StatsAccess;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.StatType;
import de.walahi.smpcore.api.event.ActionContext;
import de.walahi.smpcore.economy.EconomyOperationResult;
import de.walahi.smpcore.services.EconomyService;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PlaytimeRewardManager {
    private final SMPCorePlugin plugin;
    private final PlaytimeRewardRepository repository;
    private final StatsAccess stats;
    private final EconomyService economy;
    private final CrateManager crates;
    private final NumberFormat numbers = NumberFormat.getIntegerInstance(Locale.GERMANY);
    private final Map<Integer, PlaytimeMilestone> milestones = new LinkedHashMap<>();
    private String crateId = "playtime";
    private boolean enabled = true;

    public PlaytimeRewardManager(SMPCorePlugin plugin, PlaytimeRewardRepository repository,
                                 StatsAccess stats, EconomyService economy, CrateManager crates) {
        this.plugin = plugin;
        this.repository = repository;
        this.stats = stats;
        this.economy = economy;
        this.crates = crates;
        reload();
    }

    public void reload() {
        plugin.configs().playtimeFile().reload();
        var config = plugin.configs().playtime();
        enabled = config.getBoolean("enabled", true);
        crateId = config.getString("crate-id", "playtime").trim().toLowerCase(Locale.ROOT);
        milestones.clear();
        ConfigurationSection root = config.getConfigurationSection("milestones");
        if (root != null) {
            for (String rawId : root.getKeys(false)) {
                try {
                    int id = Integer.parseInt(rawId);
                    long hours = Math.max(1L, root.getLong(rawId + ".hours", 1L));
                    long coins = Math.max(0L, root.getLong(rawId + ".coins", 0L));
                    int xp = Math.max(0, root.getInt(rawId + ".xp", 0));
                    milestones.put(id, new PlaytimeMilestone(id, hours, coins, xp));
                } catch (NumberFormatException ignored) {
                    plugin.getLogger().warning("Ungültige Spielzeit-Meilenstein-ID: " + rawId);
                }
            }
        }
        List<PlaytimeMilestone> sorted = new ArrayList<>(milestones.values());
        sorted.sort(Comparator.comparingInt(PlaytimeMilestone::id));
        milestones.clear();
        for (PlaytimeMilestone milestone : sorted) milestones.put(milestone.id(), milestone);
        if (milestones.size() > 56) {
            plugin.getLogger().warning("/spielzeit zeigt maximal 56 Meilensteine auf zwei Seiten; konfiguriert: " + milestones.size());
        }
    }

    public boolean enabled() { return enabled; }
    public List<PlaytimeMilestone> milestones() { return List.copyOf(milestones.values()); }
    public PlaytimeMilestone milestone(int id) { return milestones.get(id); }
    public long playtimeSeconds(UUID playerId) { return stats.getStat(playerId, StatType.PLAYTIME); }

    public Set<Integer> claimed(UUID playerId) {
        try {
            return repository.claimed(playerId);
        } catch (SQLException exception) {
            plugin.getLogger().warning("Spielzeit-Claims konnten für " + playerId + " nicht geladen werden: " + exception.getMessage());
            return Set.of();
        }
    }

    public ClaimResult claim(Player player, PlaytimeMilestone milestone) {
        if (!enabled || player == null || milestone == null) return ClaimResult.DISABLED;
        if (playtimeSeconds(player.getUniqueId()) < milestone.requiredSeconds()) return ClaimResult.NOT_READY;

        CrateDefinition playtimeCrate = crates.find(crateId).orElse(null);
        if (playtimeCrate == null) {
            plugin.getLogger().warning("Spielzeit-Belohnung verweist auf unbekannte Crate '" + crateId + "'.");
            return ClaimResult.CONFIG_ERROR;
        }

        try {
            if (!repository.reserve(player.getUniqueId(), milestone.id())) return ClaimResult.ALREADY_CLAIMED;
        } catch (SQLException exception) {
            plugin.getLogger().warning("Spielzeit-Claim konnte nicht reserviert werden: " + exception.getMessage());
            return ClaimResult.STORAGE_ERROR;
        }

        if (milestone.coins() > 0L) {
            EconomyOperationResult result = economy.deposit(player.getUniqueId(), milestone.coins(),
                    "Playtime milestone " + milestone.id(), ActionContext.system(player.getUniqueId()));
            if (result != EconomyOperationResult.SUCCESS) {
                release(player.getUniqueId(), milestone.id());
                return ClaimResult.STORAGE_ERROR;
            }
        }

        crates.keys().add(player, playtimeCrate, 1);
        if (milestone.xp() > 0) player.giveExp(milestone.xp());
        return ClaimResult.SUCCESS;
    }

    private void release(UUID playerId, int milestoneId) {
        try {
            repository.release(playerId, milestoneId);
        } catch (SQLException exception) {
            plugin.getLogger().severe("Fehlgeschlagener Spielzeit-Claim konnte nicht entsperrt werden: " + exception.getMessage());
        }
    }

    public String format(long value) { return numbers.format(value); }

    public enum ClaimResult {
        SUCCESS, NOT_READY, ALREADY_CLAIMED, STORAGE_ERROR, CONFIG_ERROR, DISABLED
    }
}
