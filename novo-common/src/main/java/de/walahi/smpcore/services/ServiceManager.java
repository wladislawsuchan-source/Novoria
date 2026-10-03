package de.walahi.smpcore.services;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.api.BukkitEventPublisher;
import de.walahi.smpcore.api.EventPublisher;
import de.walahi.smpcore.homes.HomeAccess;
import de.walahi.smpcore.moderation.BanService;
import de.walahi.smpcore.moderation.KickService;
import de.walahi.smpcore.moderation.MuteService;
import de.walahi.smpcore.moderation.StaffHierarchy;
import de.walahi.smpcore.ranks.RankManager;
import de.walahi.smpcore.punishments.PunishmentManager;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.function.Predicate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Composition root for application services. No command should construct a service itself. */
public final class ServiceManager {
    private final List<Service> lifecycleServices = new ArrayList<>();
    private final PunishmentService punishmentService;
    private final PlayerDataService playerDataService;
    private final HomeAccess homeService;
    private final EconomyService economyService;
    private final ProfessionService professionService;
    private final KickService kickService;
    private final BanService banService;
    private final MuteService muteService;

    public ServiceManager(
            SMPCorePlugin plugin,
            RankManager rankManager,
            PunishmentManager punishmentManager,
            FileConfiguration config,
            Predicate<World> smpWorldPredicate
    ) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(rankManager, "rankManager");
        Objects.requireNonNull(punishmentManager, "punishmentManager");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(smpWorldPredicate, "smpWorldPredicate");

        EventPublisher events = new BukkitEventPublisher(plugin);
        punishmentService = register(new PunishmentService(punishmentManager, events));
        playerDataService = register(new PlayerDataService(plugin, plugin.databaseManager(), smpWorldPredicate));
        homeService = register(plugin.createHomeFeature(events, config));
        economyService = register(new EconomyService(plugin.storageManager(), events, plugin.getLogger()));
        professionService = register(new ProfessionService());

        StaffHierarchy hierarchy = new StaffHierarchy(rankManager);
        kickService = new KickService(plugin, hierarchy);
        banService = new BanService(plugin, hierarchy, punishmentService);
        muteService = new MuteService(plugin, hierarchy, punishmentService);
    }

    private <T extends Service> T register(T service) {
        lifecycleServices.add(service);
        return service;
    }

    public void start() {
        lifecycleServices.forEach(Service::start);
    }

    public void stop() {
        List<Service> reverse = new ArrayList<>(lifecycleServices);
        Collections.reverse(reverse);
        reverse.forEach(Service::stop);
    }

    public PunishmentService punishments() { return punishmentService; }
    public PlayerDataService playerData() { return playerDataService; }
    public HomeAccess homes() { return homeService; }
    public EconomyService economy() { return economyService; }
    public ProfessionService professions() { return professionService; }
    public KickService kicks() { return kickService; }
    public BanService bans() { return banService; }
    public MuteService mutes() { return muteService; }
}
