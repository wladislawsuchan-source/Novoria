package de.walahi.novosmp.king;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.novosmp.duel.DuelLifecycleHook;
import de.walahi.novosmp.duel.DuelDraft;
import de.walahi.novosmp.duel.DuelManager;
import de.walahi.novosmp.duel.DuelRequest;
import de.walahi.novosmp.enderchest.ExpandableEnderChestHolder;
import de.walahi.novosmp.feature.PortableShulkerBoxListener;
import de.walahi.smpcore.afk.AfkAccess;
import de.walahi.smpcore.api.event.ActionContext;
import de.walahi.smpcore.economy.EconomyOperationResult;
import de.walahi.smpcore.gui.MenuFormat;
import de.walahi.smpcore.services.EconomyService;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import io.papermc.paper.event.block.DragonEggFormEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.block.ShulkerBox;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.logging.Level;

/** Canonical-item tracker, king benefit and specialized duel lifecycle. */
public final class DragonEggKingService implements Listener, DuelLifecycleHook {
    private final NovoSMPPlugin plugin;
    private final DragonEggKingConfig config;
    private final DragonEggKingRepository repository;
    private final DuelManager duels;
    private final EconomyService economy;
    private final AfkAccess afk;
    private final NamespacedKey tokenKey;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Map<UUID, Challenge> challenges = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private DragonEggKingState state;
    private BukkitTask coordinator;
    private boolean transferInProgress;

    public DragonEggKingService(NovoSMPPlugin plugin, DuelManager duels, EconomyService economy, AfkAccess afk) {
        this.plugin = plugin; this.duels = duels; this.economy = economy; this.afk = afk;
        this.config = new DragonEggKingConfig(plugin);
        this.repository = new DragonEggKingRepository(plugin.sharedDb());
        this.tokenKey = new NamespacedKey(plugin, "canonical_dragon_egg");
    }

    public void start() {
        if (!config.enabled()) return;
        try {
            state = repository.load();
            if (state == null) state = DragonEggKingState.initial(UUID.randomUUID());
            cooldowns.putAll(repository.loadCooldowns(System.currentTimeMillis()));
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "King-Zustand konnte nicht geladen werden", exception);
            state = DragonEggKingState.initial(UUID.randomUUID());
        }
        duels.lifecycle(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTask(plugin, this::bootstrapLocation);
        coordinator = Bukkit.getScheduler().runTaskTimer(plugin, this::tickChallenges, 20L, 20L);
    }

    public void shutdown() {
        if (coordinator != null) coordinator.cancel();
        for (Challenge challenge : new ArrayList<>(challenges.values())) refund(challenge);
        challenges.clear();
        persist();
    }

    public DragonEggKingState state() { return state; }
    public int mandatoryRemaining() {
        normalizeDate();
        return Math.max(0, config.mandatoryPerDay() - state.mandatoryUsed());
    }
    public int mandatoryTotal() { return config.mandatoryPerDay(); }
    public Optional<Player> activeKing() {
        if (state == null || state.kind() != KingLocationKind.PLAYER || state.holder() == null) return Optional.empty();
        Player player = Bukkit.getPlayer(state.holder());
        return player != null && player.isOnline() && findSlot(player.getInventory(), true) >= 0
                ? Optional.of(player) : Optional.empty();
    }
    public long cooldownRemaining(UUID player) { return Math.max(0L, cooldowns.getOrDefault(player, 0L) - System.currentTimeMillis()); }

    public void challenge(Player challenger) {
        if (!config.enabled() || state == null) { send(challenger, "disabled", "<red>Das King-System ist deaktiviert.</red>"); return; }
        Player king = activeKing().orElse(null);
        if (king == null) { send(challenger, "no-active-king", "<red>Das Drachenei befindet sich aktuell nicht im Inventar eines Kings.</red>"); return; }
        if (king.equals(challenger)) { send(challenger, "self", "<red>Der King kann sich nicht selbst herausfordern.</red>"); return; }
        if (cooldownRemaining(challenger.getUniqueId()) > 0L) {
            send(challenger, "cooldown", "<red>Du kannst den King in <yellow>%seconds%</yellow> Sekunden erneut herausfordern.</red>",
                    "%seconds%", Long.toString((cooldownRemaining(challenger.getUniqueId()) + 999L) / 1000L)); return;
        }
        if (config.requireFreeSlot() && !hasFreeStorageSlot(challenger)) { send(challenger, "free-slot", "<red>Du brauchst mindestens einen freien Inventarplatz, um den King herauszufordern.</red>"); return; }
        if (config.blockAfk() && afk != null && afk.isAfk(king)) { send(challenger, "king-afk", "<red>Der aktuelle King ist AFK und kann nicht herausgefordert werden.</red>"); return; }
        duels.openSpecializedDraft(challenger, king);
    }

    @Override public long specializedRequestLifetimeMillis() { return config.requestTimeoutSeconds() * 1000L; }

    @Override public synchronized boolean beforeRequestCreated(DuelDraft draft, Player challenger, Player target, boolean specialized) {
        if (!specialized) return true;
        Player king = activeKing().orElse(null);
        if (king == null || !king.getUniqueId().equals(target.getUniqueId())) {
            send(challenger, "king-changed", "<red>Das Drachenei befindet sich nicht mehr bei diesem King.</red>"); return false;
        }
        if (config.blockAfk() && afk != null && afk.isAfk(target)) {
            send(challenger, "king-afk", "<red>Der aktuelle King ist AFK und kann nicht herausgefordert werden.</red>"); return false;
        }
        if (config.requireFreeSlot() && !hasFreeStorageSlot(challenger)) {
            send(challenger, "free-slot", "<red>Du brauchst mindestens einen freien Inventarplatz, um den King herauszufordern.</red>"); return false;
        }
        if (mandatoryRemaining() > 0 && economy.balance(challenger.getUniqueId()) < config.mandatoryCost()) {
            send(challenger, "insufficient", "<red>Du benötigst <yellow>%cost% Coins</yellow> für dieses Pflichtduell.</red>",
                    "%cost%", MenuFormat.integer(config.mandatoryCost())); return false;
        }
        return true;
    }

    @Override
    public synchronized void onRequestCreated(DuelRequest request, boolean specialized) {
        if (!specialized) return;
        boolean mandatory = mandatoryRemaining() > 0;
        Challenge challenge = new Challenge(request, mandatory, System.currentTimeMillis(),
                System.currentTimeMillis() + config.acceptTimeoutSeconds() * 1000L);
        challenges.put(request.id(), challenge);
        Player challenger = Bukkit.getPlayer(request.challenger()); Player king = Bukkit.getPlayer(request.target());
        if (challenger != null) send(challenger, mandatory ? "mandatory-sent" : "voluntary-sent",
                mandatory ? "<gold>⚔ Pflichtduell an <white>%king%</white> gesendet.</gold> <gray>Einsatz: <yellow>%cost% Coins</yellow></gray>"
                        : "<green>⚔ Freiwilliges King-Duell an <white>%king%</white> gesendet.</green>",
                "%cost%", MenuFormat.integer(config.mandatoryCost()),
                "%king%", king == null ? request.target().toString() : esc(king.getName()));
        if (king != null) send(king, mandatory ? "mandatory-received" : "voluntary-received",
                mandatory ? "<gold>⚔ Pflichtduell von <white>%player%</white>.</gold> <gray>Auto-Annahme in <yellow>%seconds%s</yellow>. <click:run_command:'/duel requests'><yellow>[Öffnen]</yellow></click></gray>"
                        : "<yellow>⚔ Freiwilliges King-Duell von <white>%player%</white>.</yellow> <gray><click:run_command:'/duel requests'><yellow>[Öffnen]</yellow></click></gray>",
                "%player%", challenger == null ? request.challenger().toString() : esc(challenger.getName()),
                "%seconds%", Integer.toString(config.acceptTimeoutSeconds()));
    }

    @Override public synchronized boolean handlesRequestCreatedMessages(DuelRequest request) {
        return challenges.containsKey(request.id());
    }

    @Override public synchronized boolean isSpecializedRequest(DuelRequest request) {
        return request != null && challenges.containsKey(request.id());
    }

    @Override public synchronized boolean handleRequestDenied(DuelRequest request, Player target) {
        Challenge challenge = challenges.get(request.id());
        if (challenge == null) return false;
        Player challenger = Bukkit.getPlayer(request.challenger());
        send(target, "declined-target", "<gray>Du hast das freiwillige King-Duell abgelehnt.</gray>");
        send(challenger, "declined-challenger", "<red>Der King hat deine freiwillige Herausforderung abgelehnt.</red>");
        return true;
    }

    @Override public synchronized boolean handleRequestExpired(DuelRequest request) {
        Challenge challenge = challenges.get(request.id());
        if (challenge == null) return false;
        send(Bukkit.getPlayer(request.challenger()), "expired-challenger", "<gray>Deine King-Herausforderung ist abgelaufen.</gray>");
        send(Bukkit.getPlayer(request.target()), "expired-king", "<gray>Die King-Herausforderung ist abgelaufen.</gray>");
        return true;
    }

    @Override
    public synchronized boolean beforeAccept(DuelRequest request, Player challenger, Player target) {
        Challenge challenge = challenges.get(request.id());
        if (challenge == null) return true;
        if (!validParticipants(challenge, challenger, target, false)) return false;
        if (plugin.combatManager() != null && (plugin.combatManager().isInCombat(challenger.getUniqueId())
                || plugin.combatManager().isInCombat(target.getUniqueId()))) {
            send(target, "combat-wait", "<yellow>Das King-Duell wartet, bis der normale Combat beendet ist.</yellow>");
            return false;
        }
        return true;
    }

    @Override
    public synchronized boolean mayDeny(DuelRequest request, Player target) {
        Challenge challenge = challenges.get(request.id());
        if (challenge == null || !challenge.mandatory) return true;
        send(target, "mandatory-no-deny", "<red>Ein offenes Pflichtduell kann nicht abgelehnt werden.</red>");
        return false;
    }

    @Override
    public synchronized boolean beforeStart(DuelRequest request, Player challenger, Player target) {
        Challenge challenge = challenges.get(request.id());
        if (challenge == null) return true;
        if (challenge.started) return true;
        if (request.kitId() == null || duels.config().kit(request.kitId()) == null) {
            send(challenger, "duel-kit-missing", "<red>Das ausgewählte King-Duell-Kit ist nicht mehr verfügbar.</red>");
            challenges.remove(request.id());
            return false;
        }
        if (!validParticipants(challenge, challenger, target, true)) { challenges.remove(request.id()); return false; }
        if (challenge.mandatory) {
            if (mandatoryRemaining() <= 0) { send(challenger, "limit-reached", "<red>Die Pflichtduelle dieser Regentschaft sind bereits erfüllt.</red>"); challenges.remove(request.id()); return false; }
            EconomyOperationResult result = economy.withdraw(challenger.getUniqueId(), config.mandatoryCost(),
                    "KING-MANDATORY-DUEL", ActionContext.system(challenger.getUniqueId()));
            if (result != EconomyOperationResult.SUCCESS) {
                send(challenger, "insufficient", "<red>Du benötigst <yellow>%cost% Coins</yellow> für dieses Pflichtduell.</red>",
                        "%cost%", MenuFormat.integer(config.mandatoryCost()));
                challenges.remove(request.id()); return false;
            }
            challenge.charged = true;
            DragonEggKingState previous = state;
            state = state.useMandatory(today());
            try {
                repository.save(state);
            } catch (SQLException exception) {
                state = previous;
                EconomyOperationResult refund = economy.deposit(challenger.getUniqueId(), config.mandatoryCost(),
                        "KING-DUEL-STATE-ROLLBACK", ActionContext.system(challenger.getUniqueId()));
                challenge.charged = false;
                send(challenger, refund == EconomyOperationResult.SUCCESS ? "technical-abort" : "technical-refund-failed",
                        refund == EconomyOperationResult.SUCCESS
                                ? "<red>Das King-Duell konnte nicht sicher gestartet werden. Dein Einsatz wurde erstattet.</red>"
                                : "<red>Das King-Duell konnte nicht sicher gestartet werden. Die Erstattung ist fehlgeschlagen – bitte melde dich beim Team.</red>");
                plugin.getLogger().log(Level.SEVERE, "Pflichtduell-Zähler konnte nicht gespeichert werden", exception);
                return false;
            }
        }
        challenge.started = true;
        return true;
    }

    @Override public synchronized void afterSnapshotsCaptured(DuelRequest request, Player challenger, Player target) {
        if (!challenges.containsKey(request.id())) return;
        removeCanonical(target.getInventory());
        locate(KingLocationKind.DUEL, target.getUniqueId(), target.getName(), null, null, null, null, request.id().toString());
    }

    @Override public synchronized List<String> requestLore(DuelRequest request) {
        Challenge challenge = challenges.get(request.id());
        if (challenge == null) return List.of();
        return challenge.mandatory
                ? List.of("<gold><bold>Pflichtduell</bold></gold>", "<gray>Einsatz bei Start:</gray> <yellow>" + MenuFormat.integer(config.mandatoryCost()) + " Coins</yellow>", "<red>Der King muss diese Herausforderung annehmen.</red>")
                : List.of("<green><bold>Freiwilliges King-Duell</bold></green>", "<gray>Kosten:</gray> <green>0 Coins</green>", "<yellow>Der King kann ablehnen.</yellow>");
    }

    @Override
    public synchronized void onFinishedAfterRestore(DuelRequest request, UUID winner) {
        Challenge challenge = challenges.remove(request.id());
        if (challenge == null) return;
        long expires = System.currentTimeMillis() + config.cooldownMillis();
        cooldowns.put(request.challenger(), expires);
        try { repository.saveCooldown(request.challenger(), expires); }
        catch (SQLException exception) { plugin.getLogger().log(Level.WARNING, "King-Cooldown konnte nicht gespeichert werden", exception); }
        if (winner != null && winner.equals(request.challenger())) transferToWinner(request.target(), request.challenger());
        else refreshOnlineLocation();
    }

    @Override public synchronized void onTechnicalAbort(DuelRequest request) {
        Challenge challenge = challenges.remove(request.id());
        if (challenge != null) {
            boolean charged = challenge.charged;
            boolean refunded = refund(challenge);
            if (charged) {
                send(Bukkit.getPlayer(request.challenger()), refunded ? "technical-abort" : "technical-refund-failed",
                        refunded
                                ? "<red>Das King-Duell wurde technisch abgebrochen. Dein Einsatz und dein Pflichtduell wurden erstattet.</red>"
                                : "<red>Das King-Duell wurde technisch abgebrochen. Die Erstattung ist fehlgeschlagen – bitte melde dich beim Team.</red>");
                send(Bukkit.getPlayer(request.target()), "technical-abort-king", "<red>Das King-Duell wurde technisch abgebrochen.</red>");
            }
        }
        refreshOnlineLocation();
    }
    @Override public synchronized void onRequestRemoved(DuelRequest request) { challenges.remove(request.id()); }

    public synchronized boolean resetToPortal() {
        Location target = recoveryLocation(); if (target == null) return false;
        removeKnownCanonical();
        UUID next = UUID.randomUUID();
        target.getBlock().setType(Material.DRAGON_EGG, false);
        state = state.reset(next, target.getWorld().getName(), target.getBlockX(), target.getBlockY(), target.getBlockZ());
        persist(); return true;
    }

    public synchronized boolean setHolder(Player player) {
        if (player == null || !hasFreeStorageSlot(player)) return false;
        removeKnownCanonical();
        UUID next = UUID.randomUUID();
        state = state.reset(next, player.getWorld().getName(), player.getLocation().getBlockX(), player.getLocation().getBlockY(), player.getLocation().getBlockZ());
        player.getInventory().addItem(canonicalItem());
        locatePlayer(player); return true;
    }

    public String locateDescription() {
        if (state == null) return "<red>Kein King-Zustand geladen.</red>";
        int loadedOccurrences = loadedCanonicalOccurrences();
        if (loadedOccurrences > 1) return "<red><bold>RECOVERY-WARNUNG:</bold> " + loadedOccurrences + " geladene Instanzen der kanonischen Token-ID gefunden. Bitte /king reset prüfen.</red>";
        if (state.kind() == KingLocationKind.UNKNOWN) return "<red><bold>RECOVERY-WARNUNG:</bold> Das kanonische Ei ist nicht eindeutig lokalisiert.</red> <gray>Letzter Status: " + esc(state.detail()) + "</gray>";
        String base = "<gold>Ei:</gold> <white>" + state.kind() + "</white>";
        if (state.holderName() != null) base += " <gray>•</gray> " + esc(state.holderName()) + " <dark_gray>(" + state.holder() + ")</dark_gray>";
        if (state.world() != null) base += " <gray>•</gray> " + esc(state.world()) + " " + state.x() + "/" + state.y() + "/" + state.z();
        if (state.detail() != null) base += " <gray>•</gray> " + esc(state.detail());
        return base + " <gray>• aktiver King:</gray> " + (state.kind() == KingLocationKind.PLAYER ? "<green>ja" : "<red>nein");
    }

    private boolean validParticipants(Challenge challenge, Player challenger, Player target, boolean atStart) {
        Player king = activeKing().orElse(null);
        if (king == null || !king.getUniqueId().equals(target.getUniqueId())) {
            send(challenger, "king-changed", "<red>Das Drachenei befindet sich nicht mehr bei diesem King.</red>"); return false;
        }
        if (!challenger.isOnline() || !target.isOnline()) return false;
        if (config.requireFreeSlot() && !hasFreeStorageSlot(challenger)) {
            send(challenger, "free-slot", "<red>Du brauchst mindestens einen freien Inventarplatz, um den King herauszufordern.</red>"); return false;
        }
        if (atStart && plugin.combatManager() != null && (plugin.combatManager().isInCombat(challenger.getUniqueId())
                || plugin.combatManager().isInCombat(target.getUniqueId()))) return false;
        return true;
    }

    private boolean refund(Challenge challenge) {
        if (!challenge.charged) return true;
        DragonEggKingState previous = state;
        DragonEggKingState refunded = state.refundMandatory();
        try {
            repository.save(refunded);
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "Pflichtduell-Erstattung konnte nicht gespeichert werden", exception);
            return false;
        }
        EconomyOperationResult result = economy.deposit(challenge.request.challenger(), config.mandatoryCost(),
                "KING-DUEL-TECHNICAL-REFUND", ActionContext.system(challenge.request.challenger()));
        if (result != EconomyOperationResult.SUCCESS) {
            try { repository.save(previous); }
            catch (SQLException exception) { plugin.getLogger().log(Level.SEVERE, "Pflichtduell-Zähler-Rollback fehlgeschlagen", exception); }
            return false;
        }
        state = refunded;
        challenge.charged = false;
        return true;
    }

    private void transferToWinner(UUID oldKing, UUID winnerId) {
        transferInProgress = true;
        try {
            Player old = Bukkit.getPlayer(oldKing); if (old != null) removeCanonical(old.getInventory());
            Player winner = Bukkit.getPlayer(winnerId);
            if (winner == null || !winner.isOnline() || !hasFreeStorageSlot(winner)) { resetToPortal(); return; }
            removeKnownCanonical();
            winner.getInventory().addItem(canonicalItem());
            locatePlayer(winner);
            send(winner, "won", "<gold>Du hast das kanonische Drachenei gewonnen und bist jetzt King.</gold>");
            if (old != null && old.isOnline()) send(old, "lost", "<red>Du hast das Drachenei im King-Duell verloren.</red>");
        } finally { transferInProgress = false; }
    }

    private void tickChallenges() {
        long now = System.currentTimeMillis(); cooldowns.entrySet().removeIf(e -> e.getValue() <= now);
        List<Challenge> mandatory = challenges.values().stream().filter(c -> c.mandatory && !c.started)
                .sorted(Comparator.comparingLong(c -> c.created)).toList();
        for (Challenge challenge : mandatory) {
            Player king = Bukkit.getPlayer(challenge.request.target());
            Player challenger = Bukkit.getPlayer(challenge.request.challenger());
            if (king == null || !king.isOnline() || challenger == null || !challenger.isOnline()) continue;
            long seconds = Math.max(0L, (challenge.autoAcceptAt - now + 999L) / 1000L);
            king.sendActionBar(mm.deserialize("<gold>Pflichtduell:</gold> <yellow>" + seconds + "s bis Auto-Annahme</yellow>"));
            if (now < challenge.autoAcceptAt || duels.inDuel(king.getUniqueId()) || duels.inDuel(challenger.getUniqueId())) continue;
            if (plugin.combatManager() != null && (plugin.combatManager().isInCombat(king.getUniqueId())
                    || plugin.combatManager().isInCombat(challenger.getUniqueId()))) continue;
            duels.accept(king, challenge.request);
            break;
        }
    }

    private void bootstrapLocation() {
        if (findLoadedCanonical()) return;
        if (restoreKnownLocation()) return;
        Location existing = findExistingEggBlock();
        if (existing != null) { locateBlock(existing, KingLocationKind.PORTAL, "bestehendes Enderdrachen-Ei"); return; }
        if (state.kind() == KingLocationKind.UNKNOWN || state.kind() == KingLocationKind.GROUND || state.kind() == KingLocationKind.FALLING
                || state.kind() == KingLocationKind.PORTAL || state.kind() == KingLocationKind.BLOCK) {
            plugin.getLogger().warning("Kanonisches Drachenei war nicht mehr auffindbar; Recovery an der End-Portalposition wird ausgeführt.");
            resetToPortal();
        }
    }

    private boolean restoreKnownLocation() {
        if (state.world() == null || state.x() == null || state.y() == null || state.z() == null) return false;
        World world = Bukkit.getWorld(state.world()); if (world == null) return false;
        if (state.kind() == KingLocationKind.GROUND || state.kind() == KingLocationKind.FALLING) {
            world.getChunkAt(state.x() >> 4, state.z() >> 4).load();
        }
        if (state.kind() == KingLocationKind.CONTAINER || state.kind() == KingLocationKind.BLOCK || state.kind() == KingLocationKind.PORTAL) {
            Block block = world.getBlockAt(state.x(), state.y(), state.z());
            if (block.getType() == Material.DRAGON_EGG) return true;
            if (block.getState() instanceof InventoryHolder holder && (findSlot(holder.getInventory(), false) >= 0 || findNestedSlot(holder.getInventory(), false) >= 0)) return true;
        }
        if (state.kind() == KingLocationKind.GROUND && state.detail() != null) {
            try { Entity entity = Bukkit.getEntity(UUID.fromString(state.detail())); return entity instanceof Item item && (isCanonical(item.getItemStack()) || containsCanonicalInShulker(item.getItemStack())); }
            catch (IllegalArgumentException ignored) { }
        }
        if (state.kind() == KingLocationKind.FALLING && state.detail() != null) {
            try { Entity entity = Bukkit.getEntity(UUID.fromString(state.detail())); return entity instanceof FallingBlock && entityToken(entity); }
            catch (IllegalArgumentException ignored) { }
        }
        return state.kind() == KingLocationKind.PLAYER || state.kind() == KingLocationKind.ENDER_CHEST || state.kind() == KingLocationKind.CLAN_CHEST;
    }

    private boolean findLoadedCanonical() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (findSlot(player.getInventory(), true) >= 0) { locatePlayer(player); return true; }
            if (findSlot(player.getEnderChest(), false) >= 0) { locateEnderChest(player.getUniqueId(), player.getName()); return true; }
            int nested = findNestedSlot(player.getInventory(), true);
            if (nested >= 0) { locate(KingLocationKind.CONTAINER, player.getUniqueId(), player.getName(), null, null, null, null, "Shulkerbox im Inventar, Slot " + nested); return true; }
            if (findNestedSlot(player.getEnderChest(), false) >= 0) { locateEnderChest(player.getUniqueId(), player.getName()); return true; }
        }
        for (World world : Bukkit.getWorlds()) for (Entity entity : world.getEntities()) if (entity instanceof Item item && (isCanonical(item.getItemStack()) || containsCanonicalInShulker(item.getItemStack()))) {
            locateGround(item); return true;
        }
        return false;
    }

    private void refreshOnlineLocation() {
        if (transferInProgress || findLoadedCanonical()) return;
        if (state.kind() == KingLocationKind.PLAYER) locateUnknown("Ei hat das Spielerinventar verlassen");
    }

    private void refreshFromView(Player player) {
        if (transferInProgress) return;
        if (findSlot(player.getInventory(), true) >= 0) { locatePlayer(player); return; }
        Inventory top = player.getOpenInventory().getTopInventory();
        int slot = findSlot(top, false);
        if (slot < 0) {
            int nestedPlayer = findNestedSlot(player.getInventory(), true);
            if (nestedPlayer >= 0) { locate(KingLocationKind.CONTAINER, player.getUniqueId(), player.getName(), null, null, null, null, "Shulkerbox im Inventar, Slot " + nestedPlayer); return; }
            int nestedTop = findNestedSlot(top, false);
            if (nestedTop >= 0) { locateNestedTop(player, top, nestedTop); return; }
            refreshOnlineLocation(); return;
        }
        if (top.getHolder() instanceof ExpandableEnderChestHolder holder) { locateEnderChest(holder.owner(), Bukkit.getOfflinePlayer(holder.owner()).getName()); return; }
        if (top.getHolder() instanceof PortableShulkerBoxListener.PortableHolder holder) { locate(KingLocationKind.CONTAINER, holder.owner(), Bukkit.getOfflinePlayer(holder.owner()).getName(), null, null, null, null, "portable Shulkerbox, Slot " + slot); return; }
        UUID clan = plugin.clanManager() == null ? null : plugin.clanManager().chestClan(top);
        if (clan != null) { locate(KingLocationKind.CLAN_CHEST, clan, plugin.clanManager().clanName(clan), null, null, null, null, "Slot " + slot); return; }
        Location location = top.getLocation();
        if (top.getType() == org.bukkit.event.inventory.InventoryType.ENDER_CHEST) { locateEnderChest(player.getUniqueId(), player.getName()); return; }
        if (location != null) locate(KingLocationKind.CONTAINER, null, null, location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), top.getType() + " Slot " + slot);
        else locate(KingLocationKind.CONTAINER, null, null, null, null, null, null, top.getType() + " Slot " + slot);
    }

    private void locateNestedTop(Player viewer, Inventory top, int slot) {
        if (top.getHolder() instanceof ExpandableEnderChestHolder holder || top.getType() == org.bukkit.event.inventory.InventoryType.ENDER_CHEST) {
            UUID owner = top.getHolder() instanceof ExpandableEnderChestHolder h ? h.owner() : viewer.getUniqueId();
            locate(KingLocationKind.ENDER_CHEST, owner, Bukkit.getOfflinePlayer(owner).getName(), null, null, null, null, "Shulkerbox in Enderchest, Slot " + slot); return;
        }
        UUID clan = plugin.clanManager() == null ? null : plugin.clanManager().chestClan(top);
        if (clan != null) { locate(KingLocationKind.CLAN_CHEST, clan, plugin.clanManager().clanName(clan), null, null, null, null, "Shulkerbox, Slot " + slot); return; }
        Location l = top.getLocation();
        if (l != null) locate(KingLocationKind.CONTAINER, null, null, l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), "Shulkerbox in " + top.getType() + ", Slot " + slot);
        else locate(KingLocationKind.CONTAINER, viewer.getUniqueId(), viewer.getName(), null, null, null, null, "Shulkerbox, Slot " + slot);
    }

    private void locatePlayer(Player player) { locate(KingLocationKind.PLAYER, player.getUniqueId(), player.getName(), null, null, null, null, "Slot " + findSlot(player.getInventory(), true)); }
    private void locateEnderChest(UUID owner, String name) { locate(KingLocationKind.ENDER_CHEST, owner, name, null, null, null, null, "Enderchest"); }
    private void locateGround(Item item) { Location l=item.getLocation(); locate(KingLocationKind.GROUND,null,null,l.getWorld().getName(),l.getBlockX(),l.getBlockY(),l.getBlockZ(),item.getUniqueId().toString()); }
    private void locateBlock(Location l, KingLocationKind kind, String detail) { locate(kind,null,null,l.getWorld().getName(),l.getBlockX(),l.getBlockY(),l.getBlockZ(),detail); }
    private void locateUnknown(String detail) { locate(KingLocationKind.UNKNOWN,null,null,null,null,null,null,detail); }
    private synchronized void locate(KingLocationKind kind, UUID holder, String name, String world, Integer x, Integer y, Integer z, String detail) {
        state = state.located(kind, holder, name, world, x, y, z, detail, today()); persist();
    }

    private int findSlot(Inventory inventory, boolean storageOnly) {
        if (inventory == null) return -1;
        ItemStack[] contents = storageOnly && inventory instanceof PlayerInventory player ? player.getStorageContents() : inventory.getContents();
        for (int i=0;i<contents.length;i++) if (isCanonical(contents[i])) return i;
        return -1;
    }
    private int findNestedSlot(Inventory inventory, boolean storageOnly) {
        if (inventory == null) return -1;
        ItemStack[] contents = storageOnly && inventory instanceof PlayerInventory player ? player.getStorageContents() : inventory.getContents();
        for (int i=0;i<contents.length;i++) if (containsCanonicalInShulker(contents[i])) return i;
        return -1;
    }
    private boolean containsCanonicalInShulker(ItemStack item) {
        if (item == null || !(item.getItemMeta() instanceof BlockStateMeta meta) || !(meta.getBlockState() instanceof ShulkerBox box)) return false;
        return findSlot(box.getInventory(), false) >= 0;
    }
    private boolean containsKingEgg(ItemStack item) {
        return isCanonical(item) || containsCanonicalInShulker(item);
    }
    private boolean isEnderChest(Inventory inventory) {
        return inventory != null && (inventory.getType() == InventoryType.ENDER_CHEST
                || inventory.getHolder() instanceof ExpandableEnderChestHolder);
    }
    private boolean hasFreeStorageSlot(Player player) { for (ItemStack item : player.getInventory().getStorageContents()) if (item == null || item.getType().isAir()) return true; return false; }
    public boolean isCanonical(ItemStack item) {
        if (item == null || item.getType() != Material.DRAGON_EGG || !item.hasItemMeta() || state == null) return false;
        String token = item.getItemMeta().getPersistentDataContainer().get(tokenKey, PersistentDataType.STRING);
        return state.token().toString().equals(token);
    }
    private boolean hasMarker(ItemStack item) { return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(tokenKey); }
    private ItemStack canonicalItem() {
        ItemStack item = new ItemStack(Material.DRAGON_EGG); var meta=item.getItemMeta();
        meta.getPersistentDataContainer().set(tokenKey, PersistentDataType.STRING, state.token().toString()); item.setItemMeta(meta); return item;
    }
    private void stamp(ItemStack item) { if (item == null || item.getType()!=Material.DRAGON_EGG) return; var meta=item.getItemMeta(); meta.getPersistentDataContainer().set(tokenKey,PersistentDataType.STRING,state.token().toString()); item.setItemMeta(meta); }
    private void adoptRawEgg(Player player) {
        if (state.kind()!=KingLocationKind.UNKNOWN || !nearTrackedOrigin(player.getLocation())) return;
        for (ItemStack item:player.getInventory().getStorageContents()) if(item!=null&&item.getType()==Material.DRAGON_EGG&&!hasMarker(item)){stamp(item);locatePlayer(player);return;}
    }
    private boolean canAdoptRawAt(Location location) { return (state.kind()==KingLocationKind.PORTAL||state.kind()==KingLocationKind.BLOCK||state.kind()==KingLocationKind.UNKNOWN)&&nearTrackedOrigin(location); }
    private boolean nearTrackedOrigin(Location location) { if(location==null||state.world()==null||state.x()==null||!state.world().equals(location.getWorld().getName()))return false;double dx=state.x()-location.getX(),dy=state.y()-location.getY(),dz=state.z()-location.getZ();return dx*dx+dy*dy+dz*dz<=1024D; }
    private boolean entityToken(Entity entity){String token=entity.getPersistentDataContainer().get(tokenKey,PersistentDataType.STRING);return state!=null&&state.token().toString().equals(token);}
    private void removeCanonical(Inventory inventory) { if(inventory==null)return;for(int i=0;i<inventory.getSize();i++)if(isCanonical(inventory.getItem(i)))inventory.setItem(i,null); }
    private void removeKnownCanonical() {
        for(Player p:Bukkit.getOnlinePlayers()){removeCanonical(p.getInventory());removeCanonical(p.getEnderChest());removeCanonical(p.getOpenInventory().getTopInventory());}
        for(World w:Bukkit.getWorlds())for(Entity e:new ArrayList<>(w.getEntities()))if(e instanceof Item i&&isCanonical(i.getItemStack()))i.remove();
        if(state.world()!=null&&state.x()!=null){World w=Bukkit.getWorld(state.world());if(w!=null){Block b=w.getBlockAt(state.x(),state.y(),state.z());if(b.getType()==Material.DRAGON_EGG)b.setType(Material.AIR,false);if(b.getState() instanceof InventoryHolder h)removeCanonical(h.getInventory());}}
    }

    private int loadedCanonicalOccurrences() {
        int count = 0;
        Set<Inventory> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (Inventory inventory : List.of(player.getInventory(), player.getEnderChest(), player.getOpenInventory().getTopInventory())) {
                if (!seen.add(inventory)) continue;
                for (ItemStack item : inventory.getContents()) {
                    if (isCanonical(item)) count += Math.max(1, item.getAmount());
                    else if (containsCanonicalInShulker(item)) count++;
                }
            }
        }
        for (World world : Bukkit.getWorlds()) for (Entity entity : world.getEntities()) if (entity instanceof Item item) {
            if (isCanonical(item.getItemStack())) count += Math.max(1, item.getItemStack().getAmount());
            else if (containsCanonicalInShulker(item.getItemStack())) count++;
        }
        return count;
    }

    private Location findExistingEggBlock() {
        World world=Bukkit.getWorld(config.world());if(world==null)return null;Location base=portalBase(world);if(base==null)return null;
        for(int x=-8;x<=8;x++)for(int y=-2;y<=8;y++)for(int z=-8;z<=8;z++){Block b=world.getBlockAt(base.getBlockX()+x,base.getBlockY()+y,base.getBlockZ()+z);if(b.getType()==Material.DRAGON_EGG)return b.getLocation();}
        return null;
    }
    private Location recoveryLocation() { World w=Bukkit.getWorld(config.world());if(w==null)return null;Location existing=findExistingEggBlock();if(existing!=null)return existing;Location base=portalBase(w);return base==null?null:base.clone().add(0,4,0).toBlockLocation(); }
    private Location portalBase(World world) { try { if(world.getEnderDragonBattle()!=null&&world.getEnderDragonBattle().getEndPortalLocation()!=null)return world.getEnderDragonBattle().getEndPortalLocation(); }catch(Exception ignored){} return null; }
    private LocalDate today(){return LocalDate.now(config.zone());}
    private void normalizeDate(){if(state!=null&&!Objects.equals(state.duelDate(),today())){state=state.normalizedDate(today());persist();}}
    private void persist(){if(state==null)return;try{repository.save(state);}catch(SQLException e){plugin.getLogger().log(Level.SEVERE,"King-Zustand konnte nicht gespeichert werden",e);}}
    private void send(Player p,String key,String fallback,String... replacements){if(p==null)return;String value=config.message(key,fallback);for(int i=0;i+1<replacements.length;i+=2)value=value.replace(replacements[i],replacements[i+1]);p.sendMessage(mm.deserialize("<dark_gray>[<gold>King</gold>]</dark_gray> "+value));}
    private String esc(String s){return s==null?"":s.replace("<","\\<").replace(">","\\>");}

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void blockKingEggInEnderChest(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!isEnderChest(top) || !(e.getWhoClicked() instanceof Player player)) return;
        boolean movingIntoTop = false;
        if (e.getRawSlot() >= 0 && e.getRawSlot() < top.getSize()) {
            InventoryAction action = e.getAction();
            movingIntoTop = switch (action) {
                case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> containsKingEgg(e.getCursor());
                case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> {
                    int hotbar = e.getHotbarButton();
                    yield hotbar >= 0 && hotbar < 9 && containsKingEgg(player.getInventory().getItem(hotbar))
                            || e.getClick() == ClickType.SWAP_OFFHAND
                            && containsKingEgg(player.getInventory().getItemInOffHand());
                }
                case UNKNOWN -> containsKingEgg(e.getCursor())
                        || containsKingEgg(player.getInventory().getItemInOffHand());
                default -> false;
            };
            if (e.getClick() == ClickType.NUMBER_KEY && e.getHotbarButton() >= 0) {
                movingIntoTop |= containsKingEgg(player.getInventory().getItem(e.getHotbarButton()));
            }
            if (e.getClick() == ClickType.SWAP_OFFHAND) {
                movingIntoTop |= containsKingEgg(player.getInventory().getItemInOffHand());
            }
        } else if (e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            movingIntoTop = containsKingEgg(e.getCurrentItem());
        }
        // A double-click may collect matching items from both inventories. Reject it
        // while the cursor carries the King item so no unusual client path can store it.
        if (e.getClick() == ClickType.DOUBLE_CLICK && containsKingEgg(e.getCursor())) movingIntoTop = true;
        if (movingIntoTop) e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void blockKingEggEnderChestDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!isEnderChest(top) || e.getRawSlots().stream().noneMatch(slot -> slot < top.getSize())) return;
        if (containsKingEgg(e.getOldCursor()) || e.getNewItems().values().stream().anyMatch(this::containsKingEgg)) {
            e.setCancelled(true);
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void blockKingEggEnderChestTransfer(InventoryMoveItemEvent e) {
        if (isEnderChest(e.getDestination()) && containsKingEgg(e.getItem())) e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void blockKingEggEnderChestPickup(InventoryPickupItemEvent e) {
        if (isEnderChest(e.getInventory()) && containsKingEgg(e.getItem().getItemStack())) e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void click(InventoryClickEvent e){if(e.getWhoClicked() instanceof Player p)Bukkit.getScheduler().runTask(plugin,()->{adoptRawEgg(p);refreshFromView(p);});}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void creative(InventoryCreativeEvent e){if(isCanonical(e.getCursor())||isCanonical(e.getCurrentItem())||containsCanonicalInShulker(e.getCursor())||containsCanonicalInShulker(e.getCurrentItem()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void drag(InventoryDragEvent e){if(e.getWhoClicked() instanceof Player p)Bukkit.getScheduler().runTask(plugin,()->{adoptRawEgg(p);refreshFromView(p);});}
    @EventHandler(priority=EventPriority.MONITOR) public void close(InventoryCloseEvent e){if(e.getPlayer() instanceof Player p){refreshFromView(p);Bukkit.getScheduler().runTask(plugin,()->refreshOnlineLocation());}}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void drop(PlayerDropItemEvent e){if(isCanonical(e.getItemDrop().getItemStack())||containsCanonicalInShulker(e.getItemDrop().getItemStack()))locateGround(e.getItemDrop());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void pickup(EntityPickupItemEvent e){if(!(e.getEntity() instanceof Player p))return;ItemStack stack=e.getItem().getItemStack();if(stack.getType()==Material.DRAGON_EGG&&!hasMarker(stack)&&canAdoptRawAt(e.getItem().getLocation()))stamp(stack);if(isCanonical(stack)||containsCanonicalInShulker(stack))Bukkit.getScheduler().runTask(plugin,()->refreshFromView(p));}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void despawn(ItemDespawnEvent e){if(isCanonical(e.getEntity().getItemStack())||containsCanonicalInShulker(e.getEntity().getItemStack()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void itemDamage(EntityDamageEvent e){if(e.getEntity() instanceof Item i&&(isCanonical(i.getItemStack())||containsCanonicalInShulker(i.getItemStack())))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.MONITOR) public void removed(EntityRemoveEvent e){if(transferInProgress)return;if(e.getEntity() instanceof Item i&&(isCanonical(i.getItemStack())||containsCanonicalInShulker(i.getItemStack()))){if(e.getCause()==EntityRemoveEvent.Cause.OUT_OF_WORLD||e.getCause()==EntityRemoveEvent.Cause.EXPLODE||e.getCause()==EntityRemoveEvent.Cause.DISCARD)Bukkit.getScheduler().runTask(plugin,()->{if(state.kind()==KingLocationKind.GROUND&&Bukkit.getEntity(i.getUniqueId())==null)resetToPortal();});}else if(e.getEntity() instanceof FallingBlock f&&entityToken(f)&&(e.getCause()==EntityRemoveEvent.Cause.OUT_OF_WORLD||e.getCause()==EntityRemoveEvent.Cause.DISCARD))Bukkit.getScheduler().runTask(plugin,this::resetToPortal);}
    @EventHandler(priority=EventPriority.MONITOR) public void death(PlayerDeathEvent e){Bukkit.getScheduler().runTask(plugin,()->refreshOnlineLocation());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void itemDrop(EntityDropItemEvent e){Item i=e.getItemDrop();if(!isCanonical(i.getItemStack())&&i.getItemStack().getType()==Material.DRAGON_EGG&&!hasMarker(i.getItemStack())&&(entityToken(e.getEntity())||canAdoptRawAt(i.getLocation())))stamp(i.getItemStack());if(isCanonical(i.getItemStack()))locateGround(i);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void blockDrop(BlockDropItemEvent e){for(Item i:e.getItems()){if(!isCanonical(i.getItemStack())&&i.getItemStack().getType()==Material.DRAGON_EGG&&!hasMarker(i.getItemStack())&&canAdoptRawAt(e.getBlock().getLocation()))stamp(i.getItemStack());if(isCanonical(i.getItemStack())||containsCanonicalInShulker(i.getItemStack()))locateGround(i);}}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void move(InventoryMoveItemEvent e){Bukkit.getScheduler().runTask(plugin,()->{Inventory d=e.getDestination();Location l=d.getLocation();if(findSlot(d,false)>=0&&l!=null)locate(KingLocationKind.CONTAINER,null,null,l.getWorld().getName(),l.getBlockX(),l.getBlockY(),l.getBlockZ(),d.getType().name());});}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void hopper(InventoryPickupItemEvent e){if(isCanonical(e.getItem().getItemStack()))Bukkit.getScheduler().runTask(plugin,()->{Location l=e.getInventory().getLocation();if(l!=null)locate(KingLocationKind.CONTAINER,null,null,l.getWorld().getName(),l.getBlockX(),l.getBlockY(),l.getBlockZ(),e.getInventory().getType().name());});}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void place(BlockPlaceEvent e){if(isCanonical(e.getItemInHand()))Bukkit.getScheduler().runTask(plugin,()->locateBlock(e.getBlock().getLocation(),KingLocationKind.BLOCK,"platziertes Ei"));}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void breakEgg(BlockBreakEvent e){if(e.getBlock().getType()==Material.DRAGON_EGG&&state.world()!=null&&sameBlock(e.getBlock().getLocation())){Location l=e.getBlock().getLocation();locate(KingLocationKind.UNKNOWN,null,null,l.getWorld().getName(),l.getBlockX(),l.getBlockY(),l.getBlockZ(),"Ei wird abgebaut");}}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void piston(BlockPistonExtendEvent e){Bukkit.getScheduler().runTask(plugin,()->trackNearbyBlock(e.getBlock().getLocation()));}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void piston(BlockPistonRetractEvent e){Bukkit.getScheduler().runTask(plugin,()->trackNearbyBlock(e.getBlock().getLocation()));}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void interact(PlayerInteractEvent e){if(e.getClickedBlock()!=null&&e.getClickedBlock().getType()==Material.DRAGON_EGG&&sameOrUnknown(e.getClickedBlock().getLocation()))Bukkit.getScheduler().runTask(plugin,()->trackNearbyBlock(e.getClickedBlock().getLocation()));}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void falling(EntityChangeBlockEvent e){if(!(e.getEntity() instanceof FallingBlock f)||f.getBlockData().getMaterial()!=Material.DRAGON_EGG)return;if(e.getTo().isAir()&&canAdoptRawAt(e.getBlock().getLocation())){f.getPersistentDataContainer().set(tokenKey,PersistentDataType.STRING,state.token().toString());Location l=f.getLocation();locate(KingLocationKind.FALLING,null,null,l.getWorld().getName(),l.getBlockX(),l.getBlockY(),l.getBlockZ(),f.getUniqueId().toString());return;}if(entityToken(f)&&!e.getTo().isAir())Bukkit.getScheduler().runTask(plugin,()->locateBlock(e.getBlock().getLocation(),KingLocationKind.BLOCK,"gelandetes Ei"));}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void dragonEggForm(DragonEggFormEvent e){if(state!=null&&state.kind()!=KingLocationKind.UNKNOWN){e.setCancelled(true);return;}Bukkit.getScheduler().runTask(plugin,()->locateBlock(e.getBlock().getLocation(),KingLocationKind.PORTAL,"Vanilla-Drachenei"));}
    @EventHandler(priority=EventPriority.MONITOR) public void join(PlayerJoinEvent e){Bukkit.getScheduler().runTask(plugin,()->{adoptRawEgg(e.getPlayer());refreshOnlineLocation();});}
    @EventHandler(priority=EventPriority.MONITOR) public void quit(PlayerQuitEvent e){if(state.kind()==KingLocationKind.PLAYER&&e.getPlayer().getUniqueId().equals(state.holder()))locate(KingLocationKind.PLAYER,e.getPlayer().getUniqueId(),e.getPlayer().getName(),null,null,null,null,"offline");challenges.values().removeIf(c->c.request.target().equals(e.getPlayer().getUniqueId())&&!c.started);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void elytra(PlayerItemDamageEvent e){if(!config.elytraProtected()||e.getItem().getType()!=Material.ELYTRA)return;Player p=e.getPlayer();if(activeKing().map(p::equals).orElse(false)&&e.getItem().equals(p.getInventory().getChestplate()))e.setCancelled(true);}
    private boolean sameBlock(Location l){return state.x()!=null&&state.world().equals(l.getWorld().getName())&&state.x()==l.getBlockX()&&state.y()==l.getBlockY()&&state.z()==l.getBlockZ();}
    private boolean sameOrUnknown(Location l){return state.kind()==KingLocationKind.UNKNOWN||state.x()==null||sameBlock(l);}
    private boolean sameWorld(Location l){return state.world()==null||state.world().equals(l.getWorld().getName());}
    private void trackNearbyBlock(Location l){if(state.kind()!=KingLocationKind.BLOCK&&state.kind()!=KingLocationKind.PORTAL)return;for(int x=-16;x<=16;x++)for(int y=-8;y<=8;y++)for(int z=-16;z<=16;z++){Block b=l.getWorld().getBlockAt(l.getBlockX()+x,l.getBlockY()+y,l.getBlockZ()+z);if(b.getType()==Material.DRAGON_EGG){locateBlock(b.getLocation(),KingLocationKind.BLOCK,"bewegtes Ei");return;}}}

    private static final class Challenge {
        final DuelRequest request; final boolean mandatory; final long created; final long autoAcceptAt; boolean charged; boolean started;
        Challenge(DuelRequest request,boolean mandatory,long created,long autoAcceptAt){this.request=request;this.mandatory=mandatory;this.created=created;this.autoAcceptAt=autoAcceptAt;}
    }
}
