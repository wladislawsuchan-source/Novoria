package de.walahi.novosmp.professions;

import de.walahi.novosmp.angler.AnglerFeature;
import de.walahi.novosmp.angler.AnglerFishingService;
import de.walahi.novosmp.angler.FishDefinition;
import de.walahi.novosmp.angler.FishRegistry;
import de.walahi.novosmp.economy.sell.SellBonusProvider;
import de.walahi.novosmp.enchants.CustomEnchantmentService;
import de.walahi.novosmp.items.CustomItemManager;
import de.walahi.novosmp.lumi.LumiRepository;
import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.StatType;
import de.walahi.smpcore.stats.StatsAccess;
import de.walahi.smpcore.api.event.ActionContext;
import de.walahi.smpcore.api.event.ActionSource;
import de.walahi.smpcore.economy.EconomyOperationResult;
import de.walahi.smpcore.gui.MenuFormat;
import de.walahi.smpcore.services.EconomyService;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class ProfessionManager implements SellBonusProvider {
    public static final String LUMBERJACK = ProfessionConfig.LUMBERJACK_ID;
    public static final String MINER = ProfessionConfig.MINER_ID;
    public static final String HUNTER = ProfessionConfig.HUNTER_ID;
    public static final String ANGLER = ProfessionConfig.ANGLER_ID;

    private final SMPCorePlugin plugin;
    private final ProfessionRepository repository;
    private final EconomyService economy;
    private final StatsAccess stats;
    private final CustomItemManager customItems;
    private final LumiRepository lumis;
    private final ProfessionConfig config;
    private final FishRegistry fishRegistry;
    private AnglerFeature anglerFeature;
    private AnglerFishingService anglerFishingService;
    private final BoosterService boosters;
    private final ProfessionToolService tools;
    private final ProfessionFeedback feedback;
    private final ProfessionContributionDepositListener contributionDeposits;
    private final SaplingGrowthTracker saplingGrowthTracker;
    private final Map<UUID, PlayerProfessionState> states = new ConcurrentHashMap<>();
    /** One marker per actually blocking phase; no timers and no repeated chat spam. */
    private final Set<BlockedMilestoneKey> shownBlockedMilestones = ConcurrentHashMap.newKeySet();
    // Selbstabbau-Zähler dürfen beim Miner nicht pro Steinblock einen synchronen DB-Write auslösen.
    // Aktueller Wert + noch nicht persistierte Deltas werden deshalb klein gebatcht.
    private final Map<MinedProgressKey, Long> minedCurrent = new ConcurrentHashMap<>();
    private final Map<MinedProgressKey, Long> minedPending = new ConcurrentHashMap<>();
    // Jagdaufträge werden bei Mobfarmen ebenfalls gebatcht, damit nicht jeder Kill einen DB-Write auslöst.
    private final Map<HuntProgressKey, Long> huntCurrent = new ConcurrentHashMap<>();
    private final Map<HuntProgressKey, Long> huntPending = new ConcurrentHashMap<>();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private BukkitTask saveTask;
    private BukkitTask serialValidationTask;
    private ProfessionMenu menu;

    public ProfessionManager(SMPCorePlugin plugin, ProfessionRepository repository,
                             EconomyService economy, StatsAccess stats, CustomItemManager customItems,
                             CustomEnchantmentService enchantments, LumiRepository lumis,
                             FishRegistry fishRegistry) {
        this.plugin = plugin;
        this.repository = repository;
        this.economy = economy;
        this.stats = stats;
        this.customItems = customItems;
        this.lumis = lumis;
        this.fishRegistry = fishRegistry;
        this.config = new ProfessionConfig(plugin, fishRegistry);
        this.boosters = new BoosterService(plugin, repository, config, customItems);
        this.tools = new ProfessionToolService(plugin, repository, customItems, enchantments, config);
        this.feedback = new ProfessionFeedback(plugin, config);
        this.contributionDeposits = new ProfessionContributionDepositListener(this, config);
        this.saplingGrowthTracker = new SaplingGrowthTracker(plugin, this);
        this.menu = new ProfessionMenu(plugin, this, config);
    }

    public void start() {
        stopSaveTask();
        Bukkit.getOnlinePlayers().forEach(player -> state(player.getUniqueId()));
        boosters.start();
        Bukkit.getPluginManager().registerEvents(boosters, plugin);
        Bukkit.getPluginManager().registerEvents(new LumberjackListener(this, config, new PlacedWoodTracker(plugin, config)), plugin);
        Bukkit.getPluginManager().registerEvents(new MinerListener(plugin, this, config), plugin);
        Bukkit.getPluginManager().registerEvents(new HunterListener(plugin, this, config, customItems), plugin);
        Bukkit.getPluginManager().registerEvents(saplingGrowthTracker, plugin);
        Bukkit.getPluginManager().registerEvents(new ProfessionToolListener(tools), plugin);
        Bukkit.getPluginManager().registerEvents(new ProfessionSessionListener(this), plugin);
        Bukkit.getPluginManager().registerEvents(contributionDeposits, plugin);
        long interval = Math.max(100L, config.saveIntervalSeconds() * 20L);
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::saveDirty, interval, interval);
        serialValidationTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                for (ItemStack armor : player.getInventory().getArmorContents()) tools.sanitizeForPlayer(player, armor);
                tools.sanitizeForPlayer(player, player.getInventory().getItemInMainHand());
                tools.sanitizeForPlayer(player, player.getInventory().getItemInOffHand());
            }
        }, 40L, 40L);
    }

    public void stop() {
        if (anglerFishingService != null) anglerFishingService.shutdown();
        stopSaveTask();
        if (serialValidationTask != null) {
            serialValidationTask.cancel();
            serialValidationTask = null;
        }
        saveDirty();
        if (anglerFeature != null) anglerFeature.shutdown();
        boosters.stop();
        feedback.stop();
        long unsavedStates = states.values().stream().filter(this::hasUnsavedChanges).count();
        if (unsavedStates > 0 || !minedPending.isEmpty() || !huntPending.isEmpty()) {
            plugin.getLogger().severe("Berufsdaten beim Shutdown nicht vollständig gespeichert: "
                    + unsavedStates + " States, " + minedPending.size() + " Bergbau- und "
                    + huntPending.size() + " Jagd-Pendings. Ohne dauerhafte Recovery können diese Daten beim Prozessende verloren gehen.");
        }
        states.clear();
        shownBlockedMilestones.clear();
        minedCurrent.clear();
        minedPending.clear();
        huntCurrent.clear();
        huntPending.clear();
    }

    public void reload() {
        flushAllMinedProgress();
        flushAllHuntProgress();
        // Failed flushes still have a pending delta; their current display value must survive reload.
        minedCurrent.keySet().removeIf(key -> !minedPending.containsKey(key));
        huntCurrent.keySet().removeIf(key -> !huntPending.containsKey(key));
        config.reload();
        states.values().forEach(this::applySlotRules);
        boosters.reload();
        menu = new ProfessionMenu(plugin, this, config);
    }

    public void open(Player player) {
        menu.openMain(player);
    }

    public void openLumberjack(Player player) {
        menu.openLumberjack(player);
    }

    public void openMiner(Player player) {
        menu.openMiner(player);
    }

    public void openHunter(Player player) {
        menu.openHunter(player);
    }

    public void openAngler(Player player) { menu.openAngler(player); }
    public void anglerFeature(AnglerFeature feature) { this.anglerFeature = feature; }
    public AnglerFeature anglerFeature() { return anglerFeature; }
    public void anglerFishingService(AnglerFishingService service) { this.anglerFishingService = service; }
    public FishRegistry fishRegistry() { return fishRegistry; }

    void openContribution(Player player, int milestone) {
        menu.openContribution(player, LUMBERJACK, milestone);
    }

    void openContribution(Player player, String professionId, int milestone) {
        menu.openContribution(player, professionId, milestone);
    }

    public ProfessionConfig config() { return config; }
    public BoosterService boosters() { return boosters; }
    public ProfessionToolService tools() { return tools; }
    SMPCorePlugin plugin() { return plugin; }

    public void openContributionDeposit(Player player, int milestone) {
        openContributionDeposit(player, LUMBERJACK, milestone);
    }

    public void openContributionDeposit(Player player, String professionId, int milestone) {
        if (!isContributionMilestone(player, professionId, milestone)) {
            send(player, "messages.contribution-locked",
                    "<yellow>Du kannst aktuell nur für die nächste Abgabe einzahlen.</yellow>");
            return;
        }
        contributionDeposits.open(player, professionId, milestone);
    }

    public PlayerProfessionState state(UUID playerId) {
        return states.computeIfAbsent(playerId, id -> {
            PlayerProfessionState loaded;
            try {
                loaded = repository.load(id);
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.SEVERE, "Berufsdaten konnten für " + id + " nicht geladen werden", exception);
                loaded = new PlayerProfessionState(id);
            }
            applySlotRules(loaded);
            syncPrestigeStat(loaded);
            return loaded;
        });
    }

    private void applySlotRules(PlayerProfessionState state) {
        state.slotRules(config.slotUnlockPrestige(), config.maxActiveProfessionSlots());
    }

    public ProfessionProgress progress(UUID playerId, String professionId) {
        return state(playerId).progress(professionId);
    }

    public ProfessionProgress lumberjack(UUID playerId) {
        return progress(playerId, LUMBERJACK);
    }

    public ProfessionProgress miner(UUID playerId) {
        return progress(playerId, MINER);
    }

    public ProfessionProgress hunter(UUID playerId) {
        return progress(playerId, HUNTER);
    }

    public ProfessionProgress angler(UUID playerId) { return progress(playerId, ANGLER); }

    public boolean isActive(UUID playerId, String professionId) {
        return state(playerId).isActive(professionId);
    }

    public boolean isLumberjackActive(UUID playerId) {
        return isActive(playerId, LUMBERJACK);
    }

    public boolean isMinerActive(UUID playerId) {
        return isActive(playerId, MINER);
    }

    public boolean isHunterActive(UUID playerId) {
        return isActive(playerId, HUNTER);
    }

    public boolean isAnglerActive(UUID playerId) { return isActive(playerId, ANGLER); }

    public boolean activateLumberjack(Player player, int slotIndex) {
        return activateProfession(player, slotIndex, LUMBERJACK);
    }

    public boolean activateMiner(Player player, int slotIndex) {
        return activateProfession(player, slotIndex, MINER);
    }

    public boolean activateHunter(Player player, int slotIndex) {
        return activateProfession(player, slotIndex, HUNTER);
    }

    public boolean activateProfession(Player player, int slotIndex, String professionId) {
        String id = professionId == null ? "" : professionId.toLowerCase(java.util.Locale.ROOT);
        if (!LUMBERJACK.equals(id) && !MINER.equals(id) && !HUNTER.equals(id) && !ANGLER.equals(id)) return false;
        PlayerProfessionState state = state(player.getUniqueId());
        if (slotIndex < 0 || slotIndex >= state.slotLimit()) {
            send(player, "messages.slot-locked",
                    "<red>Dieser Berufsslot ist noch nicht freigeschaltet.</red>");
            return false;
        }
        if (state.isActive(id)) {
            player.sendRichMessage("<yellow>" + professionPlainName(id) + " ist bereits in einem Berufsslot aktiv.</yellow>");
            return false;
        }

        List<String> oldActive = new ArrayList<>(state.activeProfessions());
        boolean oldFree = state.freeSwitch();
        boolean targetFree = slotIndex >= state.activeProfessions().size();
        boolean free = !targetFree && state.freeSwitch();
        boolean paid = !targetFree && !free;

        if (paid) {
            EconomyOperationResult result = economy.withdraw(player.getUniqueId(), config.switchCost(),
                    "Berufswechsel: " + professionPlainName(id),
                    ActionContext.player(ActionSource.GUI, player.getUniqueId()));
            if (result == EconomyOperationResult.INSUFFICIENT_FUNDS) {
                send(player, "messages.insufficient-switch-coins",
                        "<red>Für den Berufswechsel benötigst du <gold>%coins% Coins</gold>.</red>",
                        "%coins%", MenuFormat.integer(config.switchCost()));
                return false;
            }
            if (result != EconomyOperationResult.SUCCESS) {
                send(player, "messages.switch-failed",
                        "<red>Der Berufswechsel konnte nicht gespeichert werden.</red>");
                return false;
            }
        }

        if (targetFree) {
            if (slotIndex != state.activeProfessions().size()) {
                if (paid) economy.deposit(player.getUniqueId(), config.switchCost(),
                        "Berufswechsel-Rückerstattung", ActionContext.system(player.getUniqueId()));
                send(player, "messages.slot-order",
                        "<yellow>Belege zuerst den vorherigen Berufsslot.</yellow>");
                return false;
            }
            state.activeProfessions().add(id);
        } else {
            state.activeProfessions().set(slotIndex, id);
            if (free) state.freeSwitch(false);
        }

        try {
            repository.saveMetaAndActive(state);
        } catch (RuntimeException exception) {
            state.activeProfessions().clear();
            state.activeProfessions().addAll(oldActive);
            state.freeSwitch(oldFree);
            // The DB may have committed the new slots before its acknowledgement was lost.
            state.markSlotCommitUncertain();
            if (paid) economy.deposit(player.getUniqueId(), config.switchCost(),
                    "Berufswechsel-Rückerstattung", ActionContext.system(player.getUniqueId()));
            plugin.getLogger().log(Level.WARNING, professionPlainName(id) + " konnte nicht aktiviert werden", exception);
            send(player, "messages.switch-failed",
                    "<red>Der Berufswechsel konnte nicht gespeichert werden.</red>");
            return false;
        }

        shownBlockedMilestones.removeIf(key -> key.playerId().equals(player.getUniqueId()));
        if (anglerFishingService != null && !state.isActive(ANGLER))
            anglerFishingService.reset(player.getUniqueId());

        if (targetFree) {
            player.sendRichMessage("<green>" + professionPlainName(id) + " wurde in Berufsslot " + (slotIndex + 1) + " aktiviert.</green>");
        } else {
            send(player, free ? "messages.switch-free" : "messages.switch-paid",
                    free ? "<green>Dein kostenloser Berufswechsel wurde verwendet.</green>"
                            : "<green>Beruf gewechselt. Kosten: <gold>%coins% Coins</gold>.</green>",
                    "%coins%", MenuFormat.integer(config.switchCost()));
        }
        return true;
    }

    public long selectionCost(Player player, int slotIndex) {
        PlayerProfessionState state = state(player.getUniqueId());
        if (slotIndex < 0 || slotIndex >= state.slotLimit()) return -1L;
        if (slotIndex >= state.activeProfessions().size() || state.freeSwitch()) return 0L;
        return config.switchCost();
    }

    public boolean usesFreeSwitch(Player player, int slotIndex) {
        PlayerProfessionState state = state(player.getUniqueId());
        return slotIndex >= 0 && slotIndex < state.activeProfessions().size() && state.freeSwitch();
    }

    public void addLumberjackXp(Player player, double baseXp) {
        addProfessionXp(player, LUMBERJACK, baseXp);
    }

    public void addMinerXp(Player player, double baseXp) {
        addProfessionXp(player, MINER, baseXp);
    }

    public void addHunterXp(Player player, double baseXp) {
        addProfessionXp(player, HUNTER, baseXp);
    }

    /** Uses the existing profession XP and level-up path for successful Angler catches. */
    public void addAnglerXp(Player player, double baseXp) { addProfessionXp(player, ANGLER, baseXp); }

    public record AnglerCatchResult(long creditedXp, boolean importantFeedback) {
        public static final AnglerCatchResult NONE = new AnglerCatchResult(0L, false);
    }
    private enum XpFeedbackMode { NORMAL, SUPPRESS_PROGRESS }

    /** Skill records belong to this prestige, including milestones not yet level-reachable. */
    public AnglerCatchResult recordAnglerCatch(Player player, boolean greenHit, int combo, long xp) {
        if (!isAnglerActive(player.getUniqueId())) return AnglerCatchResult.NONE;
        ProfessionProgress progress = angler(player.getUniqueId());
        List<MilestoneRequirement> requirements = config.milestones().stream()
                .map(level -> config.requirement(ANGLER, progress.prestige(), level)).toList();
        repository.recordAnglerSkills(player.getUniqueId(), progress.prestige(), requirements, greenHit, combo);
        return addProfessionXp(player, ANGLER, xp, XpFeedbackMode.SUPPRESS_PROGRESS);
    }

    /** AFK catches grant the fixed XP amount without combo progress or multipliers. */
    public AnglerCatchResult recordAfkAnglerCatch(Player player, long xp) {
        return addProfessionXp(player, ANGLER, xp, XpFeedbackMode.SUPPRESS_PROGRESS, false);
    }

    private void addProfessionXp(Player player, String professionId, double baseXp) {
        addProfessionXp(player, professionId, baseXp, XpFeedbackMode.NORMAL);
    }

    private AnglerCatchResult addProfessionXp(Player player, String professionId, double baseXp,
                                               XpFeedbackMode feedbackMode) {
        return addProfessionXp(player, professionId, baseXp, feedbackMode, true);
    }

    private AnglerCatchResult addProfessionXp(Player player, String professionId, double baseXp,
                                               XpFeedbackMode feedbackMode, boolean applyMultipliers) {
        if (!config.enabled() || baseXp <= 0D || player.getGameMode() != GameMode.SURVIVAL)
            return AnglerCatchResult.NONE;
        if (!isActive(player.getUniqueId(), professionId)) return AnglerCatchResult.NONE;

        ProfessionProgress progress = progress(player.getUniqueId(), professionId);
        if (progress.level() >= config.maxLevel()) return AnglerCatchResult.NONE;

        int pending = config.nextUncompletedMilestone(progress);
        if (pending > 0 && progress.level() >= pending && progress.completedMilestone() < pending) {
            sendBlockedMessage(player, professionId, pending);
            return AnglerCatchResult.NONE;
        }

        double multiplier = 1D;
        if (applyMultipliers) {
            multiplier = boosters.multiplier(player.getUniqueId(), BoosterCategory.PROFESSION);
            if (plugin instanceof NovoSMPPlugin smp && smp.clanManager() != null) {
                multiplier += smp.clanManager().professionMultiplier(player.getUniqueId()) - 1D;
            }
        }
        double previousXp = progress.xp();
        double newXp = Math.min(config.totalXp(professionId, progress.prestige()), previousXp + baseXp * multiplier);
        int oldLevel = progress.level();
        int calculatedLevel = config.levelForXp(professionId, progress.prestige(), newXp);

        int reachedMilestone = -1;
        if (!(progress.prestige() >= config.maxPrestige() && !config.maxPrestigeRequirementsEnabled(professionId))) {
            for (int milestone : config.milestones()) {
                if (milestone > progress.completedMilestone() && calculatedLevel >= milestone) {
                    calculatedLevel = milestone;
                    newXp = config.xpThreshold(professionId, progress.prestige(), milestone);
                    reachedMilestone = milestone;
                    break;
                }
            }
        }

        progress.xp(newXp);
        progress.level(calculatedLevel);
        if (feedbackMode == XpFeedbackMode.SUPPRESS_PROGRESS)
            feedback.suppressProgress(player.getUniqueId(), professionId);
        boolean importantFeedback = calculatedLevel > oldLevel;
        if (calculatedLevel > oldLevel) {
            feedback.showLevelUp(player, progress, oldLevel, calculatedLevel, reachedMilestone);
        } else if (feedbackMode == XpFeedbackMode.NORMAL) {
            feedback.showProgress(player, progress);
        }
        return feedbackMode == XpFeedbackMode.NORMAL ? AnglerCatchResult.NONE
                : new AnglerCatchResult(Math.max(0L, Math.round(newXp - previousXp)), importantFeedback);
    }

    /** Credits one legitimate natural/generated mining block to Miner XP and the open self-mining task. */
    public void recordMinerBlock(Player player, Material material) {
        if (player == null || material == null || player.getGameMode() != GameMode.SURVIVAL
                || !isMinerActive(player.getUniqueId())) return;

        ProfessionProgress progress = miner(player.getUniqueId());
        int milestone = pendingMilestone(progress);
        if (milestone > 0) {
            MilestoneRequirement requirement = config.requirement(MINER, progress.prestige(), milestone);
            List<String> matches = config.minedGroupsFor(material, requirement.mined().keySet());
            if (!matches.isEmpty()) {
                for (String groupId : matches) {
                    long required = requirement.mined().getOrDefault(groupId, 0L);
                    if (required <= 0L) continue;
                    MinedProgressKey cacheKey = new MinedProgressKey(
                            player.getUniqueId(), MINER, progress.prestige(), milestone, groupId);
                    long current = minedCurrent.computeIfAbsent(cacheKey, ignored ->
                            repository.contributions(player.getUniqueId(), MINER, progress.prestige(), milestone)
                                    .getOrDefault(minedContributionKey(groupId), 0L));
                    if (current >= required) continue;
                    long updated = Math.min(required, current + 1L);
                    minedCurrent.put(cacheKey, updated);
                    minedPending.merge(cacheKey, updated - current, Long::sum);
                    // Maximal 32 passende Blöcke gehen bei einem ungeplanten Crash verloren;
                    // beim Aufgabenabschluss wird sofort geschrieben.
                    if (minedPending.getOrDefault(cacheKey, 0L) >= 32L || updated >= required) {
                        flushMinedProgress(cacheKey, required);
                    }
                    if (updated >= required) {
                        MinedGroup group = config.minedGroup(groupId);
                        String display = group == null ? groupId : group.displayName();
                        String raw = config.string("messages.mined-complete",
                                "<aqua><bold>Bergbau-Auftrag abgeschlossen!</bold></aqua> <gray>%type%: <yellow>%current%/%required%</yellow></gray>")
                                .replace("%type%", display)
                                .replace("%current%", MenuFormat.integer(updated))
                                .replace("%required%", MenuFormat.integer(required));
                        player.sendActionBar(miniMessage.deserialize(raw));
                    }
                }
            }
        }

        addMinerXp(player, config.xpFor(MINER, material));
    }

    /** Credits one player-caused adult kill to the Hunter task and profession XP. */
    public void recordHunterKill(Player player, String entityType, double baseXp) {
        if (player == null || entityType == null || player.getGameMode() != GameMode.SURVIVAL
                || !isHunterActive(player.getUniqueId())) return;

        ProfessionProgress progress = hunter(player.getUniqueId());
        int milestone = pendingMilestone(progress);
        if (milestone > 0) {
            MilestoneRequirement requirement = config.requirement(HUNTER, progress.prestige(), milestone);
            List<String> matches = config.huntGroupsFor(entityType, requirement.hunts().keySet());
            for (String groupId : matches) {
                long required = requirement.hunts().getOrDefault(groupId, 0L);
                if (required <= 0L) continue;
                HuntProgressKey cacheKey = new HuntProgressKey(
                        player.getUniqueId(), HUNTER, progress.prestige(), milestone, groupId);
                long current = huntCurrent.computeIfAbsent(cacheKey, ignored ->
                        repository.contributions(player.getUniqueId(), HUNTER, progress.prestige(), milestone)
                                .getOrDefault(huntContributionKey(groupId), 0L));
                if (current >= required) continue;
                long updated = Math.min(required, current + 1L);
                huntCurrent.put(cacheKey, updated);
                huntPending.merge(cacheKey, updated - current, Long::sum);
                if (huntPending.getOrDefault(cacheKey, 0L) >= 16L || updated >= required) {
                    flushHuntProgress(cacheKey, required);
                }
                if (updated >= required) {
                    HuntGroup group = config.huntGroup(groupId);
                    String display = group == null ? groupId : group.displayName();
                    String raw = config.string("messages.hunt-complete",
                            "<red><bold>Jagdauftrag abgeschlossen!</bold></red> <gray>%type%: <yellow>%current%/%required%</yellow></gray>")
                            .replace("%type%", display)
                            .replace("%current%", MenuFormat.integer(updated))
                            .replace("%required%", MenuFormat.integer(required));
                    player.sendActionBar(miniMessage.deserialize(raw));
                }
            }
        }
        addHunterXp(player, baseXp);
    }

    /**
     * Returns the next milestone whose materials may already be deposited.
     * Only one milestone is available at a time, even when its target level
     * has not been reached yet.
     */
    public int pendingMilestone(ProfessionProgress progress) {
        return config.nextUncompletedMilestone(progress);
    }

    public boolean isContributionMilestone(Player player, int milestone) {
        return isContributionMilestone(player, LUMBERJACK, milestone);
    }

    public boolean isContributionMilestone(Player player, String professionId, int milestone) {
        if (milestone <= 0) return false;
        ProfessionProgress progress = progress(player.getUniqueId(), professionId);
        return config.nextUncompletedMilestone(progress) == milestone;
    }

    public Map<String, Long> contributions(Player player, int milestone) {
        return contributions(player, LUMBERJACK, milestone);
    }

    public Map<String, Long> contributions(Player player, String professionId, int milestone) {
        ProfessionProgress progress = progress(player.getUniqueId(), professionId);
        Map<String, Long> values = new HashMap<>(repository.contributions(
                player.getUniqueId(), professionId, progress.prestige(), milestone));
        if (LUMBERJACK.equals(professionId)) {
            MilestoneRequirement requirement = config.requirement(professionId, progress.prestige(), milestone);
            applyLegacyContributionPool(values, requirement, "feldfruechte",
                    List.of("weizen", "karotten", "kartoffeln", "rote_bete", "zuckerrohr"));
            applyLegacyContributionPool(values, requirement, "mobdrops",
                    List.of("verrottetes_fleisch", "knochen", "faeden", "schwarzpulver", "spinnenaugen"));
            applyFarmRebalanceSurplus(values, requirement);
        } else if (MINER.equals(professionId)) {
            MilestoneRequirement requirement = config.requirement(professionId, progress.prestige(), milestone);
            for (String groupId : requirement.mined().keySet()) {
                MinedProgressKey cacheKey = new MinedProgressKey(
                        player.getUniqueId(), MINER, progress.prestige(), milestone, groupId);
                Long cached = minedCurrent.get(cacheKey);
                if (cached != null) values.put(minedContributionKey(groupId), cached);
            }
        } else if (HUNTER.equals(professionId)) {
            MilestoneRequirement requirement = config.requirement(professionId, progress.prestige(), milestone);
            for (String groupId : requirement.hunts().keySet()) {
                HuntProgressKey cacheKey = new HuntProgressKey(
                        player.getUniqueId(), HUNTER, progress.prestige(), milestone, groupId);
                Long cached = huntCurrent.get(cacheKey);
                if (cached != null) values.put(huntContributionKey(groupId), cached);
            }
        }
        return values;
    }

    /**
     * Keeps deposits from older configs useful after aggregate requirements were
     * replaced with concrete items. The old amount is distributed proportionally
     * across the matching new requirements without modifying the stored legacy row.
     */
    private void applyLegacyContributionPool(Map<String, Long> values, MilestoneRequirement requirement,
                                             String legacyId, List<String> concreteIds) {
        long pool = Math.max(0L, values.getOrDefault(legacyId, 0L));
        if (pool <= 0L) return;

        List<String> active = concreteIds.stream()
                .filter(requirement.materials()::containsKey)
                .toList();
        if (active.isEmpty()) return;

        long totalMissing = active.stream()
                .mapToLong(id -> Math.max(0L, requirement.materials().get(id)
                        - values.getOrDefault(id, 0L)))
                .sum();
        if (totalMissing <= 0L) return;

        long distributable = Math.min(pool, totalMissing);
        long assigned = 0L;
        for (String id : active) {
            long missing = Math.max(0L, requirement.materials().get(id) - values.getOrDefault(id, 0L));
            if (missing <= 0L) continue;
            long share = Math.min(missing, distributable * missing / totalMissing);
            if (share > 0L) {
                values.merge(id, share, Long::sum);
                assigned += share;
            }
        }

        long remainder = distributable - assigned;
        while (remainder > 0L) {
            boolean changed = false;
            for (String id : active) {
                long required = requirement.materials().get(id);
                long current = values.getOrDefault(id, 0L);
                if (current >= required) continue;
                values.put(id, current + 1L);
                remainder--;
                changed = true;
                if (remainder == 0L) break;
            }
            if (!changed) break;
        }
    }

    /**
     * Version 1.46.0 verteilt die bisherige Feldfrucht-Gesamtmenge auf fünf
     * konkrete Pflanzen. Bereits vor dem Update überzahlte alte Pflanzen werden
     * deshalb virtuell auf das neue Zuckerrohr-Ziel angerechnet.
     */
    private void applyFarmRebalanceSurplus(Map<String, Long> values, MilestoneRequirement requirement) {
        long sugarRequired = requirement.materials().getOrDefault("zuckerrohr", 0L);
        if (sugarRequired <= 0L) return;
        long sugarCurrent = Math.max(0L, values.getOrDefault("zuckerrohr", 0L));
        long sugarMissing = Math.max(0L, sugarRequired - sugarCurrent);
        if (sugarMissing <= 0L) return;

        long surplus = 0L;
        for (String id : List.of("weizen", "karotten", "kartoffeln", "rote_bete")) {
            long required = requirement.materials().getOrDefault(id, 0L);
            long current = Math.max(0L, values.getOrDefault(id, 0L));
            surplus = Math.addExact(surplus, Math.max(0L, current - required));
        }
        if (surplus > 0L) values.put("zuckerrohr", sugarCurrent + Math.min(sugarMissing, surplus));
    }

    public String growthContributionKey(String groupId) {
        return "growth:" + (groupId == null ? "" : groupId.toLowerCase(java.util.Locale.ROOT));
    }

    public String minedContributionKey(String groupId) {
        return "mined:" + (groupId == null ? "" : groupId.toLowerCase(java.util.Locale.ROOT));
    }

    public String huntContributionKey(String groupId) {
        return "hunt:" + (groupId == null ? "" : groupId.toLowerCase(java.util.Locale.ROOT));
    }

    public List<TrackedSapling> loadTrackedSaplings() {
        return repository.loadTrackedSaplings();
    }

    public TrackedSapling registerPlacedSapling(Player player, Block block) {
        if (player == null || block == null || player.getGameMode() != GameMode.SURVIVAL
                || !isLumberjackActive(player.getUniqueId())
                || !config.isLumberjackGrowthSapling(block.getType())) return null;

        ProfessionProgress progress = lumberjack(player.getUniqueId());
        int contributionMilestone = 0;
        String contributionGroupId = "__xp_only__";

        // Falls der aktuell offene Meilenstein Aufforstung verlangt, speichert derselbe Tracker
        // zusätzlich die dafür nötigen Metadaten. Auch wenn gerade keine Aufforstungsaufgabe offen
        // ist, bleibt der Setzling für den Holzfäller-XP-Bonus registriert.
        int milestone = pendingMilestone(progress);
        if (milestone > 0) {
            MilestoneRequirement requirement = config.requirement(progress.prestige(), milestone);
            String groupId = config.growthGroupFor(block.getType(), requirement.growths().keySet());
            if (groupId != null) {
                long required = requirement.growths().getOrDefault(groupId, 0L);
                long current = contributions(player, milestone).getOrDefault(growthContributionKey(groupId), 0L);
                if (required > 0L && current < required) {
                    contributionMilestone = milestone;
                    contributionGroupId = groupId;
                }
            }
        }

        TrackedSapling sapling = new TrackedSapling(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ(),
                player.getUniqueId(), progress.prestige(), contributionMilestone, contributionGroupId,
                block.getType(), System.currentTimeMillis());
        repository.saveTrackedSapling(sapling);
        return sapling;
    }

    public void discardTrackedSapling(TrackedSapling sapling) {
        if (sapling != null) repository.deleteTrackedSapling(sapling);
    }

    public long creditGrownSapling(TrackedSapling sapling) {
        if (sapling == null) return -1L;

        ProfessionProgress progress = lumberjack(sapling.ownerId());
        long updated = -1L;
        long current = -1L;
        long required = 0L;
        GrowthGroup group = null;

        // Meilenstein-Gutschrift ist optional. Neue reine XP-Tracking-Einträge verwenden
        // milestone=0; ältere bereits gespeicherte Aufforstungs-Setzlinge funktionieren weiter.
        if (sapling.milestone() > 0) {
            MilestoneRequirement requirement = config.requirement(sapling.prestige(), sapling.milestone());
            required = requirement.growths().getOrDefault(sapling.growthGroupId(), 0L);
            String contributionId = growthContributionKey(sapling.growthGroupId());

            // Ein alter Setzling darf nicht in ein späteres Prestige oder eine spätere Abgabe hinein zählen.
            if (progress.prestige() == sapling.prestige()
                    && config.nextUncompletedMilestone(progress) == sapling.milestone()
                    && required > 0L) {
                current = repository.contributions(sapling.ownerId(), LUMBERJACK,
                        sapling.prestige(), sapling.milestone()).getOrDefault(contributionId, 0L);
                if (current < required) {
                    updated = repository.creditGrowthAndDelete(sapling, contributionId, required);
                    group = config.growthGroup(sapling.growthGroupId());
                } else {
                    repository.deleteTrackedSapling(sapling);
                    updated = current;
                }
            } else {
                repository.deleteTrackedSapling(sapling);
            }
        } else {
            repository.deleteTrackedSapling(sapling);
        }

        // Erst nachdem der persistente Tracking-Eintrag erfolgreich verbraucht wurde, XP vergeben.
        // So kann ein Fehler beim DB-Löschen nicht zu mehrfacher XP-Gutschrift beim nächsten Growth-Check führen.
        addLumberjackGrowthXp(sapling.ownerId(), config.saplingGrowthXp(sapling.material()));

        Player owner = Bukkit.getPlayer(sapling.ownerId());
        if (owner != null && owner.isOnline() && current >= 0L && updated > current) {
            boolean completed = updated >= required;
            String raw = config.string(completed ? "messages.growth-complete" : "messages.growth-counted",
                    completed
                            ? "<green><bold>Aufforstung abgeschlossen!</bold></green> <gray>%type%: <yellow>%current%/%required%</yellow></gray>"
                            : "<green>Aufforstung:</green> <yellow>%current%/%required%</yellow> <gray>%type%</gray>")
                    .replace("%current%", MenuFormat.integer(updated))
                    .replace("%required%", MenuFormat.integer(required))
                    .replace("%type%", group == null ? "Baum gewachsen" : group.displayName());
            owner.sendActionBar(miniMessage.deserialize(raw));
        }
        return updated;
    }

    /**
     * Growth can happen while the planter is offline. Online players use the normal XP path
     * (including profession boosters and feedback); offline growth is credited without a booster
     * and persisted immediately.
     */
    private void addLumberjackGrowthXp(UUID playerId, double baseXp) {
        if (!config.enabled() || baseXp <= 0D || playerId == null) return;

        Player online = Bukkit.getPlayer(playerId);
        if (online != null && online.isOnline()) {
            addLumberjackXp(online, baseXp);
            return;
        }

        PlayerProfessionState state = state(playerId);
        try {
            if (!state.isActive(LUMBERJACK)) return;
            ProfessionProgress progress = state.progress(LUMBERJACK);
            if (progress.level() >= config.maxLevel()) return;

            int pending = config.nextUncompletedMilestone(progress);
            if (pending > 0 && progress.level() >= pending && progress.completedMilestone() < pending) return;

            double newXp = Math.min(config.totalXp(LUMBERJACK, progress.prestige()), progress.xp() + baseXp);
            int calculatedLevel = config.levelForXp(LUMBERJACK, progress.prestige(), newXp);
            if (!(progress.prestige() >= config.maxPrestige() && !config.maxPrestigeRequirementsEnabled(LUMBERJACK))) {
                for (int milestone : config.milestones()) {
                    if (milestone > progress.completedMilestone() && calculatedLevel >= milestone) {
                        calculatedLevel = milestone;
                        newXp = config.xpThreshold(LUMBERJACK, progress.prestige(), milestone);
                        break;
                    }
                }
            }

            progress.xp(newXp);
            progress.level(calculatedLevel);
            // Der Besitzer ist offline, daher den kleinen Growth-Fortschritt sofort sichern.
            saveState(state);
        } finally {
            // A failed save keeps its dirty state for the regular autosave retry.
            if (!hasUnsavedChanges(state)) states.remove(playerId, state);
        }
    }

    public long contribute(Player player, int milestone, String groupId) {
        return contribute(player, LUMBERJACK, milestone, groupId, true);
    }

    public long contribute(Player player, String professionId, int milestone, String groupId) {
        return contribute(player, professionId, milestone, groupId, true);
    }

    private long contribute(Player player, String professionId, int milestone, String groupId, boolean notifyEmpty) {
        ProfessionProgress progress = progress(player.getUniqueId(), professionId);
        if (config.nextUncompletedMilestone(progress) != milestone) {
            if (notifyEmpty) {
                send(player, "messages.contribution-locked",
                        "<yellow>Du kannst aktuell nur für die nächste Abgabe einzahlen.</yellow>");
            }
            return 0L;
        }
        MilestoneRequirement requirement = config.requirement(professionId, progress.prestige(), milestone);
        if (requirement.fish().containsKey(groupId))
            return contributeFish(player, professionId, milestone, groupId, notifyEmpty);
        long required = requirement.materials().getOrDefault(groupId, 0L);
        if (required <= 0L) return 0L;
        MaterialGroup group = config.materialGroup(groupId);
        if (group == null) return 0L;

        long current = contributions(player, professionId, milestone).getOrDefault(groupId, 0L);
        long remaining = Math.max(0L, required - current);
        if (remaining <= 0L) return 0L;

        List<ItemStack> removed = removeMatching(player, group.materials(), remaining);
        long amount = removed.stream().mapToLong(ItemStack::getAmount).sum();
        if (amount <= 0L) {
            if (notifyEmpty) {
                send(player, "messages.nothing-to-contribute",
                        "<yellow>Du besitzt keine passenden Materialien für diese Abgabe.</yellow>");
            }
            return 0L;
        }

        try {
            repository.addContribution(player.getUniqueId(), professionId,
                    progress.prestige(), milestone, groupId, amount, required);
            long updated = Math.min(required, current + amount);
            send(player, "messages.contributed",
                    "<green>%amount%x %material% abgegeben. Fortschritt: <yellow>%current%/%required%</yellow>.</green>",
                    "%amount%", MenuFormat.integer(amount),
                    "%material%", group.displayName(),
                    "%current%", MenuFormat.integer(updated),
                    "%required%", MenuFormat.integer(required));
            return amount;
        } catch (RuntimeException exception) {
            restoreItems(player, removed);
            throw exception;
        }
    }

    private long contributeFish(Player player, String professionId, int milestone, String fishId,
                                boolean notifyEmpty) {
        ProfessionProgress progress = progress(player.getUniqueId(), professionId);
        MilestoneRequirement requirement = config.requirement(professionId, progress.prestige(), milestone);
        FishDefinition fish = fishRegistry.find(fishId);
        if (fish == null) return 0L;
        String key = fishContributionKey(fishId);
        long required = requirement.fish().getOrDefault(fishId, 0L);
        long current = contributions(player, professionId, milestone).getOrDefault(key, 0L);
        long remaining = Math.max(0L, required - current);
        if (remaining == 0L) return 0L;
        List<ItemStack> removed = removeMatching(player,
                stack -> fishRegistry.identify(stack) == fish, remaining);
        long amount = removed.stream().mapToLong(ItemStack::getAmount).sum();
        if (amount == 0L) {
            if (notifyEmpty) send(player, "messages.nothing-to-contribute",
                    "<yellow>Du besitzt keine passenden Items für diese Abgabe.</yellow>");
            return 0L;
        }
        try {
            repository.addContribution(player.getUniqueId(), professionId,
                    progress.prestige(), milestone, key, amount, required);
            send(player, "messages.contributed",
                    "<green>%amount%x %material% abgegeben. Fortschritt: <yellow>%current%/%required%</yellow>.</green>",
                    "%amount%", MenuFormat.integer(amount), "%material%", fish.displayName(),
                    "%current%", MenuFormat.integer(Math.min(required, current + amount)),
                    "%required%", MenuFormat.integer(required));
            return amount;
        } catch (RuntimeException exception) {
            restoreItems(player, removed);
            throw exception;
        }
    }

    public String fishContributionKey(String fishId) { return "fish:" + fishId; }

    public long inventoryFishAmount(Player player, String fishId) {
        long amount = 0L;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            FishDefinition fish = fishRegistry.identify(stack);
            if (fish != null && fish.id().equals(fishId)) amount += stack.getAmount();
        }
        return amount;
    }

    public long contributeAll(Player player, int milestone) {
        return contributeAll(player, LUMBERJACK, milestone);
    }

    public long contributeAll(Player player, String professionId, int milestone) {
        return contributeAll(player, professionId, milestone, true);
    }

    public long contributeAll(Player player, String professionId, int milestone, boolean notifyEmpty) {
        ProfessionProgress progress = progress(player.getUniqueId(), professionId);
        if (config.nextUncompletedMilestone(progress) != milestone) {
            send(player, "messages.contribution-locked",
                    "<yellow>Du kannst aktuell nur für die nächste Abgabe einzahlen.</yellow>");
            return 0L;
        }
        MilestoneRequirement requirement = config.requirement(professionId, progress.prestige(), milestone);
        long total = 0L;
        for (String groupId : requirement.materials().keySet()) {
            total += contribute(player, professionId, milestone, groupId, false);
        }
        for (String fishId : requirement.fish().keySet()) {
            total += contribute(player, professionId, milestone, fishId, false);
        }
        if (total <= 0L && notifyEmpty) {
            send(player, "messages.nothing-to-contribute",
                    "<yellow>Du besitzt keine passenden Materialien für diese Abgabe.</yellow>");
        }
        return total;
    }

    public List<ItemStack> depositContributionItems(Player player, int milestone, List<ItemStack> deposited) {
        return depositContributionItems(player, LUMBERJACK, milestone, deposited);
    }

    public List<ItemStack> depositContributionItems(Player player, String professionId, int milestone, List<ItemStack> deposited) {
        if (deposited == null || deposited.isEmpty()) return List.of();
        ProfessionProgress progress = progress(player.getUniqueId(), professionId);
        if (config.nextUncompletedMilestone(progress) != milestone) {
            send(player, "messages.contribution-locked",
                    "<yellow>Diese Abgabe ist nicht mehr verfügbar. Deine Items wurden zurückgegeben.</yellow>");
            return copyStacks(deposited);
        }
        MilestoneRequirement requirement = config.requirement(professionId, progress.prestige(), milestone);
        if (requirement.materials().isEmpty() && requirement.fish().isEmpty()) return copyStacks(deposited);

        Map<String, Long> current = contributions(player, professionId, milestone);
        Map<String, Long> remaining = new LinkedHashMap<>();
        requirement.materials().forEach((groupId, required) ->
                remaining.put(groupId, Math.max(0L, required - current.getOrDefault(groupId, 0L))));
        requirement.fish().forEach((fishId, required) -> {
            String key = fishContributionKey(fishId);
            remaining.put(key, Math.max(0L, required - current.getOrDefault(key, 0L)));
        });

        Map<String, Long> accepted = new LinkedHashMap<>();
        List<ItemStack> leftovers = new ArrayList<>();
        for (ItemStack original : deposited) {
            if (original == null || original.getType().isAir() || original.getAmount() <= 0) continue;
            ItemStack stack = original.clone();
            int unassigned = stack.getAmount();
            for (Map.Entry<String, Long> entry : remaining.entrySet()) {
                if (unassigned <= 0 || entry.getValue() <= 0L) continue;
                MaterialGroup group = config.materialGroup(entry.getKey());
                if (group == null || !group.materials().contains(stack.getType())) {
                    if (!entry.getKey().startsWith("fish:")) continue;
                    FishDefinition fish = fishRegistry.identify(stack);
                    if (fish == null || !fish.id().equals(entry.getKey().substring(5))) continue;
                }
                int used = (int) Math.min((long) unassigned, entry.getValue());
                accepted.merge(entry.getKey(), (long) used, Long::sum);
                entry.setValue(entry.getValue() - used);
                unassigned -= used;
            }
            if (unassigned > 0) {
                stack.setAmount(unassigned);
                leftovers.add(stack);
            }
        }

        if (accepted.isEmpty()) {
            send(player, "messages.deposit-no-match",
                    "<yellow>Im Einzahlungsinventar lagen keine aktuell benötigten Materialien.</yellow>");
            return copyStacks(deposited);
        }

        try {
            Map<String, Long> maxima = new LinkedHashMap<>(requirement.materials());
            requirement.fish().forEach((fishId, required) -> maxima.put(fishContributionKey(fishId), required));
            repository.addContributions(player.getUniqueId(), professionId, progress.prestige(), milestone,
                    accepted, maxima);
            long amount = accepted.values().stream().mapToLong(Long::longValue).sum();
            send(player, "messages.deposit-success",
                    "<green>%amount% Materialien wurden für Level %level% eingezahlt.</green>",
                    "%amount%", MenuFormat.integer(amount),
                    "%level%", Integer.toString(milestone));
            return leftovers;
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Berufs-Einzahlungsinventar konnte nicht gespeichert werden", exception);
            send(player, "messages.deposit-failed",
                    "<red>Die Materialien konnten nicht gespeichert werden und wurden zurückgegeben.</red>");
            return copyStacks(deposited);
        }
    }

    void returnItems(Player player, List<ItemStack> stacks) {
        if (stacks == null) return;
        for (ItemStack stack : stacks) {
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) continue;
            player.getInventory().addItem(stack).values().forEach(leftover ->
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        }
    }

    public boolean completeMilestone(Player player, int milestone) {
        return completeMilestone(player, LUMBERJACK, milestone);
    }

    public boolean completeMilestone(Player player, String professionId, int milestone) {
        ProfessionProgress progress = progress(player.getUniqueId(), professionId);
        if (config.nextUncompletedMilestone(progress) != milestone
                || progress.level() < milestone
                || progress.completedMilestone() >= milestone) return false;
        MilestoneRequirement requirement = config.requirement(professionId, progress.prestige(), milestone);
        if (MINER.equals(professionId)) {
            flushMinedProgress(player.getUniqueId(), MINER, progress.prestige(), milestone, requirement);
        } else if (HUNTER.equals(professionId)) {
            flushHuntProgress(player.getUniqueId(), HUNTER, progress.prestige(), milestone, requirement);
        }
        Map<String, Long> contributed = contributions(player, professionId, milestone);
        for (Map.Entry<String, Long> entry : requirement.materials().entrySet()) {
            if (contributed.getOrDefault(entry.getKey(), 0L) < entry.getValue()) {
                send(player, "messages.requirements-incomplete",
                        "<red>Noch nicht alle Berufsanforderungen sind vollständig.</red>");
                return false;
            }
        }
        for (Map.Entry<String, Long> entry : requirement.fish().entrySet()) {
            if (contributed.getOrDefault(fishContributionKey(entry.getKey()), 0L) < entry.getValue()) {
                send(player, "messages.requirements-incomplete",
                        "<red>Noch nicht alle Berufsanforderungen sind vollständig.</red>");
                return false;
            }
        }
        for (Map.Entry<String, Long> entry : requirement.skills().entrySet()) {
            if (contributed.getOrDefault(entry.getKey(), 0L) < entry.getValue()) {
                send(player, "messages.requirements-incomplete",
                        "<red>Noch nicht alle Berufsanforderungen sind vollständig.</red>");
                return false;
            }
        }
        for (Map.Entry<String, Long> entry : requirement.growths().entrySet()) {
            if (contributed.getOrDefault(growthContributionKey(entry.getKey()), 0L) < entry.getValue()) {
                send(player, "messages.requirements-incomplete",
                        "<red>Noch nicht alle Berufsanforderungen sind vollständig.</red>");
                return false;
            }
        }
        for (Map.Entry<String, Long> entry : requirement.mined().entrySet()) {
            if (contributed.getOrDefault(minedContributionKey(entry.getKey()), 0L) < entry.getValue()) {
                send(player, "messages.requirements-incomplete",
                        "<red>Noch nicht alle Berufsanforderungen sind vollständig.</red>");
                return false;
            }
        }
        for (Map.Entry<String, Long> entry : requirement.hunts().entrySet()) {
            if (contributed.getOrDefault(huntContributionKey(entry.getKey()), 0L) < entry.getValue()) {
                send(player, "messages.requirements-incomplete",
                        "<red>Noch nicht alle Berufsanforderungen sind vollständig.</red>");
                return false;
            }
        }

        boolean paid = false;
        if (requirement.coinCost() > 0L) {
            EconomyOperationResult result = economy.withdraw(player.getUniqueId(), requirement.coinCost(),
                    professionPlainName(professionId) + "-Meilenstein " + milestone,
                    ActionContext.player(ActionSource.GUI, player.getUniqueId()));
            if (result == EconomyOperationResult.INSUFFICIENT_FUNDS) {
                send(player, "messages.milestone-coins-missing",
                        "<red>Dir fehlen Coins. Benötigt: <gold>%coins%</gold>.</red>",
                        "%coins%", MenuFormat.integer(requirement.coinCost()));
                return false;
            }
            if (result != EconomyOperationResult.SUCCESS) return false;
            paid = true;
        }

        int previous = progress.completedMilestone();
        progress.completedMilestone(milestone);
        try {
            repository.saveProgressImmediate(player.getUniqueId(), progress);
        } catch (RuntimeException exception) {
            progress.completedMilestone(previous);
            if (paid) economy.deposit(player.getUniqueId(), requirement.coinCost(),
                    "Meilenstein-Rückerstattung", ActionContext.system(player.getUniqueId()));
            throw exception;
        }
        if (MINER.equals(professionId)) {
            clearMinedProgressCache(player.getUniqueId(), MINER, progress.prestige(), milestone);
        } else if (HUNTER.equals(professionId)) {
            clearHuntProgressCache(player.getUniqueId(), HUNTER, progress.prestige(), milestone);
        }
        shownBlockedMilestones.remove(new BlockedMilestoneKey(
                player.getUniqueId(), professionId, progress.prestige(), milestone));
        send(player, "messages.milestone-complete",
                "<green>Meilenstein Level %level% abgeschlossen. Du kannst weiterleveln.</green>",
                "%level%", Integer.toString(milestone));
        return true;
    }

    public boolean canPrestige(Player player) {
        return canPrestige(player, LUMBERJACK);
    }

    public boolean canPrestige(Player player, String professionId) {
        ProfessionProgress progress = progress(player.getUniqueId(), professionId);
        return progress.prestige() < config.maxPrestige()
                && progress.level() >= config.maxLevel()
                && progress.completedMilestone() >= config.maxLevel()
                && !hasUnclaimedRewards(player, professionId);
    }

    public boolean hasUnclaimedRewards(Player player, String professionId) {
        return availableRewardCount(player, professionId) > 0;
    }

    private boolean blockPrestigeForRewards(Player player, String professionId) {
        int available = availableRewardCount(player, professionId);
        if (available <= 0) return false;
        send(player, "messages.prestige-rewards-unclaimed",
                "<red>Du musst zuerst alle freigeschalteten Berufsbelohnungen abholen. Offen: <yellow>%count%</yellow>.</red>",
                "%count%", Integer.toString(available));
        return true;
    }

    public PrestigeToolStatus prestigeToolStatus(Player player) {
        return prestigeToolStatus(player, LUMBERJACK);
    }

    public PrestigeToolStatus prestigeToolStatus(Player player, String professionId) {
        ProfessionProgress progress = progress(player.getUniqueId(), professionId);
        if (ANGLER.equals(professionId)) return PrestigeToolStatus.NOT_REQUIRED;
        if (MINER.equals(professionId) || HUNTER.equals(professionId)) {
            return firstEmptyStorageSlot(player) >= 0 ? PrestigeToolStatus.NOT_REQUIRED : PrestigeToolStatus.NO_SPACE;
        }
        if (progress.prestige() <= 0) {
            return firstEmptyStorageSlot(player) >= 0
                    ? PrestigeToolStatus.NOT_REQUIRED
                    : PrestigeToolStatus.NO_SPACE;
        }
        int slot = findToolSlot(player, progress.activeToolSerial());
        if (slot < 0) return PrestigeToolStatus.MISSING;
        return fullyRepaired(player.getInventory().getItem(slot))
                ? PrestigeToolStatus.READY
                : PrestigeToolStatus.DAMAGED;
    }

    public boolean canPerformPrestige(Player player) {
        return canPerformPrestige(player, LUMBERJACK);
    }

    public boolean canPerformPrestige(Player player, String professionId) {
        if (!canPrestige(player, professionId)) return false;
        PrestigeToolStatus status = prestigeToolStatus(player, professionId);
        return status == PrestigeToolStatus.READY || status == PrestigeToolStatus.NOT_REQUIRED;
    }

    public long coinBalance(Player player) {
        return player == null ? 0L : economy.balance(player.getUniqueId());
    }

    public long inventoryAmount(Player player, Set<Material> materials) {
        if (player == null || materials == null || materials.isEmpty()) return 0L;
        long amount = 0L;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir() || !materials.contains(stack.getType())) continue;
            amount = Math.addExact(amount, stack.getAmount());
        }
        return amount;
    }

    public enum PrestigeToolStatus {
        NOT_REQUIRED, READY, MISSING, DAMAGED, NO_SPACE
    }

    public boolean performPrestige(Player player) {
        return performPrestige(player, LUMBERJACK);
    }

    public boolean performPrestige(Player player, String professionId) {
        boolean completed = MINER.equals(professionId) ? performMinerPrestige(player)
                : HUNTER.equals(professionId) ? performHunterPrestige(player)
                : ANGLER.equals(professionId) ? performAnglerPrestige(player)
                : performLumberjackPrestige(player);
        if (completed) shownBlockedMilestones.removeIf(key ->
                key.playerId().equals(player.getUniqueId())
                        && key.professionId().equalsIgnoreCase(professionId));
        return completed;
    }

    private boolean performLumberjackPrestige(Player player) {
        PlayerProfessionState state = state(player.getUniqueId());
        ProfessionProgress progress = state.progress(LUMBERJACK);
        if (progress.prestige() >= config.maxPrestige()) {
            send(player, "messages.prestige-max",
                    "<yellow>Du hast bereits Holzfäller-Prestige V. Du kannst weiterhin bis Level 100 leveln.</yellow>");
            return false;
        }
        if (blockPrestigeForRewards(player, LUMBERJACK)) return false;
        if (!canPrestige(player, LUMBERJACK)) {
            send(player, "messages.prestige-not-ready",
                    "<red>Die Voraussetzungen für das nächste Prestige sind noch nicht erfüllt.</red>");
            return false;
        }

        int oldPrestige = progress.prestige();
        String oldSerial = progress.activeToolSerial();
        int oldToolSlot = -1;
        ItemStack oldTool = null;
        if (oldPrestige > 0) {
            oldToolSlot = findToolSlot(player, oldSerial);
            if (oldToolSlot < 0) {
                send(player, "messages.prestige-tool-missing",
                        "<red>Deine aktuell registrierte Berufsaxt fehlt. Hole sie zurück oder kaufe im Berufsshop ein Ersatzwerkzeug.</red>");
                return false;
            }
            oldTool = player.getInventory().getItem(oldToolSlot);
            if (!fullyRepaired(oldTool)) {
                send(player, "messages.prestige-tool-damaged",
                        "<red>Die alte Berufsaxt muss vollständig repariert sein.</red>");
                return false;
            }
        } else if (firstEmptyStorageSlot(player) < 0) {
            player.sendRichMessage("<red>Du benötigst einen freien Inventarplatz für deine Berufsaxt.</red>");
            return false;
        }

        int newPrestige = oldPrestige + 1;
        String customItemId = config.toolItemId(LUMBERJACK, newPrestige);
        String newSerial = ProfessionToolService.newSerial();
        ItemStack newTool = tools.createLumberjackTool(customItemId, newSerial, newPrestige);
        if (newTool == null) {
            player.sendRichMessage("<red>Das neue Berufswerkzeug ist in items.yml nicht verfügbar.</red>");
            return false;
        }

        ProfessionProgress backup = progress.copy();
        boolean oldFreeSwitch = state.freeSwitch();
        boolean oldSecondSlot = state.secondSlotUnlocked();
        int oldSlotLimit = state.slotLimit();
        if (oldToolSlot >= 0) player.getInventory().setItem(oldToolSlot, null);
        try {
            repository.prestige(player.getUniqueId(), progress, oldSerial, newSerial, newPrestige,
                    newPrestige >= config.slotUnlockPrestige(), state);
        } catch (RuntimeException exception) {
            restoreProgress(progress, backup);
            state.freeSwitch(oldFreeSwitch);
            state.secondSlotUnlocked(oldSecondSlot);
            if (oldToolSlot >= 0) player.getInventory().setItem(oldToolSlot, oldTool);
            tools.markInactive(newSerial);
            throw exception;
        }

        if (oldSerial != null) tools.markInactive(oldSerial);
        tools.markActive(newSerial);
        int targetSlot = oldToolSlot >= 0 ? oldToolSlot : firstEmptyStorageSlot(player);
        player.getInventory().setItem(targetSlot, newTool);
        syncPrestigeStat(state);

        send(player, "messages.prestige-success",
                "<gold><bold>Holzfäller-Prestige %prestige% erreicht!</bold></gold> <gray>Du erhältst %tool%.</gray>",
                "%prestige%", roman(newPrestige),
                "%tool%", displayToolName(LUMBERJACK, newPrestige));
        afterPrestigeMessages(player, state, oldSlotLimit);
        return true;
    }

    private boolean performMinerPrestige(Player player) {
        PlayerProfessionState state = state(player.getUniqueId());
        ProfessionProgress progress = state.progress(MINER);
        if (progress.prestige() >= config.maxPrestige()) {
            player.sendRichMessage("<yellow>Du hast bereits Bergarbeiter-Prestige V. Du kannst weiterhin bis Level 100 leveln.</yellow>");
            return false;
        }
        if (blockPrestigeForRewards(player, MINER)) return false;
        if (!canPrestige(player, MINER)) {
            send(player, "messages.prestige-not-ready",
                    "<red>Die Voraussetzungen für das nächste Prestige sind noch nicht erfüllt.</red>");
            return false;
        }
        int slot = firstEmptyStorageSlot(player);
        if (slot < 0) {
            player.sendRichMessage("<red>Du benötigst einen freien Inventarplatz für dein neues Bergarbeiter-Werkzeug.</red>");
            return false;
        }

        int newPrestige = progress.prestige() + 1;
        String itemId = config.toolItemId(MINER, newPrestige);
        String newSerial = ProfessionToolService.newSerial();
        ItemStack newTool = tools.createMinerTool(itemId, newSerial, newPrestige);
        if (newTool == null) {
            player.sendRichMessage("<red>Das neue Bergarbeiter-Werkzeug ist in items.yml nicht verfügbar.</red>");
            return false;
        }

        ProfessionProgress backup = progress.copy();
        boolean oldFreeSwitch = state.freeSwitch();
        boolean oldSecondSlot = state.secondSlotUnlocked();
        int oldSlotLimit = state.slotLimit();
        try {
            repository.prestigeWithCollectedTool(player.getUniqueId(), progress, newSerial, newPrestige,
                    newPrestige >= config.slotUnlockPrestige(), state);
        } catch (RuntimeException exception) {
            restoreProgress(progress, backup);
            state.freeSwitch(oldFreeSwitch);
            state.secondSlotUnlocked(oldSecondSlot);
            tools.markInactive(newSerial);
            throw exception;
        }

        tools.markActive(newSerial);
        player.getInventory().setItem(slot, newTool);
        syncPrestigeStat(state);
        player.sendRichMessage("<gold><bold>Bergarbeiter-Prestige " + roman(newPrestige)
                + " erreicht!</bold></gold> <gray>Du erhältst " + displayToolName(MINER, newPrestige) + ".</gray>");
        afterPrestigeMessages(player, state, oldSlotLimit);
        return true;
    }

    private boolean performHunterPrestige(Player player) {
        PlayerProfessionState state = state(player.getUniqueId());
        ProfessionProgress progress = state.progress(HUNTER);
        if (progress.prestige() >= config.maxPrestige()) {
            player.sendRichMessage("<yellow>Du hast bereits Jäger-Prestige V. Du kannst weiterhin bis Level 100 leveln.</yellow>");
            return false;
        }
        if (blockPrestigeForRewards(player, HUNTER)) return false;
        if (!canPrestige(player, HUNTER)) {
            send(player, "messages.prestige-not-ready",
                    "<red>Die Voraussetzungen für das nächste Prestige sind noch nicht erfüllt.</red>");
            return false;
        }
        int slot = firstEmptyStorageSlot(player);
        if (slot < 0) {
            player.sendRichMessage("<red>Du benötigst einen freien Inventarplatz für deine neue Jäger-Belohnung.</red>");
            return false;
        }

        int newPrestige = progress.prestige() + 1;
        String itemId = config.toolItemId(HUNTER, newPrestige);
        String newSerial = ProfessionToolService.newSerial();
        ItemStack rewardItem = tools.createHunterReward(itemId, newSerial, newPrestige);
        if (rewardItem == null) {
            player.sendRichMessage("<red>Die neue Jäger-Belohnung ist in items.yml nicht verfügbar.</red>");
            return false;
        }

        ProfessionProgress backup = progress.copy();
        boolean oldFreeSwitch = state.freeSwitch();
        boolean oldSecondSlot = state.secondSlotUnlocked();
        int oldSlotLimit = state.slotLimit();
        try {
            repository.prestigeWithCollectedTool(player.getUniqueId(), progress, newSerial, newPrestige,
                    newPrestige >= config.slotUnlockPrestige(), state);
        } catch (RuntimeException exception) {
            restoreProgress(progress, backup);
            state.freeSwitch(oldFreeSwitch);
            state.secondSlotUnlocked(oldSecondSlot);
            tools.markInactive(newSerial);
            throw exception;
        }

        tools.markActive(newSerial);
        player.getInventory().setItem(slot, rewardItem);
        syncPrestigeStat(state);
        player.sendRichMessage("<gold><bold>Jäger-Prestige " + roman(newPrestige)
                + " erreicht!</bold></gold> <gray>Du erhältst " + displayToolName(HUNTER, newPrestige) + ".</gray>");
        afterPrestigeMessages(player, state, oldSlotLimit);
        return true;
    }

    private boolean performAnglerPrestige(Player player) {
        PlayerProfessionState state = state(player.getUniqueId());
        ProfessionProgress progress = state.progress(ANGLER);
        if (progress.prestige() >= config.maxPrestige()) {
            send(player, "messages.prestige-max", "<yellow>Maximales Prestige bereits erreicht.</yellow>");
            return false;
        }
        if (blockPrestigeForRewards(player, ANGLER)) return false;
        if (!canPrestige(player, ANGLER)) {
            send(player, "messages.prestige-not-ready", "<red>Die Voraussetzungen sind noch nicht erfüllt.</red>");
            return false;
        }
        int next = progress.prestige() + 1;
        ProfessionProgress backup = progress.copy();
        boolean oldFree = state.freeSwitch();
        boolean oldSecond = state.secondSlotUnlocked();
        int oldSlots = state.slotLimit();
        try {
            repository.prestigeWithoutTool(player.getUniqueId(), progress, next,
                    next >= config.slotUnlockPrestige(), state);
        } catch (RuntimeException exception) {
            restoreProgress(progress, backup);
            state.freeSwitch(oldFree);
            state.secondSlotUnlocked(oldSecond);
            throw exception;
        }
        syncPrestigeStat(state);
        if (anglerFishingService != null) anglerFishingService.reset(player.getUniqueId());
        player.sendRichMessage(config.string("messages.angler-prestige-success",
                "<gold><bold>Angler-Prestige %prestige% erreicht!</bold></gold>")
                .replace("%prestige%", roman(next)));
        afterPrestigeMessages(player, state, oldSlots);
        return true;
    }

    private void afterPrestigeMessages(Player player, PlayerProfessionState state, int oldSlotLimit) {
        send(player, "messages.free-switch-granted",
                "<green>Du hast einen einmaligen kostenlosen Berufswechsel erhalten.</green>");
        int newSlotLimit = state.slotLimit();
        if (newSlotLimit > oldSlotLimit) {
            send(player, "messages.profession-slot-unlocked",
                    "<aqua>Berufsslot %slot% dauerhaft freigeschaltet! Du hast jetzt %slots% Berufsslots.</aqua>",
                    "%slot%", Integer.toString(newSlotLimit),
                    "%slots%", Integer.toString(newSlotLimit));
        }
    }

    public boolean replaceTool(Player player) {
        ProfessionProgress progress = lumberjack(player.getUniqueId());
        if (progress.prestige() <= 0) {
            send(player, "messages.replacement-not-unlocked",
                    "<red>Du hast noch kein Holzfäller-Berufswerkzeug freigeschaltet.</red>");
            return false;
        }
        int slot = firstEmptyStorageSlot(player);
        if (slot < 0) {
            send(player, "messages.replacement-no-space",
                    "<red>Du benötigst einen freien Inventarplatz.</red>");
            return false;
        }
        long price = config.replacementPrice(progress.prestige());
        EconomyOperationResult result = economy.withdraw(player.getUniqueId(), price,
                "Holzfäller-Ersatzwerkzeug",
                ActionContext.player(ActionSource.GUI, player.getUniqueId()));
        if (result == EconomyOperationResult.INSUFFICIENT_FUNDS) {
            send(player, "messages.replacement-coins-missing",
                    "<red>Für das Ersatzwerkzeug benötigst du <gold>%coins% Coins</gold>.</red>",
                    "%coins%", MenuFormat.integer(price));
            return false;
        }
        if (result != EconomyOperationResult.SUCCESS) return false;

        String oldSerial = progress.activeToolSerial();
        String newSerial = ProfessionToolService.newSerial();
        ItemStack newTool = tools.createLumberjackTool(config.toolItemId(progress.prestige()),
                newSerial, progress.prestige());
        if (newTool == null) {
            economy.deposit(player.getUniqueId(), price, "Ersatzwerkzeug-Rückerstattung",
                    ActionContext.system(player.getUniqueId()));
            send(player, "messages.replacement-failed",
                    "<red>Das Ersatzwerkzeug konnte nicht erstellt werden. Deine alte Axt bleibt aktiv.</red>");
            return false;
        }

        String previousSerial = progress.activeToolSerial();
        try {
            repository.replaceTool(player.getUniqueId(), progress, oldSerial, newSerial);
        } catch (RuntimeException exception) {
            progress.activeToolSerial(previousSerial);
            economy.deposit(player.getUniqueId(), price, "Ersatzwerkzeug-Rückerstattung",
                    ActionContext.system(player.getUniqueId()));
            tools.markInactive(newSerial);
            throw exception;
        }
        if (oldSerial != null) tools.markInactive(oldSerial);
        tools.markActive(newSerial);
        player.getInventory().setItem(slot, newTool);
        sanitizePlayerInventory(player);
        send(player, "messages.replacement-success",
                "<green>Ersatzwerkzeug gekauft. Deine vorherige Berufsaxt wurde dauerhaft deaktiviert.</green>");
        return true;
    }

    public boolean replaceMinerTool(Player player, int toolPrestige) {
        ProfessionProgress progress = miner(player.getUniqueId());
        if (toolPrestige <= 0 || toolPrestige > progress.prestige() || toolPrestige > config.maxPrestige()) {
            send(player, "messages.miner-replacement-not-unlocked",
                    "<red>Dieses Bergarbeiter-Werkzeug hast du noch nicht freigeschaltet.</red>");
            return false;
        }
        int slot = firstEmptyStorageSlot(player);
        if (slot < 0) {
            send(player, "messages.replacement-no-space",
                    "<red>Du benötigst einen freien Inventarplatz.</red>");
            return false;
        }

        long price = config.replacementPrice(MINER, toolPrestige);
        EconomyOperationResult result = economy.withdraw(player.getUniqueId(), price,
                "Bergarbeiter-Ersatzwerkzeug-P" + toolPrestige,
                ActionContext.player(ActionSource.GUI, player.getUniqueId()));
        if (result == EconomyOperationResult.INSUFFICIENT_FUNDS) {
            send(player, "messages.replacement-coins-missing",
                    "<red>Für das Ersatzwerkzeug benötigst du <gold>%coins% Coins</gold>.</red>",
                    "%coins%", MenuFormat.integer(price));
            return false;
        }
        if (result != EconomyOperationResult.SUCCESS) return false;

        String newSerial = ProfessionToolService.newSerial();
        ItemStack newTool = tools.createMinerTool(config.toolItemId(MINER, toolPrestige), newSerial, toolPrestige);
        if (newTool == null) {
            economy.deposit(player.getUniqueId(), price, "Bergarbeiter-Ersatzwerkzeug-Rückerstattung",
                    ActionContext.system(player.getUniqueId()));
            send(player, "messages.miner-replacement-failed",
                    "<red>Das Ersatzwerkzeug konnte nicht erstellt werden.</red>");
            tools.markInactive(newSerial);
            return false;
        }

        List<String> revoked;
        try {
            revoked = repository.replaceCollectedTool(player.getUniqueId(), MINER, toolPrestige, newSerial);
        } catch (RuntimeException exception) {
            economy.deposit(player.getUniqueId(), price, "Bergarbeiter-Ersatzwerkzeug-Rückerstattung",
                    ActionContext.system(player.getUniqueId()));
            tools.markInactive(newSerial);
            throw exception;
        }
        revoked.forEach(tools::markInactive);
        tools.markActive(newSerial);
        player.getInventory().setItem(slot, newTool);
        sanitizePlayerInventory(player);
        send(player, "messages.miner-replacement-success",
                "<green>Ersatzwerkzeug gekauft: <yellow>%tool%</yellow>. Eine vorherige registrierte Kopie dieser Stufe wurde deaktiviert.</green>",
                "%tool%", displayToolName(MINER, toolPrestige));
        return true;
    }

    public boolean replaceHunterReward(Player player, int rewardPrestige) {
        ProfessionProgress progress = hunter(player.getUniqueId());
        if (rewardPrestige <= 0 || rewardPrestige > progress.prestige() || rewardPrestige > config.maxPrestige()) {
            send(player, "messages.hunter-replacement-not-unlocked",
                    "<red>Diese Jäger-Belohnung hast du noch nicht freigeschaltet.</red>");
            return false;
        }
        int slot = firstEmptyStorageSlot(player);
        if (slot < 0) {
            send(player, "messages.replacement-no-space",
                    "<red>Du benötigst einen freien Inventarplatz.</red>");
            return false;
        }

        long price = config.replacementPrice(HUNTER, rewardPrestige);
        EconomyOperationResult result = economy.withdraw(player.getUniqueId(), price,
                "Jäger-Ersatzbelohnung-P" + rewardPrestige,
                ActionContext.player(ActionSource.GUI, player.getUniqueId()));
        if (result == EconomyOperationResult.INSUFFICIENT_FUNDS) {
            send(player, "messages.replacement-coins-missing",
                    "<red>Für den Ersatz benötigst du <gold>%coins% Coins</gold>.</red>",
                    "%coins%", MenuFormat.integer(price));
            return false;
        }
        if (result != EconomyOperationResult.SUCCESS) return false;

        String newSerial = ProfessionToolService.newSerial();
        ItemStack rewardItem = tools.createHunterReward(config.toolItemId(HUNTER, rewardPrestige), newSerial, rewardPrestige);
        if (rewardItem == null) {
            economy.deposit(player.getUniqueId(), price, "Jäger-Ersatzbelohnung-Rückerstattung",
                    ActionContext.system(player.getUniqueId()));
            tools.markInactive(newSerial);
            send(player, "messages.hunter-replacement-failed",
                    "<red>Die Jäger-Ersatzbelohnung konnte nicht erstellt werden.</red>");
            return false;
        }

        List<String> revoked;
        try {
            revoked = tools.replaceSerial(player.getUniqueId(), HUNTER, rewardPrestige, newSerial);
        } catch (RuntimeException exception) {
            economy.deposit(player.getUniqueId(), price, "Jäger-Ersatzbelohnung-Rückerstattung",
                    ActionContext.system(player.getUniqueId()));
            tools.markInactive(newSerial);
            throw exception;
        }
        revoked.forEach(tools::markInactive);
        tools.markActive(newSerial);
        player.getInventory().setItem(slot, rewardItem);
        sanitizePlayerInventory(player);
        send(player, "messages.hunter-replacement-success",
                "<green>Ersatz gekauft: <yellow>%tool%</yellow>. Die vorherige registrierte Kopie dieser Stufe wurde deaktiviert.</green>",
                "%tool%", displayToolName(HUNTER, rewardPrestige));
        return true;
    }

    public int claimAvailableRewards(Player player) {
        return claimAvailableRewards(player, LUMBERJACK);
    }

    public int claimAvailableRewards(Player player, String professionId) {
        ProfessionProgress progress = progress(player.getUniqueId(), professionId);
        Set<Integer> claimed = new HashSet<>(repository.claimedRewards(
                player.getUniqueId(), professionId, progress.prestige()));
        int count = 0;
        for (LevelReward reward : config.rewardsUpTo(progress.level())) {
            if (claimed.contains(reward.level())) continue;
            if (!repository.claimReward(player.getUniqueId(), professionId,
                    progress.prestige(), reward.level())) continue;
            if (!grantReward(player, professionId, reward, progress.prestige())) {
                repository.unclaimReward(player.getUniqueId(), professionId,
                        progress.prestige(), reward.level());
                continue;
            }
            count++;
        }
        if (count <= 0) {
            send(player, "messages.reward-none",
                    "<yellow>Aktuell ist keine Levelbelohnung verfügbar.</yellow>");
        } else {
            send(player, "messages.reward-claimed",
                    "<green>%count% Levelbelohnung(en) abgeholt.</green>",
                    "%count%", Integer.toString(count));
        }
        return count;
    }

    public Set<Integer> claimedRewardLevels(Player player) {
        return claimedRewardLevels(player, LUMBERJACK);
    }

    public Set<Integer> claimedRewardLevels(Player player, String professionId) {
        ProfessionProgress progress = progress(player.getUniqueId(), professionId);
        return Set.copyOf(repository.claimedRewards(player.getUniqueId(), professionId, progress.prestige()));
    }

    public boolean claimReward(Player player, int rewardLevel) {
        return claimReward(player, LUMBERJACK, rewardLevel);
    }

    public boolean claimReward(Player player, String professionId, int rewardLevel) {
        ProfessionProgress progress = progress(player.getUniqueId(), professionId);
        LevelReward reward = config.reward(rewardLevel);
        if (reward == null || progress.level() < rewardLevel) {
            send(player, "messages.reward-locked",
                    "<yellow>Diese Levelbelohnung ist noch nicht freigeschaltet.</yellow>");
            return false;
        }
        if (!repository.claimReward(player.getUniqueId(), professionId, progress.prestige(), rewardLevel)) {
            send(player, "messages.reward-already-claimed",
                    "<yellow>Diese Levelbelohnung wurde bereits abgeholt.</yellow>");
            return false;
        }
        if (!grantReward(player, professionId, reward, progress.prestige())) {
            repository.unclaimReward(player.getUniqueId(), professionId, progress.prestige(), rewardLevel);
            return false;
        }
        player.sendRichMessage("<green>Belohnung für " + professionPlainName(professionId)
                + "-Level " + rewardLevel + " abgeholt.</green>");
        return true;
    }

    public int availableRewardCount(Player player) {
        return availableRewardCount(player, LUMBERJACK);
    }

    public int availableRewardCount(Player player, String professionId) {
        ProfessionProgress progress = progress(player.getUniqueId(), professionId);
        Set<Integer> claimed = new HashSet<>(repository.claimedRewards(
                player.getUniqueId(), professionId, progress.prestige()));
        return (int) config.rewardsUpTo(progress.level()).stream()
                .filter(reward -> !claimed.contains(reward.level()))
                .count();
    }

    @Override
    public double multiplier(UUID playerId, Material material) {
        if (playerId == null || material == null) return 1D;
        double result = 1D;
        if (isLumberjackActive(playerId) && config.isSellMaterial(LUMBERJACK, material)) {
            result = Math.max(result, config.sellMultiplier(LUMBERJACK, lumberjack(playerId).prestige()));
        }
        if (isMinerActive(playerId) && config.isSellMaterial(MINER, material)) {
            result = Math.max(result, config.sellMultiplier(MINER, miner(playerId).prestige()));
        }
        if (isHunterActive(playerId) && config.isSellMaterial(HUNTER, material)) {
            result = Math.max(result, config.sellMultiplier(HUNTER, hunter(playerId).prestige()));
        }
        return result;
    }

    public void handleJoin(Player player) {
        state(player.getUniqueId());
        sanitizePlayerInventory(player);
    }

    public void handleQuit(Player player) {
        UUID playerId = player.getUniqueId();
        if (anglerFeature != null) anglerFeature.onQuit(playerId);
        flushMinedProgress(playerId);
        flushHuntProgress(playerId);
        // Wenn alle gepufferten Selbstabbau-Deltas geschrieben wurden, brauchen wir
        // die Anzeige-Caches für den ausgeloggten Spieler nicht mehr im Speicher.
        if (minedPending.keySet().stream().noneMatch(key -> key.playerId().equals(playerId))) {
            minedCurrent.keySet().removeIf(key -> key.playerId().equals(playerId));
        }
        if (huntPending.keySet().stream().noneMatch(key -> key.playerId().equals(playerId))) {
            huntCurrent.keySet().removeIf(key -> key.playerId().equals(playerId));
        }
        PlayerProfessionState current = states.get(playerId);
        if (current != null && saveState(current)) states.remove(playerId, current);
        feedback.clear(playerId);
    }

    private boolean grantReward(Player player, String professionId, LevelReward reward, int prestige) {
        for (String itemId : reward.customItems().keySet()) {
            if (customItems.create(itemId, 1) != null) continue;
            plugin.getLogger().warning("Ungültiges Custom-Item in " + professionPlainName(professionId) + "-Levelbelohnung "
                    + reward.level() + ": " + itemId);
            send(player, "messages.reward-invalid",
                    "<red>Eine konfigurierte Levelbelohnung ist ungültig. Bitte melde dies dem Team.</red>");
            return false;
        }

        UUID playerId = player.getUniqueId();
        long coins = config.scaledRewardCoins(reward, prestige);
        long grantedLumis = 0L;

        if (reward.lumis() > 0L) {
            if (!lumis.add(playerId, reward.lumis())) return false;
            grantedLumis = reward.lumis();
        }

        if (coins > 0L) {
            EconomyOperationResult result = economy.deposit(playerId, coins,
                    professionPlainName(professionId) + "-Levelbelohnung " + reward.level(),
                    ActionContext.player(ActionSource.GUI, playerId));
            if (result != EconomyOperationResult.SUCCESS) {
                if (grantedLumis > 0L) lumis.withdraw(playerId, grantedLumis);
                return false;
            }
        }

        // Custom-Items werden erst nach allen fehlbaren Kontobuchungen ausgegeben.
        // CustomItemManager liefert Restmengen sicher als Drop aus, sodass hier nichts
        // mehr fehlschlagen oder teilweise verloren gehen kann.
        reward.customItems().forEach((itemId, amount) -> customItems.give(player, itemId, amount));
        return true;
    }


    private void flushMinedProgress(MinedProgressKey key, long required) {
        long delta = minedPending.getOrDefault(key, 0L);
        if (delta <= 0L) return;
        try {
            repository.addContribution(key.playerId(), key.professionId(), key.prestige(), key.milestone(),
                    minedContributionKey(key.groupId()), delta, required);
            minedPending.remove(key);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Bergbau-Auftrag konnte nicht zwischengespeichert werden", exception);
        }
    }

    private void flushMinedProgress(UUID playerId, String professionId, int prestige, int milestone,
                                    MilestoneRequirement requirement) {
        for (String groupId : requirement.mined().keySet()) {
            MinedProgressKey key = new MinedProgressKey(playerId, professionId, prestige, milestone, groupId);
            flushMinedProgress(key, requirement.mined().getOrDefault(groupId, 0L));
        }
    }

    private void flushMinedProgress(UUID playerId) {
        for (MinedProgressKey key : new ArrayList<>(minedPending.keySet())) {
            if (!key.playerId().equals(playerId)) continue;
            long required = config.requirement(key.professionId(), key.prestige(), key.milestone())
                    .mined().getOrDefault(key.groupId(), 0L);
            flushMinedProgress(key, required);
        }
    }

    private void flushAllMinedProgress() {
        for (MinedProgressKey key : new ArrayList<>(minedPending.keySet())) {
            long required = config.requirement(key.professionId(), key.prestige(), key.milestone())
                    .mined().getOrDefault(key.groupId(), 0L);
            flushMinedProgress(key, required);
        }
    }

    private void clearMinedProgressCache(UUID playerId, String professionId, int prestige, int milestone) {
        minedCurrent.keySet().removeIf(key -> key.playerId().equals(playerId)
                && key.professionId().equals(professionId) && key.prestige() == prestige && key.milestone() == milestone);
        minedPending.keySet().removeIf(key -> key.playerId().equals(playerId)
                && key.professionId().equals(professionId) && key.prestige() == prestige && key.milestone() == milestone);
    }

    private void flushHuntProgress(HuntProgressKey key, long required) {
        long delta = huntPending.getOrDefault(key, 0L);
        if (delta <= 0L) return;
        try {
            repository.addContribution(key.playerId(), key.professionId(), key.prestige(), key.milestone(),
                    huntContributionKey(key.groupId()), delta, required);
            huntPending.remove(key);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Jagdauftrag konnte nicht zwischengespeichert werden", exception);
        }
    }

    private void flushHuntProgress(UUID playerId, String professionId, int prestige, int milestone,
                                   MilestoneRequirement requirement) {
        for (String groupId : requirement.hunts().keySet()) {
            HuntProgressKey key = new HuntProgressKey(playerId, professionId, prestige, milestone, groupId);
            flushHuntProgress(key, requirement.hunts().getOrDefault(groupId, 0L));
        }
    }

    private void flushHuntProgress(UUID playerId) {
        for (HuntProgressKey key : new ArrayList<>(huntPending.keySet())) {
            if (!key.playerId().equals(playerId)) continue;
            long required = config.requirement(key.professionId(), key.prestige(), key.milestone())
                    .hunts().getOrDefault(key.groupId(), 0L);
            flushHuntProgress(key, required);
        }
    }

    private void flushAllHuntProgress() {
        for (HuntProgressKey key : new ArrayList<>(huntPending.keySet())) {
            long required = config.requirement(key.professionId(), key.prestige(), key.milestone())
                    .hunts().getOrDefault(key.groupId(), 0L);
            flushHuntProgress(key, required);
        }
    }

    private void clearHuntProgressCache(UUID playerId, String professionId, int prestige, int milestone) {
        huntCurrent.keySet().removeIf(key -> key.playerId().equals(playerId)
                && key.professionId().equals(professionId) && key.prestige() == prestige && key.milestone() == milestone);
        huntPending.keySet().removeIf(key -> key.playerId().equals(playerId)
                && key.professionId().equals(professionId) && key.prestige() == prestige && key.milestone() == milestone);
    }

    private void saveDirty() {
        if (anglerFeature != null) anglerFeature.storage().flushDirty();
        flushAllMinedProgress();
        flushAllHuntProgress();
        for (PlayerProfessionState state : states.values()) {
            if (!saveState(state)) continue;
            Player player = Bukkit.getPlayer(state.playerId());
            if (player == null || !player.isOnline()) states.remove(state.playerId(), state);
        }
    }

    private boolean hasUnsavedChanges(PlayerProfessionState state) {
        return state.metaDirty() || state.slotCommitUncertain()
                || state.progress().values().stream().anyMatch(ProfessionProgress::dirty);
    }

    private boolean saveState(PlayerProfessionState state) {
        if (!hasUnsavedChanges(state)) return true;
        try {
            repository.save(state);
            return true;
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Berufsfortschritt von " + state.playerId()
                    + " konnte nicht gespeichert werden; RAM-State bleibt für einen Retry erhalten", exception);
            return false;
        }
    }

    private List<ItemStack> removeMatching(Player player, Set<Material> materials, long maximum) {
        return removeMatching(player, stack -> materials.contains(stack.getType()), maximum);
    }

    private List<ItemStack> removeMatching(Player player, java.util.function.Predicate<ItemStack> matches,
                                           long maximum) {
        List<ItemStack> removed = new ArrayList<>();
        long remaining = maximum;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length && remaining > 0L; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType().isAir() || !matches.test(stack)) continue;
            int amount = (int) Math.min(remaining, stack.getAmount());
            ItemStack taken = stack.clone();
            taken.setAmount(amount);
            removed.add(taken);
            if (amount >= stack.getAmount()) player.getInventory().setItem(slot, null);
            else stack.setAmount(stack.getAmount() - amount);
            remaining -= amount;
        }
        return removed;
    }

    private List<ItemStack> copyStacks(List<ItemStack> stacks) {
        List<ItemStack> copies = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (stack != null && !stack.getType().isAir() && stack.getAmount() > 0) copies.add(stack.clone());
        }
        return copies;
    }

    private void restoreItems(Player player, List<ItemStack> items) {
        for (ItemStack item : items) {
            player.getInventory().addItem(item).values().forEach(leftover ->
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        }
    }

    private int findToolSlot(Player player, String serial) {
        if (serial == null) return -1;
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (tools.matchesSerial(contents[slot], serial)) return slot;
        }
        return -1;
    }

    private int firstEmptyStorageSlot(Player player) {
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int slot = 0; slot < storage.length; slot++) {
            ItemStack item = storage[slot];
            if (item == null || item.getType().isAir()) return slot;
        }
        return -1;
    }

    private boolean fullyRepaired(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return !(meta instanceof Damageable damageable) || damageable.getDamage() <= 0;
    }

    private void syncPrestigeStat(PlayerProfessionState state) {
        long total = state.progress().values().stream().mapToLong(ProfessionProgress::prestige).sum();
        stats.setStat(state.playerId(), StatType.PRESTIGE, total);
    }

    private void sanitizePlayerInventory(Player player) {
        for (ItemStack item : player.getInventory().getContents()) tools.sanitizeForPlayer(player, item);
    }

    private void restoreProgress(ProfessionProgress target, ProfessionProgress backup) {
        target.prestige(backup.prestige());
        target.level(backup.level());
        target.xp(backup.xp());
        target.completedMilestone(backup.completedMilestone());
        target.activeToolSerial(backup.activeToolSerial());
        target.markClean();
    }

    private void sendBlockedMessage(Player player, String professionId, int milestone) {
        ProfessionProgress progress = progress(player.getUniqueId(), professionId);
        BlockedMilestoneKey key = new BlockedMilestoneKey(
                player.getUniqueId(), professionId, progress.prestige(), milestone);
        if (!shownBlockedMilestones.add(key)) return;
        send(player, "messages.milestone-blocked",
                "<dark_gray>[</dark_gray><light_purple>Abgabe</light_purple><dark_gray>]</dark_gray> <yellow>Schließe zuerst die Abgabe für Level %level% ab.</yellow>",
                "%level%", Integer.toString(milestone));
    }

    private record BlockedMilestoneKey(UUID playerId, String professionId, int prestige, int milestone) { }

    private void send(Player player, String path, String fallback, String... replacements) {
        String raw = config.string(path, fallback);
        for (int index = 0; index + 1 < replacements.length; index += 2) {
            raw = raw.replace(replacements[index], replacements[index + 1]);
        }
        player.sendMessage(miniMessage.deserialize(raw));
    }

    private String displayToolName(int prestige) {
        return displayToolName(LUMBERJACK, prestige);
    }

    private String displayToolName(String professionId, int prestige) {
        if (MINER.equals(professionId)) {
            return switch (prestige) {
                case 1 -> "eine Schmelzer-Eisenspitzhacke";
                case 2 -> "eine Veinminer-Eisenspitzhacke";
                case 3 -> "eine 3×3-Diamantspitzhacke";
                case 4 -> "eine Diamantspitzhacke mit Veinminer + Schmelzer";
                default -> "eine TNT-Netheritespitzhacke";
            };
        }
        if (HUNTER.equals(professionId)) {
            return switch (prestige) {
                case 1 -> "das Jäger-Schwert";
                case 2 -> "den Jäger-Speer";
                case 3 -> "den Jäger-Bogen";
                case 4 -> "die Jäger-Mace";
                default -> "die Jäger-Brustplatte";
            };
        }
        return switch (prestige) {
            case 1 -> "eine Eisenaxt mit Holzschlag I";
            case 2 -> "eine Eisenaxt mit Holzschlag II";
            case 3 -> "eine Eisenaxt mit Holzschlag III";
            case 4 -> "eine Diamantaxt mit Holzschlag IV";
            default -> "eine Netherite-Axt mit Holzschlag V";
        };
    }

    private String professionPlainName(String professionId) {
        return switch (professionId == null ? "" : professionId.toLowerCase(java.util.Locale.ROOT)) {
            case LUMBERJACK -> "Holzfäller";
            case MINER -> "Bergarbeiter";
            case HUNTER -> "Jäger";
            case ANGLER -> "Angler";
            default -> professionId == null ? "Beruf" : professionId;
        };
    }

    private String roman(int value) {
        return switch (value) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> Integer.toString(value);
        };
    }

    private record MinedProgressKey(UUID playerId, String professionId, int prestige, int milestone, String groupId) {}
    private record HuntProgressKey(UUID playerId, String professionId, int prestige, int milestone, String groupId) {}

    private void stopSaveTask() {
        if (saveTask != null) saveTask.cancel();
        saveTask = null;
    }
}
