package de.walahi.novosmp.angler;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import de.walahi.novosmp.enchants.CustomEnchantmentService;
import de.walahi.novosmp.professions.ProfessionManager;
import de.walahi.smpcore.SMPCorePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;

/** Hooks the vanilla fishing lifecycle for all players; only Anglers earn profession progress. */
public final class AnglerFishingService implements Listener {
    private enum FishingMode { NORMAL, AFK }

    private static final class Session {
        private final UUID playerId;
        private final UUID hookId;
        private final FishHook hook;
        private final World world;
        private final int luckLevel;
        private final int ausdauerLevel;
        private final FishingAttemptState state = new FishingAttemptState();
        private FishDefinition fish;
        private FishingGame game;
        private int concentrationLevel;
        private int meistergriffLevel;
        private boolean hookRetired;
        private FishingMode mode = FishingMode.NORMAL;
        private int afkIntervalSeconds;
        private boolean afkResolving;
        private final ItemStack castRod;

        private Session(Player player, FishHook hook, int luckLevel, int ausdauerLevel) {
            this.playerId = player.getUniqueId();
            this.hookId = hook.getUniqueId();
            this.hook = hook;
            this.world = hook.getWorld();
            this.luckLevel = luckLevel;
            this.ausdauerLevel = ausdauerLevel;
            this.castRod = player.getInventory().getItemInMainHand().clone();
        }
    }

    private final SMPCorePlugin plugin;
    private final ProfessionManager professions;
    private final AnglerFeature feature;
    private final FishRegistry registry;
    private final FishItemFactory factory;
    private final AnglerLootFoundation lootFoundation;
    private final CustomEnchantmentService customEnchantments;
    private final FishingComboTracker combos;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Random random = new Random();
    private final Enchantment luckOfTheSea = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("luck_of_the_sea"));
    private final Map<UUID, Session> sessions = new HashMap<>();
    private FishingPool pool;
    private FishingLootPoolSelector lootPools;
    private FishingJunkPool junkPool;
    private FishingTreasurePool treasurePool;
    private FishingRarePool rarePool;
    private FishingEpicPool epicPool;
    private FishingLegendaryPool legendaryPool;
    private BukkitTask activeTask;
    private final int[] afkIntervals = new int[6];
    private java.util.function.Consumer<Player> refreshHud = ignored -> { };
    private java.util.function.Consumer<Player> fishCaught = ignored -> { };

    public void fishCaught(java.util.function.Consumer<Player> listener) {
        fishCaught = listener == null ? ignored -> { } : listener;
    }

    public void hudRefresh(java.util.function.Consumer<Player> refresh) {
        refreshHud = refresh == null ? ignored -> { } : refresh;
    }

    public AnglerFishingService(SMPCorePlugin plugin, ProfessionManager professions,
                                AnglerFeature feature, FishRegistry registry,
                                AnglerLootFoundation lootFoundation,
                                CustomEnchantmentService customEnchantments) {
        this.plugin = plugin;
        this.professions = professions;
        this.feature = feature;
        this.registry = registry;
        this.factory = new FishItemFactory(registry);
        this.lootFoundation = lootFoundation;
        this.customEnchantments = customEnchantments;
        this.combos = new FishingComboTracker(plugin.configs().angler());
        reload();
    }

    public void reload() {
        pool = new FishingPool(plugin.configs().angler());
        lootPools = new FishingLootPoolSelector(plugin.configs().angler());
        junkPool = new FishingJunkPool(plugin.configs().angler(), plugin.getLogger());
        treasurePool = new FishingTreasurePool(plugin.configs().angler(), plugin.getLogger());
        rarePool = new FishingRarePool(plugin.configs().angler(), treasurePool,
                lootFoundation.overlevelBooks(), plugin.getLogger());
        epicPool = new FishingEpicPool(plugin.configs().angler(), rarePool, plugin.getLogger());
        legendaryPool = new FishingLegendaryPool(plugin.configs().angler(), rarePool, plugin.getLogger());
        combos.reload(plugin.configs().angler());
        loadAfkIntervals();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        if (!canUseActiveFishing(player)) {
            if (sessions.containsKey(playerId)) reset(playerId);
            return;
        }
        FishHook hook = event.getHook();
        if (event.getState() == PlayerFishEvent.State.FISHING) {
            if (playerFishingAction(event.getState(), event.getHand()))
                plugin.getAfkManager().markActivity(player);
            // The custom stop interaction is deliberately main-hand only.
            if (event.getHand() == EquipmentSlot.OFF_HAND) {
                endSession(playerId, false);
                return;
            }
            endSession(playerId, false);
            ItemStack rod = player.getInventory().getItemInMainHand();
            int luckLevel = rod.getType() == Material.FISHING_ROD && luckOfTheSea != null
                    ? rod.getEnchantmentLevel(luckOfTheSea) : 0;
            int ausdauerLevel = rod.getType() == Material.FISHING_ROD
                    ? customEnchantments.level(rod, "ausdauer") : 0;
            sessions.put(playerId, new Session(player, hook, luckLevel, ausdauerLevel));
            return;
        }
        Session session = sessions.get(playerId);
        if (session == null || !session.hookId.equals(hook.getUniqueId())) return;
        if (session.mode == FishingMode.NORMAL && plugin.getAfkManager().isAfk(player))
            onBecomeAfk(playerId);
        if (session.mode == FishingMode.AFK) {
            handleAfkFishEvent(event, session);
            return;
        }
        switch (event.getState()) {
            case BITE -> {
                session.state.bite();
            }
            case FAILED_ATTEMPT -> {
                if (session.state.expireBite()) {
                    combos.reset(playerId); // Vanilla bite window ended without confirmation.
                }
            }
            case CAUGHT_FISH -> {
                if (session.state.phase() == FishingAttemptState.Phase.PLAYING) {
                    event.setCancelled(true);
                    event.setExpToDrop(0);
                    if (event.getCaught() instanceof Item caught) caught.remove();
                } else if (session.state.phase() == FishingAttemptState.Phase.BITTEN) {
                    // Prevent both the vanilla loot item and vanilla fishing XP.
                    event.setCancelled(true);
                    event.setExpToDrop(0);
                    if (event.getCaught() instanceof Item caught) caught.remove();
                    if (playerFishingAction(event.getState(), event.getHand()))
                        plugin.getAfkManager().markActivity(player);
                    startGame(player, session);
                }
            }
            case REEL_IN -> {
                if (playerFishingAction(event.getState(), event.getHand()))
                    plugin.getAfkManager().markActivity(player);
                if (session.state.phase() == FishingAttemptState.Phase.PLAYING) event.setCancelled(true);
                else {
                    if (session.state.phase() == FishingAttemptState.Phase.BITTEN) combos.reset(playerId);
                    endSession(playerId, false);
                }
            }
            case CAUGHT_ENTITY, IN_GROUND -> {
                if (playerFishingAction(event.getState(), event.getHand()))
                    plugin.getAfkManager().markActivity(player);
                endSession(playerId, false);
            }
            default -> { }
        }
    }

    private void handleAfkFishEvent(PlayerFishEvent event, Session session) {
        Player player = event.getPlayer();
        switch (event.getState()) {
            case BITE -> {
                // Cancel before Paper opens its vanilla nibble window. Resetting
                // an uncancelled BITE here would be overwritten after this listener.
                event.setCancelled(true);
                if (session.afkResolving || session.hook.getTimeUntilBite() > 0) return;
                if (!validAfk(player, session)) { endSession(session.playerId, true); return; }
                session.afkResolving = true;
                try {
                    catchAfk(player, session);
                    if (sessions.get(session.playerId) == session && validAfk(player, session)) {
                        prepareAfkHook(session.hook, session.afkIntervalSeconds);
                        refreshHud.accept(player);
                    } else {
                        endSession(session.playerId, true);
                    }
                } catch (RuntimeException exception) {
                    plugin.getLogger().log(Level.SEVERE, "AFK-Angelfang fehlgeschlagen", exception);
                    endSession(session.playerId, true, "CATCH_FAILED");
                } finally {
                    session.afkResolving = false;
                }
            }
            case LURED -> { } // Hook lifecycle event, never player activity.
            case FAILED_ATTEMPT -> {
                event.setCancelled(true);
                // No catch here: BITE is the only reward source.
            }
            case CAUGHT_FISH -> {
                event.setCancelled(true);
                event.setExpToDrop(0);
                if (event.getCaught() instanceof Item caught) caught.remove();
                if (playerFishingAction(event.getState(), event.getHand())) {
                    // A real reel is activity; cancelling the Vanilla catch also
                    // requires us to retire this hook explicitly.
                    plugin.getAfkManager().markActivity(player);
                    endSession(session.playerId, true);
                }
            }
            case REEL_IN, CAUGHT_ENTITY, IN_GROUND -> {
                event.setCancelled(true);
                if (playerFishingAction(event.getState(), event.getHand()))
                    plugin.getAfkManager().markActivity(player);
                endSession(session.playerId, true, "PLAYER_ACTIVITY_" + event.getState());
            }
            default -> { }
        }
    }

    static boolean playerFishingAction(PlayerFishEvent.State state, EquipmentSlot hand) {
        if (hand == null) return false;
        return switch (state) {
            case FISHING, CAUGHT_FISH, REEL_IN, CAUGHT_ENTITY, IN_GROUND -> true;
            case LURED, BITE, FAILED_ATTEMPT -> false;
        };
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick() || event.getHand() != EquipmentSlot.HAND) return;
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null || event.getPlayer().getInventory().getItemInMainHand().getType() != Material.FISHING_ROD)
            return;
        if (session.mode == FishingMode.AFK) {
            event.setUseItemInHand(Event.Result.DENY);
            event.setCancelled(true);
            plugin.getAfkManager().markActivity(event.getPlayer());
            endSession(session.playerId, true, "PLAYER_ACTIVITY");
            return;
        }
        if (session.state.phase() == FishingAttemptState.Phase.BITTEN) {
            session.state.confirmingInteract(Bukkit.getCurrentTick());
            return;
        }
        if (session.state.phase() != FishingAttemptState.Phase.PLAYING) return;
        // Air clicks may be pre-cancelled by Paper, and block clicks by protection plugins.
        event.setUseItemInHand(Event.Result.DENY);
        event.setCancelled(true); // Never reel/cast as a side effect of the stop input.
        if (!session.state.canStop(Bukkit.getCurrentTick())) return;
        plugin.getAfkManager().markActivity(event.getPlayer());
        handleMinigameStop(event.getPlayer(), session);
    }

    private void handleMinigameStop(Player player, Session session) {
        long now = System.nanoTime();
        boolean timedOut = session.game.timedOut(now);
        FishingGame.Quality quality = timedOut ? FishingGame.Quality.GRAY
                : session.game.quality(session.game.pointer(now));
        if (session.state.consumeGrayRetry(quality, timedOut)) return;
        finish(player, session, timedOut, quality);
    }

    private void startGame(Player player, Session session) {
        if (!valid(player, session)) { endSession(player.getUniqueId(), false); return; }
        session.fish = chooseFish(session);
        if (session.fish == null) { endSession(player.getUniqueId(), true); return; }
        combos.attempt(player.getUniqueId(), System.currentTimeMillis());
        ItemStack rod = player.getInventory().getItemInMainHand();
        int ruhigeHandLevel = rod.getType() == Material.FISHING_ROD
                ? customEnchantments.level(rod, "ruhige_hand") : 0;
        session.concentrationLevel = rod.getType() == Material.FISHING_ROD
                ? customEnchantments.level(rod, "konzentration") : 0;
        session.meistergriffLevel = rod.getType() == Material.FISHING_ROD
                ? customEnchantments.level(rod, "meistergriff") : 0;
        session.game = new FishingGame(plugin.configs().angler(), session.fish.difficulty(),
                random, System.nanoTime(), ruhigeHandLevel);
        int grayRetries = rod.getType() == Material.FISHING_ROD
                ? customEnchantments.level(rod, "nachfassen") : 0;
        if (!session.state.startGame(Bukkit.getCurrentTick(), grayRetries)) return;
        actionbar(player, plugin.configs().angler().getString("fishing.actionbar.start",
                "<aqua>Ein Biss! Rechtsklick stoppt den Zeiger.</aqua>"));
        startActiveTask();
    }

    private FishDefinition chooseFish(Session session) {
        World world = session.hook.getWorld();
        String biome = world.getBiome(session.hook.getLocation()).getKey().getKey();
        long time = world.getTime();
        long nightStart = plugin.configs().angler().getLong("fishing.night.start-tick", 13000L);
        long nightEnd = plugin.configs().angler().getLong("fishing.night.end-tick", 23000L);
        boolean night = nightStart <= nightEnd ? time >= nightStart && time <= nightEnd
                : time >= nightStart || time <= nightEnd;
        boolean rain = world.hasStorm();
        return pool.choose(registry.definitions(), new FishingPool.Conditions(biome, night, rain), random);
    }

    private void startActiveTask() {
        if (activeTask == null) activeTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickActive, 1L, 1L);
    }

    private void tickActive() {
        long now = System.nanoTime();
        for (Session session : new ArrayList<>(sessions.values())) {
            if (session.state.phase() != FishingAttemptState.Phase.PLAYING) continue;
            Player player = Bukkit.getPlayer(session.playerId);
            if (player == null || !valid(player, session)) { endSession(session.playerId, false); continue; }
            if (session.game.timedOut(now)) { finish(player, session, true); continue; }
            int width = plugin.configs().angler().getInt("fishing.minigame.bar-width", 41);
            String pointer = plugin.configs().angler().getString("fishing.actionbar.pointer", "◆");
            String segment = plugin.configs().angler().getString("fishing.actionbar.segment", "▌");
            actionbar(player, session.game.bar(width, pointer, segment, now));
        }
        stopTaskIfIdle();
    }

    private void finish(Player player, Session session, boolean timedOut) {
        finish(player, session, timedOut, null);
    }

    private void finish(Player player, Session session, boolean timedOut,
                        FishingGame.Quality stoppedQuality) {
        if (sessions.get(session.playerId) != session || !session.state.beginResolve()) return;
        sessions.remove(session.playerId); // Gate before any storage, XP or hook callback.
        stopTaskIfIdle();
        if (!valid(player, session)) {
            combos.reset(session.playerId);
            if (session.hook.isValid()) session.hook.remove();
            return;
        }
        long now = System.nanoTime();
        FishingGame.Quality quality = timedOut || session.game.timedOut(now)
                ? FishingGame.Quality.GRAY : stoppedQuality != null
                ? stoppedQuality : session.game.quality(session.game.pointer(now));
        if (session.hook.isValid()) session.hook.remove();
        boolean doubleGreenCombo = rollMeistergriff(plugin.configs().angler(), quality,
                session.meistergriffLevel, random);
        FishingComboTracker.Result combo = combos.finish(session.playerId, quality,
                session.concentrationLevel, doubleGreenCombo);
        if (quality == FishingGame.Quality.GRAY) {
            actionbar(player, plugin.configs().angler().getString(timedOut
                    ? "fishing.actionbar.timeout" : "fishing.actionbar.gray",
                    timedOut ? "<gray>Zeit abgelaufen • Combo zurückgesetzt.</gray>"
                            : "<gray>GRAU • %fish% verfehlt | Combo: 0</gray>")
                    .replace("%fish%", session.fish.displayName()));
            return;
        }
        boolean angler = isActiveAngler(player);
        int prestige = angler ? professions.angler(player.getUniqueId()).prestige() : -1;
        FishingLootPoolSelector.Result loot = lootPools.roll(
                angler, prestige, quality, session.luckLevel, random);
        FishingLootPoolSelector.Pool selectedPool = loot.main();
        if (selectedPool == FishingLootPoolSelector.Pool.FISH) {
            ItemStack caught = factory.create(session.fish.id(), 1);
            if (caught == null) return;
            if (angler) {
                ItemStack overflow = feature.storeCatch(player, caught);
                if (overflow != null) player.getWorld().dropItemNaturally(player.getLocation(), overflow);
            } else {
                // Bukkit fills matching partial stacks before free storage slots.
                for (ItemStack overflow : player.getInventory().addItem(caught.clone()).values())
                    player.getWorld().dropItemNaturally(player.getLocation(), overflow);
            }
            fishCaught.accept(player);
        }
        double base = plugin.configs().angler().getDouble("fishing.xp.base", 25D);
        double colorMultiplier = plugin.configs().angler().getDouble(
                "fishing.xp.color-multipliers." + quality.name().toLowerCase(java.util.Locale.ROOT), 1D);
        long xp = Math.max(0L, Math.round(base * colorMultiplier * combo.multiplier()));
        ProfessionManager.AnglerCatchResult award = ProfessionManager.AnglerCatchResult.NONE;
        if (angler) {
            try { award = professions.recordAnglerCatch(player, quality == FishingGame.Quality.GREEN, combo.combo(), xp); }
            catch (RuntimeException exception) {
                plugin.getLogger().log(Level.SEVERE, "Angler-Fortschritt konnte nicht gespeichert werden", exception);
            }
        }
        if (selectedPool != FishingLootPoolSelector.Pool.FISH) {
            if (selectedPool == null) {
                player.sendMessage(miniMessage.deserialize(plugin.configs().angler().getString(
                        "loot.empty-message", "<red>Kein Fishing-Loot-Pool konfiguriert.</red>")));
            } else if (selectedPool == FishingLootPoolSelector.Pool.JUNK) {
                giveJunk(player);
            } else if (selectedPool == FishingLootPoolSelector.Pool.TREASURE && angler) {
                giveTreasure(player);
            } else if (selectedPool == FishingLootPoolSelector.Pool.RARE && angler) {
                giveRare(player, null);
            } else if (selectedPool == FishingLootPoolSelector.Pool.EPIC && angler) {
                giveEpic(player, null);
            } else if (selectedPool == FishingLootPoolSelector.Pool.LEGENDARY && angler) {
                giveLegendary(player, null);
            } else previewPool(player, selectedPool);
        }
        // The extra roll is loot only: no second XP, combo, Green hit or fish item in this block.
        if (loot.extra() == FishingLootPoolSelector.Pool.JUNK) giveJunk(player);
        else if (loot.extra() == FishingLootPoolSelector.Pool.TREASURE && angler) giveTreasure(player);
        else if (loot.extra() == FishingLootPoolSelector.Pool.RARE && angler) giveRare(player, null);
        else if (loot.extra() == FishingLootPoolSelector.Pool.EPIC && angler) giveEpic(player, null);
        else if (loot.extra() == FishingLootPoolSelector.Pool.LEGENDARY && angler) giveLegendary(player, null);
        else if (loot.extra() != null) previewExtraPool(player, loot.extra());
        if (selectedPool != FishingLootPoolSelector.Pool.FISH) return;
        if (award.importantFeedback()) return; // Keep the rarer level-up/milestone actionbar visible.
        int capacity = 0;
        int occupied = 0;
        if (angler) {
            capacity = feature.storage().rows(prestige) * 9;
            try {
                for (ItemStack item : feature.storage().snapshot(player.getUniqueId(), prestige))
                    if (item != null && !item.getType().isAir()) occupied++;
            } catch (IOException | SQLException exception) {
                plugin.getLogger().log(Level.WARNING, "Fanglager-Anzeige konnte nicht gelesen werden", exception);
            }
        }
        String qualityKey = quality.name().toLowerCase(java.util.Locale.ROOT);
        String template = plugin.configs().angler().getString(
                angler ? "fishing.actionbar." + qualityKey : "fishing.actionbar.non-angler." + qualityKey,
                angler ? "<green>%fish% • +%xp% XP | Combo: %combo% | Lager: %stored%/%capacity%</green>"
                        : "<green>%fish% | Combo: %combo%</green>");
        actionbar(player, template.replace("%fish%", session.fish.displayName())
                .replace("%xp%", Long.toString(award.creditedXp()))
                .replace("%combo%", Integer.toString(combo.combo()))
                .replace("%stored%", Integer.toString(occupied))
                .replace("%capacity%", Integer.toString(capacity)));
    }

    private void loadAfkIntervals() {
        try (InputStream stream = plugin.getResource("angler.yml")) {
            if (stream == null) throw new IOException("Gebündelte angler.yml fehlt");
            FileConfiguration bundled = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            for (int level = 0; level < afkIntervals.length; level++) {
                afkIntervals[level] = afkIntervalSeconds(plugin.configs().angler(), bundled, level);
                if (afkIntervals[level] <= 0)
                    plugin.getLogger().severe("AFK-Angelintervall für Ausdauer " + level + " fehlt oder ist ungültig");
            }
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "AFK-Angelintervalle konnten nicht geladen werden", exception);
            java.util.Arrays.fill(afkIntervals, 0); // Fail closed, never invent interval values.
        }
    }

    static int afkIntervalSeconds(FileConfiguration configured, FileConfiguration bundled, int level) {
        String path = "enchants.ausdauer.afk-interval-seconds." + Math.max(0, Math.min(5, level));
        int fallback = validAfkInterval(bundled.get(path));
        int selected = validAfkInterval(configured.get(path));
        return selected > 0 ? selected : fallback;
    }

    private static int validAfkInterval(Object value) {
        if (!(value instanceof Number number)) return 0;
        double seconds = number.doubleValue();
        return Double.isFinite(seconds) && seconds >= 1D && seconds <= 3600D && seconds == Math.rint(seconds)
                ? (int) seconds : 0;
    }

    /** Transfers a valid cast to AFK ownership; the same entity remains the clock. */
    public void onBecomeAfk(UUID playerId) {
        Session session = sessions.get(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (session == null || player == null || session.mode == FishingMode.AFK || !validAfk(player, session)) return;
        int level = Math.max(0, Math.min(5, session.ausdauerLevel));
        int interval = afkIntervals[level];
        if (interval <= 0) return;
        session.afkIntervalSeconds = interval;
        session.mode = FishingMode.AFK;
        session.state.expireBite();
        prepareAfkHook(session.hook, interval);
        plugin.messages().sendConfiguredAuto(player, plugin.configs().angler(),
                "afk.started-message", "<green>AFK-Angeln wurde gestartet.</green>");
        refreshHud.accept(player);
    }

    public void onLeaveAfk(UUID playerId) {
        Session session = sessions.get(playerId);
        if (session != null && session.mode == FishingMode.AFK)
            endSession(playerId, true, "GLOBAL_AFK_ENDED");
    }

    private boolean validAfk(Player player, Session session) {
        return afkInvalidReason(player, session) == null;
    }

    private String afkInvalidReason(Player player, Session session) {
        if (player == null || !player.isOnline()) return "QUIT";
        if (player.isDead()) return "DEATH";
        if (!plugin.configs().angler().getBoolean("fishing.active.enabled", true)) return "FISHING_DISABLED";
        if (player.getGameMode() != GameMode.SURVIVAL) return "GAME_MODE_CHANGED";
        if (registry.definitions().isEmpty()) return "FISH_REGISTRY_EMPTY";
        if (!player.getWorld().equals(session.world)) return "WORLD_CHANGE";
        if (sessions.get(session.playerId) != session) return "SESSION_REPLACED";
        if (!isActiveAngler(player)) return "PROFESSION_CHANGED";
        if (!plugin.getAfkManager().isAfk(player)) return "GLOBAL_AFK_ENDED";
        if (session.state.phase() == FishingAttemptState.Phase.PLAYING
                || session.state.phase() == FishingAttemptState.Phase.RESOLVING) return "ACTIVE_MINIGAME";
        if (player.getInventory().getItemInMainHand().getType() != Material.FISHING_ROD
                || !sameRod(session.castRod, player.getInventory().getItemInMainHand())) return "ROD_INVALID";
        // Starting still requires the strict settled-in-water check. Once owned by
        // AFK, surface bobbing must not be mistaken for leaving the pond.
        if (session.mode == FishingMode.NORMAL && !hookReady(player, session)) return "HOOK_NOT_READY";
        return afkHookInvalidReason(player, session.hook, session.hookId);
    }

    static String afkHookInvalidReason(Player player, FishHook hook, UUID hookId) {
        if (!hook.isValid() || hook.isDead()) return "HOOK_INVALID";
        if (!hookId.equals(hook.getUniqueId())) return "HOOK_REPLACED";
        FishHook current = player.getFishHook();
        if (current == null || !hookId.equals(current.getUniqueId())) return "HOOK_OWNER_MISMATCH";
        if (hook.getHookedEntity() != null) return "HOOK_CAUGHT_ENTITY";
        if (hook.getState() != FishHook.HookState.BOBBING) return "HOOK_STATE_CHANGED";
        if (!hook.isInWater() && !hookTouchesWaterSurface(hook)) return "HOOK_NOT_IN_WATER";
        return null;
    }

    private static boolean hookTouchesWaterSurface(FishHook hook) {
        // Paper's entity water flag can briefly be false above the surface while
        // the same BOBBING hook is still fishing. Check physical water contact,
        // including the small part of its bounding box over the block below.
        Location location = hook.getLocation();
        if (waterBlock(location.getBlock())) return true;
        double aboveBlock = location.getY() - Math.floor(location.getY());
        return aboveBlock <= hook.getHeight()
                && waterBlock(location.getBlock().getRelative(0, -1, 0));
    }

    private static boolean waterBlock(Block block) {
        return block.getType() == Material.WATER || block.getType() == Material.BUBBLE_COLUMN
                || block.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
    }

    private boolean hookReady(Player player, Session session) {
        if (session.hookRetired || !session.hook.isValid()
                || session.hook.getState() != FishHook.HookState.BOBBING
                || !session.hook.isInWater() || session.hook.getHookedEntity() != null) return false;
        FishHook current = player.getFishHook();
        return current != null && session.hookId.equals(current.getUniqueId());
    }

    /** Called by the existing one-second Lumi/HUD task; null means no claim,
     * Component.empty() means the active minigame owns the actionbar. */
    public Component fishingHud(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return null;
        if (!valid(player, session)) { endSession(session.playerId, false); return null; }
        if (session.state.phase() == FishingAttemptState.Phase.PLAYING) return Component.empty();
        if (session.mode == FishingMode.AFK) {
            if (!plugin.getAfkManager().isAfk(player)) {
                endSession(session.playerId, true);
                return null;
            } else if (!validAfk(player, session)) {
                endSession(session.playerId, false);
                return null;
            } else {
                return afkHud(player, session);
            }
        }
        if (!isActiveAngler(player) || player.getInventory().getItemInMainHand().getType() != Material.FISHING_ROD
                || !hookReady(player, session))
            return null;
        if (plugin.getAfkManager().isAfk(player)) {
            // The AFK flag may have been set while the hook was still flying.
            // Retry the transition once the existing hook is actually bobbing.
            onBecomeAfk(session.playerId);
            return session.mode == FishingMode.AFK ? afkHud(player, session) : null;
        }
        return null;
    }

    /** No plugin clock: Paper decrements its own approach timer and emits BITE. */
    static void prepareAfkHook(FishHook hook, int intervalSeconds) {
        if (!hook.isValid()) return;
        hook.setApplyLure(false);
        hook.setRainInfluenced(false);
        hook.setSkyInfluenced(false);
        hook.resetFishingState();
        // resetFishingState re-randomizes the wait. Override only AFTER reset.
        hook.setWaitTime(intervalSeconds * 20, intervalSeconds * 20);
        hook.setLureTime(20, 20);
        hook.setWaitTime(0);
        // This API enters the real approach phase, also initializing fishAngle.
        // It clears the random idle wait; the complete interval belongs to the
        // actual hook. No delayed reset, task or separate deadline is needed.
        hook.setTimeUntilBite(intervalSeconds * 20);
    }

    static long afkSecondsRemaining(FishHook hook) {
        int approach = Math.max(0, hook.getTimeUntilBite());
        int waiting = Math.max(0, hook.getWaitTime());
        return (approach > 0 ? approach + 19L : waiting + 19L) / 20L;
    }

    private static boolean sameRod(ItemStack original, ItemStack current) {
        ItemStack first = original.clone();
        ItemStack second = current.clone();
        // Mending and normal damage can change durability while this cast lives.
        for (ItemStack stack : new ItemStack[]{first, second}) {
            if (stack.getItemMeta() instanceof Damageable meta) {
                meta.setDamage(0);
                stack.setItemMeta(meta);
            }
        }
        return first.isSimilar(second);
    }

    private void catchAfk(Player player, Session session) {
        FishDefinition fish = chooseFish(session);
        if (fish == null) return;
        int prestige = professions.angler(session.playerId).prestige();
        FishingLootPoolSelector.Result loot = lootPools.roll(true, prestige,
                FishingGame.Quality.RED, session.luckLevel, random);
        if (loot.main() == null) {
            plugin.getLogger().warning("Kein AFK-Fishing-Loot-Pool konfiguriert; Session beendet.");
            endSession(session.playerId, true, "LOOT_UNAVAILABLE");
            return;
        }
        if (!deliverAfkPool(player, fish, loot.main())) {
            endSession(session.playerId, true, "LOOT_DELIVERY_FAILED");
            return;
        }
        professions.recordAfkAnglerCatch(player, 25L);
        // Preserve the existing Luck extra-roll policy (FISH is preview-only),
        // but never send its admin preview message during an AFK session.
        if (loot.extra() != null && loot.extra() != FishingLootPoolSelector.Pool.FISH)
            deliverAfkPool(player, fish, loot.extra());
        ItemStack rod = player.getInventory().getItemInMainHand();
        ItemStack damaged = rod.damage(1, player); // Vanilla/Paper Unbreaking and break event.
        player.getInventory().setItemInMainHand(damaged);
        // Paper applies Mending as with collected XP; profession XP never repairs the rod.
        player.giveExp(random.nextInt(1, 7), true);
        if (damaged.isEmpty() || damaged.getType() != Material.FISHING_ROD) {
            endSession(session.playerId, true, "ROD_BROKEN");
        }
    }

    private boolean deliverAfkPool(Player player, FishDefinition fish, FishingLootPoolSelector.Pool selected) {
        if (selected == null) return false;
        if (selected == FishingLootPoolSelector.Pool.FISH) {
            ItemStack caught = factory.create(fish.id(), 1);
            if (caught == null) return false;
            ItemStack overflow = feature.storeCatch(player, caught);
            if (overflow != null) player.getWorld().dropItemNaturally(player.getLocation(), overflow);
            fishCaught.accept(player);
            return true;
        }
        int prestige = professions.angler(player.getUniqueId()).prestige();
        // Same pool rolls and delivery foundation as active fishing, without
        // public/admin helper chat messages on every automatic catch.
        AnglerLootFoundation.LootReward reward = switch (selected) {
            case JUNK -> junkPool.ready() ? junkPool.roll(random).reward() : null;
            case TREASURE -> treasurePool.ready() ? treasurePool.roll(random).reward() : null;
            case RARE -> rarePool.ready() ? rarePool.roll(random, prestige).reward() : null;
            case EPIC -> epicPool.ready() ? epicPool.roll(random, prestige).reward() : null;
            case LEGENDARY -> legendaryPool.ready() && prestige >= legendaryPool.requiredPrestige()
                    ? legendaryPool.roll(random, prestige).reward() : null;
            case FISH -> throw new IllegalStateException("Fish handled above");
        };
        return reward != null && lootFoundation.deliver(player, reward, feature).success();
    }

    private Component afkHud(Player player, Session session) {
        String template = plugin.configs().angler().getString("afk.actionbar", "");
        if (template == null || template.isBlank()) template =
                "<aqua>🎣 AFK-Angeln</aqua> <gray>• Nächster Biss: %seconds%s • Lager: %stored%/%capacity%</gray>";
        long seconds = afkSecondsRemaining(session.hook);
        int prestige = professions.angler(session.playerId).prestige();
        int capacity = feature.storage().rows(prestige) * 9;
        int occupied = 0;
        try {
            for (ItemStack item : feature.storage().snapshot(session.playerId, prestige))
                if (item != null && !item.getType().isAir()) occupied++;
        } catch (IOException | SQLException exception) {
            plugin.getLogger().log(Level.WARNING, "AFK-Fanglager-Anzeige konnte nicht gelesen werden", exception);
        }
        return miniMessage.deserialize(template.replace("%seconds%", Long.toString(seconds))
                .replace("%stored%", Integer.toString(occupied))
                .replace("%capacity%", Integer.toString(capacity)));
    }

    private boolean canUseActiveFishing(Player player) {
        return plugin.configs().angler().getBoolean("fishing.active.enabled", true)
                && player.isOnline() && !player.isDead() && player.getGameMode() == GameMode.SURVIVAL
                && !registry.definitions().isEmpty();
    }

    private boolean isActiveAngler(Player player) { return professions.isAnglerActive(player.getUniqueId()); }

    private boolean valid(Player player, Session session) {
        return canUseActiveFishing(player) && (session.hookRetired || session.hook.isValid())
                && player.getWorld().equals(session.world)
                && session.hookId.equals(session.hook.getUniqueId());
    }

    private void endSession(UUID playerId, boolean removeHook) {
        Session session = sessions.get(playerId);
        String reason = session == null ? null : afkInvalidReason(Bukkit.getPlayer(playerId), session);
        endSession(playerId, removeHook, true, reason == null ? "SESSION_REPLACED" : reason);
    }

    private void endSession(UUID playerId, boolean removeHook, String reason) {
        endSession(playerId, removeHook, true, reason);
    }

    private void endSession(UUID playerId, boolean removeHook, boolean notify, String reason) {
        Session old = sessions.remove(playerId);
        boolean wasAfk = old != null && old.mode == FishingMode.AFK;
        if (wasAfk && plugin.configs().main().getBoolean("debug.afk-fishing", false)) {
            Player owner = Bukkit.getPlayer(playerId);
            plugin.getLogger().info("[AFK-Fishing] STOP player=" + (owner == null ? playerId : owner.getName())
                    + " reason=" + reason + " globalAfk=" + plugin.getAfkManager().isAfk(owner)
                    + " hookValid=" + old.hook.isValid() + " hookState=" + old.hook.getState()
                    + " inWater=" + old.hook.isInWater() + " wait=" + old.hook.getWaitTime()
                    + " untilBite=" + old.hook.getTimeUntilBite());
        }
        // Remove ownership before hook callbacks or HUD refresh can re-enter us.
        if (old != null && (removeHook || wasAfk) && old.hook.isValid()) old.hook.remove();
        stopTaskIfIdle();
        Player player = Bukkit.getPlayer(playerId);
        if (wasAfk && notify && player != null && player.isOnline()) {
            plugin.messages().sendConfiguredAuto(player, plugin.configs().angler(),
                    "afk.ended-message", "<gray>AFK-Angeln wurde beendet.</gray>");
            refreshHud.accept(player);
        }
    }

    private void stopTaskIfIdle() {
        if (activeTask == null || sessions.values().stream().anyMatch(session ->
                session.state.phase() == FishingAttemptState.Phase.PLAYING)) return;
        activeTask.cancel();
        activeTask = null;
    }

    private void actionbar(Player player, String raw) { player.sendActionBar(miniMessage.deserialize(raw)); }

    static boolean rollMeistergriff(FileConfiguration config, FishingGame.Quality quality,
                                    int level, Random random) {
        if (quality != FishingGame.Quality.GREEN || level <= 0) return false;
        String path = "enchants.meistergriff.levels." + level + ".double-combo-chance";
        double chance = config.isSet(path) ? config.getDouble(path)
                : config.getDefaults() == null ? 0D : config.getDefaults().getDouble(path);
        return random.nextDouble() < Math.max(0D, Math.min(1D, chance));
    }

    /** Admin-only command hook: shows a category without rolling rewards or changing progress. */
    public void previewPool(Player player, FishingLootPoolSelector.Pool selectedPool) {
        player.sendMessage(miniMessage.deserialize(plugin.configs().angler().getString(
                "loot.test-message", "<gray>[Fishing-Test] Pool: <yellow>%pool%</yellow></gray>")
                .replace("%pool%", selectedPool.name())));
    }

    public boolean treasureReady() { return treasurePool.ready(); }
    public boolean junkReady() { return junkPool.ready(); }
    public boolean rareReady() { return rarePool.ready(); }
    public boolean rareAnglerActive(Player player) { return isActiveAngler(player); }
    public java.util.Set<String> rareEntryIds() { return rarePool.entryIds(); }
    public boolean rareOverlevelAvailable(Player player) {
        return isActiveAngler(player)
                && rarePool.hasEligibleOverlevel(professions.angler(player.getUniqueId()).prestige());
    }

    public boolean epicReady() { return epicPool.ready(); }
    public java.util.Set<String> epicEntryIds() { return epicPool.entryIds(); }
    public boolean epicOverlevelAvailable(Player player) {
        return isActiveAngler(player)
                && epicPool.hasEligibleOverlevel(professions.angler(player.getUniqueId()).prestige());
    }

    /** One Epic entry, either weighted or explicitly selected by the admin test. */
    public FishingEpicPool.Result giveEpic(Player player, String entryId) {
        if (!isActiveAngler(player)) {
            plugin.messages().sendConfiguredAuto(player, plugin.configs().angler(),
                    "loot.epic.angler-required-message", "<red>Für Epic-Loot muss Angler aktiv sein.</red>");
            return null;
        }
        if (!epicPool.ready()) {
            plugin.messages().sendConfiguredAuto(player, plugin.configs().angler(),
                    "loot.epic.unavailable-message", "<red>Epic-Loot ist derzeit nicht vollständig konfiguriert.</red>");
            return null;
        }
        try {
            int prestige = professions.angler(player.getUniqueId()).prestige();
            FishingEpicPool.Result result = entryId == null
                    ? epicPool.roll(random, prestige) : epicPool.rollSpecific(entryId, random, prestige);
            if (!lootFoundation.deliver(player, result.reward(), feature).success()) {
                plugin.messages().sendConfiguredAuto(player, plugin.configs().angler(),
                        "loot.epic.failed-message",
                        "<red>Dein epischer Fang konnte nicht ausgegeben werden. Das Team wurde informiert.</red>");
                return null;
            }
            plugin.messages().sendConfiguredAuto(player, plugin.configs().angler(),
                    "loot.epic.received-message",
                    "<gold>Epischer Fang: <white>%entry%</white> erhalten.</gold>",
                    "%entry%", result.displayName());
            return result;
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Epic-Loot konnte nicht ausgegeben werden", exception);
            plugin.messages().sendConfiguredAuto(player, plugin.configs().angler(),
                    "loot.epic.failed-message",
                    "<red>Dein epischer Fang konnte nicht ausgegeben werden. Das Team wurde informiert.</red>");
            return null;
        }
    }

    public Map<String, Integer> simulateEpic(Player player, int attempts) {
        if (!epicPool.ready() || !isActiveAngler(player)) return Map.of();
        int prestige = professions.angler(player.getUniqueId()).prestige();
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        epicPool.weights().keySet().stream().sorted().forEach(id -> counts.put(id, 0));
        for (int index = 0; index < attempts; index++)
            counts.merge(epicPool.rollEntryId(random, prestige), 1, Integer::sum);
        return Map.copyOf(counts);
    }

    public boolean legendaryReady() { return legendaryPool.ready(); }
    public java.util.Set<String> legendaryEntryIds() { return legendaryPool.entryIds(); }
    public boolean legendaryEligible(Player player) {
        return isActiveAngler(player)
                && professions.angler(player.getUniqueId()).prestige() >= legendaryPool.requiredPrestige();
    }
    public int legendaryRequiredPrestige() { return legendaryPool.requiredPrestige(); }

    /** One Legendary entry, selected by the live outer roll or the admin test. */
    public FishingLegendaryPool.Result giveLegendary(Player player, String entryId) {
        if (!isActiveAngler(player)) {
            plugin.messages().sendConfiguredAuto(player, plugin.configs().angler(),
                    "loot.legendary.angler-required-message", "<red>Für Legendary-Loot muss Angler aktiv sein.</red>");
            return null;
        }
        int prestige = professions.angler(player.getUniqueId()).prestige();
        if (prestige < legendaryPool.requiredPrestige()) {
            plugin.messages().sendConfiguredAuto(player, plugin.configs().angler(),
                    "loot.legendary.prestige-required-message", "<red>Legendary-Loot erfordert Angler-Prestige V.</red>");
            return null;
        }
        if (!legendaryPool.ready()) {
            plugin.messages().sendConfiguredAuto(player, plugin.configs().angler(),
                    "loot.legendary.unavailable-message", "<red>Legendary-Loot ist derzeit nicht vollständig konfiguriert.</red>");
            return null;
        }
        try {
            FishingLegendaryPool.Result result = entryId == null
                    ? legendaryPool.roll(random, prestige)
                    : legendaryPool.rollSpecific(entryId, random, prestige);
            if (!lootFoundation.deliver(player, result.reward(), feature).success()) {
                plugin.messages().sendConfiguredAuto(player, plugin.configs().angler(),
                        "loot.legendary.failed-message",
                        "<red>Dein legendärer Fang konnte nicht ausgegeben werden. Das Team wurde informiert.</red>");
                return null;
            }
            plugin.messages().sendConfiguredAuto(player, plugin.configs().angler(),
                    "loot.legendary.received-message",
                    "<gold>Legendärer Fang: <white>%entry%</white> erhalten.</gold>",
                    "%entry%", result.displayName());
            return result;
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Legendary-Loot konnte nicht ausgegeben werden", exception);
            plugin.messages().sendConfiguredAuto(player, plugin.configs().angler(),
                    "loot.legendary.failed-message",
                    "<red>Dein legendärer Fang konnte nicht ausgegeben werden. Das Team wurde informiert.</red>");
            return null;
        }
    }

    public Map<String, Integer> simulateLegendary(Player player, int attempts) {
        if (!legendaryPool.ready() || !legendaryEligible(player)) return Map.of();
        int prestige = professions.angler(player.getUniqueId()).prestige();
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        legendaryPool.weights().keySet().stream().sorted().forEach(id -> counts.put(id, 0));
        for (int index = 0; index < attempts; index++)
            counts.merge(legendaryPool.rollEntryId(random, prestige), 1, Integer::sum);
        return Map.copyOf(counts);
    }

    /** One Rare entry, either weighted or explicitly selected by the admin test. */
    public FishingRarePool.Result giveRare(Player player, String entryId) {
        if (!isActiveAngler(player)) {
            plugin.messages().sendConfiguredAuto(player, plugin.configs().angler(),
                    "loot.rare.angler-required-message", "<red>Für Rare-Loot muss Angler aktiv sein.</red>");
            return null;
        }
        if (!rarePool.ready()) {
            plugin.messages().sendConfiguredAuto(player, plugin.configs().angler(),
                    "loot.rare.unavailable-message", "<red>Rare-Loot ist derzeit nicht vollständig konfiguriert.</red>");
            return null;
        }
        try {
            int prestige = professions.angler(player.getUniqueId()).prestige();
            FishingRarePool.Result result = entryId == null
                    ? rarePool.roll(random, prestige) : rarePool.rollSpecific(entryId, random, prestige);
            if (!lootFoundation.deliver(player, result.reward(), feature).success()) {
                plugin.messages().sendConfiguredAuto(player, plugin.configs().angler(),
                        "loot.rare.failed-message",
                        "<red>Dein seltener Fang konnte nicht ausgegeben werden. Das Team wurde informiert.</red>");
                return null;
            }
            plugin.messages().sendConfiguredAuto(player, plugin.configs().angler(),
                    "loot.rare.received-message",
                    "<light_purple>Seltener Fang: <white>%entry%</white> erhalten.</light_purple>",
                    "%entry%", result.displayName());
            return result;
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Rare-Loot konnte nicht ausgegeben werden", exception);
            plugin.messages().sendConfiguredAuto(player, plugin.configs().angler(),
                    "loot.rare.failed-message",
                    "<red>Dein seltener Fang konnte nicht ausgegeben werden. Das Team wurde informiert.</red>");
            return null;
        }
    }

    public Map<String, Integer> simulateRare(Player player, int attempts) {
        if (!rarePool.ready() || !isActiveAngler(player)) return Map.of();
        int prestige = professions.angler(player.getUniqueId()).prestige();
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        rarePool.weights().keySet().stream().sorted().forEach(id -> counts.put(id, 0));
        for (int index = 0; index < attempts; index++)
            counts.merge(rarePool.rollEntryId(random, prestige), 1, Integer::sum);
        return Map.copyOf(counts);
    }

    /** Used by real catches and the single admin test; exactly one internal Junk roll. */
    public FishingJunkPool.Result giveJunk(Player player) {
        if (!junkPool.ready()) {
            plugin.messages().sendConfiguredAuto(player, plugin.configs().angler(),
                    "loot.junk.unavailable-message",
                    "<red>Junk-Loot ist derzeit nicht vollständig konfiguriert.</red>");
            return null;
        }
        try {
            FishingJunkPool.Result result = junkPool.roll(random);
            if (isActiveAngler(player)) {
                if (!lootFoundation.deliver(player, result.reward(), feature).success()) {
                    plugin.messages().sendConfiguredAuto(player, plugin.configs().angler(),
                            "loot.junk.failed-message",
                            "<red>Dein Beifang konnte nicht ausgegeben werden. Das Team wurde informiert.</red>");
                    return null;
                }
            } else {
                ItemStack item = lootFoundation.createPhysicalReward(result.reward());
                for (ItemStack overflow : player.getInventory().addItem(item).values())
                    player.getWorld().dropItemNaturally(player.getLocation(), overflow);
            }
            plugin.messages().sendConfiguredAuto(player, plugin.configs().angler(),
                    "loot.junk.received-message", "<gray>Beifang: <white>%entry%</white> erhalten.</gray>",
                    "%entry%", result.displayName());
            return result;
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Junk-Loot konnte nicht ausgegeben werden", exception);
            plugin.messages().sendConfiguredAuto(player, plugin.configs().angler(),
                    "loot.junk.failed-message",
                    "<red>Dein Beifang konnte nicht ausgegeben werden. Das Team wurde informiert.</red>");
            return null;
        }
    }

    /** Read-only internal-roll simulation: no item construction or reward delivery. */
    public Map<String, Integer> simulateJunk(int attempts) {
        if (!junkPool.ready()) return Map.of();
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        junkPool.weights().keySet().stream().sorted().forEach(id -> counts.put(id, 0));
        for (int index = 0; index < attempts; index++)
            counts.merge(junkPool.rollEntryId(random), 1, Integer::sum);
        return Map.copyOf(counts);
    }

    /** Used by real catches and the single admin test; exactly one internal Treasure roll. */
    public FishingTreasurePool.Result giveTreasure(Player player) {
        if (!treasurePool.ready()) {
            plugin.messages().sendConfiguredAuto(player, plugin.configs().angler(),
                    "loot.treasure.unavailable-message",
                    "<red>Treasure-Loot ist derzeit nicht vollständig konfiguriert.</red>");
            return null;
        }
        try {
            FishingTreasurePool.Result result = treasurePool.roll(random);
            if (!lootFoundation.deliver(player, result.reward(), feature).success()) {
                plugin.messages().sendConfiguredAuto(player, plugin.configs().angler(),
                        "loot.treasure.failed-message",
                        "<red>Dein Schatzfund konnte nicht ausgegeben werden. Das Team wurde informiert.</red>");
                return null;
            }
            plugin.messages().sendConfiguredAuto(player, plugin.configs().angler(),
                    "loot.treasure.received-message",
                    "<aqua>Schatzfund: <white>%entry%</white> erhalten.</aqua>",
                    "%entry%", result.displayName());
            return result;
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Treasure-Loot konnte nicht erzeugt werden", exception);
            plugin.messages().sendConfiguredAuto(player, plugin.configs().angler(),
                    "loot.treasure.failed-message",
                    "<red>Dein Schatzfund konnte nicht ausgegeben werden. Das Team wurde informiert.</red>");
            return null;
        }
    }

    /** Read-only internal-roll simulation: no item construction or reward delivery. */
    public Map<String, Integer> simulateTreasure(int attempts) {
        if (!treasurePool.ready()) return Map.of();
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        treasurePool.weights().keySet().stream().sorted().forEach(id -> counts.put(id, 0));
        for (int index = 0; index < attempts; index++)
            counts.merge(treasurePool.rollEntryId(random), 1, Integer::sum);
        return Map.copyOf(counts);
    }

    private void previewExtraPool(Player player, FishingLootPoolSelector.Pool selectedPool) {
        player.sendMessage(miniMessage.deserialize(plugin.configs().angler().getString(
                "loot.extra-test-message", "<gray>[Fishing-Test] Luck-Zusatzroll: <yellow>%pool%</yellow></gray>")
                .replace("%pool%", selectedPool.name())));
    }

    public record RollSimulation(boolean angler, int prestige, FishingGame.Quality quality,
                                 int attempts, int[] counts) { }

    /** Read-only admin simulation of the actual weighted primary roll. */
    public RollSimulation simulateRolls(Player target, FishingGame.Quality quality, int attempts) {
        boolean angler = isActiveAngler(target);
        int prestige = angler ? professions.angler(target.getUniqueId()).prestige() : -1;
        int[] counts = new int[FishingLootPoolSelector.Pool.values().length];
        for (int index = 0; index < attempts; index++) {
            FishingLootPoolSelector.Pool selected = lootPools.choose(angler, prestige, quality, random);
            if (selected != null) counts[selected.ordinal()]++;
        }
        return new RollSimulation(angler, prestige, quality, attempts, counts);
    }

    public void reset(UUID playerId) {
        combos.reset(playerId);
        endSession(playerId, true);
    }

    public void shutdown() {
        for (UUID playerId : new ArrayList<>(sessions.keySet()))
            endSession(playerId, true, false, "PLUGIN_DISABLE");
        if (activeTask != null) { activeTask.cancel(); activeTask = null; }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHookRemoved(EntityRemoveFromWorldEvent event) {
        if (!(event.getEntity() instanceof FishHook hook)) return;
        for (Session session : new ArrayList<>(sessions.values())) {
            if (!session.hookId.equals(hook.getUniqueId())) continue;
            if (session.state.phase() == FishingAttemptState.Phase.PLAYING
                    && Bukkit.getCurrentTick() == session.state.startedTick()) {
                // Some server builds retire the vanilla hook after the cancelled
                // first reel. The already validated catch continues logically.
                session.hookRetired = true;
                continue;
            }
            if (session.state.phase() == FishingAttemptState.Phase.PLAYING) combos.reset(session.playerId);
            endSession(session.playerId, false, "HOOK_REMOVED");
        }
    }

    @EventHandler public void onDeath(PlayerDeathEvent event) {
        combos.reset(event.getEntity().getUniqueId());
        endSession(event.getEntity().getUniqueId(), true, "DEATH");
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        combos.reset(event.getPlayer().getUniqueId());
        endSession(event.getPlayer().getUniqueId(), true, false, "QUIT");
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        combos.reset(event.getPlayer().getUniqueId());
        endSession(event.getPlayer().getUniqueId(), true, false, "KICK");
    }
    @EventHandler public void onWorld(PlayerChangedWorldEvent event) {
        combos.reset(event.getPlayer().getUniqueId());
        endSession(event.getPlayer().getUniqueId(), true, "WORLD_CHANGE");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) { endAfkRodSession(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) { endAfkRodSession(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (event.getItemDrop().getItemStack().getType() == Material.FISHING_ROD)
            endAfkRodSession(event.getPlayer());
    }

    private void endAfkRodSession(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null && session.mode == FishingMode.AFK)
            endSession(session.playerId, true, "ROD_INVALID");
    }
}
