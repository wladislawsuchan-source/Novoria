package de.walahi.novosmp.professions;

import de.walahi.novosmp.items.CustomItemManager;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.gui.MenuFormat;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class BoosterService implements Listener {
    private final SMPCorePlugin plugin;
    private final ProfessionRepository repository;
    private final ProfessionConfig config;
    private final CustomItemManager customItems;
    private final Map<UUID, EnumMap<BoosterCategory, ActiveBooster>> active = new ConcurrentHashMap<>();
    private final Set<UUID> retainedForRetry = ConcurrentHashMap.newKeySet();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private BukkitTask tickTask;
    private int saveCounter;

    public BoosterService(SMPCorePlugin plugin, ProfessionRepository repository,
                          ProfessionConfig config, CustomItemManager customItems) {
        this.plugin = plugin;
        this.repository = repository;
        this.config = config;
        this.customItems = customItems;
    }

    public void start() {
        stopTaskOnly();
        Bukkit.getOnlinePlayers().forEach(player -> load(player.getUniqueId()));
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        stopTaskOnly();
        boolean[] failed = {false};
        active.forEach((playerId, boosters) -> {
            if (!saveAll(playerId, boosters)) failed[0] = true;
        });
        if (failed[0]) plugin.getLogger().severe("Booster beim Shutdown nicht vollständig gespeichert. "
                + "Ohne dauerhafte Recovery kann die Restzeit beim Prozessende zurückspringen.");
        active.clear();
        retainedForRetry.clear();
    }

    public void reload() {
        // Mappings come from ProfessionConfig and are read dynamically.
    }

    public double multiplier(UUID playerId, BoosterCategory category) {
        ActiveBooster booster = boosters(playerId).get(category);
        return booster == null || booster.remainingSeconds() <= 0 ? 1D : booster.multiplier();
    }

    public int remainingSeconds(UUID playerId, BoosterCategory category) {
        ActiveBooster booster = boosters(playerId).get(category);
        return booster == null ? 0 : booster.remainingSeconds();
    }

    public Map<BoosterCategory, ActiveBooster> snapshot(UUID playerId) {
        EnumMap<BoosterCategory, ActiveBooster> visible = new EnumMap<>(BoosterCategory.class);
        boosters(playerId).forEach((category, booster) -> {
            if (booster.remainingSeconds() > 0) visible.put(category, booster);
        });
        return Map.copyOf(visible);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() == null) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        String customItemId = customItems.identify(item);
        ProfessionConfig.BoosterDefinition definition = config.booster(customItemId);
        if (definition == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        EnumMap<BoosterCategory, ActiveBooster> playerBoosters = boosters(player.getUniqueId());
        ActiveBooster existing = playerBoosters.get(definition.category());
        if (existing != null && existing.remainingSeconds() > 0) {
            send(player, "messages.booster-active",
                    "<red>Es ist bereits ein %category%-Booster aktiv. Verbleibend: <yellow>%time%</yellow>.</red>",
                    "%category%", definition.category().display(),
                    "%time%", MenuFormat.durationSeconds(existing.remainingSeconds()));
            return;
        }

        ActiveBooster activated = new ActiveBooster(definition.category(), definition.multiplier(),
                config.boosterDurationSeconds(definition.category()));
        try {
            repository.saveBooster(player.getUniqueId(), activated);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Booster konnte nicht aktiviert werden", exception);
            player.sendRichMessage("<red>Der Booster konnte nicht gespeichert werden.</red>");
            return;
        }

        consumeOne(player, event.getHand());
        playerBoosters.put(definition.category(), activated);
        send(player, "messages.booster-activated",
                "<green>%booster% für <yellow>60 Minuten Onlinezeit</yellow> aktiviert.</green>",
                "%booster%", definition.displayName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (retainedForRetry.contains(playerId) && active.containsKey(playerId)) return;
        retainedForRetry.remove(playerId);
        load(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        EnumMap<BoosterCategory, ActiveBooster> boosters = active.get(playerId);
        if (boosters == null) return;
        if (saveAll(playerId, boosters)) {
            retainedForRetry.remove(playerId);
            active.remove(playerId, boosters);
        } else {
            retainedForRetry.add(playerId);
        }
    }

    private void tick() {
        saveCounter++;
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID playerId = player.getUniqueId();
            EnumMap<BoosterCategory, ActiveBooster> boosters = boosters(playerId);
            for (BoosterCategory category : BoosterCategory.values()) {
                ActiveBooster booster = boosters.get(category);
                if (booster == null || booster.remainingSeconds() <= 0) continue;
                ActiveBooster ticked = booster.tick();
                if (ticked.remainingSeconds() <= 0) {
                    try {
                        repository.deleteBooster(playerId, category);
                        boosters.remove(category);
                    }
                    catch (RuntimeException exception) {
                        // Zero seconds is a RAM tombstone: never reactivate the stale DB row on rejoin.
                        boosters.put(category, ticked);
                        plugin.getLogger().log(Level.WARNING, "Abgelaufener Booster von " + playerId
                                + " konnte nicht gelöscht werden; inaktiver RAM-State bleibt für Retry", exception);
                    }
                    send(player, "messages.booster-expired",
                            "<yellow>Dein %category%-Booster ist abgelaufen.</yellow>",
                            "%category%", category.display());
                } else {
                    boosters.put(category, ticked);
                }
            }
        }
        if (saveCounter >= 60) {
            saveCounter = 0;
            active.forEach((playerId, boosters) -> {
                if (saveAll(playerId, boosters) && retainedForRetry.remove(playerId)) {
                    Player player = Bukkit.getPlayer(playerId);
                    if (player == null || !player.isOnline()) active.remove(playerId, boosters);
                }
            });
        }
    }

    private EnumMap<BoosterCategory, ActiveBooster> boosters(UUID playerId) {
        return active.computeIfAbsent(playerId, this::loadMap);
    }

    private void load(UUID playerId) {
        active.put(playerId, loadMap(playerId));
    }

    private EnumMap<BoosterCategory, ActiveBooster> loadMap(UUID playerId) {
        EnumMap<BoosterCategory, ActiveBooster> result = new EnumMap<>(BoosterCategory.class);
        try {
            result.putAll(repository.loadBoosters(playerId));
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Booster konnten für " + playerId + " nicht geladen werden", exception);
        }
        return result;
    }

    private boolean saveAll(UUID playerId, Map<BoosterCategory, ActiveBooster> boosters) {
        boolean saved = true;
        for (BoosterCategory category : BoosterCategory.values()) {
            ActiveBooster booster = boosters.get(category);
            if (booster == null) continue;
            try {
                repository.saveBooster(playerId, booster);
                if (booster.remainingSeconds() <= 0) boosters.remove(category, booster);
            }
            catch (RuntimeException exception) {
                saved = false;
                plugin.getLogger().log(Level.WARNING, "Booster von " + playerId + " (" + category
                        + ") konnte nicht gespeichert werden; RAM-State bleibt für Retry", exception);
            }
        }
        return saved;
    }

    private void consumeOne(Player player, EquipmentSlot hand) {
        ItemStack held = hand == EquipmentSlot.HAND
                ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();
        if (held == null || held.getType().isAir()) return;

        if (held.getAmount() <= 1) {
            if (hand == EquipmentSlot.HAND) player.getInventory().setItemInMainHand(null);
            else player.getInventory().setItemInOffHand(null);
            return;
        }

        held.setAmount(held.getAmount() - 1);
        // Explizit zurückschreiben. Bei gestapelten Verbrauchsitems verlassen wir uns nicht
        // darauf, dass event.getItem() dieselbe mutable Inventar-Referenz ist.
        if (hand == EquipmentSlot.HAND) player.getInventory().setItemInMainHand(held);
        else player.getInventory().setItemInOffHand(held);
    }

    private void send(Player player, String path, String fallback, String... replacements) {
        String raw = config.string(path, fallback);
        for (int index = 0; index + 1 < replacements.length; index += 2) {
            raw = raw.replace(replacements[index], replacements[index + 1]);
        }
        player.sendMessage(miniMessage.deserialize(raw));
    }

    private void stopTaskOnly() {
        if (tickTask != null) tickTask.cancel();
        tickTask = null;
    }
}
