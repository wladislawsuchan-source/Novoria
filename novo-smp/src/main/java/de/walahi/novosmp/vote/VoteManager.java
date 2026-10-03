package de.walahi.novosmp.vote;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.novosmp.crates.CrateDefinition;
import de.walahi.novosmp.crates.CrateManager;
import de.walahi.smpcore.api.event.ActionContext;
import de.walahi.smpcore.economy.EconomyOperationResult;
import de.walahi.smpcore.services.EconomyService;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Receives NuVotifier events and pays Novoria's native vote rewards. */
public final class VoteManager implements Listener {
    private final NovoSMPPlugin plugin;
    private final VoteRepository repository;
    private final EconomyService economy;
    private final CrateManager crates;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Listener votifierBridgeListener = new Listener() { };
    private final Map<UUID, BukkitTask> reminderTasks = new HashMap<>();
    private boolean votifierBridgeActive;

    public VoteManager(NovoSMPPlugin plugin, EconomyService economy, CrateManager crates) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.repository = new VoteRepository(plugin.sharedDb());
        this.economy = Objects.requireNonNull(economy, "economy");
        this.crates = Objects.requireNonNull(crates, "crates");

        Bukkit.getPluginManager().registerEvents(this, plugin);
        registerVotifierBridge();
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getOnlinePlayers().forEach(player -> {
            claimPending(player);
            scheduleReminderChecks(player);
        }));
    }

    public boolean votifierBridgeActive() {
        return votifierBridgeActive;
    }

    public long totalVotes(UUID playerId) {
        if (playerId == null) return 0L;
        try {
            return repository.totalVotes(playerId);
        } catch (SQLException exception) {
            plugin.getLogger().warning("Vote-Anzahl konnte nicht geladen werden: " + exception.getMessage());
            return 0L;
        }
    }

    public Optional<UUID> knownPlayerId(String playerName) {
        Player online = findOnline(playerName);
        return online == null ? findKnownUuid(playerName) : Optional.of(online.getUniqueId());
    }

    /** Admin correction only: synthetic additions never pay the normal per-vote reward. */
    public synchronized VoteAdminResult adjustTotal(UUID playerId, String playerName,
                                                     VoteAdminAction action, long amount) {
        if (playerId == null || playerName == null || playerName.isBlank() || amount < 0L) {
            return VoteAdminResult.failure("Ungültige Eingabe.");
        }
        try {
            long before = repository.totalVotes(playerId);
            long target = switch (action) {
                case GET -> before;
                case SET -> amount;
                case ADD -> Math.addExact(before, amount);
                case REMOVE -> Math.max(0L, before - amount);
            };
            if (target > 1_000_000L) return VoteAdminResult.failure("Maximal 1.000.000 Votes sind erlaubt.");
            long after = action == VoteAdminAction.GET
                    ? before
                    : repository.setTotalVotes(playerId, playerName, target);
            VoteMilestoneConfig milestone = milestoneConfig();
            return new VoteAdminResult(true, before, after, milestone.interval(), null);
        } catch (ArithmeticException exception) {
            return VoteAdminResult.failure("Die Vote-Anzahl ist zu groß.");
        } catch (SQLException exception) {
            plugin.getLogger().severe("Vote-Adminänderung für " + playerName + " fehlgeschlagen: " + exception.getMessage());
            return VoteAdminResult.failure("Die Vote-Datenbank konnte nicht geändert werden.");
        }
    }

    public List<String> serviceNamesSince(UUID playerId, long since) {
        if (playerId == null) return List.of();
        try {
            return repository.serviceNamesSince(playerId, since);
        } catch (SQLException exception) {
            plugin.getLogger().warning("Heutige Vote-Seiten konnten nicht geladen werden: " + exception.getMessage());
            return List.of();
        }
    }

    public void claimPendingRewards(Player player) {
        if (player == null || !player.isOnline()) return;
        claimPending(player);
    }

    public VoteRewardOverview rewardOverview() {
        VoteRewardConfig rewards = rewardConfig();
        return new VoteRewardOverview(rewards.coins(), rewards.keyAmount(), keyDisplayName(rewards));
    }

    public VoteProgress progress(UUID playerId) {
        VoteMilestoneConfig milestone = milestoneConfig();
        if (playerId == null) return VoteProgress.empty(milestone.interval(), milestone.keyAmount(), keyDisplayName(milestone.keyCrateId()));
        try {
            long total = repository.totalVotes(playerId);
            long rewarded = repository.milestonesRewarded(playerId);
            long earned = milestone.enabled() ? total / milestone.interval() : 0L;
            long pending = Math.max(0L, earned - rewarded);
            long intoCurrent = milestone.enabled() ? total % milestone.interval() : 0L;
            long untilNext = milestone.enabled() ? milestone.interval() - intoCurrent : 0L;
            long nextAt = milestone.enabled() ? total - intoCurrent + milestone.interval() : 0L;
            return new VoteProgress(total, milestone.interval(), intoCurrent, untilNext, nextAt,
                    earned, rewarded, pending, milestone.keyAmount(), keyDisplayName(milestone.keyCrateId()));
        } catch (SQLException exception) {
            plugin.getLogger().warning("Vote-Fortschritt konnte nicht geladen werden: " + exception.getMessage());
            return VoteProgress.empty(milestone.interval(), milestone.keyAmount(), keyDisplayName(milestone.keyCrateId()));
        }
    }

    public void shutdown() {
        HandlerList.unregisterAll(this);
        HandlerList.unregisterAll(votifierBridgeListener);
        reminderTasks.values().forEach(BukkitTask::cancel);
        reminderTasks.clear();
        votifierBridgeActive = false;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            claimPending(player);
            scheduleReminderChecks(player);
        }, 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        cancelReminderChecks(event.getPlayer().getUniqueId());
    }

    private void scheduleReminderChecks(Player player) {
        cancelReminderChecks(player.getUniqueId());
        VoteReminderConfig config = reminderConfig();
        if (!config.enabled()) return;

        long firstDelay = Math.max(1L, config.joinDelaySeconds() * 20L);
        long period = Math.max(20L, config.intervalMinutes() * 60L * 20L);
        UUID playerId = player.getUniqueId();
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Player online = Bukkit.getPlayer(playerId);
            if (online == null || !online.isOnline()) {
                cancelReminderChecks(playerId);
                return;
            }
            remindIfNeeded(online);
        }, firstDelay, period);
        reminderTasks.put(playerId, task);
    }

    private void cancelReminderChecks(UUID playerId) {
        BukkitTask existing = reminderTasks.remove(playerId);
        if (existing != null) existing.cancel();
    }

    private void remindIfNeeded(Player player) {
        VoteReminderConfig config = reminderConfig();
        if (!config.enabled() || player == null || !player.isOnline()) return;
        long todayStart = startOfTodayMillis();
        try {
            if (repository.voteCountSince(player.getUniqueId(), todayStart) > 0L) return;
        } catch (SQLException exception) {
            plugin.getLogger().warning("Vote-Erinnerung für " + player.getName() +
                    " konnte nicht geprüft werden: " + exception.getMessage());
            return;
        }

        Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put("%player%", miniMessage.escapeTags(player.getName()));
        values.put("%command%", "/vote");
        sendConfiguredMessage(player, "reminders.message",
                "<dark_gray>[</dark_gray><light_purple>VOTE</light_purple><dark_gray>]</dark_gray> " +
                        "<gray>Du hast heute noch nicht gevotet! " +
                        "<light_purple><click:run_command:'/vote'><underlined>Klicke hier</underlined></click></light_purple> " +
                        "<gray>oder nutze <light_purple>/vote</light_purple>.</gray>",
                values);
    }

    private long startOfTodayMillis() {
        String configured = plugin.configs().vote().getString("menu.time-zone", "Europe/Berlin");
        ZoneId zone;
        try {
            zone = ZoneId.of(configured == null || configured.isBlank() ? "Europe/Berlin" : configured.trim());
        } catch (DateTimeException exception) {
            zone = ZoneId.of("Europe/Berlin");
        }
        return ZonedDateTime.now(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli();
    }

    private void registerVotifierBridge() {
        Plugin votifier = Bukkit.getPluginManager().getPlugin("Votifier");
        if (votifier == null || !votifier.isEnabled()) {
            plugin.getLogger().warning("Vote-System bereit, aber NuVotifier/Votifier ist auf dem SMP nicht aktiv. " +
                    "Votes werden erst verarbeitet, sobald das Plugin vorhanden ist.");
            return;
        }

        try {
            ClassLoader votifierLoader = votifier.getClass().getClassLoader();
            Class<?> rawEventClass = Class.forName(
                    "com.vexsoftware.votifier.model.VotifierEvent", true, votifierLoader);
            if (!Event.class.isAssignableFrom(rawEventClass)) {
                throw new IllegalStateException("VotifierEvent ist kein Bukkit-Event.");
            }
            @SuppressWarnings("unchecked")
            Class<? extends Event> eventClass = (Class<? extends Event>) rawEventClass;
            Method getVote = rawEventClass.getMethod("getVote");

            Bukkit.getPluginManager().registerEvent(
                    eventClass,
                    votifierBridgeListener,
                    EventPriority.NORMAL,
                    (listener, event) -> receiveReflectiveVote(event, getVote),
                    plugin,
                    false
            );
            votifierBridgeActive = true;
            VoteRewardConfig rewards = rewardConfig();
            VoteMilestoneConfig milestone = milestoneConfig();
            plugin.getLogger().info("Vote-System aktiv: NuVotifier-Listener registriert | Pro Vote: " +
                    plainRewardSummary(rewards) + (milestone.enabled()
                    ? " | Meilenstein: alle " + milestone.interval() + " Votes " + milestone.keyAmount() + "x " + keyDisplayName(milestone.keyCrateId())
                    : " | Meilensteine deaktiviert") + ".");
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().severe("NuVotifier konnte nicht an das Vote-System angebunden werden: " +
                    exception.getMessage());
        }
    }

    private void receiveReflectiveVote(Event event, Method getVote) {
        try {
            Object vote = getVote.invoke(event);
            if (vote == null) return;
            Method getServiceName = vote.getClass().getMethod("getServiceName");
            Method getUsername = vote.getClass().getMethod("getUsername");
            Method getAddress = vote.getClass().getMethod("getAddress");
            Method getTimeStamp = vote.getClass().getMethod("getTimeStamp");

            VotePayload payload = VotePayload.create(
                    stringValue(getServiceName.invoke(vote)),
                    stringValue(getUsername.invoke(vote)),
                    stringValue(getAddress.invoke(vote)),
                    stringValue(getTimeStamp.invoke(vote)),
                    System.currentTimeMillis()
            );
            if (!payload.valid()) {
                plugin.getLogger().warning("Vote ohne gültigen Spielernamen ignoriert (Service: " +
                        payload.serviceName() + ").");
                return;
            }

            Runnable process = () -> receive(payload);
            if (event.isAsynchronous()) Bukkit.getScheduler().runTask(plugin, process);
            else process.run();
        } catch (ReflectiveOperationException exception) {
            Throwable cause = exception instanceof InvocationTargetException invocation && invocation.getCause() != null
                    ? invocation.getCause() : exception;
            plugin.getLogger().warning("Vote-Payload konnte nicht gelesen werden: " + cause.getMessage());
        }
    }

    private void receive(VotePayload vote) {
        Player online = findOnline(vote.playerName());
        UUID knownPlayer = online == null ? findKnownUuid(vote.playerName()).orElse(null) : online.getUniqueId();
        VoteSiteMatch matchedSite = configuredVoteSites().stream()
                .filter(site -> site.matchesService(vote.serviceName()))
                .findFirst()
                .orElse(null);

        // NuVotifier can deliver the same site repeatedly (for example TopG test/retry votes).
        // One configured server list therefore counts and rewards at most once per player/day.
        if (matchedSite != null) {
            try {
                List<String> today = knownPlayer == null
                        ? repository.serviceNamesSince(vote.playerName(), startOfTodayMillis())
                        : repository.serviceNamesSince(knownPlayer, startOfTodayMillis());
                if (today.stream().anyMatch(matchedSite::matchesService)) {
                    plugin.getLogger().info("Vote bereits heute gewertet und ignoriert: " + vote.playerName()
                            + " / " + matchedSite.name() + " (gemeldet als " + vote.serviceName() + ")");
                    return;
                }
            } catch (SQLException exception) {
                plugin.getLogger().severe("Tages-Duplikatprüfung für Vote fehlgeschlagen; Vote wurde sicherheitshalber nicht gewertet: "
                        + exception.getMessage());
                return;
            }
        }
        DailyCompletionState completionBefore = DailyCompletionState.empty();
        if (knownPlayer != null && plugin.configs().vote().getBoolean("daily-completion.enabled", true)) {
            try {
                completionBefore = dailyCompletionState(knownPlayer);
            } catch (SQLException exception) {
                plugin.getLogger().warning("Vote-Tagesfortschritt vor dem neuen Vote konnte nicht geprüft werden: "
                        + exception.getMessage());
            }
        }

        try {
            if (!repository.insert(vote, knownPlayer)) {
                plugin.getLogger().info("Doppelter Vote ignoriert: " + vote.serviceName() + " / " + vote.playerName());
                return;
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Vote konnte nicht gespeichert werden: " + exception.getMessage());
            return;
        }

        plugin.getLogger().info("Vote empfangen: " + vote.playerName() + " über " + vote.serviceName()
                + (online == null ? " (Belohnung vorgemerkt)" : ""));
        announceDailyCompletionIfReached(knownPlayer, vote.playerName(), completionBefore);
        if (online != null) claimPending(online);
    }

    private void announceDailyCompletionIfReached(UUID playerId, String playerName, DailyCompletionState before) {
        if (playerId == null || !plugin.configs().vote().getBoolean("daily-completion.enabled", true)) return;
        try {
            DailyCompletionState after = dailyCompletionState(playerId);
            if (after.target() <= 0 || before.completed() >= after.target() || after.completed() < after.target()) return;

            String template = plugin.configs().vote().getString("daily-completion.message",
                    "<dark_gray>[</dark_gray><light_purple>VOTE</light_purple><dark_gray>]</dark_gray> " +
                            "<light_purple>%player%</light_purple> <gray>hat heute alle <white>%site_count% Votes</white> gemacht!</gray>");
            if (template == null || template.isBlank()) return;

            Map<String, String> values = new java.util.LinkedHashMap<>();
            values.put("%player%", miniMessage.escapeTags(playerName == null ? "Unbekannt" : playerName));
            values.put("%votes%", String.valueOf(after.completed()));
            values.put("%site_count%", String.valueOf(after.target()));
            var component = miniMessage.deserialize(replacePlaceholders(template, values));
            Bukkit.getOnlinePlayers().forEach(viewer -> viewer.sendMessage(component));
        } catch (SQLException exception) {
            plugin.getLogger().warning("Vote-Tagesabschluss für " + playerName + " konnte nicht geprüft werden: "
                    + exception.getMessage());
        }
    }

    private DailyCompletionState dailyCompletionState(UUID playerId) throws SQLException {
        List<VoteSiteMatch> sites = configuredVoteSites();
        if (sites.isEmpty()) return DailyCompletionState.empty();
        List<String> received = repository.serviceNamesSince(playerId, startOfTodayMillis());
        int completed = 0;
        for (VoteSiteMatch site : sites) {
            if (received.stream().anyMatch(site::matchesService)) completed++;
        }
        return new DailyCompletionState(completed, sites.size());
    }

    private List<VoteSiteMatch> configuredVoteSites() {
        ConfigurationSection root = plugin.configs().vote().getConfigurationSection("sites");
        if (root == null) return List.of();
        List<VoteSiteMatch> result = new java.util.ArrayList<>();
        for (String id : root.getKeys(false)) {
            if (result.size() >= 7) break;
            ConfigurationSection site = root.getConfigurationSection(id);
            if (site == null || !site.getBoolean("enabled", true)) continue;
            String name = site.getString("name", id);
            List<String> aliases = new java.util.ArrayList<>(site.getStringList("service-names"));
            if (aliases.isEmpty()) aliases.add(name == null ? id : name);
            result.add(new VoteSiteMatch(id, name == null ? id : name, aliases));
        }
        return result;
    }

    private static String normalizeService(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private Optional<UUID> findKnownUuid(String playerName) {
        try {
            return repository.findKnownUuid(playerName);
        } catch (SQLException exception) {
            plugin.getLogger().warning("Spieler konnte für Vote nicht aus der Datenbank aufgelöst werden: " +
                    exception.getMessage());
            return Optional.empty();
        }
    }

    private Player findOnline(String playerName) {
        if (playerName == null || playerName.isBlank()) return null;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().equalsIgnoreCase(playerName)) return player;
        }
        return null;
    }

    private void claimPending(Player player) {
        try {
            repository.attachPendingByName(player.getUniqueId(), player.getName());
            var pending = repository.pending(player.getUniqueId());

            VoteRewardConfig rewards = rewardConfig();
            String keyDisplayName = keyDisplayName(rewards);
            long coinsPaid = 0L;
            int keysPaid = 0;
            boolean keyBlockedByInventory = false;
            boolean keyUnavailable = false;

            for (VoteRepository.VoteRewardRecord reward : pending) {
                if (!reward.coinsRewarded()) {
                    if (rewards.coins() <= 0L) {
                        repository.markCoinsRewarded(reward.voteId(), System.currentTimeMillis());
                    } else {
                        EconomyOperationResult result = economy.deposit(
                                player.getUniqueId(),
                                rewards.coins(),
                                "Vote-Belohnung " + reward.voteId(),
                                ActionContext.system(player.getUniqueId())
                        );
                        if (result == EconomyOperationResult.SUCCESS) {
                            repository.markCoinsRewarded(reward.voteId(), System.currentTimeMillis());
                            coinsPaid += rewards.coins();
                        } else {
                            plugin.getLogger().warning("Vote-Coins für " + player.getName() + " konnten nicht ausgezahlt werden: " + result);
                        }
                    }
                }

                if (!reward.keyRewarded()) {
                    if (rewards.keyAmount() <= 0) {
                        repository.markKeyRewarded(reward.voteId(), System.currentTimeMillis());
                    } else {
                        KeyGrantResult result = giveKey(player, rewards.keyCrateId(), rewards.keyAmount(), "Vote-Key");
                        if (result == KeyGrantResult.SUCCESS) {
                            repository.markKeyRewarded(reward.voteId(), System.currentTimeMillis());
                            keysPaid += rewards.keyAmount();
                        } else if (result == KeyGrantResult.INVENTORY_FULL) {
                            keyBlockedByInventory = true;
                        } else {
                            keyUnavailable = true;
                        }
                    }
                }
            }

            MilestoneClaim milestoneClaim = claimMilestones(player);

            Map<String, String> messageValues = new java.util.LinkedHashMap<>();
            messageValues.put("%player%", miniMessage.escapeTags(player.getName()));
            messageValues.put("%coins%", formatCoins(coinsPaid));
            messageValues.put("%key_amount%", String.valueOf(keysPaid));
            messageValues.put("%key_name%", miniMessage.escapeTags(keyDisplayName));
            messageValues.put("%reward_summary%", rewardSummary(coinsPaid, keysPaid, keyDisplayName));
            messageValues.put("%milestone_key_amount%", String.valueOf(milestoneClaim.keysPaid()));
            messageValues.put("%milestone_key_name%", miniMessage.escapeTags(milestoneClaim.keyDisplayName()));

            if (coinsPaid > 0L || keysPaid > 0) {
                sendConfiguredMessage(player, "messages.reward-paid",
                        "<green><bold>✔ Danke fürs Voten!</bold></green> <gray>Du hast %reward_summary%<gray> erhalten.</gray>",
                        messageValues);
            }
            if (keyBlockedByInventory) {
                sendConfiguredMessage(player, "messages.key-inventory-full",
                        "<yellow>Deine ausstehende Key-Belohnung (%key_name%) konnte nicht ausgezahlt werden, weil dein Inventar voll ist. Sie wird beim nächsten Join erneut versucht.</yellow>",
                        messageValues);
            }
            if (keyUnavailable) {
                sendConfiguredMessage(player, "messages.key-unavailable",
                        "<yellow>Deine Key-Belohnung (%key_name%) ist gespeichert, konnte aber gerade nicht erzeugt werden. Die Belohnung bleibt ausstehend.</yellow>",
                        messageValues);
            }
            if (milestoneClaim.keysPaid() > 0) {
                sendConfiguredMessage(player, "messages.milestone-paid",
                        "<light_purple><bold>★ Vote-Meilenstein!</bold></light_purple> <gray>Du hast <gold>%milestone_key_amount%x %milestone_key_name%</gold> für deine Votes erhalten.</gray>",
                        messageValues);
            }
            if (milestoneClaim.blockedByInventory()) {
                sendConfiguredMessage(player, "messages.milestone-inventory-full",
                        "<yellow>Dein ausstehender Vote-Meilenstein (%milestone_key_name%) konnte nicht ausgezahlt werden, weil dein Inventar voll ist. Er bleibt gespeichert.</yellow>",
                        messageValues);
            }
            if (milestoneClaim.unavailable()) {
                sendConfiguredMessage(player, "messages.milestone-unavailable",
                        "<yellow>Dein Vote-Meilenstein ist erreicht, aber der konfigurierte Key konnte gerade nicht erzeugt werden. Die Belohnung bleibt ausstehend.</yellow>",
                        messageValues);
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Ausstehende Vote-Belohnungen für " + player.getName() +
                    " konnten nicht verarbeitet werden: " + exception.getMessage());
        }
    }

    private KeyGrantResult giveKey(Player player, String crateId, int amount, String rewardLabel) {
        CrateDefinition crate = crates.find(crateId).orElse(null);
        if (crate == null || !crate.enabled()) {
            plugin.getLogger().warning(rewardLabel + " konnte nicht vergeben werden: Crate '" + crateId +
                    "' fehlt oder ist deaktiviert.");
            return KeyGrantResult.UNAVAILABLE;
        }
        ItemStack key = crates.keys().create(crate, amount);
        if (!canFit(player, key)) return KeyGrantResult.INVENTORY_FULL;
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(key);
        if (!leftovers.isEmpty()) {
            plugin.getLogger().warning(rewardLabel + " für " + player.getName() +
                    " passte trotz vorheriger Inventarprüfung nicht vollständig ins Inventar.");
            return KeyGrantResult.INVENTORY_FULL;
        }
        return KeyGrantResult.SUCCESS;
    }

    private MilestoneClaim claimMilestones(Player player) throws SQLException {
        VoteMilestoneConfig config = milestoneConfig();
        String displayName = keyDisplayName(config.keyCrateId());
        if (!config.enabled()) return new MilestoneClaim(0, false, false, displayName);

        long total = repository.totalVotes(player.getUniqueId());
        long earned = total / config.interval();
        long rewarded = repository.milestonesRewarded(player.getUniqueId());
        if (earned <= rewarded) return new MilestoneClaim(0, false, false, displayName);

        int keysPaid = 0;
        boolean inventoryFull = false;
        boolean unavailable = false;
        while (rewarded < earned) {
            KeyGrantResult result = giveKey(player, config.keyCrateId(), config.keyAmount(), "Vote-Meilenstein-Key");
            if (result == KeyGrantResult.INVENTORY_FULL) {
                inventoryFull = true;
                break;
            }
            if (result == KeyGrantResult.UNAVAILABLE) {
                unavailable = true;
                break;
            }
            rewarded++;
            repository.setMilestonesRewarded(player.getUniqueId(), rewarded);
            keysPaid += config.keyAmount();
        }
        return new MilestoneClaim(keysPaid, inventoryFull, unavailable, displayName);
    }

    private boolean canFit(Player player, ItemStack item) {
        int remaining = item.getAmount();
        int maxStackSize = item.getMaxStackSize();
        for (ItemStack current : player.getInventory().getStorageContents()) {
            if (current == null || current.getType().isAir()) {
                remaining -= maxStackSize;
            } else if (current.isSimilar(item)) {
                remaining -= Math.max(0, maxStackSize - current.getAmount());
            }
            if (remaining <= 0) return true;
        }
        return false;
    }

    private VoteReminderConfig reminderConfig() {
        var config = plugin.configs().vote();
        boolean enabled = config.getBoolean("reminders.enabled", true);
        int joinDelaySeconds = Math.max(1, config.getInt("reminders.join-delay-seconds", 25));
        int intervalMinutes = Math.max(1, config.getInt("reminders.interval-minutes", 30));
        return new VoteReminderConfig(enabled, joinDelaySeconds, intervalMinutes);
    }

    private VoteRewardConfig rewardConfig() {
        var config = plugin.configs().vote();
        long coins = config.getBoolean("rewards.coins.enabled", true)
                ? Math.max(0L, config.getLong("rewards.coins.amount", 500L))
                : 0L;
        int keyAmount = config.getBoolean("rewards.key.enabled", true)
                ? Math.max(0, Math.min(64, config.getInt("rewards.key.amount", 1)))
                : 0;
        String keyCrateId = config.getString("rewards.key.crate-id", "daily");
        keyCrateId = keyCrateId == null ? "daily" : keyCrateId.trim().toLowerCase(Locale.ROOT);
        return new VoteRewardConfig(coins, keyCrateId, keyAmount);
    }

    private VoteMilestoneConfig milestoneConfig() {
        var config = plugin.configs().vote();
        boolean enabled = config.getBoolean("milestones.enabled", true);
        int interval = Math.max(1, config.getInt("milestones.every-votes", 70));
        int keyAmount = Math.max(1, Math.min(64, config.getInt("milestones.key.amount", 1)));
        String crateId = config.getString("milestones.key.crate-id", "novo");
        crateId = crateId == null || crateId.isBlank() ? "novo" : crateId.trim().toLowerCase(Locale.ROOT);
        return new VoteMilestoneConfig(enabled, interval, crateId, keyAmount);
    }

    private String keyDisplayName(VoteRewardConfig rewards) {
        if (rewards.keyAmount() <= 0) return "Key";
        return keyDisplayName(rewards.keyCrateId());
    }

    private String keyDisplayName(String crateId) {
        CrateDefinition crate = crates.find(crateId).orElse(null);
        return crate == null ? crateId + "-Key" : crates.keys().keyDisplayName(crate);
    }

    private String plainRewardSummary(VoteRewardConfig rewards) {
        StringBuilder result = new StringBuilder();
        if (rewards.coins() > 0L) result.append(formatCoins(rewards.coins())).append(" Coins");
        if (rewards.keyAmount() > 0) {
            if (!result.isEmpty()) result.append(" + ");
            result.append(rewards.keyAmount()).append("x ").append(keyDisplayName(rewards));
        }
        return result.isEmpty() ? "keine Belohnung" : result.toString();
    }

    private String rewardSummary(long coins, int keys, String keyDisplayName) {
        var config = plugin.configs().vote();
        String coinsTemplate = config.getString("messages.reward-summary.coins", "<yellow>%coins% Coins</yellow>");
        String keyTemplate = config.getString("messages.reward-summary.key", "<gold>%key_amount%x %key_name%</gold>");
        String separator = config.getString("messages.reward-summary.separator", " <gray>und</gray> ");
        Map<String, String> values = Map.of(
                "%coins%", formatCoins(coins),
                "%key_amount%", String.valueOf(keys),
                "%key_name%", miniMessage.escapeTags(keyDisplayName == null ? "Key" : keyDisplayName)
        );
        StringBuilder result = new StringBuilder();
        if (coins > 0L) result.append(replacePlaceholders(coinsTemplate, values));
        if (keys > 0) {
            if (!result.isEmpty()) result.append(separator == null ? "" : separator);
            result.append(replacePlaceholders(keyTemplate, values));
        }
        return result.toString();
    }

    private void sendConfiguredMessage(Player player, String path, String fallback, Map<String, String> values) {
        String template = plugin.configs().vote().getString(path, fallback);
        if (template == null || template.isBlank()) return;
        player.sendMessage(miniMessage.deserialize(replacePlaceholders(template, values)));
    }

    private static String replacePlaceholders(String input, Map<String, String> values) {
        String result = input == null ? "" : input;
        if (values == null || values.isEmpty()) return result;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
        }
        return result;
    }

    private static String formatCoins(long amount) {
        return String.format(Locale.GERMANY, "%,d", amount);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record DailyCompletionState(int completed, int target) {
        static DailyCompletionState empty() { return new DailyCompletionState(0, 0); }
    }

    private record VoteSiteMatch(String id, String name, List<String> serviceNames) {
        boolean matchesService(String serviceName) {
            String actual = normalizeService(serviceName);
            if (actual.isEmpty()) return false;
            if (actual.equals(normalizeService(id)) || actual.equals(normalizeService(name))) return true;
            for (String alias : serviceNames) {
                String expected = normalizeService(alias);
                if (!expected.isEmpty()
                        && (actual.equals(expected) || actual.contains(expected) || expected.contains(actual))) {
                    return true;
                }
            }
            return false;
        }
    }

    private record VoteReminderConfig(boolean enabled, int joinDelaySeconds, int intervalMinutes) { }

    private record VoteRewardConfig(long coins, String keyCrateId, int keyAmount) { }

    private record VoteMilestoneConfig(boolean enabled, int interval, String keyCrateId, int keyAmount) { }

    private record MilestoneClaim(int keysPaid, boolean blockedByInventory, boolean unavailable, String keyDisplayName) { }

    public record VoteRewardOverview(long coins, int keyAmount, String keyDisplayName) { }

    public enum VoteAdminAction { GET, SET, ADD, REMOVE }

    public record VoteAdminResult(boolean success, long before, long after, int milestoneInterval, String error) {
        static VoteAdminResult failure(String error) {
            return new VoteAdminResult(false, 0L, 0L, 70, error);
        }
    }

    public record VoteProgress(long totalVotes, int milestoneInterval, long progressVotes, long votesUntilNext,
                               long nextMilestoneAt, long milestonesEarned, long milestonesRewarded,
                               long milestonesPending, int milestoneKeyAmount, String milestoneKeyDisplayName) {
        static VoteProgress empty(int interval, int keyAmount, String keyName) {
            int safeInterval = Math.max(1, interval);
            return new VoteProgress(0L, safeInterval, 0L, safeInterval, safeInterval,
                    0L, 0L, 0L, Math.max(1, keyAmount), keyName == null ? "Novo-Key" : keyName);
        }
    }

    private enum KeyGrantResult { SUCCESS, INVENTORY_FULL, UNAVAILABLE }
}
