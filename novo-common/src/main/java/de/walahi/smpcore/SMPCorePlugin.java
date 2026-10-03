package de.walahi.smpcore;

import de.walahi.smpcore.rtp.RtpAccess;
import de.walahi.smpcore.homes.HomeAccess;
import de.walahi.smpcore.homes.DisabledHomeAccess;
import de.walahi.smpcore.homes.HomeLimitResolver;
import de.walahi.smpcore.tpa.TpaAccess;
import de.walahi.smpcore.tpa.DisabledTpaAccess;
import de.walahi.smpcore.api.EventPublisher;

import de.walahi.smpcore.database.DatabaseManager;
import de.walahi.smpcore.config.CoreConfigurationSystem;
import de.walahi.smpcore.config.PluginConfigurations;
import de.walahi.smpcore.api.SMPCoreApi;
import de.walahi.smpcore.api.internal.DefaultSMPCoreApi;
import de.walahi.smpcore.bootstrap.StartupLogger;
import de.walahi.smpcore.bootstrap.StartupValidator;
import de.walahi.smpcore.bootstrap.ApiContractVerifier;
import de.walahi.smpcore.bridge.CrateLocator;
import de.walahi.smpcore.bridge.EnderChestAccess;
import de.walahi.smpcore.storage.StorageManager;
import de.walahi.smpcore.stats.StatsAccess;
import de.walahi.smpcore.gui.Gui;
import de.walahi.smpcore.gui.GuiButton;
import de.walahi.smpcore.gui.GuiManager;
import de.walahi.smpcore.hub.HubCompassController;
import de.walahi.smpcore.lifecycle.HologramLifecycle;
import de.walahi.smpcore.location.LocationStore;
import de.walahi.smpcore.teleport.TeleportCoordinator;
import de.walahi.smpcore.punishments.PunishmentManager;
import de.walahi.smpcore.punishments.JdbcPunishmentRepository;
import de.walahi.smpcore.tablist.TablistManager;
import de.walahi.smpcore.tablist.TabTestManager;
import de.walahi.smpcore.tablist.TabVisibilityManager;
import de.walahi.smpcore.ranks.RankManager;
import de.walahi.smpcore.commands.DiscordCommand;
import de.walahi.smpcore.commands.BuildCommand;
import de.walahi.smpcore.commands.BuildActivityListener;
import de.walahi.smpcore.commands.BuildModeManager;
import de.walahi.smpcore.commands.CommandFilter;
import de.walahi.smpcore.commands.CommandVisibilityManager;
import de.walahi.smpcore.commands.StaffCommands;
import de.walahi.smpcore.commands.GamemodeCommand;
import de.walahi.smpcore.commands.KickCommand;
import de.walahi.smpcore.commands.KillCommand;
import de.walahi.smpcore.commands.BanCommand;
import de.walahi.smpcore.commands.TempBanCommand;
import de.walahi.smpcore.commands.MuteCommand;
import de.walahi.smpcore.commands.TempMuteCommand;
import de.walahi.smpcore.commands.UnmuteCommand;
import de.walahi.smpcore.commands.UnbanCommand;
import de.walahi.smpcore.moderation.KickService;
import de.walahi.smpcore.moderation.BanService;
import de.walahi.smpcore.moderation.BanLoginListener;
import de.walahi.smpcore.moderation.MuteService;
import de.walahi.smpcore.moderation.MuteListener;
import de.walahi.smpcore.commands.MessageCommand;
import de.walahi.smpcore.commands.PrivateMessageManager;
import de.walahi.smpcore.commands.ReplyCommand;
import de.walahi.smpcore.commands.SocialSpyCommand;
import de.walahi.smpcore.commands.TeleportCommand;
import de.walahi.smpcore.commands.framework.CommandRegistry;
import de.walahi.smpcore.commands.HistoryCommand;
import de.walahi.smpcore.commands.PingCommand;
import de.walahi.smpcore.messages.MessageService;
import de.walahi.smpcore.network.NetworkManager;
import de.walahi.smpcore.network.ServerType;
import de.walahi.smpcore.services.ServiceManager;
import de.walahi.smpcore.services.EconomyService;
import de.walahi.smpcore.services.SaveManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.Locale;
import java.util.logging.Level;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.sql.SQLException;

import java.time.Instant;

public class SMPCorePlugin extends JavaPlugin implements Listener {

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Set<String> smpWorlds = new HashSet<>();
    private final Set<UUID> voidRescueTeleports = new HashSet<>();

    private LocationStore locationStore;
    private HologramManager hologramManager;
    private HologramLifecycle hologramLifecycle;
    private HubCompassController hubCompassController;
    private PlayerSessionLifecycle playerSessionLifecycle;
    private TeleportCoordinator teleportCoordinator;
    private RtpAccess rtpManager;
    private final Set<UUID> rtpSearches = new HashSet<>();
    /** Eindeutige Generation je RTP-Anfrage, damit alte asynchrone Suchergebnisse niemals eine neue Anfrage beeinflussen. */
    private final Map<UUID, UUID> rtpSearchTokens = new HashMap<>();
    private final Map<UUID, Long> rtpCooldowns = new HashMap<>();
    private TpaAccess tpaAccess = new DisabledTpaAccess();
    private StatsAccess statsManager;
    private TablistManager tablistManager;
    private RankManager rankManager;
    private TabTestManager tabTestManager;
    private TabVisibilityManager tabVisibilityManager;
    private CrateLocator crateLocator;
    private EnderChestAccess enderChestAccess;
    private CommandVisibilityManager commandVisibilityManager;
    private BuildModeManager buildModeManager;
    private StaffCommands staffCommands;
    private DatabaseManager databaseManager;
    private StorageManager storageManager;
    private GuiManager guiManager;
    private PunishmentManager punishmentManager;
    private BukkitTask punishmentCleanupTask;
    private de.walahi.smpcore.afk.AfkAccess afkAccess = new de.walahi.smpcore.afk.DisabledAfkAccess();
    private MessageService messageService;
    private de.walahi.smpcore.sounds.SoundManager soundManager;
    private ServerType serverType;
    private Instant startedAt;
    private NetworkManager networkManager;
    private ServiceManager serviceManager;
    private SaveManager saveManager;
    private CoreConfigurationSystem configurationSystem;

    /** Vom jeweiligen eigenständigen Plugin fest vorgegebener Servertyp. */
    protected ServerType forcedServerType() {
        return ServerType.HUB;
    }

    /** Eindeutige eingebettete Standarddatei des jeweiligen Plugins. */
    protected String localConfigResourceName() {
        return forcedServerType() == ServerType.HUB ? "novo-hub.yml" : "novo-smp.yml";
    }

    @Override
    public void onEnable() {
        startedAt = Instant.now();
        StartupLogger startup = new StartupLogger(this);
        configurationSystem = new CoreConfigurationSystem(this, localConfigResourceName(), forcedServerType());
        configurationSystem.initialize();
        startup.phase("Konfiguration geladen");
        serverType = forcedServerType();
        networkManager = new NetworkManager(this);
        networkManager.register();
        messageService = new MessageService(this);
        soundManager = new de.walahi.smpcore.sounds.SoundManager(this);
        guiManager = new GuiManager();
        locationStore = new LocationStore(this);
        locationStore.load();
        hubCompassController = new HubCompassController(this);
        playerSessionLifecycle = new PlayerSessionLifecycle(this, locationStore, hubCompassController);
        teleportCoordinator = new TeleportCoordinator(this, this::clearRtpSearch);
        hologramLifecycle = new HologramLifecycle(this, () -> hologramManager);
        loadSettings(false);
        normalizeCommandsConfiguration();
        if (!initializeDatabase()) {
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        startup.phase("Storage initialisiert");
        if (!new StartupValidator(this).validate()) {
            getLogger().severe("Startup-Validierung fehlgeschlagen. SMPCore wird deaktiviert.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        startup.phase("Startup validiert");
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(hubCompassController, this);
        Bukkit.getPluginManager().registerEvents(playerSessionLifecycle, this);
        Bukkit.getPluginManager().registerEvents(teleportCoordinator, this);
        Bukkit.getPluginManager().registerEvents(hologramLifecycle, this);
        registerServerListeners();
        initializeEarlyServerFeatures();
        afkAccess = createAfkFeature();
        if (afkAccess instanceof Listener listener) {
            Bukkit.getPluginManager().registerEvents(listener, this);
        }
        afkAccess.start();
        tpaAccess = createTpaFeature();
        if (tpaAccess instanceof Listener listener) {
            Bukkit.getPluginManager().registerEvents(listener, this);
        }
        if (isSmpServer()) {
            statsManager = createStatsFeature();
            if (statsManager instanceof Listener listener) {
                Bukkit.getPluginManager().registerEvents(listener, this);
            }
        }
        Bukkit.getPluginManager().registerEvents(guiManager, this);
        commandVisibilityManager = new CommandVisibilityManager(this);
        buildModeManager = new BuildModeManager(this, commandVisibilityManager);
        Bukkit.getPluginManager().registerEvents(new CommandFilter(this, commandVisibilityManager, buildModeManager), this);
        // Nach dem vollständigen Plugin-Start die Brigadier-Befehlsliste neu senden.
        // Dadurch verschwinden auch Befehle von Plugins, die ihre Commands erst später registrieren.
        Bukkit.getScheduler().runTaskLater(this, () -> Bukkit.getOnlinePlayers().forEach(Player::updateCommands), 20L);
        Bukkit.getScheduler().runTaskLater(this, () -> Bukkit.getOnlinePlayers().forEach(Player::updateCommands), 60L);
        Bukkit.getPluginManager().registerEvents(new BuildActivityListener(buildModeManager), this);
        BuildCommand buildCommand = new BuildCommand(buildModeManager, commandVisibilityManager);
        if (getCommand("bau") != null) {
            getCommand("bau").setExecutor(buildCommand);
            getCommand("bau").setTabCompleter(buildCommand);
        }
        rankManager = new RankManager(this);
        staffCommands = new StaffCommands(this);
        if (isSmpServer()) {
            staffCommands.register("fly", "vanish", "dnd", "list", "ec", "ecsee", "invsee");
        } else {
            staffCommands.register("fly", "vanish", "invsee");
        }
        CommandRegistry commandRegistry = new CommandRegistry(this);
        commandRegistry.register("discord", new DiscordCommand(this));
        serviceManager = new ServiceManager(
                this,
                rankManager,
                punishmentManager,
                configs().main(),
                this::isSmpWorld
        );
        serviceManager.start();
        saveManager = new SaveManager(this, serviceManager.playerData());
        saveManager.start();
        Bukkit.getServicesManager().register(
                SMPCoreApi.class,
                new DefaultSMPCoreApi(getPluginMeta().getVersion(), serverType, startedAt, serviceManager),
                this,
                ServicePriority.Normal
        );
        if (!new ApiContractVerifier(this).verify()) {
            getLogger().severe("Öffentliche API ist inkonsistent. SMPCore wird deaktiviert.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        startup.phase("Services und API verifiziert");
        KickService kickService = serviceManager.kicks();
        BanService banService = serviceManager.bans();
        MuteService muteService = serviceManager.mutes();
        commandRegistry.register("kick", new KickCommand(this, kickService));
        commandRegistry.register("ban", new BanCommand(this, banService));
        commandRegistry.register("tempban", new TempBanCommand(this, banService));
        commandRegistry.register("unban", new UnbanCommand(this, banService));
        commandRegistry.register("mute", new MuteCommand(this, muteService));
        commandRegistry.register("tempmute", new TempMuteCommand(this, muteService));
        commandRegistry.register("unmute", new UnmuteCommand(this, muteService));
        commandRegistry.register("history", new HistoryCommand(this, serviceManager.punishments()));
        commandRegistry.register("ping", new PingCommand(this));
        registerServerCommands(commandRegistry);
        registerServerIntegrations();
        commandRegistry.register("kill", new KillCommand(this));
        Bukkit.getPluginManager().registerEvents(new BanLoginListener(serviceManager.punishments(), banService), this);
        Bukkit.getPluginManager().registerEvents(new MuteListener(this, muteService), this);
        commandRegistry.register("gamemode", new GamemodeCommand(this));
        commandRegistry.register("tp", new TeleportCommand(this));
        PrivateMessageManager privateMessageManager = new PrivateMessageManager(
                this,
                messageService,
                rankManager,
                soundManager
        );
        commandRegistry.register("msg", new MessageCommand(this, privateMessageManager));
        commandRegistry.register("reply", new ReplyCommand(this, privateMessageManager));
        commandRegistry.register("socialspy", new SocialSpyCommand(this, privateMessageManager));
        Bukkit.getPluginManager().registerEvents(privateMessageManager, this);
        startup.phase("Commands registriert");
        Bukkit.getPluginManager().registerEvents(staffCommands, this);
        tablistManager = new TablistManager(this);
        tabTestManager = new TabTestManager(this, rankManager);
        tabVisibilityManager = new TabVisibilityManager(this, rankManager);
        Bukkit.getPluginManager().registerEvents(tabVisibilityManager, this);
        tablistManager.start();
        tabVisibilityManager.start();
        hologramManager = new HologramManager(this);
        if (isSmpServer()) {
            rtpManager = createRtpFeature();
            if (rtpManager == null) {
                getLogger().severe("NovoSMP hat keine RTP-Implementierung bereitgestellt. RTP bleibt deaktiviert.");
            }
        }
        initializeLateServerFeatures();
        startPunishmentCleanup();
        startup.phase("Listener und Manager gestartet");
        // Hologramme werden erst im ServerLoadEvent erzeugt. Zu diesem Zeitpunkt
        // sind auch Multiverse-Welten wie "hub" vollständig geladen.
        startup.complete(serverType.name());
    }

    /** Hooks used by the concrete server plugin. NovoCommon never imports Hub/SMP feature classes. */
    protected void initializeEarlyServerFeatures() { }
    protected void initializeLateServerFeatures() { }
    protected void registerServerListeners() { }
    protected de.walahi.smpcore.afk.AfkAccess createAfkFeature() { return new de.walahi.smpcore.afk.DisabledAfkAccess(); }
    protected void registerServerCommands(CommandRegistry commandRegistry) { }
    protected void registerServerIntegrations() { }
    protected void scheduleServerJoinReminder(Player player) { }
    protected void stopServerFeatures() { }
    protected void reloadServerFeatures() { }
    protected void applyServerPlayerProfile(Player player) { }

    /**
     * Viewer-spezifische öffentliche Identität. Common bleibt standardmäßig unverändert;
     * NovoSMP kann hier seine Unsichtbarkeits-Anonymität einhängen.
     */
    public boolean shouldAnonymizeIdentity(Player viewer, Player target) { return false; }

    /** NovoSMP owns home persistence. NovoHub receives a non-persistent disabled implementation. */
    public HomeAccess createHomeFeature(EventPublisher events, FileConfiguration config) {
        return new DisabledHomeAccess();
    }

    /** SMP module supplies the concrete statistics implementation. */
    protected StatsAccess createStatsFeature() { return null; }

    /** SMP module supplies the concrete RTP implementation. */
    protected RtpAccess createRtpFeature() { return null; }

    /** SMP module owns all TPA request state and teleport handling. */
    protected TpaAccess createTpaFeature() { return new DisabledTpaAccess(); }

    protected final HologramManager hologramManager() { return hologramManager; }
    protected final StatsAccess sharedStatsManager() { return statsManager; }
    public final StatsAccess statsAccess() { return statsManager; }
    public final TpaAccess tpaAccess() { return tpaAccess; }
    public final HomeAccess homeAccess() { return serviceManager.homes(); }
    protected final EconomyService sharedEconomyService() {
        return serviceManager == null ? null : serviceManager.economy();
    }
    protected final DatabaseManager sharedDatabaseManager() { return databaseManager; }
    protected final StorageManager sharedStorageManager() { return storageManager; }
    protected final RankManager sharedRankManager() { return rankManager; }
    protected final void registerCrateLocator(CrateLocator locator) { this.crateLocator = locator; }
    protected final void registerEnderChestAccess(EnderChestAccess access) { this.enderChestAccess = access; }

    public BuildModeManager getBuildModeManager() { return buildModeManager; }
    public RankManager getRankManager() { return rankManager; }
    public StaffCommands getStaffCommands() { return staffCommands; }
    public EnderChestAccess expandableEnderChestManager() { return enderChestAccess; }
    public TabVisibilityManager getTabVisibilityManager() { return tabVisibilityManager; }
    public YamlConfiguration getCommandConfiguration() { return commandVisibilityManager.configuration(); }
    public de.walahi.smpcore.afk.AfkAccess getAfkManager() { return afkAccess; }
    public boolean isHubServer() { return serverType == ServerType.HUB; }
    public boolean isSmpServer() { return serverType == ServerType.SMP; }
    /** NovoSMP is a dedicated SMP server: every loaded world on it is an SMP world, including smp_spawn. */
    public boolean isSmpGameplayWorld(World world) { return world != null && isSmpServer(); }
    public NetworkManager getNetworkManager() { return networkManager; }

    /**
     * Zentrale, viewerabhaengige Online-Anzahl fuer sichtbare Anzeigen.
     * Technisch bleiben Vanish- und DND-Spieler online; sichtbare Anzeigen
     * ziehen nur lokal verborgene Spieler für den jeweiligen Betrachter ab.
     */
    public int visibleOnlineCount(Player viewer, int rawOnline, Predicate<Player> scope) {
        if (staffCommands == null) return Math.max(0, rawOnline);
        long hidden = Bukkit.getOnlinePlayers().stream()
                .filter(scope)
                .filter(target -> staffCommands.isDnd(target)
                        && (viewer == null || !staffCommands.canSeeDnd(viewer, target))
                        || staffCommands.isVanished(target)
                        && (viewer == null || !staffCommands.canSeeVanished(viewer, target)))
                .count();
        return Math.max(0, rawOnline - (int) hidden);
    }

    /** Aktualisiert alle sichtbaren Online-Anzeigen unmittelbar. */
    public void refreshVisibleOnlineDisplays() {
        if (tablistManager != null) tablistManager.refreshNow();
    }
    public StorageManager storageManager() { return storageManager; }
    public DatabaseManager databaseManager() { return databaseManager; }
    public ServiceManager services() { return serviceManager; }
    public CrateLocator crates() { return crateLocator; }
    private boolean initializeDatabase() {
        storageManager = new StorageManager(this);
        databaseManager = new DatabaseManager(this, storageManager, serverType);
        try {
            storageManager.initialize();
            databaseManager.initialize();
            punishmentManager = new PunishmentManager(this, new JdbcPunishmentRepository(databaseManager));
            punishmentManager.cleanupExpired();
            return true;
        } catch (SQLException exception) {
            getLogger().log(Level.SEVERE, "Storage konnte nicht initialisiert werden. SMPCore wird deaktiviert.", exception);
            databaseManager.close();
            storageManager.close();
            return false;
        }
    }

    private void startPunishmentCleanup() {
        if (serviceManager == null) return;
        punishmentCleanupTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                this,
                serviceManager.punishments()::cleanupExpired,
                20L * 60L,
                20L * 60L * 5L
        );
    }





    @Override
    public void onDisable() {
        Bukkit.getServicesManager().unregisterAll(this);
        if (networkManager != null) networkManager.unregister();
        afkAccess.shutdown();
        if (saveManager != null) {
            saveManager.saveAllOnlinePlayers();
            saveManager.stop();
        }
        if (serviceManager != null) serviceManager.stop();
        if (punishmentCleanupTask != null) {
            punishmentCleanupTask.cancel();
            punishmentCleanupTask = null;
        }
        if (buildModeManager != null) buildModeManager.disableAll();
        stopServerFeatures();
        if (tabTestManager != null) tabTestManager.hideAll();
        if (tabVisibilityManager != null) tabVisibilityManager.stop();
        if (tablistManager != null) tablistManager.stop();
        if (statsManager != null) statsManager.shutdown();
        if (teleportCoordinator != null) teleportCoordinator.shutdown();
        rtpSearches.clear();
        rtpSearchTokens.clear();
        rtpCooldowns.clear();
        tpaAccess.shutdown();
        if (playerSessionLifecycle != null) playerSessionLifecycle.clear();
        if (hologramLifecycle != null) hologramLifecycle.shutdown();
        if (hologramManager != null) hologramManager.removeAll();
        if (locationStore != null) locationStore.save();
        if (databaseManager != null) databaseManager.close();
        if (storageManager != null) storageManager.close();
    }


    /**
     * Einmalige Bereinigung alter, mehrfach verschachtelter commands.yml-Versionen.
     * Hub und SMP teilen dieselbe Datei; deshalb läuft die Migration bereits im Common-Core.
     */
    private void normalizeCommandsConfiguration() {
        var commandsFile = configs().commandsFile();
        YamlConfiguration current = commandsFile.yaml();
        if (current.contains("schema-version", true) && current.getInt("schema-version", 0) >= 2) return;

        try (InputStream stream = getResource(commandsFile.resourceName())) {
            if (stream == null) {
                getLogger().warning("commands.yml konnte nicht auf das neue Schema bereinigt werden: Resource fehlt.");
                return;
            }

            YamlConfiguration bundled = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            for (String key : Set.copyOf(current.getKeys(false))) {
                current.set(key, null);
            }
            for (String key : bundled.getKeys(true)) {
                if (!bundled.isConfigurationSection(key)) {
                    current.set(key, bundled.get(key));
                }
            }
            commandsFile.save();
            commandsFile.reload();
            getLogger().info("commands.yml auf Schema 2 bereinigt: Hub nur /smp, SMP-Befehle getrennt.");
        } catch (Exception exception) {
            getLogger().log(Level.WARNING, "commands.yml konnte nicht bereinigt werden.", exception);
        }
    }

    private void loadSettings(boolean reloadConfigurations) {
        if (reloadConfigurations) configurationSystem.reloadAll();
        serverType = forcedServerType();
        smpWorlds.clear();
        if (isSmpServer()) {
            String spawn = configs().server().getString("spawn-world", "smp_spawn");
            if (spawn != null && !spawn.isBlank()) smpWorlds.add(spawn);
            for (String worldName : configs().server().getStringList("worlds.smp")) {
                if (worldName != null && !worldName.isBlank()
                        && smpWorlds.stream().noneMatch(existing -> existing.equalsIgnoreCase(worldName))) {
                    smpWorlds.add(worldName);
                }
            }
            for (String worldName : configs().server().getStringList("survival-worlds")) {
                if (worldName != null && !worldName.isBlank()
                        && smpWorlds.stream().noneMatch(existing -> existing.equalsIgnoreCase(worldName))) {
                    smpWorlds.add(worldName);
                }
            }
        } else {
            smpWorlds.addAll(configs().server().getStringList("worlds.smp"));
        }
    }

    /**
     * Backward-compatible access to the server-local config.yml only.
     * New code must use {@link #configs()} so the owning YAML file is explicit.
     */
    @Override
    public FileConfiguration getConfig() {
        return configurationSystem == null ? super.getConfig() : configurationSystem.configurations().server();
    }

    @Override
    public void reloadConfig() {
        if (configurationSystem == null) {
            super.reloadConfig();
        } else {
            configurationSystem.reloadAll();
        }
    }

    public PluginConfigurations configs() {
        if (configurationSystem == null) {
            throw new IllegalStateException("Konfigurationen wurden noch nicht initialisiert.");
        }
        return configurationSystem.configurations();
    }

    public File getSharedFolder() {
        return configurationSystem == null ? getDataFolder() : configurationSystem.sharedFolder();
    }

    /** Globale Netzwerk-Konfigurationen, die HUB und SMP gemeinsam verwenden. */
    public File getGlobalFolder() {
        return configurationSystem == null ? getSharedFolder() : configurationSystem.globalFolder();
    }

    /** Servertypspezifische Konfigurationen unter shared/SMPCore/hub bzw. /smp. */
    public File getServerConfigFolder() {
        return configurationSystem == null ? getDataFolder() : configurationSystem.serverFolder();
    }

    /** Rückwärtskompatibel: shared Dateien sind ab 11.17.0 globale Dateien. */
    public File getSharedFile(String name) {
        return getGlobalFile(name);
    }

    public File getGlobalFile(String name) {
        return configurationSystem == null ? new File(getGlobalFolder(), name) : configurationSystem.globalFile(name);
    }

    public File getServerConfigFile(String name) {
        return configurationSystem == null ? new File(getServerConfigFolder(), name) : configurationSystem.serverFile(name);
    }

    public void ensureSharedResource(String resourceName) {
        configurationSystem.ensureGlobalResource(resourceName);
    }

    public void ensureServerResource(String resourceName) {
        configurationSystem.ensureServerResource(resourceName);
    }










    







    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (playerSessionLifecycle != null) playerSessionLifecycle.handleQuit(event);
        teleportCoordinator.cancel(event.getPlayer().getUniqueId());
        rtpSearches.remove(event.getPlayer().getUniqueId());
        rtpSearchTokens.remove(event.getPlayer().getUniqueId());
        tpaAccess.handleQuit(event.getPlayer());
        if (buildModeManager != null) buildModeManager.disableOnQuit(event.getPlayer());

    }


    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() != null) {
            handleVoidRescue(event.getPlayer(), event.getTo());
        }
    }

    private boolean handleVoidRescue(Player player, Location location) {
        if (!configs().main().getBoolean("void-rescue.enabled", true)) {
            return false;
        }

        UUID uuid = player.getUniqueId();
        if (voidRescueTeleports.contains(uuid)) {
            return true;
        }

        String worldName = location.getWorld() == null ? "" : location.getWorld().getName();
        String hubWorld = configs().server().getString("world", "world");
        String smpSpawnWorld = configs().server().getString("spawn-world", "smp_spawn");

        Location target = null;
        String messagePath = null;

        if (worldName.equalsIgnoreCase(hubWorld)
                && location.getY() <= configs().main().getDouble("void-rescue.hub-min-y", -10.0)) {
            target = loadFixedLocation("locations.hub");
            if (target == null) {
                target = fallbackWorldSpawn(hubWorld);
            }
            messagePath = "messages.void-rescue-hub";
        } else if (worldName.equalsIgnoreCase(smpSpawnWorld)
                && location.getY() <= configs().main().getDouble("void-rescue.smp-spawn-min-y", -10.0)) {
            target = loadFixedLocation("locations.smp-spawn");
            if (target == null) {
                target = fallbackWorldSpawn(smpSpawnWorld);
            }
            messagePath = "messages.void-rescue-spawn";
        }

        if (target == null) {
            return false;
        }

        teleportCoordinator.cancel(uuid);
        player.sendActionBar(Component.empty());
        voidRescueTeleports.add(uuid);

        Location finalTarget = target;
        String finalMessagePath = messagePath;
        player.teleportAsync(finalTarget).thenAccept(success ->
                Bukkit.getScheduler().runTask(this, () -> {
                    voidRescueTeleports.remove(uuid);
                    if (!success || !player.isOnline()) {
                        return;
                    }

                    if (configs().main().getBoolean("void-rescue.message.enabled", false)
                            && finalMessagePath != null && !finalMessagePath.isBlank()) {
                        send(player, finalMessagePath);
                    }
                    playConfiguredSound(player, "sounds.success");
                    if (isHubWorld(player.getWorld())) {
                        hubCompassController.give(player);
                    }
                })
        );
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase();

        if (name.equals("smp")) {
            if (args.length == 0) {
                if (!(sender instanceof Player player)) return playerOnly(sender);
                if (!player.hasPermission("smpcore.smp")) return noPermission(player);
                goToSmp(player);
                return true;
            }

            if (!isHubServer()) {
                sender.sendMessage("§cBenutzung: /smp");
                return true;
            }
            if (!sender.hasPermission("smpcore.smp.others")) return noPermission(sender);
            if (args.length != 1) {
                sender.sendMessage("§cBenutzung: /smp <Spieler>");
                return true;
            }

            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null || !target.isOnline()) {
                sender.sendMessage("§cDieser Spieler ist nicht auf dem Hub online.");
                return true;
            }

            goToSmp(target);
            if (!(sender instanceof Player player) || !player.getUniqueId().equals(target.getUniqueId())) {
                sender.sendMessage("§a" + target.getName() + " wird auf den SMP geschickt.");
            }
            return true;
        }

        if (name.equals("hub")) {
            if (!(sender instanceof Player player)) return playerOnly(sender);
            if (!player.hasPermission("smpcore.hub")) return noPermission(player);
            goToHub(player);
            return true;
        }

        if (name.equals("tabtest")) {
            if (!(sender instanceof Player player)) return playerOnly(sender);
            if (!player.hasPermission("smpcore.tabtest")) return noPermission(player);
            if (args.length == 0) {
                player.sendMessage("§eBenutzung: /tabtest <Anzahl|off>");
                return true;
            }
            if (args[0].equalsIgnoreCase("off")) {
                if (tabTestManager != null) tabTestManager.hide(player);
                player.sendMessage("§aTablist-Simulation deaktiviert.");
                return true;
            }
            if (tabTestManager == null || !tabTestManager.isAvailable()) {
                player.sendMessage("§cFür /tabtest muss ProtocolLib 5.4.0 oder neuer installiert sein.");
                return true;
            }
            try {
                int amount = Integer.parseInt(args[0]);
                int shown = tabTestManager.show(player, amount);
                player.sendMessage("§aDu siehst jetzt §f" + shown + " §asimulierte Tablist-Spieler. §7/tabtest off");
            } catch (NumberFormatException exception) {
                player.sendMessage("§cBitte gib eine Zahl oder 'off' an.");
            } catch (Exception exception) {
                getLogger().log(java.util.logging.Level.SEVERE, "Tabtest konnte nicht angezeigt werden", exception);
                player.sendMessage("§cDie Tablist-Simulation konnte nicht erstellt werden. Prüfe ProtocolLib und die Konsole.");
            }
            return true;
        }

        if (name.equals("smpcore")) {
            return handleAdmin(sender, args);
        }

        return false;
    }

    private String configuredHubServer() {
        String path = isHubServer() ? "velocity.this-server" : "velocity.hub-server";
        return configs().server().getString(path, "hub");
    }

    private String configuredSmpServer() {
        String path = isSmpServer() ? "velocity.this-server" : "velocity.smp-server";
        return configs().server().getString(path, "smp");
    }

    private void goToSmp(Player player) {
        if (isHubServer()) {
            networkManager.connect(player, configuredSmpServer());
            return;
        }

        Location target = loadPlayerLocation(player);

        if (target != null) {
            getLogger().info("SMP-Ziel für " + player.getName() + ": "
                    + formatLocation(target) + " (gespeicherte Position)");
            beginTeleport(player, target, "messages.teleported-to-smp");
            return;
        }

        getLogger().warning("Keine gespeicherte SMP-Position für " + player.getName()
                + " / UUID " + player.getUniqueId() + " gefunden. Nutze SMP-Spawn.");

        if (configs().main().getBoolean("settings.use-smp-spawn-as-fallback", true)) {
            target = loadFixedLocation("locations.smp-spawn");
            if (target == null) {
                target = fallbackWorldSpawn(configs().server().getString("spawn-world", "smp_spawn"));
            }
        }

        if (target == null) {
            send(player, "messages.missing-location");
            return;
        }

        beginTeleport(player, target, "messages.teleported-to-smp");
    }

    private void goToHub(Player player) {
        if (isSmpServer()) {
            if (configs().main().getBoolean("settings.save-last-smp-location", true)
                    && isSmpWorld(player.getWorld())) {
                savePlayerLocation(player, player.getLocation());
            }
            networkManager.connect(player, configuredHubServer());
            return;
        }

        if (configs().main().getBoolean("settings.save-last-smp-location", true)
                && isSmpWorld(player.getWorld())) {
            savePlayerLocation(player, player.getLocation());
        }

        Location target = loadFixedLocation("locations.hub");
        if (target == null) {
            target = fallbackWorldSpawn(configs().server().getString("world", "world"));
        }

        if (target == null) {
            send(player, "messages.missing-location");
            return;
        }

        beginTeleport(player, target, "messages.teleported-to-hub");

        int delaySeconds = Math.max(0, configs().main().getInt("settings.teleport-delay-seconds", 0));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline() && isHubWorld(player.getWorld())) {
                hubCompassController.give(player);
            }
        }, delaySeconds * 20L + 5L);
    }




    /**
     * Prüft, ob zwei Spieler über diesen lokalen Paper-Server miteinander
     * interagieren dürfen. Durch die Velocity-Trennung sieht ein Hub- oder
     * SMP-Server ohnehin nur seine eigenen Online-Spieler; verschiedene
     * Welten desselben Servers gehören daher zum selben Kommunikationsbereich.
     */
    public boolean isSamePlayerArea(Player first, Player second) {
        return first != null
                && second != null
                && first.isOnline()
                && second.isOnline();
    }

    private boolean isHubWorld(World world) {
        return world != null && isHubServer();
    }

    private Component parseMiniMessage(String text) {
        return miniMessage.deserialize(text == null ? "" : text);
    }





    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (staffCommands != null && staffCommands.isVanished(player)) {
            staffCommands.reapplyVanishState(player);
        }
        if (staffCommands != null && staffCommands.isDnd(player)) {
            staffCommands.reapplyDndVisibility(player);
        }
    }






    /** Called after the local vanish state changed so server-specific friend features can update. */
    public void notifyFriendVanishState(Player player, boolean vanished) {
        // Optional server-specific implementation.
    }

    /** Refreshes social visibility for DND without pretending to be moderation vanish. */
    public void notifyDndVisibilityState(Player player) {
        // Optional server-specific implementation.
    }

    public void setDuelTabPair(Player first, Player second) {
        if (tabVisibilityManager != null) tabVisibilityManager.setDuelPair(first, second);
    }

    public void clearDuelTabPair(UUID first, UUID second) {
        if (tabVisibilityManager != null) tabVisibilityManager.clearDuelPair(first, second);
    }

    public CommandVisibilityManager getCommandVisibilityManager() {
        return commandVisibilityManager;
    }

    /** Entry point used by the dedicated NovoSMP spawn command. */
    public void teleportToSmpSpawn(Player player) {
        goToSpawn(player);
    }

    /** Immediate SMP spawn target for controlled server systems such as duels. */
    public Location getSmpSpawnLocation() {
        Location target = loadFixedLocation("locations.smp-spawn");
        if (target == null) {
            target = fallbackWorldSpawn(configs().server().getString("spawn-world", "smp_spawn"));
        }
        return target == null ? null : target.clone();
    }

    /** Persists the dedicated SMP spawn and updates the Bukkit world spawn. */
    public void setSmpSpawn(Location location) {
        if (location == null) return;
        Location spawnLocation = location.clone();
        saveFixedLocation("locations.smp-spawn", spawnLocation);
        if (spawnLocation.getWorld() != null) {
            spawnLocation.getWorld().setSpawnLocation(spawnLocation);
        }
    }

    private void goToSpawn(Player player) {
        Location target = loadFixedLocation("locations.smp-spawn");
        if (target == null) {
            target = fallbackWorldSpawn(configs().server().getString("spawn-world", "smp_spawn"));
        }

        if (target == null) {
            send(player, "messages.missing-location");
            return;
        }

        beginSpawnTeleport(player, target);
    }

    public final void openRtpMenu(Player player) {
        String configuredTitle = configs().menus().getString("rtp.menu.title", "&8RTP-Auswahl");
        Component title = Component.text(colorize(configuredTitle));
        int rows = Math.max(1, Math.min(6, configs().menus().getInt("rtp.menu.rows", 3)));
        Gui menu = new Gui(rows, title);

        Material fillerMaterial = getConfiguredMaterial(
                "rtp.menu.filler.material",
                Material.GRAY_STAINED_GLASS_PANE
        );
        String fillerName = colorize(configs().menus().getString("rtp.menu.filler.name", " "));
        menu.filler(createMenuItem(fillerMaterial, fillerName, List.of()));

        setConfiguredRtpButton(menu, player, "overworld", 11, Material.GRASS_BLOCK,
                configs().menus().getString("rtp.worlds.overworld", "smp_world"));
        setConfiguredRtpButton(menu, player, "nether", 13, Material.NETHERRACK,
                configs().menus().getString("rtp.worlds.nether", "smp_nether"));
        setConfiguredRtpButton(menu, player, "end", 15, Material.END_STONE,
                configs().menus().getString("rtp.worlds.end", "smp_end"));

        menu.open(player);
    }

    private void setConfiguredRtpButton(
            Gui menu,
            Player player,
            String id,
            int defaultSlot,
            Material defaultMaterial,
            String worldName
    ) {
        String base = "rtp.menu.items." + id;
        int slot = configs().menus().getInt(base + ".slot", defaultSlot);
        int menuSize = Math.max(1, Math.min(6, configs().menus().getInt("rtp.menu.rows", 3))) * 9;

        if (slot < 0 || slot >= menuSize) {
            getLogger().warning("Ungültiger RTP-Menü-Slot für '" + id + "': " + slot);
            return;
        }

        Material material = getConfiguredMaterial(base + ".material", defaultMaterial);
        String name = colorize(configs().menus().getString(base + ".name", id));
        List<String> lore = configs().menus().getStringList(base + ".lore");
        ItemStack item = createMenuItem(material, name, lore);

        menu.button(slot, new GuiButton(item, event -> {
            player.closeInventory();
            beginRtp(player, worldName);
        }));
    }

    private Material getConfiguredMaterial(String path, Material fallback) {
        String configured = configs().menus().getString(path, fallback.name());

        try {
            return Material.valueOf(configured.toUpperCase());
        } catch (IllegalArgumentException exception) {
            getLogger().warning("Ungültiges Material in " + path + ": " + configured);
            return fallback;
        }
    }

    private ItemStack createMenuItem(Material material, String name, List<String> loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(colorize(name)));

        if (!loreLines.isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String line : loreLines) {
                lore.add(Component.text(colorize(line)));
            }
            meta.lore(lore);
        }

        item.setItemMeta(meta);
        return item;
    }

    private String colorize(String text) {
        return text == null ? "" : text.replace('&', '§');
    }


    private void beginRtp(Player player, String worldName) {
        if (!configs().menus().getBoolean("rtp.enabled", true)) {
            send(player, "messages.rtp-disabled");
            return;
        }

        UUID uuid = player.getUniqueId();
        if (rtpSearches.contains(uuid)) {
            send(player, "messages.rtp-already-searching");
            return;
        }

    boolean bypassCooldown =
        player.hasPermission("smpcore.bypass.cooldown")
        || player.hasPermission("smpcore.rtp.bypass.cooldown");

if (!bypassCooldown) {
    long now = System.currentTimeMillis();
    long readyAt = rtpCooldowns.getOrDefault(uuid, 0L);

    if (readyAt > now) {
        long remaining = Math.max(1L, (readyAt - now + 999L) / 1000L);
        send(player,
                "messages.rtp-cooldown",
                "<seconds>",
                Long.toString(remaining));
        return;
    }
}

        teleportCoordinator.cancel(uuid);
        rtpSearches.add(uuid);
        UUID searchToken = UUID.randomUUID();
        rtpSearchTokens.put(uuid, searchToken);
        send(player, "messages.rtp-searching");

        if (rtpManager == null) {
            rtpSearches.remove(uuid);
            send(player, "messages.rtp-failed");
            return;
        }

        beginRtpCountdownAndSearch(player, worldName, searchToken);
    }

    /**
     * Startet den sichtbaren RTP-Countdown sofort beim Ausführen des Befehls.
     * Die sichere Position wird währenddessen asynchron gesucht. Ist die Suche
     * nach Ablauf des Countdowns noch nicht fertig, bleibt der Spieler in einer
     * kurzen Suchanzeige, bis das Ergebnis vorliegt.
     */
    private void beginRtpCountdownAndSearch(Player player, String worldName, UUID searchToken) {
        UUID uuid = player.getUniqueId();
        boolean bypassDelay =
                player.hasPermission("smpcore.bypass.delay")
                        || player.hasPermission("smpcore.rtp.bypass.delay");

        int configuredDelay = Math.max(0, configs().menus().getInt("rtp.delay-seconds", 3));
        int delay = bypassDelay ? 0 : configuredDelay;
        AtomicReference<Location> targetRef = new AtomicReference<>();
        AtomicBoolean searchFinished = new AtomicBoolean(false);
        AtomicBoolean teleportStarted = new AtomicBoolean(false);
        final int[] remaining = {delay};

        // Die erste Anzeige wird bewusst direkt in diesem Methodenaufruf gesendet.
        // Dadurch sieht der Spieler sofort nach dem Klick, dass RTP angenommen wurde,
        // noch bevor irgendein Chunk geladen oder eine sichere Position berechnet ist.
        if (remaining[0] > 0) {
            sendRtpCountdownFeedback(player, remaining[0]--);
        } else {
            sendRtpSearchFeedback(player);
        }

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!player.isOnline()) {
                rtpSearches.remove(uuid);
                rtpSearchTokens.remove(uuid, searchToken);
                teleportCoordinator.cancel(uuid);
                return;
            }

            if (remaining[0] > 0) {
                sendRtpCountdownFeedback(player, remaining[0]--);
                return;
            }

            if (!searchFinished.get()) {
                sendRtpSearchFeedback(player);
                return;
            }

            if (!teleportStarted.compareAndSet(false, true)) {
                return;
            }

            Location target = targetRef.get();
            rtpSearches.remove(uuid);
            rtpSearchTokens.remove(uuid, searchToken);
            teleportCoordinator.cancel(uuid);
            player.sendActionBar(Component.empty());

            if (target == null) {
                send(player, "messages.rtp-failed");
                return;
            }
            finishRtp(player, target);
        }, 20L, 20L);

        teleportCoordinator.register(
                uuid,
                task,
                configs().menus().getBoolean("rtp.cancel-on-move", true),
                "messages.rtp-cancelled",
                true
        );

        rtpManager.findSafeLocation(player, worldName, target -> {
            // Nur das Ergebnis der aktuellsten Anfrage darf verwendet werden.
            // Alte asynchrone Chunk-Suchen können nach einem Abbruch noch fertiglaufen,
            // beeinflussen aber weder eine neue RTP-Anfrage noch deren Status.
            if (!searchToken.equals(rtpSearchTokens.get(uuid))) return;
            if (!teleportCoordinator.isPending(uuid) || !player.isOnline()) {
                rtpSearches.remove(uuid);
                rtpSearchTokens.remove(uuid, searchToken);
                return;
            }
            targetRef.set(target);
            searchFinished.set(true);
        });
    }

    private void clearRtpSearch(UUID playerId) {
        rtpSearches.remove(playerId);
        rtpSearchTokens.remove(playerId);
    }

    /**
     * Cancels every teleport countdown/search owned by the shared core. Features
     * such as combat can use this to invalidate a teleport that was requested
     * before the player entered a state in which teleporting is forbidden.
     */
    public final void cancelPendingTeleport(UUID playerId) {
        if (playerId == null) return;
        if (teleportCoordinator != null) teleportCoordinator.cancel(playerId);
        clearRtpSearch(playerId);
        Player player = getServer().getPlayer(playerId);
        if (player != null && player.isOnline()) player.sendActionBar(Component.empty());
    }

    private void finishRtp(Player player, Location target) {
        if (target == null || target.getWorld() == null || !player.isOnline()) {
            send(player, "messages.rtp-failed");
            return;
        }

        // Chunk-Preload ist nur eine Optimierung und darf RTP niemals blockieren.
        // Falls Paper/Mojang beim asynchronen Laden hängt oder eine Exception wirft,
        // wird spätestens nach 8 Sekunden trotzdem teleportiert.
        rtpManager.prepareDestination(target)
                .completeOnTimeout(false, 8, java.util.concurrent.TimeUnit.SECONDS)
                .exceptionally(error -> false)
                .thenCompose(ignored -> player.isOnline()
                        ? player.teleportAsync(target)
                        : CompletableFuture.completedFuture(false))
                .whenComplete((success, error) -> Bukkit.getScheduler().runTask(this, () -> {
                    if (error != null || !Boolean.TRUE.equals(success)) {
                        send(player, "messages.rtp-failed");
                        return;
                    }

                    int cooldown = resolveRtpCooldownSeconds(player);
                    boolean bypassCooldown =
                            player.hasPermission("smpcore.bypass.cooldown")
                                    || player.hasPermission("smpcore.rtp.bypass.cooldown");

                    if (!bypassCooldown && cooldown > 0) {
                        UUID playerId = player.getUniqueId();
                        long readyAt = System.currentTimeMillis() + cooldown * 1000L;
                        rtpCooldowns.put(playerId, readyAt);
                        // Keep reconnect protection for the full cooldown, then discard the
                        // runtime entry even when the player never executes /rtp again.
                        Bukkit.getScheduler().runTaskLater(this,
                                () -> rtpCooldowns.remove(playerId, readyAt), cooldown * 20L + 1L);
                    }
                    send(player, "messages.rtp-success");
                    sendRtpSuccessFeedback(player);
                }));
    }


    private int resolveRtpCooldownSeconds(Player player) {
        int base = Math.max(0, configs().menus().getInt("rtp.cooldown-seconds", 60));
        if (player == null) return base;
        if (player.hasPermission("group.premiumplus") || player.hasPermission("group.premium-plus")) {
            return Math.max(0, configs().main().getInt("rtp.cooldown-by-rank.premium-plus", 20));
        }
        if (player.hasPermission("group.premium")) {
            return Math.max(0, configs().main().getInt("rtp.cooldown-by-rank.premium", 40));
        }
        return base;
    }

    public final void setHome(Player player, String[] args) {
        if (args.length != 1) {
            send(player, "messages.home-set-usage");
            return;
        }
        if (!isSmpWorld(player.getWorld())) {
            send(player, "messages.home-wrong-world");
            return;
        }

        var result = serviceManager.homes().setHome(player, args[0], player.getLocation());
        switch (result.status()) {
            case INVALID_NAME -> send(player, "messages.home-invalid-name");
            case LIMIT_REACHED -> send(player, "messages.home-limit-reached", "<limit>", Integer.toString(result.limit()));
            case ALREADY_EXISTS -> sendOrDefault(
                    player,
                    "messages.home-already-exists",
                    "<dark_gray>[<green>SMP</green>]</dark_gray> <red>Du besitzt bereits ein Home mit dem Namen <yellow>\"<home>\"</yellow>.</red>",
                    "<home>",
                    result.displayName()
            );
            case CREATED -> send(player, "messages.home-set", "<home>", result.displayName());
        }
    }

    public final void teleportHome(Player player, String[] args) {
        List<String> homes = serviceManager.homes().getNames(player);
        String requestedName;
        if (args.length == 0 && homes.size() == 1) {
            requestedName = homes.get(0);
        } else if (args.length == 1) {
            requestedName = args[0];
        } else {
            send(player, "messages.home-usage");
            return;
        }

        var home = serviceManager.homes().find(player, requestedName);
        if (home == null) {
            send(player, "messages.home-not-found", "<home>", requestedName);
            return;
        }
        beginHomeTeleport(player, home.location(), home.displayName());
    }

    public final void deleteHome(Player player, String[] args) {
        if (args.length != 1) {
            send(player, "messages.home-delete-usage");
            return;
        }
        var result = serviceManager.homes().delete(player, args[0]);
        if (!result.deleted()) {
            send(player, "messages.home-not-found", "<home>", args[0]);
            return;
        }
        send(player, "messages.home-deleted", "<home>", result.displayName());
    }

    public final void listHomes(Player player) {
        List<String> displayNames = serviceManager.homes().getDisplayNames(player);
        if (displayNames.isEmpty()) {
            send(player, "messages.home-list-empty");
            return;
        }
        send(player, "messages.home-list", "<homes>", String.join(", ", displayNames));
    }

    private void beginHomeTeleport(Player player, Location target, String homeName) {
        beginHomeTeleport(player, target, () ->
                send(player, "messages.home-teleported", "<home>", homeName));
    }

    /**
     * Teleports a player to a home shared by a friend using exactly the same
     * countdown, movement cancellation, bypass permissions and sounds as /home.
     */
    public final void teleportFriendHome(Player player, Location target, String ownerName, String homeName) {
        beginHomeTeleport(player, target, () -> sendOrDefault(
                player,
                "messages.friend-home-teleported",
                "<dark_gray>[<green>SMP</green>]</dark_gray> <green>Du wurdest zum Home <yellow><home></yellow> von <yellow><owner></yellow> teleportiert.</green>",
                "<home>", homeName,
                "<owner>", ownerName
        ));
    }

    /** Reuses the normal-home warmup, safety warning and combat cancellation path. */
    public final void teleportClanHome(Player player, Location target, String homeName) {
        beginHomeTeleport(player, target, () -> player.sendRichMessage(
                "<green>Du wurdest zum Clan-Home <yellow>" + homeName + "</yellow> teleportiert.</green>"));
    }

    private void beginHomeTeleport(Player player, Location target, Runnable successFeedback) {
        teleportCoordinator.beginHome(player, target, successFeedback);
    }



    private int getHomeLimit(Player player) {
        return HomeLimitResolver.resolve(player, configs().main());
    }

    private String normalizeHomeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private boolean isValidHomeName(String name) {
        int maxLength = Math.max(1, configs().main().getInt("homes.max-name-length", 16));
        return name.length() <= maxLength && name.matches("[a-z0-9_-]+");
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("smpcore.admin")) {
            if (sender instanceof Player player) noPermission(player);
            else sender.sendMessage("Keine Berechtigung.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("/smpcore reload | sethub | setsmp | info | debug | hologram");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                loadSettings(true);
                if (locationStore != null) locationStore.reload();
                if (buildModeManager != null) buildModeManager.reload();
                if (commandVisibilityManager != null) commandVisibilityManager.reload();
                if (hologramManager != null) hologramManager.reloadAll();
                if (rankManager != null) rankManager.reload();
                if (tablistManager != null) tablistManager.reload();
                if (tabVisibilityManager != null) tabVisibilityManager.start();
                reloadServerFeatures();
                send(sender, "messages.config-reloaded");
            }
            case "sethub" -> {
                if (!(sender instanceof Player player)) return playerOnly(sender);
                saveFixedLocation("locations.hub", player.getLocation());
                send(player, "messages.location-set-hub");
            }
            case "setsmp" -> {
                if (!(sender instanceof Player player)) return playerOnly(sender);
                saveFixedLocation("locations.smp-spawn", player.getLocation());
                send(player, "messages.location-set-smp");
            }
            case "info" -> sendInfo(sender);
            case "debug" -> {
                if (!(sender instanceof Player player)) return playerOnly(sender);
                sendDebug(player);
            }
            case "hologram" -> handleHologramCommand(sender, args);
            default -> sender.sendMessage("/smpcore reload | sethub | setsmp | info | debug | hologram");
        }
        return true;
    }

    private void beginTeleport(Player player, Location target, String successMessagePath) {
        teleportCoordinator.beginStandard(player, target, successMessagePath);
    }

    private void beginSpawnTeleport(Player player, Location target) {
        teleportCoordinator.beginSpawn(player, target);
    }




    private void saveFixedLocation(String path, Location location) {
        locationStore.set(path, location);
    }

    private Location loadFixedLocation(String path) {
        return locationStore.get(path);
    }

    private void savePlayerLocation(Player player, Location location) {
        if (serviceManager != null) {
            serviceManager.playerData().saveLastSmpLocation(player, location);
        }
    }

    private Location loadPlayerLocation(Player player) {
        return serviceManager == null ? null : serviceManager.playerData().loadLastSmpLocation(player);
    }

    private Location loadPlayerLocation(UUID playerUuid, String playerName) {
        return serviceManager == null ? null : serviceManager.playerData().loadLastSmpLocation(playerUuid, playerName);
    }



    private Location fallbackWorldSpawn(String worldName) {
        return locationStore.worldSpawn(worldName);
    }

    private boolean isSmpWorld(World world) {
        return isSmpGameplayWorld(world);
    }

    private void handleHologramCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§e/smpcore hologram sethere <id>");
            sender.sendMessage("§e/smpcore hologram attach <id> <npc-id>");
            sender.sendMessage("§e/smpcore hologram attachblock <id> §7(Block ansehen)");
            sender.sendMessage("§e/smpcore hologram attachcrate <id> §7(Crate-Shulkerbox ansehen)");
            sender.sendMessage("§e/smpcore hologram detach <id>");
            sender.sendMessage("§e/smpcore hologram offset <id> <x> <y> <z>");
            sender.sendMessage("§e/smpcore hologram reload | delete <id> | list");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "sethere" -> {
                if (!(sender instanceof Player player)) { playerOnly(sender); return; }
                if (args.length < 3) { sender.sendMessage("§cBenutzung: /smpcore hologram sethere <id>"); return; }
                String id=args[2].toLowerCase();
                if(hologramManager.setHere(id,player.getLocation())) sender.sendMessage("§aHologramm '"+id+"' wurde lose hier gesetzt.");
                else sender.sendMessage("§cHologramm konnte nicht gesetzt werden.");
            }
            case "attach" -> {
                if (args.length < 4) { sender.sendMessage("§cBenutzung: /smpcore hologram attach <id> <npc-id>"); return; }
                int npcId;
                try { npcId = Integer.parseInt(args[3]); }
                catch (NumberFormatException ex) { sender.sendMessage("§cDie NPC-ID muss eine Zahl sein."); return; }
                String id=args[2].toLowerCase();
                if (hologramManager.attach(id, npcId)) {
                    sender.sendMessage("§aHologramm '"+id+"' ist jetzt an Citizens-NPC #"+npcId+" gebunden.");
                    sender.sendMessage("§7Höhe ändern: /smpcore hologram offset "+id+" 0 2.7 0");
                } else sender.sendMessage("§cNPC #"+npcId+" wurde nicht gefunden oder Citizens ist nicht geladen.");
            }
            case "attachblock" -> {
                if (!(sender instanceof Player player)) { playerOnly(sender); return; }
                if (args.length < 3) { sender.sendMessage("§cBenutzung: /smpcore hologram attachblock <id>"); return; }
                org.bukkit.block.Block block = player.getTargetBlockExact(6);
                if (block == null || block.getType().isAir()) { sender.sendMessage("§cDu musst einen Block in höchstens 6 Blöcken Entfernung ansehen."); return; }
                String id = args[2].toLowerCase();
                if (hologramManager.attachBlock(id, block.getLocation())) {
                    sender.sendMessage("§aHologramm '" + id + "' ist jetzt an den angesehenen Block gebunden.");
                    sender.sendMessage("§7Position ändern: /smpcore hologram offset " + id + " 0.5 1.65 0.5");
                } else sender.sendMessage("§cDas Hologramm konnte nicht an den Block gebunden werden.");
            }
            case "attachcrate" -> {
                if (!(sender instanceof Player player)) { playerOnly(sender); return; }
                if (args.length < 3) { sender.sendMessage("§cBenutzung: /smpcore hologram attachcrate <id>"); return; }
                org.bukkit.block.Block block = player.getTargetBlockExact(6);
                if (block == null || block.getType().isAir()) { sender.sendMessage("§cDu musst eine Crate-Shulkerbox in höchstens 6 Blöcken Entfernung ansehen."); return; }
                String id = args[2].toLowerCase();
                if (hologramManager.attachCrate(id, block.getLocation())) {
                    String crateId = crates() == null ? "unbekannt" : crates().crateAt(block.getLocation()).orElse("unbekannt");
                    sender.sendMessage("§aHologramm '" + id + "' ist jetzt an die Crate-Shulkerbox '" + crateId + "' gebunden.");
                    sender.sendMessage("§7Position ändern: /smpcore hologram offset " + id + " 0.5 1.65 0.5");
                } else sender.sendMessage("§cDer angesehene Block ist keine registrierte Crate-Shulkerbox.");
            }
            case "detach" -> {
                if (args.length < 3) { sender.sendMessage("§cBenutzung: /smpcore hologram detach <id>"); return; }
                String id=args[2].toLowerCase();
                if (hologramManager.detach(id)) sender.sendMessage("§aHologramm '"+id+"' ist jetzt wieder lose/statisch.");
                else sender.sendMessage("§cDas Hologramm ist nicht an einen erreichbaren NPC gebunden.");
            }
            case "offset" -> {
                if (args.length < 6) { sender.sendMessage("§cBenutzung: /smpcore hologram offset <id> <x> <y> <z>"); return; }
                try {
                    String id=args[2].toLowerCase();
                    double x=Double.parseDouble(args[3]), y=Double.parseDouble(args[4]), z=Double.parseDouble(args[5]);
                    if (hologramManager.setOffset(id,x,y,z)) sender.sendMessage("§aOffset für '"+id+"' gesetzt: §f"+x+", "+y+", "+z);
                    else sender.sendMessage("§cHologramm nicht gefunden.");
                } catch (NumberFormatException ex) { sender.sendMessage("§cX, Y und Z müssen Zahlen sein."); }
            }
            case "reload" -> { hologramManager.reloadAll(); sender.sendMessage("§aHologramme neu geladen."); }
            case "delete" -> { if(args.length<3){sender.sendMessage("§cBenutzung: /smpcore hologram delete <id>");return;} hologramManager.delete(args[2].toLowerCase()); sender.sendMessage("§aHologramm gelöscht."); }
            case "list" -> sender.sendMessage("§eHologramme: §f"+String.join(", ",hologramManager.listIds()));
            default -> sender.sendMessage("§cNutze sethere, attach, attachblock, attachcrate, detach, offset, reload, delete oder list.");
        }
    }

    private void sendDebug(Player player) {
        player.sendMessage(Component.text("§6--- SMPCore Debug ---"));
        player.sendMessage(Component.text("§eName: §f" + player.getName()));
        player.sendMessage(Component.text("§eAktuelle UUID: §f" + player.getUniqueId()));
        player.sendMessage(Component.text("§eAktuelle Welt: §f" + player.getWorld().getName()));
        player.sendMessage(Component.text("§eSMP-Welten: §f" + String.join(", ", smpWorlds)));

        Location direct = loadPlayerLocation(player.getUniqueId(), player.getName());
        player.sendMessage(Component.text("§ePosition per UUID: §f" + formatLocation(direct)));

        Location resolved = loadPlayerLocation(player);
        player.sendMessage(Component.text("§eAufgelöste Position: §f" + formatLocation(resolved)));
    }

    private void sendInfo(CommandSender sender) {
        String hub = formatLocation(loadFixedLocation("locations.hub"));
        String smp = formatLocation(loadFixedLocation("locations.smp-spawn"));
        int count = Bukkit.getOnlinePlayers().size();

        send(sender, "messages.admin-info",
                "<hub>", hub,
                "<smp>", smp,
                "<players>", Integer.toString(count));
    }

    private String formatLocation(Location location) {
        if (location == null || location.getWorld() == null) return "nicht gesetzt";
        return String.format("%s %.2f %.2f %.2f",
                location.getWorld().getName(),
                location.getX(),
                location.getY(),
                location.getZ());
    }

    private void playConfiguredSound(Player player, String path) {
        if (!configs().sounds().getBoolean("sounds.enabled", true)) return;

        String soundName = configs().sounds().getString(path, "");
        if (soundName.isBlank()) return;

        try {
            Sound sound = Sound.valueOf(soundName.toUpperCase());
            float volume = (float) configs().sounds().getDouble("sounds.volume", 1.0);
            float pitch = (float) configs().sounds().getDouble("sounds.pitch", 1.0);
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (IllegalArgumentException exception) {
            getLogger().warning("Ungültiger Sound in " + path + ": " + soundName);
        }
    }

    private boolean unavailableHere(CommandSender sender) {
        sender.sendMessage(parseMiniMessage(configs().messages().getString("messages.not-available-on-this-server", "<red>Dieser Befehl ist auf diesem Server nicht verfügbar.</red>")));
        return true;
    }

    private boolean playerOnly(CommandSender sender) {
        messageService.smp(sender, "<red>Dieser Befehl kann nur von Spielern benutzt werden.</red>");
        return true;
    }

    private boolean noPermission(CommandSender sender) {
        send(sender, "messages.no-permission");
        return true;
    }

    private void send(CommandSender sender, String path, String... replacements) {
        String raw = configs().messages().getString(path);
        if (raw == null || raw.isBlank()) return;
        messageService.sendConfiguredAuto(sender, configs().messages(), path, raw, replacements);
    }

    /**
     * Sends a configured message and falls back to a bundled default when an older
     * server configuration does not yet contain the newly introduced key.
     */
    private void sendOrDefault(CommandSender sender, String path, String fallback, String... replacements) {
        String raw = configs().messages().getString(path);
        if (raw == null || raw.isBlank()) raw = fallback;
        messageService.sendConfiguredAuto(sender, configs().messages(), path, raw, replacements);
    }

    public long economyBalance(UUID uuid) {
        if (uuid == null || serviceManager == null || serviceManager.economy() == null) return 0L;
        return serviceManager.economy().balance(uuid);
    }

    public MessageService messages() {
        return messageService;
    }

    public de.walahi.smpcore.sounds.SoundManager sounds() {
        return soundManager;
    }

    public void sendConfigured(CommandSender sender, String path, String... replacements) { send(sender, path, replacements); }
    public void playConfigured(Player player, String path) { playConfiguredSound(player, path); }

    /** Gemeinsames RTP-/Back-Feedback ohne doppelte Sound- und Actionbar-Logik. */
    public void sendRtpCountdownFeedback(Player player, int seconds) {
        String raw = configs().menus().getString(
                "rtp.actionbar",
                "<gold>[RTP]</gold> <yellow>Teleport in <green>%seconds%s</green>..."
        ).replace("%seconds%", Integer.toString(seconds));
        player.sendActionBar(miniMessage.deserialize(raw));
        playConfiguredSound(player, "sounds.countdown");
    }

    public void sendRtpSearchFeedback(Player player) {
        String raw = configs().menus().getString(
                "rtp.search-actionbar",
                "<gold>[RTP]</gold> <yellow>Sicherer Ort wird gesucht...</yellow>"
        );
        player.sendActionBar(miniMessage.deserialize(raw));
    }

    public void sendRtpSuccessFeedback(Player player) {
        playConfiguredSound(player, "sounds.success");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) return List.of();
        String commandName = command.getName().toLowerCase(Locale.ROOT);
        if (commandName.equals("smp") && isHubServer() && args.length == 1
                && player.hasPermission("smpcore.smp.others")) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }
        return List.of();
    }

}
