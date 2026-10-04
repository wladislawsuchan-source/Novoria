package de.walahi.novosmp;

import de.walahi.novosmp.angler.AnglerCommand;
import de.walahi.novosmp.angler.AnglerFeature;
import de.walahi.novosmp.angler.AnglerFishingService;
import de.walahi.novosmp.angler.AnglerLootFoundation;
import de.walahi.novosmp.angler.FishRegistry;
import de.walahi.novosmp.chestlog.ChestLogManager;
import de.walahi.novosmp.commands.admin.ChestLogCommand;
import de.walahi.novosmp.commands.admin.IpCommand;
import de.walahi.novosmp.commands.admin.InvSwapCommand;
import de.walahi.novosmp.ip.PlayerIpRepository;
import de.walahi.novosmp.ip.PlayerIpTracker;
import de.walahi.novosmp.stats.StatsManager;
import de.walahi.novosmp.stats.LeaderboardProfileCache;
import de.walahi.novosmp.homes.HomeService;
import de.walahi.smpcore.homes.HomeAccess;
import de.walahi.smpcore.api.EventPublisher;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import de.walahi.novosmp.rtp.RtpManager;
import de.walahi.smpcore.stats.StatsAccess;
import de.walahi.smpcore.rtp.RtpAccess;
import de.walahi.smpcore.tpa.TpaAccess;
import de.walahi.novosmp.tpa.TpaService;
import de.walahi.novosmp.spawn.SpawnCommand;
import de.walahi.novosmp.spawn.SetSpawnCommand;
import de.walahi.novosmp.commands.BalanceCommand;
import de.walahi.novosmp.commands.EcoCommand;
import de.walahi.novosmp.commands.PayCommand;
import de.walahi.novosmp.commands.NightVisionCommand;
import de.walahi.novosmp.commands.ComingSoonCommand;
import de.walahi.novosmp.commands.ReferralCommand;
import de.walahi.novosmp.commands.gameplay.RtpCommand;
import de.walahi.novosmp.commands.gameplay.HomeCommand;
import de.walahi.novosmp.commands.gameplay.TpaCommand;
import de.walahi.novosmp.commands.gameplay.StatsCommand;
import de.walahi.novosmp.commands.gameplay.BackCommand;
import de.walahi.novosmp.commands.gameplay.StreamCommand;
import de.walahi.novosmp.back.DeathBackManager;
import de.walahi.novosmp.back.DeathBackRepository;
import de.walahi.novosmp.friends.*;
import de.walahi.smpcore.friends.FriendRepository;
import de.walahi.smpcore.database.DatabaseManager;
import de.walahi.novosmp.integrations.SMPCorePlaceholderExpansion;
import de.walahi.novosmp.afk.AfkManager;
import de.walahi.novosmp.commands.AfkCommand;
import de.walahi.novosmp.feature.GameplayPolishListener;
import de.walahi.novosmp.feature.DragonEggBundleListener;
import de.walahi.novosmp.feature.InvisibilityAnonymityService;
import de.walahi.novosmp.feature.StarterGearListener;
import de.walahi.novosmp.feature.NightVisionManager;
import de.walahi.novosmp.feature.SoulSpeedExploitFixListener;
import de.walahi.novosmp.feature.WorldDamageProtectionListener;
import de.walahi.novosmp.chat.SmpChatListener;
import de.walahi.novosmp.chat.ChatColorService;
import de.walahi.novosmp.combat.CombatManager;
import de.walahi.novosmp.combat.CombatRelationshipService;
import de.walahi.novosmp.sit.SitCommand;
import de.walahi.novosmp.sit.SitManager;
import de.walahi.novosmp.bounty.BountyCommand;
import de.walahi.novosmp.bounty.BountyManager;
import de.walahi.novosmp.clan.ClanManager;
import de.walahi.novosmp.king.DragonEggKingService;
import de.walahi.novosmp.king.KingCommand;
import de.walahi.novosmp.clan.ClanCommand;
import de.walahi.novosmp.clan.ClanHomeCommand;
import de.walahi.novosmp.clan.ClanChatCommand;
import de.walahi.novosmp.clan.ClanPartyCommand;
import de.walahi.smpcore.afk.AfkAccess;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.novosmp.auction.AuctionManager;
import de.walahi.novosmp.auction.AuctionRepository;
import de.walahi.novosmp.commands.AuctionHouseCommand;
import de.walahi.novosmp.commands.DailyCommand;
import de.walahi.novosmp.commands.OrderCommand;
import de.walahi.smpcore.commands.framework.CommandRegistry;
import de.walahi.novosmp.commands.CrateCommand;
import de.walahi.novosmp.commands.CraftCommand;
import de.walahi.novosmp.commands.CondenseCommand;
import de.walahi.novosmp.commands.ChatColorCommand;
import de.walahi.novosmp.commands.EnderChestUpgradeCommand;
import de.walahi.novosmp.commands.LumiCommand;
import de.walahi.novosmp.commands.LumiShopCommand;
import de.walahi.novosmp.commands.SellCommand;
import de.walahi.novosmp.commands.SetAfkPositionCommand;
import de.walahi.novosmp.commands.ShopCommand;
import de.walahi.novosmp.commands.WorthCommand;
import de.walahi.novosmp.commands.RanksCommand;
import de.walahi.novosmp.commands.PlaytimeCommand;
import de.walahi.novosmp.commands.VoteCommand;
import de.walahi.novosmp.commands.admin.ChatClearCommand;
import de.walahi.novosmp.commands.ChatEventCommand;
import de.walahi.novosmp.chatevent.ChatEventManager;
import de.walahi.novosmp.commands.admin.PerformanceClearCommand;
import de.walahi.novosmp.commands.admin.ExposeOreCommand;
import de.walahi.novosmp.commands.admin.SmpRestartCommand;
import de.walahi.novosmp.feature.PerformanceCleanupManager;
import de.walahi.novosmp.feature.PlayerIdentityListener;
import de.walahi.novosmp.feature.PortableShulkerBoxListener;
import de.walahi.novosmp.crates.CrateManager;
import de.walahi.novosmp.enchants.CustomEnchantCommand;
import de.walahi.novosmp.enchants.CustomEnchantmentService;
import de.walahi.novosmp.enchants.CustomEnchantmentAnvilListener;
import de.walahi.novosmp.enchants.AnglerEnchantmentDefinitions;
import de.walahi.novosmp.enchants.HolzschlagConfig;
import de.walahi.novosmp.enchants.MiningEnchantConfig;
import de.walahi.novosmp.enchants.MiningEnchantListener;
import de.walahi.novosmp.enchants.TreeFellerListener;
import de.walahi.novosmp.items.CustomItemCommand;
import de.walahi.novosmp.items.CustomItemManager;
import de.walahi.novosmp.items.CustomItemLegacyMigrationListener;
import de.walahi.novosmp.items.HunterMasterChestSmithingListener;
import de.walahi.novosmp.items.InfiniteRocketListener;
import de.walahi.novosmp.items.LibrarianSealListener;
import de.walahi.novosmp.heads.HeadCollectionCommand;
import de.walahi.novosmp.heads.HeadAdminCommand;
import de.walahi.novosmp.heads.HeadCollectionManager;
import de.walahi.novosmp.heads.MergeHelmetListener;
import de.walahi.novosmp.heads.HeadRepository;
import de.walahi.novosmp.economy.sell.SellManager;
import de.walahi.novosmp.economy.worth.WorthMenu;
import de.walahi.novosmp.enderchest.EnderChestUpgradeMenu;
import de.walahi.novosmp.enderchest.ExpandableEnderChestManager;
import de.walahi.novosmp.lumi.AfkZoneManager;
import de.walahi.novosmp.lumi.LumiShopMenu;
import de.walahi.novosmp.shop.ShopMenu;
import de.walahi.novosmp.shop.ShopMendingPolicy;
import de.walahi.novosmp.daily.DailyManager;
import de.walahi.novosmp.daily.DailyMenu;
import de.walahi.novosmp.daily.DailyRepository;
import de.walahi.novosmp.duel.*;
import de.walahi.novosmp.lumi.LumiRepository;
import de.walahi.novosmp.quests.QuestCommand;
import de.walahi.novosmp.quests.QuestListener;
import de.walahi.novosmp.quests.QuestMenu;
import de.walahi.novosmp.quests.QuestService;
import de.walahi.smpcore.network.ServerType;
import de.walahi.novosmp.feature.SmpScoreboardManager;
import de.walahi.novosmp.feature.DeathMessageListener;
import de.walahi.novosmp.feature.SmpSpawnProtectionListener;
import de.walahi.novosmp.orders.OrderManager;
import de.walahi.novosmp.trade.TradeItemPolicy;
import de.walahi.novosmp.orders.OrderRepository;
import de.walahi.novosmp.professions.BoosterCategory;
import de.walahi.novosmp.professions.ProfessionCommand;
import de.walahi.novosmp.professions.ContributionStatusCommand;
import de.walahi.novosmp.professions.ProfessionManager;
import de.walahi.novosmp.professions.ProfessionRepository;
import de.walahi.novosmp.playtime.PlaytimeRewardManager;
import de.walahi.novosmp.playtime.PlaytimeRewardMenu;
import de.walahi.novosmp.playtime.PlaytimeRewardRepository;
import de.walahi.novosmp.referral.ReferralManager;
import de.walahi.novosmp.referral.ReferralMenu;
import de.walahi.novosmp.referral.ReferralRepository;
import de.walahi.novosmp.referral.ReferralVerificationListener;
import de.walahi.novosmp.vote.VoteManager;
import de.walahi.novosmp.vote.VoteMenu;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.LivingEntity;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public final class NovoSMPPlugin extends SMPCorePlugin {
    private AuctionManager auctionManager;
    private DailyManager dailyManager;
    private CrateManager crateManager;
    private AfkZoneManager lumiAfkZoneManager;
    private ExpandableEnderChestManager expandableEnderChestManager;
    private LumiRepository lumiRepository;
    private QuestService questService;
    private QuestMenu questMenu;
    private SmpScoreboardManager smpScoreboardManager;
    private AfkAccess afkAccess;
    private PerformanceCleanupManager performanceCleanupManager;
    private ChatEventManager chatEventManager;
    private RtpManager rtpManager;
    private DeathBackManager deathBackManager;
    private FriendManager friendManager;
    private DuelManager duelManager;
    private CustomItemManager customItemManager;
    private CustomEnchantmentService customEnchantments;
    private HolzschlagConfig holzschlagConfig;
    private MiningEnchantConfig miningEnchantConfig;
    private MiningEnchantListener miningEnchantListener;
    private TreeFellerListener treeFellerListener;
    private ProfessionManager professionManager;
    private FishRegistry fishRegistry;
    private AnglerFishingService anglerFishingService;
    private AnglerLootFoundation anglerLootFoundation;
    private HeadCollectionManager headCollectionManager;
    private InvisibilityAnonymityService invisibilityAnonymityService;
    private LeaderboardProfileCache leaderboardProfileCache;
    private VoteManager voteManager;
    private WorldDamageProtectionListener worldDamageProtectionListener;
    private ChatColorService chatColorService;
    private CombatManager combatManager;
    private SitManager sitManager;
    private CombatRelationshipService combatRelationshipService;
    private BountyManager bountyManager;
    private ClanManager clanManager;
    private de.walahi.novosmp.jumpnrun.JumpRunManager jumpRunManager;
    private de.walahi.novosmp.commands.RulesCommand rulesCommand;
    private DragonEggKingService dragonEggKingService;
    private ChestLogManager chestLogManager;
    private DeathMessageListener deathMessageListener;

    @Override
    protected ServerType forcedServerType() {
        return ServerType.SMP;
    }

    @Override
    protected String localConfigResourceName() {
        return "novo-smp.yml";
    }

    /**
     * Keeps mandatory SMP gameplay commands usable on installations that still have an
     * older physical global commands.yml. Bukkit defaults do not merge list entries: if
     * an administrator file already owns a list, newly bundled entries are invisible.
     *
     * This runs before CommandVisibilityManager is created, updates only the mandatory
     * command names, persists the real global commands.yml and leaves every other admin
     * setting untouched.
     */
    @Override
    protected void initializeEarlyServerFeatures() {
        migrateLegacyVoteConfiguration();
        ensureVoteConfigurationSchema();
        ensureDailyConfigurationSchema();
        ensureWorldDamageConfiguration();
        ensureCombatConfiguration();
        ensureBountyConfiguration();
        ensureClanConfiguration();
        ensureDndMessages();
        ensureRequiredCommandVisibility();
    }

    /** Adds the new DND texts to existing shared config.yml files without changing custom messages. */
    private void ensureDndMessages() {
        var mainFile = configs().mainFile();
        YamlConfiguration current = YamlConfiguration.loadConfiguration(mainFile.file());
        boolean changed = false;
        try (InputStream stream = getResource("config.yml")) {
            if (stream == null) return;
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            for (String key : List.of("dnd-usage", "dnd-enabled", "dnd-disabled", "online-list")) {
                String path = "staff.messages." + key;
                if (!current.contains(path)) {
                    current.set(path, defaults.getString(path));
                    changed = true;
                }
            }
            if (!changed) return;
            current.save(mainFile.file());
            mainFile.reload();
            getLogger().info("Globale config.yml um DND-Nachrichten ergänzt.");
        } catch (IOException exception) {
            getLogger().log(Level.WARNING, "DND-Nachrichten konnten nicht ergänzt werden.", exception);
        }
    }

    /** Makes new combat settings visible without replacing any administrator value. */
    private void ensureCombatConfiguration() {
        var serverFile = configs().serverFile();
        YamlConfiguration current = YamlConfiguration.loadConfiguration(serverFile.file());
        boolean changed = false;
        try (InputStream stream = getResource(serverFile.resourceName())) {
            if (stream == null) return;
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            var section = defaults.getConfigurationSection("combat");
            if (section == null) return;
            for (String key : section.getKeys(true)) {
                String path = "combat." + key;
                if (!section.isConfigurationSection(key) && !current.contains(path)) {
                    current.set(path, defaults.get(path));
                    changed = true;
                }
            }
            if (!changed) return;
            current.save(serverFile.file());
            serverFile.reload();
            getLogger().info("config.yml ergänzt: Combat-Timer, Anzeige, Nachrichten und Befehlsliste.");
        } catch (IOException exception) {
            getLogger().log(Level.WARNING, "Combat-Konfiguration konnte nicht ergänzt werden.", exception);
        }
    }

    /** Adds missing bounty leaves while preserving every administrator-owned value. */
    private void ensureBountyConfiguration() {
        var serverFile = configs().serverFile();
        YamlConfiguration current = YamlConfiguration.loadConfiguration(serverFile.file());
        boolean changed = false;
        try (InputStream stream = getResource(serverFile.resourceName())) {
            if (stream == null) return;
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            var section = defaults.getConfigurationSection("bounty");
            if (section == null) return;
            for (String key : section.getKeys(true)) {
                String path = "bounty." + key;
                if (!section.isConfigurationSection(key) && !current.contains(path)) {
                    current.set(path, defaults.get(path));
                    changed = true;
                }
            }
            if (!changed) return;
            current.save(serverFile.file());
            serverFile.reload();
            getLogger().info("config.yml ergänzt: Bounty-Regeln, GUI und Nachrichten.");
        } catch (IOException exception) {
            getLogger().log(Level.WARNING, "Bounty-Konfiguration konnte nicht ergänzt werden.", exception);
        }
    }

    /** Adds new clan leaves without overwriting administrator-owned values. */
    private void ensureClanConfiguration() {
        var serverFile = configs().serverFile();
        YamlConfiguration current = YamlConfiguration.loadConfiguration(serverFile.file());
        boolean changed = false;
        try (InputStream stream = getResource(serverFile.resourceName())) {
            if (stream == null) return;
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            var section = defaults.getConfigurationSection("clan");
            if (section == null) return;
            for (String key : section.getKeys(true)) {
                String path = "clan." + key;
                if (!section.isConfigurationSection(key) && !current.contains(path)) { current.set(path, defaults.get(path)); changed = true; }
            }
            if (!changed) return;
            current.save(serverFile.file()); serverFile.reload();
            getLogger().info("config.yml ergänzt: Clan-Regeln, Level, Party, GUI und Nachrichten.");
        } catch (IOException exception) { getLogger().log(Level.WARNING, "Clan-Konfiguration konnte nicht ergänzt werden.", exception); }
    }

    /** Ergänzt jeden fehlenden Spawn-Schadensschalter einzeln, ohne vorhandene Werte zu überschreiben. */
    private void ensureWorldDamageConfiguration() {
        var serverFile = configs().serverFile();
        YamlConfiguration current = YamlConfiguration.loadConfiguration(serverFile.file());
        boolean changed = false;
        for (String cause : List.of("FLY_INTO_WALL", "SUFFOCATION")) {
            String path = "world-damage.smp_spawn." + cause;
            if (!current.contains(path)) {
                current.set(path, false);
                changed = true;
            }
        }
        if (!changed) return;
        try {
            current.save(serverFile.file());
            serverFile.reload();
            getLogger().info("config.yml ergänzt: Wandaufprall- und Erstickungsschaden sind in smp_spawn deaktiviert.");
        } catch (IOException exception) {
            getLogger().log(Level.WARNING,
                    "Weltbezogener Schadensschutz konnte nicht in config.yml ergänzt werden.", exception);
        }
    }

    /**
     * Adds configurable slots and cumulative rank bonuses to an existing daily.yml. Existing
     * base rewards always win. Legacy crate commands are converted into structured keys so an
     * update can neither double-pay nor drop a key because of a full inventory.
     */
    private void ensureDailyConfigurationSchema() {
        var dailyFile = configs().dailyFile();
        YamlConfiguration current = YamlConfiguration.loadConfiguration(dailyFile.file());
        if (current.getInt("schema-version", 0) >= 2) return;

        try (InputStream stream = getResource(dailyFile.resourceName())) {
            if (stream == null) {
                getLogger().warning("daily.yml konnte nicht auf Schema 2 erweitert werden: Resource fehlt.");
                return;
            }
            YamlConfiguration merged = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            for (String key : current.getKeys(true)) {
                if (!current.isConfigurationSection(key)) merged.set(key, current.get(key));
            }

            for (int day = 1; day <= 7; day++) {
                String commandPath = "days." + day + ".commands";
                if (!current.contains(commandPath)) continue;
                java.util.Map<String, Integer> convertedKeys = new java.util.LinkedHashMap<>();
                List<String> externalCommands = new ArrayList<>();
                for (String command : current.getStringList(commandPath)) {
                    String normalized = command == null ? "" : command.trim();
                    if (normalized.startsWith("/")) normalized = normalized.substring(1);
                    String[] parts = normalized.split("\\s+");
                    String crateId = null;
                    if (parts.length == 5 && parts[0].equalsIgnoreCase("crate")
                            && parts[1].equalsIgnoreCase("givekey")) {
                        if (parts[2].equalsIgnoreCase("%player%")) crateId = parts[3];
                        else if (parts[3].equalsIgnoreCase("%player%")) crateId = parts[2];
                    }
                    try {
                        int amount = crateId == null ? 0 : Integer.parseInt(parts[4]);
                        if (amount > 0) convertedKeys.merge(crateId.toLowerCase(java.util.Locale.ROOT), amount, Integer::sum);
                        else externalCommands.add(command);
                    } catch (NumberFormatException | ArrayIndexOutOfBoundsException exception) {
                        externalCommands.add(command);
                    }
                }
                merged.set("days." + day + ".keys", null);
                for (java.util.Map.Entry<String, Integer> entry : convertedKeys.entrySet()) {
                    merged.set("days." + day + ".keys." + entry.getKey(), entry.getValue());
                }
                merged.set(commandPath, externalCommands.isEmpty() ? null : externalCommands);
            }
            merged.set("schema-version", 2);
            merged.save(dailyFile.file());
            dailyFile.reload();
            getLogger().info("daily.yml auf Schema 2 erweitert: Rangboni, Slots und sichere Key-Auszahlung ergänzt.");
        } catch (IOException exception) {
            getLogger().log(Level.WARNING, "daily.yml konnte nicht auf Schema 2 erweitert werden.", exception);
        }
    }

    /**
     * Moves the vote settings from the historical server config.yml into the dedicated
     * vote.yml once. Vote settings belong to one physical file only; after a successful
     * migration the old vote section is removed from config.yml.
     */
    private void migrateLegacyVoteConfiguration() {
        var serverFile = configs().serverFile();
        YamlConfiguration legacy = YamlConfiguration.loadConfiguration(serverFile.file());
        var legacyVote = legacy.getConfigurationSection("vote");
        if (legacyVote == null) return;

        var voteFile = configs().voteFile();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(voteFile.file());
        for (String key : legacyVote.getKeys(true)) {
            if (!legacyVote.isConfigurationSection(key)) {
                target.set(key, legacyVote.get(key));
            }
        }

        try {
            target.save(voteFile.file());
            legacy.set("vote", null);
            legacy.save(serverFile.file());
            voteFile.reload();
            serverFile.reload();
            getLogger().info("Vote-Konfiguration aus config.yml nach vote.yml verschoben.");
        } catch (IOException exception) {
            getLogger().log(Level.SEVERE,
                    "Vote-Konfiguration konnte nicht nach vote.yml migriert werden; config.yml bleibt unverändert.",
                    exception);
        }
    }

    /**
     * Makes newly introduced vote settings physically visible in vote.yml. Existing administrator
     * values always win; only missing keys are supplied from the bundled vote.yml. This is intentionally
     * scoped to vote.yml and does not rewrite other configs.
     */
    private void ensureVoteConfigurationSchema() {
        var voteFile = configs().voteFile();
        YamlConfiguration current = YamlConfiguration.loadConfiguration(voteFile.file());
        if (current.getInt("schema-version", 0) >= 4) return;

        try (InputStream stream = getResource(voteFile.resourceName())) {
            if (stream == null) {
                getLogger().warning("vote.yml konnte nicht auf Schema 4 erweitert werden: Resource fehlt.");
                return;
            }

            YamlConfiguration merged = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            String oldDefaultVotePrefix = "<light_purple><bold>[VOTE]</bold></light_purple> ";
            String oldDefaultInvalidLink = "<light_purple><bold>[VOTE]</bold></light_purple> <red>Für %site% ist kein gültiger Vote-Link konfiguriert.</red>";
            boolean migrateDefaultVotePrefix = oldDefaultVotePrefix.equals(current.getString("messages.vote-link.prefix"));
            boolean migrateDefaultInvalidLink = oldDefaultInvalidLink.equals(current.getString("messages.invalid-link"));
            for (String key : current.getKeys(true)) {
                if (!current.isConfigurationSection(key)) {
                    merged.set(key, current.get(key));
                }
            }
            // Schema 3 also adopts the requested gray-bracket/lilac VOTE style, but only when
            // the administrator still had the old bundled defaults. Custom text is never overwritten.
            if (migrateDefaultVotePrefix) {
                merged.set("messages.vote-link.prefix",
                        "<dark_gray>[</dark_gray><light_purple>VOTE</light_purple><dark_gray>]</dark_gray> ");
            }
            if (migrateDefaultInvalidLink) {
                merged.set("messages.invalid-link",
                        "<dark_gray>[</dark_gray><light_purple>VOTE</light_purple><dark_gray>]</dark_gray> <red>Für %site% ist kein gültiger Vote-Link konfiguriert.</red>");
            }
            merged.set("schema-version", 4);
            merged.save(voteFile.file());
            voteFile.reload();
            getLogger().info("vote.yml auf Schema 4 erweitert: globale Tagesabschluss-Nachricht ergänzt.");
        } catch (IOException exception) {
            getLogger().log(Level.WARNING, "vote.yml konnte nicht auf Schema 4 erweitert werden.", exception);
        }
    }

    private void ensureRequiredCommandVisibility() {
        var commandsFile = configs().commandsFile();
        var commands = commandsFile.yaml();
        List<String> required = List.of(
                "duel", "duell",
                "berufe", "beruf", "abgabe",
                "koepfe", "köpfe", "heads", "kopfsammlung",
                "vote", "nachtsicht", "nv",
                "clan", "csethome", "chome", "cdelhome", "cc", "cp", "king", "quests"
        );
        List<String> paths = List.of(
                "server-groups.smp.default.allowed"
        );

        boolean changed = false;
        for (String path : paths) {
            List<String> configured = new ArrayList<>(commands.getStringList(path));
            for (String command : required) {
                boolean present = configured.stream().anyMatch(value -> value.equalsIgnoreCase(command));
                if (!present) {
                    configured.add(command);
                    changed = true;
                }
            }
            commands.set(path, configured);
        }

        String premiumPath = "server-groups.smp.premium.allowed";
        List<String> premiumCommands = new ArrayList<>(commands.getStringList(premiumPath));
        for (String command : List.of("craft", "condense", "chatfarbe")) {
            if (premiumCommands.stream().noneMatch(value -> value.equalsIgnoreCase(command))) {
                premiumCommands.add(command);
                changed = true;
            }
        }
        commands.set(premiumPath, premiumCommands);
        String premiumPlusInheritance = "server-groups.smp.premiumplus.inherit";
        Object inherited = commands.get(premiumPlusInheritance);
        if (inherited instanceof String parent) {
            if (!parent.equalsIgnoreCase("premium")) {
                commands.set(premiumPlusInheritance, List.of(parent, "premium"));
                changed = true;
            }
        } else if (inherited instanceof List<?> parents) {
            boolean inheritsPremium = parents.stream().anyMatch(parent ->
                    parent != null && parent.toString().equalsIgnoreCase("premium"));
            if (!inheritsPremium) {
                List<String> updatedParents = new ArrayList<>();
                parents.forEach(parent -> { if (parent != null) updatedParents.add(parent.toString()); });
                updatedParents.add("premium");
                commands.set(premiumPlusInheritance, updatedParents);
                changed = true;
            }
        } else {
            commands.set(premiumPlusInheritance, "premium");
            changed = true;
        }

        String staffPath = "server-groups.smp.developer.allowed";
        List<String> staffCommands = new ArrayList<>(commands.getStringList(staffPath));
        if (staffCommands.stream().noneMatch(value -> value.equalsIgnoreCase("ip"))) {
            staffCommands.add("ip");
            commands.set(staffPath, staffCommands);
            changed = true;
        }

        String adminPath = "server-groups.smp.admin.allowed";
        List<String> adminCommands = new ArrayList<>(commands.getStringList(adminPath));
        for (String requiredCommand : List.of("smprestart", "head", "exposeore", "dnd")) {
            if (adminCommands.stream().noneMatch(value -> value.equalsIgnoreCase(requiredCommand))) {
                adminCommands.add(requiredCommand);
                changed = true;
            }
        }
        commands.set(adminPath, adminCommands);

        if (changed) {
            commandsFile.save();
            getLogger().info("commands.yml automatisch ergänzt: Pflicht-, Premium- und Team-Befehle.");
        }
    }


    @Override
    protected void registerServerListeners() {
        Bukkit.getPluginManager().registerEvents(new SmpSpawnProtectionListener(this), this);
        worldDamageProtectionListener = new WorldDamageProtectionListener(this);
        Bukkit.getPluginManager().registerEvents(worldDamageProtectionListener, this);
        Bukkit.getPluginManager().registerEvents(new GameplayPolishListener(this), this);
        Bukkit.getPluginManager().registerEvents(new DragonEggBundleListener(this), this);
        Bukkit.getPluginManager().registerEvents(new SoulSpeedExploitFixListener(this), this);
        invisibilityAnonymityService = new InvisibilityAnonymityService(this);
        Bukkit.getPluginManager().registerEvents(invisibilityAnonymityService, this);
        chatColorService = new ChatColorService(this);
        Bukkit.getPluginManager().registerEvents(chatColorService, this);
        Bukkit.getPluginManager().registerEvents(new SmpChatListener(this, chatColorService), this);
        Bukkit.getPluginManager().registerEvents(new PlayerIdentityListener(this, sharedDatabaseManager()), this);
        if (configs().messages().getBoolean("death-messages.enabled", true)) {
            deathMessageListener = new DeathMessageListener(this);
            Bukkit.getPluginManager().registerEvents(deathMessageListener, this);
        }
    }

    @Override
    protected void registerServerCommands(CommandRegistry commands) {
        commands.register("spawn", new SpawnCommand(this));
        commands.register("rtp", new RtpCommand(this));
        if (rtpManager == null) {
            rtpManager = new RtpManager(this);
        }
        deathBackManager = new DeathBackManager(this, rtpManager, new DeathBackRepository(sharedStorageManager()));
        Bukkit.getPluginManager().registerEvents(deathBackManager, this);
        commands.register("back", new BackCommand(this, deathBackManager));
        commands.register("stream", new StreamCommand(this, deathBackManager));
        friendManager = new FriendManager(this, new FriendRepository(sharedDatabaseManager()));
        friendManager.start();
        combatRelationshipService = new CombatRelationshipService(this, friendManager);
        combatManager = new CombatManager(this, combatRelationshipService, sharedStatsManager());
        combatManager.start();
        sitManager = new SitManager(this);
        Bukkit.getPluginManager().registerEvents(sitManager, this);
        commands.register("sit", new SitCommand(this, sitManager));
        bountyManager = new BountyManager(this, combatRelationshipService);
        bountyManager.start();
        commands.register("friend", new FriendCommand(this, friendManager));
        commands.register("fl", new FriendCommand(this, friendManager));
        commands.register("fhome", new FHomeCommand(this, friendManager));
        commands.register("ahome", new AHomeCommand(this, friendManager));
        commands.register("sethome", new HomeCommand(this, HomeCommand.Mode.SET));
        commands.register("home", new HomeCommand(this, HomeCommand.Mode.TELEPORT));
        commands.register("delhome", new HomeCommand(this, HomeCommand.Mode.DELETE));
        commands.register("homes", new HomeCommand(this, HomeCommand.Mode.LIST));
        commands.register("tpa", new TpaCommand(this, TpaCommand.Mode.REQUEST));
        commands.register("tpaccept", new TpaCommand(this, TpaCommand.Mode.ACCEPT));
        commands.register("tpdeny", new TpaCommand(this, TpaCommand.Mode.DENY));
        commands.register("tpacancel", new TpaCommand(this, TpaCommand.Mode.CANCEL));
        commands.register("stats", new StatsCommand(this, false));
        commands.register("leaderboards", new StatsCommand(this, true));
        commands.register("afk", new AfkCommand(this, afkAccess));
        commands.register("setspawn", new SetSpawnCommand(this));
        commands.register("balance", new BalanceCommand(this, sharedEconomyService()));
        commands.register("eco", new EcoCommand(this, sharedEconomyService()));
        commands.register("pay", new PayCommand(this, sharedEconomyService()));
        commands.register("craft", new CraftCommand(this));
        commands.register("condense", new CondenseCommand(this));
        commands.register("chatfarbe", new ChatColorCommand(this, chatColorService));
        NightVisionManager nightVision = new NightVisionManager(this);
        Bukkit.getPluginManager().registerEvents(nightVision, this);
        commands.register("nachtsicht", new NightVisionCommand(this, nightVision));
        performanceCleanupManager = new PerformanceCleanupManager(this);
        Bukkit.getPluginManager().registerEvents(performanceCleanupManager, this);
        performanceCleanupManager.start();
        commands.register("entityclear", new PerformanceClearCommand(this, performanceCleanupManager));
        commands.register("exposeore", new ExposeOreCommand(this));
        commands.register("chatclear", new ChatClearCommand(this));
        rulesCommand = new de.walahi.novosmp.commands.RulesCommand(this);
        commands.register("rules", rulesCommand);
        jumpRunManager = new de.walahi.novosmp.jumpnrun.JumpRunManager(this, this::hologramManager);
        commands.register("jumpnrun", jumpRunManager);
        Bukkit.getPluginManager().registerEvents(jumpRunManager, this);
        commands.register("smprestart", new SmpRestartCommand(this));
        chestLogManager = new ChestLogManager(this);
        Bukkit.getPluginManager().registerEvents(chestLogManager, this);
        commands.register("chestlog", new ChestLogCommand(this, chestLogManager));
        PlayerIpRepository playerIpRepository = new PlayerIpRepository(sharedDatabaseManager());
        PlayerIpTracker playerIpTracker = new PlayerIpTracker(this, playerIpRepository);
        Bukkit.getPluginManager().registerEvents(playerIpTracker, this);
        Bukkit.getOnlinePlayers().forEach(playerIpTracker::record);
        commands.register("ip", new IpCommand(this, playerIpRepository));
        RanksCommand ranksCommand = new RanksCommand(this, sharedEconomyService());
        commands.register("ranks", ranksCommand);
        commands.register("bounty", new BountyCommand(this, bountyManager));
        TradeItemPolicy tradeItemPolicy = new TradeItemPolicy(this);
        fishRegistry = new FishRegistry(this);
        tradeItemPolicy.fishRegistry(fishRegistry);

        Bukkit.getPluginManager().registerEvents(new PortableShulkerBoxListener(this), this);

        customItemManager = new CustomItemManager(this);
        Bukkit.getPluginManager().registerEvents(new CustomItemLegacyMigrationListener(customItemManager), this);
        Bukkit.getPluginManager().registerEvents(new LibrarianSealListener(this, customItemManager), this);
        holzschlagConfig = new HolzschlagConfig(this);
        miningEnchantConfig = new MiningEnchantConfig(this);
        customEnchantments = new CustomEnchantmentService(this, () -> configs().angler());
        customEnchantments.register(holzschlagConfig.enchantment());
        miningEnchantConfig.enchantments().forEach(customEnchantments::register);
        customEnchantments.register(new de.walahi.novosmp.enchants.CustomEnchantment(
                de.walahi.novosmp.enchants.MagnetListener.ID, "Magnet", "<aqua>", 1,
                de.walahi.novosmp.enchants.CustomEnchantment.Applicability.TOOL_OR_WEAPON));
        AnglerEnchantmentDefinitions.register(customEnchantments);
        registerMiningEnchantConflicts();
        customItemManager.enchantApplier(customEnchantments);
        Bukkit.getPluginManager().registerEvents(new InfiniteRocketListener(this, customItemManager), this);

        lumiRepository = new LumiRepository(sharedStorageManager());
        anglerLootFoundation = new AnglerLootFoundation(customItemManager, lumiRepository,
                configs().angler(), getLogger());
        professionManager = new ProfessionManager(
                this,
                new ProfessionRepository(sharedDatabaseManager()),
                sharedEconomyService(),
                sharedStatsManager(),
                customItemManager,
                customEnchantments,
                lumiRepository,
                fishRegistry
        );
        AnglerFeature anglerFeature = new AnglerFeature(this, professionManager, fishRegistry);
        professionManager.anglerFeature(anglerFeature);
        Bukkit.getPluginManager().registerEvents(anglerFeature, this);
        anglerFishingService = new AnglerFishingService(this, professionManager, anglerFeature,
                fishRegistry, anglerLootFoundation, customEnchantments);
        professionManager.anglerFishingService(anglerFishingService);
        Bukkit.getPluginManager().registerEvents(anglerFishingService, this);
        if (afkAccess instanceof AfkManager afkManager) {
            afkManager.onBecomeAfk(anglerFishingService::onBecomeAfk);
            afkManager.onLeaveAfk(anglerFishingService::onLeaveAfk);
        }
        professionManager.start();
        clanManager = new ClanManager(this, sharedStatsManager(), professionManager);
        friendManager.setClanGlowProvider(clanManager::shouldClanGlow);
        clanManager.start();
        commands.register("clan", new ClanCommand(this, clanManager));
        commands.register("csethome", new ClanHomeCommand(this, clanManager, ClanHomeCommand.Mode.SET));
        commands.register("chome", new ClanHomeCommand(this, clanManager, ClanHomeCommand.Mode.GO));
        commands.register("cdelhome", new ClanHomeCommand(this, clanManager, ClanHomeCommand.Mode.DELETE));
        commands.register("cc", new ClanChatCommand(this, clanManager));
        commands.register("cp", new ClanPartyCommand(this, clanManager));
        commands.register("berufe", new ProfessionCommand(this, professionManager));
        commands.register("abgabe", new ContributionStatusCommand(this, professionManager));
        commands.register("fische", new AnglerCommand(this, anglerFeature, false));
        commands.register("fanglager", new AnglerCommand(this, anglerFeature, true));
        headCollectionManager = new HeadCollectionManager(
                this, new HeadRepository(sharedDatabaseManager()), professionManager, customItemManager,
                sharedStatsManager(), sharedEconomyService());
        headCollectionManager.start();
        Bukkit.getPluginManager().registerEvents(
                new MergeHelmetListener(this, customItemManager, headCollectionManager, professionManager.tools()), this);
        commands.register("koepfe", new HeadCollectionCommand(this, headCollectionManager));
        commands.register("head", new HeadAdminCommand(this, headCollectionManager));
        Bukkit.getPluginManager().registerEvents(new HunterMasterChestSmithingListener(customItemManager, professionManager.tools()), this);

        SellManager sellManager = new SellManager(
                this, sharedEconomyService(), sharedStatsManager(), tradeItemPolicy, professionManager, headCollectionManager);
        sellManager.fishRegistry(fishRegistry);
        Bukkit.getPluginManager().registerEvents(sellManager, this);
        commands.register("sell", new SellCommand(this, sharedEconomyService(), sellManager));
        commands.register("worth", new WorthCommand(this, new WorthMenu(this, sellManager)));

        treeFellerListener = new TreeFellerListener(
                customEnchantments, holzschlagConfig, professionManager.tools()::validateForAbility);
        miningEnchantListener = new MiningEnchantListener(this, customEnchantments, miningEnchantConfig);
        Bukkit.getPluginManager().registerEvents(treeFellerListener, this);
        Bukkit.getPluginManager().registerEvents(miningEnchantListener, this);
        Bukkit.getPluginManager().registerEvents(
                new CustomEnchantmentAnvilListener(customEnchantments, customItemManager), this);
        Bukkit.getPluginManager().registerEvents(
                new de.walahi.novosmp.enchants.TotemBindingListener(customEnchantments), this);
        Bukkit.getPluginManager().registerEvents(
                new de.walahi.novosmp.enchants.SpawnergriffListener(this, customEnchantments), this);
        Bukkit.getPluginManager().registerEvents(new de.walahi.novosmp.enchants.MagnetListener(customEnchantments), this);

        CustomItemCommand customItemAdmin = new CustomItemCommand(
                this, customItemManager, holzschlagConfig, miningEnchantConfig, customEnchantments,
                null, fishRegistry, anglerFeature, anglerFishingService, anglerLootFoundation);
        commands.register("customitem", customItemAdmin);
        commands.register("angler", customItemAdmin);
        commands.register("fragmente", new CustomItemCommand(
                this, customItemManager, holzschlagConfig, miningEnchantConfig,
                customEnchantments, customItemManager.currencyId()));
        commands.register("holzschlag", new CustomEnchantCommand(
                this, customEnchantments, HolzschlagConfig.ENCHANTMENT_ID));
        commands.register("flaechenabbau", new CustomEnchantCommand(
                this, customEnchantments, MiningEnchantConfig.THREE_BY_THREE_ID));
        commands.register("veinminer", new CustomEnchantCommand(
                this, customEnchantments, MiningEnchantConfig.VEINMINER_ID));
        commands.register("schmelzer", new CustomEnchantCommand(
                this, customEnchantments, MiningEnchantConfig.SMELTER_ID));
        commands.register("tntspitzhacke", new CustomEnchantCommand(
                this, customEnchantments, MiningEnchantConfig.TNT_ID));

        crateManager = new CrateManager(this, customItemManager);
        registerCrateLocator(crateManager);
        commands.register("crate", new CrateCommand(this, crateManager));

        voteManager = new VoteManager(this, sharedEconomyService(), crateManager);
        VoteMenu voteMenu = new VoteMenu(this, voteManager);
        Bukkit.getPluginManager().registerEvents(voteMenu, this);
        commands.register("vote", new VoteCommand(this, voteMenu, voteManager));

        ReferralManager referralManager = new ReferralManager(
                this, new ReferralRepository(sharedStorageManager()), sharedStatsManager(), crateManager);
        ReferralMenu referralMenu = new ReferralMenu(this, referralManager);
        Bukkit.getPluginManager().registerEvents(new ReferralVerificationListener(this, referralManager), this);
        for (Player online : Bukkit.getOnlinePlayers()) referralManager.trackPlayer(online);
        commands.register("ref", new ReferralCommand(this, referralManager, referralMenu));

        PlaytimeRewardManager playtimeRewardManager = new PlaytimeRewardManager(
                this, new PlaytimeRewardRepository(sharedStorageManager()), sharedStatsManager(),
                sharedEconomyService(), crateManager);
        PlaytimeRewardMenu playtimeRewardMenu = new PlaytimeRewardMenu(this, playtimeRewardManager);
        Bukkit.getPluginManager().registerEvents(playtimeRewardMenu, this);
        commands.register("playtime", new PlaytimeCommand(this, playtimeRewardMenu));

        chatEventManager = new ChatEventManager(this, crateManager);
        Bukkit.getPluginManager().registerEvents(chatEventManager, this);
        chatEventManager.start();
        commands.register("chatevent", new ChatEventCommand(this, chatEventManager));
        ShopMendingPolicy shopMendingPolicy = new ShopMendingPolicy(this, customItemManager);
        Bukkit.getPluginManager().registerEvents(shopMendingPolicy, this);
        commands.register("shop", new ShopCommand(this, new ShopMenu(this, customItemManager, shopMendingPolicy)));

        LumiRepository featureLumiRepository = lumiRepository;
        commands.register("lumi", new LumiCommand(this, featureLumiRepository));
        commands.register("lumishop", new LumiShopCommand(this,
                new LumiShopMenu(this, featureLumiRepository, crateManager, customItemManager)));
        lumiAfkZoneManager = new AfkZoneManager(
                this, featureLumiRepository,
                playerId -> professionManager == null
                        ? 1.0D
                        : professionManager.boosters().multiplier(playerId, BoosterCategory.LUMI));
        lumiAfkZoneManager.actionbarOverlay(anglerFishingService::fishingHud);
        anglerFishingService.hudRefresh(lumiAfkZoneManager::refreshHud);
        lumiAfkZoneManager.start();
        commands.register("setafkpos1", new SetAfkPositionCommand(this, lumiAfkZoneManager, 1));
        commands.register("setafkpos2", new SetAfkPositionCommand(this, lumiAfkZoneManager, 2));

        expandableEnderChestManager = new ExpandableEnderChestManager(this);
        registerEnderChestAccess(expandableEnderChestManager);
        Bukkit.getPluginManager().registerEvents(expandableEnderChestManager, this);
        commands.register("ecupgrade", new EnderChestUpgradeCommand(this, new EnderChestUpgradeMenu(this, expandableEnderChestManager)));
        auctionManager = new AuctionManager(this, new AuctionRepository(sharedDatabaseManager()));
        auctionManager.start();
        commands.register("ah", new AuctionHouseCommand(this, auctionManager, sharedEconomyService(), tradeItemPolicy));

        OrderManager orderManager = new OrderManager(
                this,
                new OrderRepository(sharedDatabaseManager()),
                sharedEconomyService(),
                tradeItemPolicy
        );
        commands.register("order", new OrderCommand(this, orderManager));

        dailyManager = new DailyManager(this, new DailyRepository(sharedStorageManager()));
        DailyMenu dailyMenu = new DailyMenu(
                this, dailyManager, sharedEconomyService(), featureLumiRepository, crateManager);
        commands.register("daily", new DailyCommand(this, dailyMenu, dailyManager));

        questService = new QuestService(this, sharedStorageManager(), lumiRepository, crateManager);
        questService.start();
        questMenu = new QuestMenu(this, questService);
        questService.rowsChanged(ignored -> Bukkit.getScheduler().runTask(this, questMenu::reopenViewers));
        QuestListener questListener = new QuestListener(this, questService);
        Bukkit.getPluginManager().registerEvents(questListener, this);
        anglerFishingService.fishCaught(questListener::customFish);
        commands.register("quests", new QuestCommand(this, questMenu));

        validateRequiredCommandVisibility();
        DuelConfig duelConfig = new DuelConfig(this);
        WorldEditArenaService duelArenas = new WorldEditArenaService(this, duelConfig);
        DuelInventoryEditor duelEditor = new DuelInventoryEditor(this, duelConfig);
        duelManager = new DuelManager(this, duelConfig, sharedEconomyService(), duelArenas, duelEditor);
        DuelWagerSignInput duelWagerInput = new DuelWagerSignInput(this, duelManager);
        DuelMenu duelMenu = new DuelMenu(this, duelManager, duelWagerInput);
        duelManager.menu(duelMenu);
        Bukkit.getPluginManager().registerEvents(duelEditor, this);
        duelManager.listeners().forEach(listener -> Bukkit.getPluginManager().registerEvents(listener, this));
        Bukkit.getPluginManager().registerEvents(
                new de.walahi.novosmp.enchants.SoulboundDeathListener(customEnchantments), this);
        Bukkit.getPluginManager().registerEvents(duelWagerInput, this);
        commands.register("duel", new DuelCommand(this, duelManager, duelMenu));
        commands.register("dueladmin", new DuelAdminCommand(this, duelManager));
        commands.register("invswap", new InvSwapCommand(this, duelManager));
        dragonEggKingService = new DragonEggKingService(this, duelManager, sharedEconomyService(), afkAccess);
        dragonEggKingService.start();
        commands.register("king", new KingCommand(this, dragonEggKingService));
        if (!duelArenas.available()) {
            getLogger().warning("WorldEdit ist nicht aktiv. Duell-Maps können erst nach Installation/Aktivierung von WorldEdit genutzt werden.");
        }

        Bukkit.getPluginManager().registerEvents(new StarterGearListener(this), this);
    }


    private void registerMiningEnchantConflicts() {
        // Erlaubt: 3x3 + Schmelzer sowie Veinminer + Schmelzer.
        // Gesperrt: 3x3 + Veinminer und jede Kombination mit TNT.
        customEnchantments.registerConflict(
                MiningEnchantConfig.THREE_BY_THREE_ID,
                MiningEnchantConfig.VEINMINER_ID
        );
        customEnchantments.registerConflict(
                MiningEnchantConfig.TNT_ID,
                MiningEnchantConfig.THREE_BY_THREE_ID
        );
        customEnchantments.registerConflict(
                MiningEnchantConfig.TNT_ID,
                MiningEnchantConfig.VEINMINER_ID
        );
        customEnchantments.registerConflict(
                MiningEnchantConfig.TNT_ID,
                MiningEnchantConfig.SMELTER_ID
        );
    }

    private void validateRequiredCommandVisibility() {
        FileConfiguration commands = configs().commands();
        for (String path : List.of(
                "server-groups.smp.default.allowed"
        )) {
            if (!commands.contains(path, true)) {
                getLogger().warning("commands.yml: Der Pfad '" + path
                        + "' fehlt. Die Datei wird nicht automatisch verändert.");
                continue;
            }
            List<String> configured = commands.getStringList(path);
            for (String command : List.of("duel", "duell", "berufe", "beruf", "abgabe", "koepfe", "köpfe", "heads", "kopfsammlung", "vote", "nachtsicht", "nv", "clan", "csethome", "chome", "cdelhome", "cc", "cp", "king")) {
                boolean present = configured.stream().anyMatch(value -> value.equalsIgnoreCase(command));
                if (!present) {
                    getLogger().warning("commands.yml: '" + command + "' fehlt unter " + path
                            + ". Die Datei wird nicht automatisch verändert.");
                }
            }
        }
    }


    public boolean isPlayerInDuel(UUID playerId) {
        return playerId != null && duelManager != null && duelManager.inDuel(playerId);
    }

    public boolean areDuelOpponents(UUID first, UUID second) {
        return duelManager != null && duelManager.areOpponents(first, second);
    }

    @Override
    public boolean shouldAnonymizeIdentity(Player viewer, Player target) {
        return invisibilityAnonymityService != null
                && invisibilityAnonymityService.shouldAnonymize(viewer, target);
    }

    public DailyManager dailyManager() {
        return dailyManager;
    }

    @Override
    protected void registerServerIntegrations() {
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new SMPCorePlaceholderExpansion(this, sharedEconomyService(), sharedRankManager()).register();
            getLogger().info("PlaceholderAPI-Integration aktiviert: %smpcore_balance%, %smpcore_money%, %smpcore_rank%");
        }
    }


    @Override
    protected void initializeLateServerFeatures() {
        smpScoreboardManager = new SmpScoreboardManager(
                this, sharedStatsManager(), sharedEconomyService(), lumiRepository);
        smpScoreboardManager.start();
        if (invisibilityAnonymityService != null) invisibilityAnonymityService.start();
        scheduleSmpRuntimeValidation();
    }

    private void scheduleSmpRuntimeValidation() {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            getLogger().info("========== NovoSMP Stabilitätsprüfung " + getPluginMeta().getVersion() + " ==========");
            File hologramFile = new File(getDataFolder(), "holograms.yml");
            File crateLocationFile = new File(getDataFolder(), "crate-locations.yml");
            getLogger().info("Datenordner: " + getDataFolder().getAbsolutePath());
            getLogger().info("holograms.yml: " + (hologramFile.isFile() ? "vorhanden" : "FEHLT"));
            getLogger().info("crate-locations.yml: " + (crateLocationFile.isFile() ? "vorhanden" : "FEHLT"));

            if (crateManager == null) {
                getLogger().severe("CrateManager wurde nicht initialisiert.");
            } else {
                getLogger().info("Crates: " + crateManager.configuredCrateCount() + " Definitionen, "
                        + crateManager.enabledCrateCount() + " aktiviert, " + crateManager.placementCount() + " Positionen.");
                for (String warning : crateManager.validationWarnings()) {
                    getLogger().warning("Crate-Prüfung: " + warning);
                }
            }

            if (hologramManager() == null) {
                getLogger().severe("HologramManager wurde nicht initialisiert.");
            } else {
                int active = hologramManager().spawnAvailable();
                int configured = hologramManager().configuredEnabledCount();
                int resolvable = hologramManager().resolvableEnabledCount();
                getLogger().info("Hologramme: " + resolvable + "/" + configured
                        + " auflösbar; aktuell " + active + " als TextDisplay geladen.");
                for (String unresolved : hologramManager().unresolvedDescriptions()) {
                    getLogger().warning("Hologramm nicht auflösbar: " + unresolved);
                }
            }
            getLogger().info("======================================================");
        }, 80L);
    }

    @Override
    protected void scheduleServerJoinReminder(Player player) {
        scheduleProfessionSelectionReminder(player);
        if (dailyManager == null || !player.hasPermission("smpcore.daily.use")) return;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) return;
            try {
                if (dailyManager.canClaim(player.getUniqueId())) {
                    FileConfiguration dailyConfig = configs().daily();
                    String configured = dailyConfig.getString(
                            "messages.join-actionbar",
                            "<green><bold>Tägliche Belohnung verfügbar!</bold></green>"
                    );
                    player.sendActionBar(MiniMessage.miniMessage().deserialize(configured));
                }
            } catch (RuntimeException exception) {
                getLogger().warning("Daily-Join-Erinnerung für " + player.getName()
                        + " konnte nicht geprüft werden: " + exception.getMessage());
            }
        }, 40L);
    }

    private void scheduleProfessionSelectionReminder(Player player) {
        if (professionManager == null || !configs().professions().getBoolean("selection-reminder.enabled", true)) {
            return;
        }
        long delayMinutes = Math.max(1L, configs().professions().getLong("selection-reminder.delay-minutes", 10L));
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player online = Bukkit.getPlayer(playerId);
            if (online == null || !online.isOnline() || professionManager == null) return;
            if (!professionManager.state(playerId).activeProfessions().isEmpty()) return;

            String configured = configs().professions().getString(
                    "selection-reminder.message",
                    "<yellow>Du hast noch keinen Beruf ausgewählt. Nutze <green>/berufe</green>.</yellow>"
            );
            online.sendMessage(MiniMessage.miniMessage().deserialize(configured));
        }, delayMinutes * 60L * 20L);
    }

    @Override
    protected void reloadServerFeatures() {
        if (questService != null) questService.reload();
        if (chestLogManager != null) chestLogManager.flushForReload();
        if (worldDamageProtectionListener != null) worldDamageProtectionListener.reload();
        if (combatManager != null) combatManager.reload();
        if (bountyManager != null) bountyManager.reload();
        if (chatColorService != null) chatColorService.refreshAll();
        if (performanceCleanupManager != null) performanceCleanupManager.start();
        if (jumpRunManager != null) jumpRunManager.reload();
        if (rulesCommand != null) rulesCommand.reload();
        if (holzschlagConfig == null || customEnchantments == null || customItemManager == null) return;
        holzschlagConfig.reload();
        miningEnchantConfig.reload();
        customEnchantments.register(holzschlagConfig.enchantment());
        miningEnchantConfig.enchantments().forEach(customEnchantments::register);
        customEnchantments.register(new de.walahi.novosmp.enchants.CustomEnchantment(
                de.walahi.novosmp.enchants.MagnetListener.ID, "Magnet", "<aqua>", 1,
                de.walahi.novosmp.enchants.CustomEnchantment.Applicability.TOOL_OR_WEAPON));
        AnglerEnchantmentDefinitions.register(customEnchantments);
        customItemManager.reload();
        if (fishRegistry != null) fishRegistry.reload();
        if (anglerLootFoundation != null) anglerLootFoundation.reload(configs().angler());
        if (anglerFishingService != null) anglerFishingService.reload();
        if (professionManager != null) professionManager.reload();
        if (treeFellerListener != null) treeFellerListener.clearCooldowns();
        if (miningEnchantListener != null) miningEnchantListener.clearRuntimeState();
    }

    @Override
    protected void stopServerFeatures() {
        if (questMenu != null) { questMenu.stop(); questMenu = null; }
        if (questService != null) { questService.shutdown(); questService = null; }
        if (sitManager != null) { sitManager.shutdown(); sitManager = null; }
        if (chestLogManager != null) { chestLogManager.shutdown(); chestLogManager = null; }
        if (jumpRunManager != null) { jumpRunManager.shutdown(); jumpRunManager = null; }
        if (clanManager != null) { clanManager.shutdown(); clanManager = null; }
        bountyManager = null;
        if (combatManager != null) {
            combatManager.shutdown();
            combatManager = null;
        }
        if (expandableEnderChestManager != null) {
            expandableEnderChestManager.shutdown();
            expandableEnderChestManager = null;
        }
        if (voteManager != null) {
            voteManager.shutdown();
            voteManager = null;
        }
        if (leaderboardProfileCache != null) {
            leaderboardProfileCache.shutdown();
            leaderboardProfileCache = null;
        }
        if (invisibilityAnonymityService != null) {
            invisibilityAnonymityService.stop();
            invisibilityAnonymityService = null;
        }
        if (headCollectionManager != null) {
            headCollectionManager.stop();
            headCollectionManager = null;
        }
        if (professionManager != null) {
            professionManager.stop();
            professionManager = null;
        }
        if (friendManager != null) {
            friendManager.stop();
            friendManager = null;
        }
        if (duelManager != null) {
            duelManager.shutdown();
            duelManager = null;
        }
        if (dragonEggKingService != null) { dragonEggKingService.shutdown(); dragonEggKingService = null; }
        if (smpScoreboardManager != null) {
            smpScoreboardManager.stop();
            smpScoreboardManager = null;
        }
        if (auctionManager != null) {
            auctionManager.stop();
            auctionManager = null;
        }
        if (lumiAfkZoneManager != null) {
            lumiAfkZoneManager.stop();
            lumiAfkZoneManager = null;
        }
        if (crateManager != null) {
            if (chatEventManager != null) chatEventManager.shutdown();
            crateManager.shutdown();
            crateManager = null;
        }
    }
    @Override
    protected AfkAccess createAfkFeature() {
        afkAccess = new AfkManager(this);
        return afkAccess;
    }

    @Override
    public HomeAccess createHomeFeature(EventPublisher events, FileConfiguration config) {
        return new HomeService(this, events, config, sharedDatabaseManager());
    }

    public long lumiBalance(java.util.UUID uuid) {
        return lumiRepository == null || uuid == null ? 0L : lumiRepository.balance(uuid);
    }

    public java.util.Map<java.util.UUID, Long> lumiBalances(java.util.Collection<java.util.UUID> playerIds) {
        return lumiRepository == null ? java.util.Map.of() : lumiRepository.balances(playerIds);
    }

    /** Shared profile cache used by all player-head leaderboard menus. */
    public LeaderboardProfileCache leaderboardProfiles() {
        if (leaderboardProfileCache == null) {
            leaderboardProfileCache = new LeaderboardProfileCache(this);
        }
        return leaderboardProfileCache;
    }

    @Override
    protected StatsAccess createStatsFeature() {
        return new StatsManager(this);
    }

    @Override
    protected RtpAccess createRtpFeature() {
        if (rtpManager == null) {
            rtpManager = new RtpManager(this);
        }
        return rtpManager;
    }

    @Override
    protected TpaAccess createTpaFeature() {
        return new TpaService(this);
    }

    public DatabaseManager sharedDb() { return sharedDatabaseManager(); }

    public FriendManager friendManager() { return friendManager; }

    public CombatManager combatManager() { return combatManager; }

    public BountyManager bountyManager() { return bountyManager; }
    public ClanManager clanManager() { return clanManager; }

    public void broadcastCombatDummyDeath(UUID victimId, String victimName, UUID killerId,
                                          String killerName, Player killer, LivingEntity dummy) {
        if (deathMessageListener != null) {
            deathMessageListener.broadcastCombatDummyDeath(
                    victimId, victimName, killerId, killerName, killer, dummy);
        }
    }

    public void cancelCombatEscape(UUID playerId) {
        cancelPendingTeleport(playerId);
        if (deathBackManager != null) deathBackManager.cancelTeleport(playerId);
    }

    public InvisibilityAnonymityService invisibilityAnonymity() { return invisibilityAnonymityService; }

    @Override
    public void notifyFriendVanishState(Player player, boolean vanished) {
        if (friendManager != null) friendManager.handleVanishState(player, vanished);
    }

    @Override
    public void notifyDndVisibilityState(Player player) {
        if (friendManager != null) friendManager.refreshGlow();
    }

    @Override
    public void refreshVisibleOnlineDisplays() {
        super.refreshVisibleOnlineDisplays();
        if (smpScoreboardManager != null) smpScoreboardManager.refreshNow();
    }
}
