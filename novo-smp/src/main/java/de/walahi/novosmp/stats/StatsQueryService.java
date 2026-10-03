package de.walahi.novosmp.stats;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.StatType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Read-only queries and ranking rules over the in-memory statistics cache. */
final class StatsQueryService {
    private final NovoSMPPlugin plugin;
    private final StatsCache cache;

    StatsQueryService(NovoSMPPlugin plugin, StatsCache cache) {
        this.plugin = plugin;
        this.cache = cache;
    }

    List<StatsSnapshot> visibleEntries() {
        List<StatsSnapshot> entries = new ArrayList<>(cache.snapshots());
        long minimumPlaytimeSeconds = Math.max(1L,
                plugin.configs().menus().getLong("leaderboards.minimum-playtime-seconds", 60L));
        Set<String> hidden = hiddenPlayers();
        entries.removeIf(entry -> entry.playtimeSeconds() < minimumPlaytimeSeconds
                || hidden.contains(entry.uuid().toString().toLowerCase(Locale.ROOT))
                || hidden.contains(entry.name().toLowerCase(Locale.ROOT)));
        return entries;
    }

    StatsSnapshot findByName(String playerName) {
        if (playerName == null || playerName.isBlank()) return null;
        for (StatsSnapshot entry : visibleEntries()) {
            if (entry.name().equalsIgnoreCase(playerName)) return entry;
        }
        return null;
    }

    List<String> registeredPlayerNames(String prefix) {
        String lowerPrefix = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return visibleEntries().stream()
                .map(StatsSnapshot::name)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(lowerPrefix))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    int rank(UUID uuid, StatType type) {
        if (uuid == null || type == null) return 0;
        return rank(uuid, type.path());
    }

    /** Rank for any data source referenced by menus.yml. */
    int rank(UUID uuid, String statPath) {
        if (uuid == null || statPath == null || statPath.isBlank()) return 0;
        return values(visibleEntries(), statPath, null).rank(uuid);
    }

    int coinRank(UUID uuid) {
        return rank(uuid, "coins");
    }

    int lumiRank(UUID uuid) {
        return rank(uuid, "lumis");
    }

    /** One request-local value set for ranking, sorting and rendering the same menu. */
    Values values(List<StatsSnapshot> entries, String statPath, StatsSnapshot additionalEntry) {
        String path = normalizePath(statPath);
        List<StatsSnapshot> requested = new ArrayList<>(entries);
        if (additionalEntry != null && requested.stream().noneMatch(
                entry -> entry.uuid().equals(additionalEntry.uuid()))) {
            requested.add(additionalEntry);
        }
        List<UUID> ids = requested.stream().map(StatsSnapshot::uuid).toList();
        Map<UUID, Long> loaded;
        switch (path) {
            case "coins" -> loaded = plugin.services() == null || plugin.services().economy() == null
                    ? Map.of() : plugin.services().economy().balances(ids);
            case "lumis" -> {
                try {
                    loaded = plugin.lumiBalances(ids);
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning("Lumi-Kontostände konnten nicht geladen werden: "
                            + exception.getMessage());
                    loaded = Map.of();
                }
            }
            case "active-days" -> {
                try {
                    loaded = plugin.dailyManager() == null ? Map.of() : plugin.dailyManager().totalClaims(ids);
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning("Aktive Tage konnten nicht geladen werden: "
                            + exception.getMessage());
                    loaded = Map.of();
                }
            }
            default -> {
                Map<UUID, Long> fromMemory = new HashMap<>(requested.size());
                for (StatsSnapshot entry : requested) fromMemory.put(entry.uuid(), entry.value(path));
                loaded = fromMemory;
            }
        }
        return new Values(entries, loaded);
    }

    static String normalizePath(String statPath) {
        return statPath == null ? "" : statPath.trim().toLowerCase(Locale.ROOT);
    }

    record Values(List<StatsSnapshot> entries, Map<UUID, Long> byPlayer) {
        Values {
            entries = List.copyOf(entries);
            byPlayer = Map.copyOf(byPlayer);
        }

        long value(UUID uuid) {
            return byPlayer.getOrDefault(uuid, 0L);
        }

        int rank(UUID uuid) {
            if (uuid == null) return 0;
            StatsSnapshot target = null;
            for (StatsSnapshot entry : entries) {
                if (entry.uuid().equals(uuid)) {
                    target = entry;
                    break;
                }
            }
            if (target == null) return 0;

            long targetValue = value(uuid);
            int rank = 1;
            for (StatsSnapshot entry : entries) {
                if (entry.uuid().equals(uuid)) continue;
                long otherValue = value(entry.uuid());
                if (otherValue > targetValue || (otherValue == targetValue
                        && String.CASE_INSENSITIVE_ORDER.compare(entry.name(), target.name()) < 0)) {
                    rank++;
                }
            }
            return rank;
        }
    }

    private Set<String> hiddenPlayers() {
        Set<String> hidden = new HashSet<>();
        for (String configured : plugin.configs().menus().getStringList("leaderboards.hidden-players")) {
            if (configured != null && !configured.isBlank()) {
                hidden.add(configured.toLowerCase(Locale.ROOT));
            }
        }
        return hidden;
    }
}
