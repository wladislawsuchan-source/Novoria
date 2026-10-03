package de.walahi.novosmp.duel;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.services.EconomyService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Facade for duel requests, match lifecycle, arena allocation and cleanup. */
public final class DuelManager {
    private final NovoSMPPlugin plugin;
    private final DuelConfig config;
    private final WorldEditArenaService arenas;
    private final DuelInventoryEditor editor;
    private final DuelSounds sounds;
    private final DuelEscrowService escrow;
    private final DuelCountdownService countdown;
    private final MiniMessage mm = MiniMessage.miniMessage();

    private final Map<UUID, DuelDraft> drafts = new ConcurrentHashMap<>();
    private final Map<UUID, DuelRequest> requests = new ConcurrentHashMap<>();
    private final Map<UUID, DuelMatch> matchesByPlayer = new ConcurrentHashMap<>();
    private final Map<String, DuelMatch> matchesByMap = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> crystalOwners = new ConcurrentHashMap<>();
    private final Set<String> resetRequired = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> arenaLeaveWarningCounts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> arenaLeaveWarningTimes = new ConcurrentHashMap<>();
    private final Set<UUID> specializedDrafts = ConcurrentHashMap.newKeySet();
    private DuelMenu menu;
    private DuelLifecycleHook lifecycle = new DuelLifecycleHook() { };

    public DuelManager(NovoSMPPlugin plugin, DuelConfig config, EconomyService economy,
                       WorldEditArenaService arenas, DuelInventoryEditor editor) {
        this.plugin = plugin;
        this.config = config;
        this.arenas = arenas;
        this.editor = editor;
        this.sounds = new DuelSounds(plugin, config);
        this.escrow = new DuelEscrowService(plugin, config, economy);
        this.countdown = new DuelCountdownService(plugin, config, sounds);
        Bukkit.getScheduler().runTask(plugin, this::refreshDuelWorldSettings);
    }

    public Collection<Listener> listeners() {
        return List.of(
                new DuelCombatListener(this),
                new DuelRestrictionListener(this),
                new DuelArenaEnvironmentListener(this),
                new DuelSessionListener(this),
                new DuelAdvancementListener(this)
        );
    }

    NovoSMPPlugin plugin() { return plugin; }
    DuelMatch match(UUID playerId) { return matchesByPlayer.get(playerId); }
    void rememberExplosiveOwner(UUID entityId, UUID playerId) { crystalOwners.put(entityId, playerId); }
    UUID explosiveOwner(UUID entityId) { return crystalOwners.get(entityId); }
    Location smpSpawnLocation() { return plugin.getSmpSpawnLocation(); }

    public void menu(DuelMenu menu) { this.menu = menu; }
    public void lifecycle(DuelLifecycleHook lifecycle) { this.lifecycle = lifecycle == null ? new DuelLifecycleHook() { } : lifecycle; }
    public List<String> requestLore(DuelRequest request) { return lifecycle.requestLore(request); }
    public boolean isSpecializedRequest(DuelRequest request) { return lifecycle.isSpecializedRequest(request); }
    public DuelConfig config() { return config; }
    public DuelInventoryEditor editor() { return editor; }
    public DuelSounds sounds() { return sounds; }
    public WorldEditArenaService arenas() { return arenas; }
    public void reopenDraft(Player player) { if (menu != null) menu.openDraft(player); }
    public DuelDraft draft(UUID playerId) { return drafts.get(playerId); }
    public boolean isSpecializedDraft(UUID playerId) { return specializedDrafts.contains(playerId); }
    public boolean inDuel(UUID playerId) { return matchesByPlayer.containsKey(playerId); }

    /**
     * Returns true only when both UUIDs belong to the same duel and are the two opponents.
     * This is used by other NovoSMP systems so duel rules can temporarily override
     * relationship-based restrictions without changing the players' saved settings.
     */
    public boolean areOpponents(UUID first, UUID second) {
        if (first == null || second == null || first.equals(second)) return false;
        DuelMatch match = matchesByPlayer.get(first);
        return match != null
                && match == matchesByPlayer.get(second)
                && second.equals(match.opponent(first));
    }

    /** True when this exact physical arena copy is occupied or being reset. */
    public boolean arenaBusy(DuelMap arena) {
        if (arena == null) return false;
        String physicalId = DuelConfig.normalize(arena.id());
        return matchesByMap.containsKey(physicalId) || arenas.isResetting(physicalId);
    }

    /** True when at least one physical copy belonging to the logical map is busy. */
    public boolean mapBusy(String mapId) {
        if (mapId == null) return false;
        List<DuelMap> physicalArenas = config.arenas(mapId);
        if (physicalArenas.isEmpty()) {
            String normalized = DuelConfig.normalize(mapId);
            return matchesByMap.containsKey(normalized) || arenas.isResetting(normalized);
        }
        for (DuelMap arena : physicalArenas) if (arenaBusy(arena)) return true;
        return false;
    }

    public int availableArenaCount(String mapId) {
        int available = 0;
        for (DuelMap arena : config.arenas(mapId)) if (arena.ready() && !arenaBusy(arena)) available++;
        return available;
    }

    private DuelMap availableArena(String mapId) {
        for (DuelMap arena : config.arenas(mapId)) {
            if (arena.ready() && !arenaBusy(arena)) return arena;
        }
        return null;
    }

    public void openDraft(Player challenger, Player target) {
        openDraft(challenger, target, false);
    }

    private void openDraft(Player challenger, Player target, boolean specialized) {
        if (challenger.equals(target)) {
            send(challenger, config.message("requests.self", "<red>Du kannst dich nicht selbst herausfordern.</red>"));
            return;
        }
        if (inDuel(challenger.getUniqueId()) || inDuel(target.getUniqueId())) {
            send(challenger, config.message("requests.player-busy", "<red>Einer von euch befindet sich bereits in einem Duell.</red>"));
            return;
        }
        DuelMap first = firstReadyMap();
        if (first == null) {
            send(challenger, config.message("requests.no-map", "<red>Es ist noch keine vollständig eingerichtete Duell-Map verfügbar.</red>"));
            return;
        }
        DuelKit defaultKit = specialized ? config.kit("sword") : null;
        if (specialized && defaultKit == null) {
            send(challenger, config.message("kits.king-sword-missing",
                    "<red>Das Sword-Kit für King-Duelle ist nicht eingerichtet.</red>"));
            return;
        }
        DuelDraft draft = new DuelDraft(challenger.getUniqueId(), target.getUniqueId(), first.id(),
                config.defaultDurationSeconds(), config.defaultRules());
        if (specialized) {
            draft.kitId(defaultKit.id());
            specializedDrafts.add(challenger.getUniqueId());
        } else {
            specializedDrafts.remove(challenger.getUniqueId());
        }
        drafts.put(challenger.getUniqueId(), draft);
        menu.openDraft(challenger);
        sounds.play(challenger, "menu-open");
    }

    /** Opens the normal draft GUI while marking the resulting request as a specialized duel. */
    public void openSpecializedDraft(Player challenger, Player target) {
        openDraft(challenger, target, true);
    }

    public List<DuelMap> readyMaps() {
        List<DuelMap> result = new ArrayList<>();
        for (DuelMap map : config.maps().values()) if (map.ready()) result.add(map);
        return result;
    }

    public void cycleMap(Player player) {
        DuelDraft draft = drafts.get(player.getUniqueId());
        if (draft == null) return;
        List<DuelMap> maps = readyMaps();
        if (maps.isEmpty()) return;
        int index = 0;
        for (int i = 0; i < maps.size(); i++) if (maps.get(i).id().equals(draft.mapId())) index = i;
        draft.mapId(maps.get((index + 1) % maps.size()).id());
        sounds.play(player, "menu-click");
        menu.openDraft(player);
    }

    public void cycleDuration(Player player) {
        DuelDraft draft = drafts.get(player.getUniqueId());
        if (draft == null) return;
        List<Integer> durations = config.durations();
        int index = durations.indexOf(draft.durationSeconds());
        draft.durationSeconds(durations.get((Math.max(0, index) + 1) % durations.size()));
        sounds.play(player, "menu-click");
        menu.openDraft(player);
    }

    public void setWager(Player player, long wager) {
        DuelDraft draft = drafts.get(player.getUniqueId());
        if (draft == null) return;
        if (wager < config.minimumWager() || wager > config.maximumWager()) {
            send(player, config.message("wager.outside-range", "<red>Der Einsatz liegt außerhalb des erlaubten Bereichs.</red>"));
            sounds.play(player, "error");
            menu.openDraft(player);
            return;
        }
        draft.wager(wager);
        sounds.play(player, "wager-set");
        menu.openDraft(player);
    }

    public void selectKit(Player player, String kitId) {
        DuelDraft draft = drafts.get(player.getUniqueId());
        if (draft == null) return;
        if (kitId == null && isSpecializedDraft(player.getUniqueId())) return;
        if (kitId != null && config.kit(kitId) == null) return;
        draft.kitId(kitId);
        sounds.play(player, "menu-click");
        menu.openDraft(player);
    }

    public void setRules(Player player, DuelRules rules) {
        DuelDraft draft = drafts.get(player.getUniqueId());
        if (draft == null) return;
        DuelRules previous = draft.rules();
        boolean enabled = (!previous.crystalsAllowed() && rules.crystalsAllowed())
                || (!previous.crystalDamage() && rules.crystalDamage())
                || (!previous.tntAllowed() && rules.tntAllowed())
                || (!previous.tntDamage() && rules.tntDamage());
        draft.rules(rules);
        sounds.play(player, enabled ? "rule-enabled" : "rule-disabled");
        menu.openRules(player);
    }

    public void cancelDraft(Player player) {
        drafts.remove(player.getUniqueId());
        specializedDrafts.remove(player.getUniqueId());
        sounds.play(player, "menu-back");
        player.closeInventory();
    }

    public void confirmDraft(Player challenger) {
        DuelDraft draft = drafts.get(challenger.getUniqueId());
        if (draft == null) return;
        boolean specialized = isSpecializedDraft(challenger.getUniqueId());
        if (specialized && draft.kitId() == null) {
            send(challenger, config.message("kits.king-sword-missing",
                    "<red>Für King-Duelle muss ein gültiges Kit ausgewählt sein.</red>"));
            return;
        }
        Player target = Bukkit.getPlayer(draft.target());
        DuelMap map = config.map(draft.mapId());
        if (target == null || !target.isOnline()) {
            send(challenger, config.message("requests.target-offline", "<red>Der Spieler ist nicht mehr online.</red>"));
            return;
        }
        if (map == null || !map.ready()) {
            send(challenger, config.message("requests.map-unavailable", "<red>Die ausgewählte Map ist nicht verfügbar.</red>"));
            return;
        }
        if (availableArena(map.id()) == null) {
            send(challenger, config.message("requests.map-busy", "<red>Alle Arenen dieser Map werden momentan verwendet oder zurückgesetzt.</red>"));
            return;
        }
        if (draft.kitId() != null && config.kit(draft.kitId()) == null) {
            send(challenger, config.message("kits.missing-after-selection", "<red>Das ausgewählte Kit existiert nicht mehr.</red>"));
            return;
        }
        if (inDuel(challenger.getUniqueId()) || inDuel(target.getUniqueId())) {
            send(challenger, config.message("requests.player-busy", "<red>Einer von euch befindet sich bereits in einem Duell.</red>"));
            return;
        }
        if (!escrow.canAffordBoth(challenger, target, draft.wager())) return;

        if (!lifecycle.beforeRequestCreated(draft, challenger, target, specialized)) return;
        specializedDrafts.remove(challenger.getUniqueId());
        long requestedLifetime = specialized ? lifecycle.specializedRequestLifetimeMillis() : 0L;
        long lifetime = specialized && requestedLifetime > 0L
                ? requestedLifetime
                : config.requestExpirySeconds() * 1000L;
        long expires = System.currentTimeMillis() + lifetime;
        DuelRequest request = new DuelRequest(UUID.randomUUID(), challenger.getUniqueId(), target.getUniqueId(),
                draft.mapId(), draft.durationSeconds(), draft.wager(), draft.kitId(), draft.rules(), expires);
        requests.put(request.id(), request);
        lifecycle.onRequestCreated(request, specialized);
        drafts.remove(challenger.getUniqueId());
        challenger.closeInventory();
        if (!lifecycle.handlesRequestCreatedMessages(request)) {
            send(challenger, config.message("requests.sent", "<green>Duellanfrage an <white>%player%</white> gesendet.</green>", "%player%", escape(target.getName())));
            sounds.play(challenger, "request-sent");
            sendRequestNotification(target, challenger);
        }
        long expiryTicks = Math.max(1L, (expires - System.currentTimeMillis() + 49L) / 50L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> expireRequest(request.id()), expiryTicks);
    }

    public DuelRequest findRequest(UUID target, String challengerName) {
        // Chat links identify the exact request, independently of names and
        // other challenges arriving after the notification was sent.
        if (challengerName != null) {
            try {
                DuelRequest exact = requests.get(UUID.fromString(challengerName));
                return exact != null && exact.target().equals(target) && !exact.expired() ? exact : null;
            } catch (IllegalArgumentException ignored) { /* Existing challenger-name syntax. */ }
        }
        DuelRequest newest = null;
        for (DuelRequest request : requests.values()) {
            if (!request.target().equals(target) || request.expired()) continue;
            Player challenger = Bukkit.getPlayer(request.challenger());
            if (challengerName != null && (challenger == null || !challenger.getName().equalsIgnoreCase(challengerName))) continue;
            if (newest == null || request.expiresAtMillis() > newest.expiresAtMillis()) newest = request;
        }
        return newest;
    }

    public List<String> pendingChallengerNames(UUID target) {
        List<String> result = new ArrayList<>();
        for (DuelRequest request : requests.values()) {
            if (!request.target().equals(target) || request.expired()) continue;
            Player player = Bukkit.getPlayer(request.challenger());
            if (player != null) result.add(player.getName());
        }
        return result;
    }

    public void showRequest(Player target, DuelRequest request) {
        if (request == null || request.expired() || !requests.containsKey(request.id())
                || !request.target().equals(target.getUniqueId())) {
            send(target, config.message("requests.invalid", "<red>Diese Duellanfrage ist nicht mehr gültig.</red>"));
            return;
        }
        if (menu != null) {
            menu.openRequest(target, request);
            sounds.play(target, "menu-open");
        }
    }

    private void sendRequestNotification(Player target, Player challenger) {
        String command = "/duel open " + challenger.getName();
        Component button = mm.deserialize(config.message("requests.notification.button",
                        "<green><bold>[HIER KLICKEN]</bold></green>"))
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(mm.deserialize(config.message("requests.notification.button-hover",
                        "<gray>Duellanfrage öffnen</gray>"))));
        Component message = mm.deserialize(config.message("requests.notification.intro",
                        "<yellow>Du hast eine Duellanfrage von <white>%player%</white> erhalten. </yellow>",
                        "%player%", escape(challenger.getName())))
                .append(button)
                .append(mm.deserialize(config.message("requests.notification.outro",
                        " <yellow>und danach im Menü annehmen.</yellow>")));
        target.sendMessage(DuelMessages.decorate(config, message));
        sounds.play(target, "request-received");
    }

    public void accept(Player target, DuelRequest request) {
        if (target == null || !target.isOnline()) return;
        if (request == null || !request.target().equals(target.getUniqueId())
                || requests.get(request.id()) != request || request.expired()) {
            if (request != null && request.target().equals(target.getUniqueId()) && request.expired()
                    && requests.remove(request.id(), request)) lifecycle.onRequestRemoved(request);
            send(target, config.message("requests.invalid", "<red>Diese Duellanfrage ist nicht mehr gültig.</red>"));
            return;
        }
        Player challenger = Bukkit.getPlayer(request.challenger());
        if (challenger == null || !challenger.isOnline()) {
            send(target, config.message("requests.challenger-offline", "<red>Der Herausforderer ist nicht mehr online.</red>"));
            return;
        }
        DuelMap logicalMap = config.map(request.mapId());
        DuelMap map = logicalMap == null || !logicalMap.ready() ? null : availableArena(request.mapId());
        if (map == null) {
            send(target, config.message("requests.all-arenas-unavailable-target", "<red>Alle Arenen dieser Map sind momentan belegt, werden zurückgesetzt oder sind nicht verfügbar.</red>"));
            send(challenger, config.message("requests.all-arenas-unavailable-challenger", "<red>Alle Arenen der ausgewählten Map sind momentan nicht verfügbar.</red>"));
            return;
        }
        if (request.kitId() != null && config.kit(request.kitId()) == null) {
            send(target, config.message("kits.missing-after-selection", "<red>Das ausgewählte Kit existiert nicht mehr.</red>"));
            send(challenger, config.message("kits.missing-after-selection", "<red>Das ausgewählte Kit existiert nicht mehr.</red>"));
            return;
        }
        if (lifecycle.isSpecializedRequest(request) && request.kitId() == null) {
            send(target, config.message("kits.king-sword-missing",
                    "<red>Für King-Duelle muss ein gültiges Kit ausgewählt sein.</red>"));
            return;
        }
        if (inDuel(challenger.getUniqueId()) || inDuel(target.getUniqueId())) {
            send(target, config.message("requests.player-busy", "<red>Einer von euch befindet sich bereits in einem Duell.</red>"));
            return;
        }
        if (!lifecycle.beforeAccept(request, challenger, target)) return;
        if (!escrow.canAffordBoth(challenger, target, request.wager())) return;

        if (!requests.remove(request.id(), request)) {
            send(target, config.message("requests.invalid", "<red>Diese Duellanfrage ist nicht mehr gültig.</red>"));
            return;
        }
        lifecycle.onRequestAccepted(request);

        requests.values().removeIf(other -> {
            boolean removed = other.challenger().equals(challenger.getUniqueId())
                || other.target().equals(challenger.getUniqueId())
                || other.challenger().equals(target.getUniqueId())
                || other.target().equals(target.getUniqueId());
            if (removed) lifecycle.onRequestRemoved(other);
            return removed;
        });

        DuelMatch match = new DuelMatch(request, map);
        clearArenaLeaveWarnings(challenger.getUniqueId(), target.getUniqueId());
        matchesByMap.put(map.id(), match);
        matchesByPlayer.put(challenger.getUniqueId(), match);
        matchesByPlayer.put(target.getUniqueId(), match);

        if (!escrow.withdraw(match, challenger, target)) {
            lifecycle.onTechnicalAbort(request);
            releaseMatch(match);
            return;
        }
        match.escrowed = request.wager() > 0L;
        sounds.play(target, "request-accepted");
        sounds.play(challenger, "request-accepted");

        // Ein Reset vor UND nach jedem Kampf war doppelte Arbeit und hat den Server beim
        // Annehmen bis zu 15 Sekunden blockiert. Normalerweise reicht der Reset nach dem
        // vorherigen Kampf. Vorher wird nur noch zurückgesetzt, wenn after-match deaktiviert
        // ist oder ein früherer Reset fehlgeschlagen ist.
        boolean prepareArena = resetRequired.contains(map.id())
                || (config.resetBeforeMatch() && !config.resetAfterMatch());
        if (prepareArena) {
            send(challenger, config.message("arena.preparing", "<yellow>Die Arena wird vorbereitet...</yellow>"));
            send(target, config.message("arena.preparing", "<yellow>Die Arena wird vorbereitet...</yellow>"));
            boolean resetStarted = arenas.resetAsync(map, error -> {
                if (match.state != DuelMatch.State.PREPARING) return;
                if (error != null) {
                    resetRequired.add(map.id());
                    plugin.getLogger().severe("Arena " + map.id()
                            + " konnte vor dem Duell nicht zurückgesetzt werden: " + error.getMessage());
                    escrow.refund(match);
                    lifecycle.onTechnicalAbort(request);
                    releaseMatch(match);
                    send(challenger, config.message("arena.prepare-failed", "<red>Die Arena konnte nicht vorbereitet werden.</red>"));
                    send(target, config.message("arena.prepare-failed", "<red>Die Arena konnte nicht vorbereitet werden.</red>"));
                    match.state = DuelMatch.State.FINISHED;
                    return;
                }
                resetRequired.remove(map.id());
                Player currentChallenger = Bukkit.getPlayer(request.challenger());
                Player currentTarget = Bukkit.getPlayer(request.target());
                if (currentChallenger == null || currentTarget == null
                        || !currentChallenger.isOnline() || !currentTarget.isOnline()) {
                    escrow.refund(match);
                    lifecycle.onTechnicalAbort(request);
                    releaseMatch(match);
                    match.state = DuelMatch.State.FINISHED;
                    return;
                }
                startMatch(match, currentChallenger, currentTarget);
            });
            if (!resetStarted) {
                escrow.refund(match);
                lifecycle.onTechnicalAbort(request);
                releaseMatch(match);
                send(challenger, config.message("arena.reset-running", "<red>Die Arena wird bereits zurückgesetzt.</red>"));
                send(target, config.message("arena.reset-running", "<red>Die Arena wird bereits zurückgesetzt.</red>"));
                match.state = DuelMatch.State.FINISHED;
            }
            return;
        }

        startMatch(match, challenger, target);
    }

    public void deny(Player target, DuelRequest request) {
        if (request != null && !lifecycle.mayDeny(request, target)) return;
        if (request == null || requests.remove(request.id()) == null) {
            send(target, config.message("requests.invalid", "<red>Diese Duellanfrage ist nicht mehr gültig.</red>"));
            return;
        }
        boolean handled = lifecycle.handleRequestDenied(request, target);
        lifecycle.onRequestRemoved(request);
        if (handled) { target.closeInventory(); return; }
        Player challenger = Bukkit.getPlayer(request.challenger());
        send(target, config.message("requests.denied-target", "<gray>Du hast die Duellanfrage abgelehnt.</gray>"));
        sounds.play(target, "request-denied");
        if (challenger != null) {
            send(challenger, config.message("requests.denied-challenger", "<red>%player% hat deine Duellanfrage abgelehnt.</red>", "%player%", escape(target.getName())));
            sounds.play(challenger, "request-denied");
        }
        target.closeInventory();
    }

    public void leave(Player player) {
        DuelMatch match = matchesByPlayer.get(player.getUniqueId());
        if (match == null) {
            send(player, config.message("match.not-in-duel", "<red>Du befindest dich in keinem Duell.</red>"));
            return;
        }
        finishMatch(match, match.opponent(player.getUniqueId()), config.message("match.leave", "<red>Das Duell wurde aufgegeben.</red>"));
    }

    private void startMatch(DuelMatch match, Player challenger, Player target) {
        if (!lifecycle.beforeStart(match.request, challenger, target)) {
            if (match.escrowed) escrow.refund(match);
            releaseMatch(match);
            match.state = DuelMatch.State.FINISHED;
            return;
        }
        refreshDuelWorldSettings();
        ejectOtherPlayersFromArena(match, challenger, target);
        clearLooseArenaItems(match);
        challenger.closeInventory();
        target.closeInventory();
        match.snapshots.put(challenger.getUniqueId(), DuelPlayerSnapshot.capture(challenger));
        match.snapshots.put(target.getUniqueId(), DuelPlayerSnapshot.capture(target));
        lifecycle.afterSnapshotsCaptured(match.request, challenger, target);
        prepareInventory(match, challenger);
        prepareInventory(match, target);
        DuelPlayerSnapshot.prepareForDuel(challenger);
        DuelPlayerSnapshot.prepareForDuel(target);
        plugin.setDuelTabPair(challenger, target);
        clearArenaLeaveWarnings(challenger.getUniqueId(), target.getUniqueId());

        // Der Freeze bezieht sich immer auf exakt die beiden eingestellten Map-Spawns.
        // Zustand und Anker werden VOR den Teleports gesetzt, damit kein Bewegungsfenster entsteht.
        Location challengerSpawn = match.map.spawnOne();
        Location targetSpawn = match.map.spawnTwo();
        match.state = DuelMatch.State.COUNTDOWN;
        match.countdownAnchors.clear();
        match.countdownAnchors.put(challenger.getUniqueId(), challengerSpawn.clone());
        match.countdownAnchors.put(target.getUniqueId(), targetSpawn.clone());

        challenger.setVelocity(new Vector(0D, 0D, 0D));
        target.setVelocity(new Vector(0D, 0D, 0D));
        teleportAllowed(challenger, challengerSpawn);
        teleportAllowed(target, targetSpawn);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            sounds.play(challenger, "arena-teleport");
            sounds.play(target, "arena-teleport");
        }, 1L);
        countdown.start(
                match,
                () -> clearArenaLeaveWarnings(match.request.challenger(), match.request.target()),
                () -> finishByScore(match)
        );
    }

    private void prepareInventory(DuelMatch match, Player player) {
        if (match.request.kitId() == null) return;
        DuelLoadout layout = config.layout(player.getUniqueId(), match.request.kitId());
        if (layout == null) {
            DuelKit kit = config.kit(match.request.kitId());
            layout = kit == null ? DuelLoadout.empty() : kit.loadout();
        }
        layout.apply(player);
    }

    private void finishByScore(DuelMatch match) {
        double first = match.damage.getOrDefault(match.request.challenger(), 0D);
        double second = match.damage.getOrDefault(match.request.target(), 0D);
        UUID winner = null;
        if (config.compareTotalDamage() && Math.abs(first - second) > 0.0001D) {
            winner = first > second ? match.request.challenger() : match.request.target();
        } else if (config.compareHealthOnTie()) {
            Player one = Bukkit.getPlayer(match.request.challenger());
            Player two = Bukkit.getPlayer(match.request.target());
            double healthOne = one == null ? 0D : one.getHealth() + one.getAbsorptionAmount();
            double healthTwo = two == null ? 0D : two.getHealth() + two.getAbsorptionAmount();
            if (Math.abs(healthOne - healthTwo) > 0.0001D) winner = healthOne > healthTwo ? match.request.challenger() : match.request.target();
        }
        finishMatch(match, winner, winner == null ? config.message("match.draw", "<yellow>Das Duell endet unentschieden.</yellow>") : config.message("match.timeout", "<yellow>Die Zeit ist abgelaufen.</yellow>"));
    }

    private void finishMatch(DuelMatch match, UUID winner, String reason) {
        finishMatch(match, winner, reason, null, false);
    }

    private void finishMatch(DuelMatch match, UUID winner, String reason, Player quittingPlayer) {
        finishMatch(match, winner, reason, quittingPlayer, false);
    }

    private synchronized void finishMatch(DuelMatch match, UUID winner, String reason,
                                          Player quittingPlayer, boolean lethalCombatVictory) {
        if (match.state == DuelMatch.State.ENDING || match.state == DuelMatch.State.FINISHED) return;
        countdown.stop(match);
        match.countdownAnchors.clear();
        match.state = DuelMatch.State.ENDING;
        match.winner = winner;
        match.resolved = true;
        if (match.timerTask != null) match.timerTask.cancel();

        Player one = resolvePlayer(match.request.challenger(), quittingPlayer);
        Player two = resolvePlayer(match.request.target(), quittingPlayer);
        if (lethalCombatVictory && winner != null && plugin.combatManager() != null) {
            UUID victim = match.opponent(winner);
            Player victimPlayer = resolvePlayer(victim, quittingPlayer);
            String winnerName = Bukkit.getOfflinePlayer(winner).getName();
            String victimName = Bukkit.getOfflinePlayer(victim).getName();
            plugin.combatManager().publishDuelKill(
                    winner, winnerName == null ? winner.toString() : winnerName,
                    victim, victimName == null ? victim.toString() : victimName,
                    victimPlayer == null ? null : victimPlayer.getLocation());
        }
        secureEndingCursor(match, one);
        secureEndingCursor(match, two);
        plugin.clearDuelTabPair(match.request.challenger(), match.request.target());

        if (match.escrowed) {
            if (winner == null) escrow.refund(match);
            else escrow.payoutWinner(match, winner);
            match.escrowed = false;
        }

        String winnerName = winner == null ? null : Bukkit.getOfflinePlayer(winner).getName();
        if (winnerName == null && winner != null) winnerName = winner.toString();
        sendResult(one, match, winner, winnerName, reason);
        sendResult(two, match, winner, winnerName, reason);

        // Bei einem Disconnect sofort sauber wiederherstellen. Bei einem normalen Ende bleiben
        // beide Spieler noch konfigurierbar lange in der Arena und sind dabei vollständig geschützt.
        int delaySeconds = quittingPlayer == null ? config.endDelaySeconds() : 0;
        if (delaySeconds <= 0) {
            finalizeMatch(match, quittingPlayer);
            return;
        }

        enableEndFlight(one);
        enableEndFlight(two);

        final int[] remaining = {delaySeconds};
        match.timerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (match.state != DuelMatch.State.ENDING) {
                if (match.timerTask != null) match.timerTask.cancel();
                return;
            }
            int seconds = remaining[0]--;
            if (seconds <= 0) {
                if (match.timerTask != null) match.timerTask.cancel();
                finalizeMatch(match, null);
                return;
            }
            if (shouldAnnounceEndDelay(seconds, delaySeconds)) {
                String unit = config.message(seconds == 1 ? "match.second-singular" : "match.second-plural",
                        seconds == 1 ? "Sekunde" : "Sekunden");
                Component actionBar = DuelMessages.actionBar(config, config.message("match.return-actionbar",
                        "<yellow>Rückkehr zum SMP in <white>%seconds% %unit%</white>...</yellow>",
                        "%seconds%", Integer.toString(seconds), "%unit%", unit));
                Player currentOne = Bukkit.getPlayer(match.request.challenger());
                Player currentTwo = Bukkit.getPlayer(match.request.target());
                if (currentOne != null && currentOne.isOnline()) currentOne.sendActionBar(actionBar);
                if (currentTwo != null && currentTwo.isOnline()) currentTwo.sendActionBar(actionBar);
            }
        }, 0L, 20L);
    }

    private void sendResult(Player player, DuelMatch match, UUID winner, String winnerName, String reason) {
        if (player == null || !player.isOnline()) return;
        send(player, reason);
        if (winner == null) {
            if (match.request.wager() > 0L) send(player, config.message("wager.refunded", "<gray>Beide Einsätze wurden zurückgezahlt.</gray>"));
            sounds.play(player, "result-draw");
        } else {
            send(player, config.message("match.winner", "<gold>Gewinner: <white>%player%</white></gold>", "%player%", escape(winnerName)));
            if (player.getUniqueId().equals(winner)) sounds.playVictory(player);
            else sounds.play(player, "result-loser");
        }
    }

    private void enableEndFlight(Player player) {
        if (player == null || !player.isOnline() || player.isDead()) return;
        player.setFallDistance(0F);
        player.setAllowFlight(true);
        player.setFlying(true);
    }

    private synchronized void finalizeMatch(DuelMatch match, Player quittingPlayer) {
        if (match.state != DuelMatch.State.ENDING) return;
        if (match.timerTask != null) match.timerTask.cancel();

        Player one = resolvePlayer(match.request.challenger(), quittingPlayer);
        Player two = resolvePlayer(match.request.target(), quittingPlayer);
        restorePlayer(match, one);
        restorePlayer(match, two);
        if (!match.lifecycleCompleted) {
            lifecycle.onFinishedAfterRestore(match.request, match.winner);
            match.lifecycleCompleted = true;
        }

        // Spieler sofort freigeben. Die Arena bleibt nur noch als belegt markiert, während
        // der blockweise Reset im Hintergrund über mehrere Ticks läuft. Dadurch wird der
        // Teleport zum SMP nicht mehr von einem riesigen WorldEdit-Paste blockiert.
        clearArenaTracking(match.map);
        match.playerPlacedBlocks.clear();
        releasePlayers(match);

        if (!config.resetAfterMatch()) {
            releaseMap(match);
            match.state = DuelMatch.State.FINISHED;
            return;
        }

        boolean resetStarted = arenas.resetAsync(match.map, error -> {
            if (error != null) {
                resetRequired.add(match.map.id());
                plugin.getLogger().severe("Arena " + match.map.id()
                        + " konnte nach dem Duell nicht zurückgesetzt werden: " + error.getMessage());
                plugin.getLogger().warning("Vor dem nächsten Duell wird automatisch ein erneuter Reset versucht.");
            } else {
                resetRequired.remove(match.map.id());
            }
            releaseMap(match);
            match.state = DuelMatch.State.FINISHED;
        });
        if (!resetStarted) {
            resetRequired.add(match.map.id());
            plugin.getLogger().warning("Arena " + match.map.id()
                    + " konnte nach dem Duell nicht zurückgesetzt werden, weil bereits ein Reset läuft.");
            releaseMap(match);
            match.state = DuelMatch.State.FINISHED;
        }
    }

    private void restorePlayer(DuelMatch match, Player player) {
        if (player == null || !match.restoredPlayers.add(player.getUniqueId())) return;
        boolean kitMatch = match.request.kitId() != null;
        if (kitMatch) clearTemporaryCraftingInventory(player);
        else secureEndingCursor(match, player);

        DuelPlayerSnapshot.prepareAfterDuel(player);
        DuelPlayerSnapshot snapshot = match.snapshots.get(player.getUniqueId());
        if (snapshot != null) {
            if (kitMatch) snapshot.restoreKitMatch(player);
            else snapshot.restoreCommon(player);
        }
        if (kitMatch) {
            // Zweite Bereinigung nach dem Snapshot-Restore verhindert Cursor-/Crafting-Ghost-Item-Leaks.
            clearTemporaryCraftingInventory(player);
            player.updateInventory();
        }
        Location spawn = plugin.getSmpSpawnLocation();
        if (spawn != null) {
            teleportAllowed(player, spawn);
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> sounds.play(player, "return-to-smp"), 1L);
            }
        }
    }

    private void secureEndingCursor(DuelMatch match, Player player) {
        if (player == null) return;
        ItemStack cursor = player.getItemOnCursor();
        if (cursor == null || cursor.getType().isAir() || cursor.getAmount() <= 0) return;

        // Kit-Matches sind vollständig temporär: Cursor-Inhalte dürfen niemals ins SMP gelangen.
        if (match.request.kitId() != null) {
            clearTemporaryCraftingInventory(player);
            return;
        }

        // Beim eigenen Inventar gehört das Cursor-Item dem Spieler. Erst Cursor leeren und
        // anschließend wieder in sein reales Inventar einsortieren. Falls wider Erwarten kein
        // Platz vorhanden ist, bleibt nur der Rest am Cursor statt Items zu löschen/duplizieren.
        ItemStack owned = cursor.clone();
        player.setItemOnCursor(null);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(owned);
        if (!leftovers.isEmpty()) {
            ItemStack remaining = leftovers.values().iterator().next();
            player.setItemOnCursor(remaining);
        }
        player.updateInventory();
    }

    /**
     * Das 2x2-Crafting-Feld gehört nicht zu den 36 normalen PlayerInventory-Slots und wird
     * deshalb vom Duell-Snapshot nicht überschrieben. Ohne diese Bereinigung würde Bukkit
     * dort abgelegte Kit-Items beim Schließen ins echte SMP-Inventar zurückgeben.
     */
    private void clearTemporaryCraftingInventory(Player player) {
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top.getType() == InventoryType.CRAFTING) {
            top.clear();
        }
        player.setItemOnCursor(null);
        player.updateInventory();
    }

    private void clearLooseArenaItems(DuelMatch match) {
        World world = match.map.world();
        if (world == null) return;
        for (Entity entity : new ArrayList<>(world.getEntities())) {
            if (entity instanceof Item && match.map.contains(entity.getLocation())) entity.remove();
        }
    }

    private Player resolvePlayer(UUID playerId, Player quittingPlayer) {
        if (quittingPlayer != null && quittingPlayer.getUniqueId().equals(playerId)) return quittingPlayer;
        return Bukkit.getPlayer(playerId);
    }

    private void teleportAllowed(Player player, Location target) {
        if (player == null || target == null) return;
        DuelTeleportRegistry.allow(player.getUniqueId());
        player.teleport(target);
        Bukkit.getScheduler().runTask(plugin, () -> DuelTeleportRegistry.disallow(player.getUniqueId()));
    }

    private void expireRequest(UUID id) {
        DuelRequest request = requests.remove(id);
        if (request == null) return;
        boolean handled = lifecycle.handleRequestExpired(request);
        lifecycle.onRequestRemoved(request);
        if (handled) return;
        Player challenger = Bukkit.getPlayer(request.challenger());
        Player target = Bukkit.getPlayer(request.target());
        if (challenger != null) {
            send(challenger, config.message("requests.expired-challenger", "<gray>Deine Duellanfrage ist abgelaufen.</gray>"));
            sounds.play(challenger, "request-expired");
        }
        if (target != null) {
            send(target, config.message("requests.expired-target", "<gray>Die Duellanfrage ist abgelaufen.</gray>"));
            sounds.play(target, "request-expired");
        }
    }

    private DuelMap firstReadyMap() {
        for (DuelMap map : config.maps().values()) if (map.ready()) return map;
        return null;
    }

    private void releasePlayers(DuelMatch match) {
        countdown.stop(match);
        match.countdownAnchors.clear();
        UUID challenger = match.request.challenger();
        UUID target = match.request.target();
        matchesByPlayer.remove(challenger, match);
        matchesByPlayer.remove(target, match);
        clearArenaLeaveWarnings(challenger, target);
    }

    void warnArenaLeave(Player player) {
        UUID playerId = player.getUniqueId();
        int sent = arenaLeaveWarningCounts.getOrDefault(playerId, 0);
        if (sent >= config.maximumArenaLeaveWarnings()) return;

        long now = System.currentTimeMillis();
        long last = arenaLeaveWarningTimes.getOrDefault(playerId, 0L);
        if (now - last < config.arenaLeaveWarningCooldownMillis()) return;

        arenaLeaveWarningTimes.put(playerId, now);
        arenaLeaveWarningCounts.put(playerId, sent + 1);
        send(player, config.message("arena.leave-blocked", "<red>Du kannst die Duell-Arena nicht verlassen.</red>"));
        sounds.play(player, "blocked-action");
    }

    private void clearArenaLeaveWarnings(UUID... playerIds) {
        for (UUID playerId : playerIds) {
            if (playerId == null) continue;
            arenaLeaveWarningCounts.remove(playerId);
            arenaLeaveWarningTimes.remove(playerId);
        }
    }

    private void releaseMap(DuelMatch match) {
        matchesByMap.remove(match.map.id(), match);
    }

    private void releaseMatch(DuelMatch match) {
        releasePlayers(match);
        releaseMap(match);
    }

    void finishFromCombat(DuelMatch match, UUID winner, String reason) {
        finishMatch(match, winner, reason, null, true);
    }

    void finishOnDisconnect(DuelMatch match, Player quittingPlayer) {
        UUID winner = config.instantLossOnDisconnect()
                ? match.opponent(quittingPlayer.getUniqueId())
                : null;
        finishMatch(
                match,
                winner,
                config.message("match.disconnected", "<red>Ein Spieler hat den Server verlassen.</red>"),
                quittingPlayer
        );
    }

    void removePendingState(UUID playerId) {
        drafts.remove(playerId);
        specializedDrafts.remove(playerId);
        requests.values().removeIf(request -> {
            boolean remove = request.challenger().equals(playerId) || request.target().equals(playerId);
            if (remove) lifecycle.onRequestRemoved(request);
            return remove;
        });
    }

    void restoreEndingPlayer(DuelMatch match, Player player) {
        restorePlayer(match, player);
        matchesByPlayer.remove(player.getUniqueId(), match);
    }

    void sendMessage(Player player, String message) {
        send(player, message);
    }

    public void refreshDuelWorldSettings() {
        Set<World> handled = new HashSet<>();
        for (DuelMap map : config.allArenas()) {
            World world = map.world();
            if (world == null || !handled.add(world)) continue;
            world.setSpawnFlags(false, false);
            for (Entity entity : new ArrayList<>(world.getEntities())) {
                if (entity instanceof Mob) entity.remove();
            }
        }
    }

    boolean isDuelWorld(World world) {
        if (world == null) return false;
        for (DuelMap map : config.allArenas()) {
            if (map.worldName() != null && map.worldName().equalsIgnoreCase(world.getName())) return true;
        }
        return false;
    }

    void ejectUnauthorizedDuelWorldPlayer(Player player) {
        if (player == null || !player.isOnline() || inDuel(player.getUniqueId()) || canBypassDuelWorldLock(player)) return;
        for (DuelMap map : config.allArenas()) {
            if (map.worldName() != null && map.worldName().equalsIgnoreCase(player.getWorld().getName())) {
                Location spawn = plugin.getSmpSpawnLocation();
                if (spawn != null) teleportAllowed(player, spawn);
                send(player, config.message("arena.world-entry-blocked", "<red>Die Duellwelt kann nur während eines Duells betreten werden.</red>"));
                return;
            }
        }
    }

    private void ejectOtherPlayersFromArena(DuelMatch match, Player challenger, Player target) {
        if (match.map.world() == null) return;
        Location spawn = plugin.getSmpSpawnLocation();
        for (Player player : new ArrayList<>(match.map.world().getPlayers())) {
            if (player.equals(challenger) || player.equals(target) || inDuel(player.getUniqueId())
                    || canBypassDuelWorldLock(player)) continue;
            if (spawn != null) teleportAllowed(player, spawn);
            send(player, config.message("arena.world-active-only", "<red>Die Duellwelt ist nur für aktive Duelle verfügbar.</red>"));
        }
    }

    private boolean canBypassDuelWorldLock(Player player) {
        return player.hasPermission(DuelPermissions.WORLD_BYPASS)
                || player.hasPermission(DuelPermissions.ADMIN);
    }

    DuelMatch matchAt(Location location) {
        if (location == null) return null;
        for (DuelMatch match : matchesByMap.values()) {
            if (match.map.contains(location)) return match;
        }
        return null;
    }

    DuelMatch matchForDamager(Entity damager) {
        if (damager instanceof Player player) return matchesByPlayer.get(player.getUniqueId());
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return matchesByPlayer.get(player.getUniqueId());
        }
        if (damager instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player player) {
            return matchesByPlayer.get(player.getUniqueId());
        }
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player) {
            return matchesByPlayer.get(player.getUniqueId());
        }
        return null;
    }

    private void clearArenaTracking(DuelMap map) {
        crystalOwners.keySet().removeIf(entityId -> {
            Entity entity = Bukkit.getEntity(entityId);
            return entity == null || map.contains(entity.getLocation());
        });
    }

    public void resetArena(Player player, DuelMap map) {
        if (map == null) {
            send(player, config.message("arena.map-not-found", "<red>Map nicht gefunden.</red>"));
            sounds.play(player, "error");
            return;
        }
        if (arenaBusy(map)) {
            send(player, config.message("arena.busy", "<red>Diese Arena wird gerade verwendet oder bereits zurückgesetzt.</red>"));
            sounds.play(player, "error");
            return;
        }
        send(player, config.message("arena.reset-started", "<yellow>Arena-Reset gestartet. Der Server bleibt dabei spielbar.</yellow>"));
        boolean started = arenas.resetAsync(map, error -> {
            if (error != null) {
                resetRequired.add(map.id());
                plugin.getLogger().severe("Manueller Reset der Arena " + map.id()
                        + " fehlgeschlagen: " + error.getMessage());
                send(player, config.message("arena.reset-failed", "<red>Reset fehlgeschlagen: %error%</red>", "%error%", escape(error.getMessage())));
                sounds.play(player, "error");
            } else {
                resetRequired.remove(map.id());
                send(player, config.message("arena.reset-success", "<green>Arena wurde zurückgesetzt.</green>"));
                sounds.play(player, "admin-success");
            }
        });
        if (!started) {
            send(player, config.message("arena.reset-already-running", "<red>Für diese Arena läuft bereits ein Reset.</red>"));
            sounds.play(player, "error");
        }
    }

    public void shutdown() {
        for (DuelMatch match : new HashSet<>(matchesByMap.values())) {
            if (match.escrowed) escrow.refund(match);
            Player one = Bukkit.getPlayer(match.request.challenger());
            Player two = Bukkit.getPlayer(match.request.target());
            restorePlayer(match, one);
            restorePlayer(match, two);
            if (!match.lifecycleCompleted) {
                if (match.resolved) lifecycle.onFinishedAfterRestore(match.request, match.winner);
                else lifecycle.onTechnicalAbort(match.request);
                match.lifecycleCompleted = true;
            }
            plugin.clearDuelTabPair(match.request.challenger(), match.request.target());
            if (match.timerTask != null) match.timerTask.cancel();
            clearArenaTracking(match.map);
            releaseMatch(match);
        }
        requests.values().forEach(lifecycle::onRequestRemoved);
        requests.clear();
        drafts.clear();
        specializedDrafts.clear();
        DuelTeleportRegistry.clear();
    }

    private boolean shouldAnnounceEndDelay(int seconds, int totalSeconds) {
        return seconds == totalSeconds || config.endDelayAnnouncementSeconds().contains(seconds);
    }

    private void send(Player player, String message) {
        if (player != null && player.isOnline()) DuelMessages.send(config, player, message);
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("<", "\\<").replace(">", "\\>");
    }

}
