package de.walahi.novosmp.quests;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.novosmp.crates.CrateDefinition;
import de.walahi.novosmp.crates.CrateManager;
import de.walahi.novosmp.lumi.LumiRepository;
import de.walahi.smpcore.storage.StorageManager;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.logging.Level;

/** Main-thread quest state. Only immutable checkpoint snapshots cross to the async DB writer. */
public final class QuestService {
    private final NovoSMPPlugin plugin;
    private final QuestRepository repository;
    private final CrateManager crates;
    private final MiniMessage mini = MiniMessage.miniMessage();
    private QuestConfig config;
    private final Map<UUID, QuestState.DailySet> personal = new HashMap<>();
    private final QuestState.Global[] slots = new QuestState.Global[45];
    private final Map<QuestType, List<QuestState.Global>> index = new EnumMap<>(QuestType.class);
    private final Map<Material, List<QuestState.Global>> materialIndex = new EnumMap<>(Material.class);
    private final Map<EntityType, List<QuestState.Global>> entityIndex = new EnumMap<>(EntityType.class);
    private final AtomicBoolean flushing = new AtomicBoolean();
    private volatile CountDownLatch inFlight = new CountDownLatch(0);
    private BukkitTask flushTask;
    private int activeRows;
    private long nextCooldownAt = Long.MAX_VALUE;
    private long lastReachSequence;
    private LocalDate lastCleanupDay;
    private boolean stopped;
    private IntConsumer rowsChanged = ignored -> { };

    public QuestService(NovoSMPPlugin plugin, StorageManager storage, LumiRepository lumis, CrateManager crates) {
        this.plugin = plugin;
        this.repository = new QuestRepository(storage, lumis);
        this.crates = crates;
        reload();
    }

    public void start() {
        if (!config.yaml.getBoolean("enabled", true)) return;
        try {
            for (QuestState.Global slot : repository.loadGlobal()) if (slot.slot >= 0 && slot.slot < slots.length) slots[slot.slot] = slot;
            for (QuestState.Global slot : slots) if (slot != null) for (QuestState.Participant p : slot.participants.values())
                lastReachSequence = Math.max(lastReachSequence, p.reachedAt);
            setRows(Bukkit.getOnlinePlayers().size());
            repository.cleanup(day().minusDays(Math.max(1, config.yaml.getInt("storage.daily-retention-days", 14))),
                    System.currentTimeMillis() - Duration.ofDays(30).toMillis());
            lastCleanupDay = day();
        } catch (SQLException error) {
            plugin.getLogger().log(Level.SEVERE, "Quest-Start fehlgeschlagen; Quests werden deaktiviert", error);
            stopped = true;
            return;
        }
        long interval = Math.max(20L, config.yaml.getLong("storage.flush-seconds", 60) * 20L);
        flushTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkpointAsync, interval, interval);
    }

    public void reload() { config = new QuestConfig(plugin.configs().quests(), plugin.getLogger()); rebuildIndex(); }
    public boolean enabled() { return !stopped && config.yaml.getBoolean("enabled", true); }
    public void rowsChanged(IntConsumer listener) { rowsChanged = listener == null ? ignored -> { } : listener; }
    QuestConfig config() { return config; }
    int activeRows() { return activeRows; }
    QuestState.Global slot(int number) { return number >= 0 && number < activeRows * 9 ? slots[number] : null; }

    private LocalDate day() {
        return QuestClock.day(Instant.now(), config.zone, config.resetHour, config.resetMinute);
    }

    long millisToReset() {
        return QuestClock.untilReset(Instant.now(), config.zone, config.resetHour, config.resetMinute);
    }

    QuestState.DailySet daily(Player player) throws SQLException {
        UUID id = player.getUniqueId();
        LocalDate today = day();
        QuestState.DailySet current = personal.get(id);
        if (current != null && today.equals(current.day)) return current;
        List<QuestState.Daily> loaded = repository.loadDaily(id);
        for (QuestState.Daily old : loaded) if (old.completed && !old.keyDelivered) deliverKey(player, old, false, 0);
        loaded = loaded.stream().filter(q -> q.day.equals(today)).toList();
        if (loaded.size() != config.dailyCount) {
            if (config.daily.isEmpty()) return new QuestState.DailySet(today);
            loaded = new ArrayList<>(config.dailyCount);
            for (int slot = 0; slot < config.dailyCount; slot++) {
                QuestDefinition chosen = random(config.daily);
                loaded.add(new QuestState.Daily(UUID.randomUUID(), id, today, slot, chosen.id(), 0, false, false));
            }
            repository.replaceDaily(id, loaded);
        }
        QuestState.DailySet set = new QuestState.DailySet(today);
        set.quests.addAll(loaded);
        personal.put(id, set);
        return set;
    }

    void retryPendingKeys(Player player) throws SQLException {
        for (QuestState.Daily quest : repository.loadDaily(player.getUniqueId()))
            if (quest.completed && !quest.keyDelivered) deliverKey(player, quest, false, 0);
        daily(player);
    }

    void join(Player player) {
        if (!enabled()) return;
        int online = Bukkit.getOnlinePlayers().size() + (Bukkit.getOnlinePlayers().contains(player) ? 0 : 1);
        try { setRows(online); retryPendingKeys(player); }
        catch (SQLException error) { plugin.getLogger().log(Level.WARNING, "Quests beim Join konnten nicht geladen werden", error); }
    }

    void quit(Player player) {
        if (!enabled()) return;
        checkpointNow(player.getUniqueId());
        personal.remove(player.getUniqueId());
        int online = Bukkit.getOnlinePlayers().size() - (Bukkit.getOnlinePlayers().contains(player) ? 1 : 0);
        try { setRows(Math.max(0, online)); }
        catch (SQLException error) { plugin.getLogger().log(Level.WARNING, "Quest-Reihe beim Quit konnte nicht eingefroren werden", error); }
    }

    void setRows(int online) throws SQLException {
        int wanted = config.rows(online);
        if (wanted == activeRows) return;
        long now = System.currentTimeMillis();
        int old = activeRows;
        if (wanted < old) for (int i = wanted * 9; i < old * 9; i++) {
            QuestState.Global slot = slots[i];
            if (slot == null) continue;
            if (slot.cooling()) slot.cooldownLeft = Math.max(0, slot.cooldownLeft - (now - slot.activeSince));
            slot.activeSince = 0;
            repository.saveSlot(slot, null);
        }
        activeRows = wanted;
        if (wanted > old) for (int i = old * 9; i < wanted * 9; i++) {
            QuestState.Global slot = slots[i];
            if (slot == null) {
                if (config.global.isEmpty()) continue;
                QuestDefinition chosen = random(config.global);
                slot = new QuestState.Global(i, UUID.randomUUID(), chosen.id(), 0, now, null);
                slots[i] = slot;
            } else {
                slot.activeSince = now;
                if (slot.cooling() && slot.cooldownLeft <= 0) newInstance(slot, now);
            }
            repository.saveSlot(slot, null);
        }
        rebuildIndex();
        updateNextCooldown();
        rowsChanged.accept(wanted);
    }

    private void newInstance(QuestState.Global slot, long now) throws SQLException {
        if (config.global.isEmpty()) return;
        UUID old = slot.id;
        QuestDefinition chosen = random(config.global);
        slot.id = UUID.randomUUID(); slot.definitionId = chosen.id(); slot.cooldownLeft = 0;
        slot.winner = null; slot.activeSince = now; slot.participants.clear();
        repository.saveSlot(slot, old);
    }

    private static QuestDefinition random(List<QuestDefinition> pool) {
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    private void updateNextCooldown() {
        nextCooldownAt = Long.MAX_VALUE;
        for (int i = 0; i < activeRows * 9; i++) {
            QuestState.Global slot = slots[i];
            if (slot != null && slot.cooling()) nextCooldownAt = Math.min(nextCooldownAt, slot.activeSince + slot.cooldownLeft);
        }
    }

    void refreshCooldowns() {
        if (System.currentTimeMillis() < nextCooldownAt) return;
        long now = System.currentTimeMillis();
        for (int i = 0; i < activeRows * 9; i++) {
            QuestState.Global slot = slots[i];
            if (slot == null || !slot.cooling() || slot.activeSince + slot.cooldownLeft > now) continue;
            try { newInstance(slot, now); }
            catch (SQLException error) { plugin.getLogger().log(Level.WARNING, "Quest-Cooldown konnte nicht beendet werden", error); }
        }
        rebuildIndex(); updateNextCooldown();
    }

    private void rebuildIndex() {
        index.clear();
        materialIndex.clear(); entityIndex.clear();
        for (int i = 0; i < activeRows * 9; i++) {
            QuestState.Global slot = slots[i];
            if (slot == null || slot.cooling()) continue;
            QuestDefinition quest = config.byId.get(slot.definitionId);
            if (quest == null) continue;
            index.computeIfAbsent(quest.type(), ignored -> new ArrayList<>()).add(slot);
            if (quest.type() == QuestType.MATERIAL_BREAK && quest.material() != null)
                materialIndex.computeIfAbsent(quest.material(), ignored -> new ArrayList<>()).add(slot);
            if ((quest.type() == QuestType.ENTITY_KILL || quest.type() == QuestType.BOSS_KILL)
                    && quest.entity() != null)
                entityIndex.computeIfAbsent(quest.entity(), ignored -> new ArrayList<>()).add(slot);
        }
    }

    boolean relevant(Player player, QuestType type) {
        if (!enabled()) return false;
        if (!index.getOrDefault(type, List.of()).isEmpty()) return true;
        QuestState.DailySet set = personal.get(player.getUniqueId());
        if (set == null) return false;
        for (QuestState.Daily quest : set.quests) {
            QuestDefinition definition = config.byId.get(quest.definitionId);
            if (!quest.completed && definition != null && definition.type() == type) return true;
        }
        return false;
    }

    boolean relevantForBlock(Player player, Material material) {
        if (!enabled()) return false;
        if (!index.getOrDefault(QuestType.BLOCK_BREAK, List.of()).isEmpty()) return true;
        if (!materialIndex.getOrDefault(material, List.of()).isEmpty()) return true;
        if (config.ores.contains(material) && !index.getOrDefault(QuestType.ORE_BREAK, List.of()).isEmpty()) return true;
        QuestState.DailySet set = personal.get(player.getUniqueId());
        if (set == null) return false;
        for (QuestState.Daily quest : set.quests) {
            QuestDefinition definition = config.byId.get(quest.definitionId);
            if (!quest.completed && definition != null && definition.matches(QuestType.MATERIAL_BREAK,
                    material, null, config.ores.contains(material))) return true;
        }
        return false;
    }

    void progress(Player player, QuestType eventType, Material material, EntityType entity, double amount) {
        if (!enabled() || amount <= 0 || !Double.isFinite(amount)) return;
        refreshCooldowns();
        QuestState.DailySet set;
        try { set = daily(player); }
        catch (SQLException error) { plugin.getLogger().log(Level.WARNING, "Quest-Fortschritt konnte nicht geladen werden", error); return; }
        boolean ore = material != null && config.ores.contains(material);
        for (QuestState.Daily quest : set.quests) {
            QuestDefinition definition = config.byId.get(quest.definitionId);
            if (quest.completed || definition == null || !definition.matches(eventType, material, entity, ore)) continue;
            quest.progress = Math.min(definition.target(), quest.progress + amount);
            quest.revision++; quest.dirty = true;
            if (quest.progress >= definition.target()) completeDaily(player, quest, definition);
        }
        for (QuestState.Global slot : candidates(eventType, material, entity)) {
            if (slot.cooling()) continue;
            QuestDefinition definition = config.byId.get(slot.definitionId);
            if (definition == null || !definition.matches(eventType, material, entity, ore)) continue;
            QuestState.Participant participant = slot.participants.computeIfAbsent(player.getUniqueId(),
                    id -> new QuestState.Participant(id, player.getName(), 0, 0));
            participant.name = player.getName();
            participant.progress = Math.min(definition.target(), participant.progress + amount);
            participant.reachedAt = lastReachSequence = Math.max(System.currentTimeMillis() * 1000L,
                    lastReachSequence + 1L);
            participant.revision++; participant.dirty = true;
            if (participant.progress >= definition.target()) completeGlobal(player, slot, definition);
        }
    }

    private List<QuestState.Global> candidates(QuestType eventType, Material material, EntityType entity) {
        List<QuestState.Global> found = new ArrayList<>();
        if (eventType == QuestType.ENTITY_KILL) {
            found.addAll(index.getOrDefault(QuestType.MOB_KILL, List.of()));
            found.addAll(entityIndex.getOrDefault(entity, List.of()));
        } else if (eventType == QuestType.MATERIAL_BREAK) {
            found.addAll(materialIndex.getOrDefault(material, List.of()));
            found.addAll(index.getOrDefault(QuestType.ORE_BREAK, List.of()));
            found.addAll(index.getOrDefault(QuestType.BLOCK_BREAK, List.of()));
        } else found.addAll(index.getOrDefault(eventType, List.of()));
        return found;
    }

    private void completeDaily(Player player, QuestState.Daily quest, QuestDefinition definition) {
        try {
            if (!repository.completeDaily(quest, definition.lumis(), config.keyAmount)) return;
            quest.completed = true; quest.dirty = false;
            deliverKey(player, quest, true, definition.lumis());
        } catch (SQLException error) { plugin.getLogger().log(Level.SEVERE, "Daily-Quest-Belohnung fehlgeschlagen", error); }
    }

    private void completeGlobal(Player player, QuestState.Global slot, QuestDefinition definition) {
        long cooldown = config.cooldownSeconds * 1000L;
        try {
            if (!repository.completeGlobal(slot, player.getUniqueId(), definition.lumis(), cooldown)) return;
            slot.definitionId = null; slot.cooldownLeft = cooldown;
            slot.activeSince = System.currentTimeMillis(); slot.winner = player.getUniqueId();
            slot.participants.clear();
            message(player, "messages.global-win", "%lumis%", Integer.toString(definition.lumis()));
            rebuildIndex(); updateNextCooldown();
        } catch (SQLException error) { plugin.getLogger().log(Level.SEVERE, "Globale Quest-Belohnung fehlgeschlagen", error); }
    }

    private void deliverKey(Player player, QuestState.Daily quest, boolean announce, int lumis) throws SQLException {
        CrateDefinition crate = crates.find(config.yaml.getString("daily.key-crate-id", "daily")).orElse(null);
        if (crate == null) { plugin.getLogger().severe("Quest-Daily-Key-Crate fehlt; Key bleibt zur Nachlieferung offen"); return; }
        // Durable claim is recorded before physical delivery: DB retry/reload cannot duplicate a key.
        int keyAmount = repository.keyAmount(quest.id);
        if (keyAmount <= 0) throw new SQLException("Quest-Key-Reward fehlt in der Ledger: " + quest.id);
        repository.markKeyDelivered(quest.id);
        quest.keyDelivered = true;
        int dropped = crates.keys().addAndCountDropped(player, crate, keyAmount);
        if (announce) {
            String template = config.yaml.getString(dropped > 0 ? "messages.daily-complete-dropped" : "messages.daily-complete", "")
                    .replace("%lumis%", Integer.toString(lumis)).replace("%keys%", Integer.toString(keyAmount));
            if (!template.isBlank()) player.sendMessage(mini.deserialize(template));
        }
    }

    private void message(Player player, String key, String token, String value) {
        String template = config.yaml.getString(key, "").replace(token, value);
        if (!template.isBlank()) player.sendMessage(mini.deserialize(template));
    }

    void checkpointAsync() {
        if (!enabled() || !flushing.compareAndSet(false, true)) return;
        Snapshot snapshot = snapshot(null);
        boolean cleanup = !day().equals(lastCleanupDay);
        if (snapshot.daily.isEmpty() && snapshot.global.isEmpty() && snapshot.cooldowns.isEmpty() && !cleanup) {
            flushing.set(false); return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        inFlight = latch;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                repository.saveProgress(snapshot.daily, snapshot.global, snapshot.cooldowns);
                if (cleanup) repository.cleanup(day().minusDays(Math.max(1,
                        config.yaml.getInt("storage.daily-retention-days", 14))),
                        System.currentTimeMillis() - Duration.ofDays(30).toMillis());
                if (!stopped) Bukkit.getScheduler().runTask(plugin, () -> {
                    markSaved(snapshot);
                    if (cleanup) lastCleanupDay = day();
                });
            } catch (SQLException error) { plugin.getLogger().log(Level.WARNING, "Quest-Checkpoint fehlgeschlagen", error); }
            finally { latch.countDown(); flushing.set(false); }
        });
    }

    private record Snapshot(List<QuestRepository.DailyProgress> daily, List<QuestRepository.GlobalProgress> global,
                            List<QuestRepository.CooldownProgress> cooldowns,
                            Map<UUID, Long> dailyVersions, Map<String, Long> globalVersions) { }

    private Snapshot snapshot(UUID onlyPlayer) {
        List<QuestRepository.DailyProgress> daily = new ArrayList<>();
        List<QuestRepository.GlobalProgress> global = new ArrayList<>();
        List<QuestRepository.CooldownProgress> cooldowns = new ArrayList<>();
        Map<UUID, Long> dailyVersions = new HashMap<>();
        Map<String, Long> globalVersions = new HashMap<>();
        for (QuestState.DailySet set : personal.values()) for (QuestState.Daily quest : set.quests) {
            if (onlyPlayer != null && !onlyPlayer.equals(quest.player)) continue;
            if (quest.completed || quest.revision == quest.savedRevision) continue;
            daily.add(new QuestRepository.DailyProgress(quest.id, quest.progress));
            dailyVersions.put(quest.id, quest.revision);
        }
        for (QuestState.Global slot : slots) {
            if (slot == null || slot.id == null) continue;
            if (slot.cooling()) {
                if (onlyPlayer == null && slot.activeSince > 0)
                    cooldowns.add(new QuestRepository.CooldownProgress(slot.slot, slot.id,
                            Math.max(0, slot.cooldownLeft - (System.currentTimeMillis() - slot.activeSince))));
                continue;
            }
            for (QuestState.Participant participant : slot.participants.values()) {
                if (onlyPlayer != null && !onlyPlayer.equals(participant.player)) continue;
                if (participant.revision == participant.savedRevision) continue;
                global.add(new QuestRepository.GlobalProgress(slot.id, participant.player, participant.name,
                        participant.progress, participant.reachedAt));
                globalVersions.put(slot.id + ":" + participant.player, participant.revision);
            }
        }
        return new Snapshot(daily, global, cooldowns, dailyVersions, globalVersions);
    }

    private void markSaved(Snapshot snapshot) {
        for (QuestState.DailySet set : personal.values()) for (QuestState.Daily quest : set.quests) {
            Long version = snapshot.dailyVersions.get(quest.id);
            if (version != null) quest.savedRevision = Math.max(quest.savedRevision, version);
        }
        for (QuestState.Global slot : slots) if (slot != null && slot.id != null) {
            for (QuestState.Participant p : slot.participants.values()) {
                Long version = snapshot.globalVersions.get(slot.id + ":" + p.player);
                if (version != null) p.savedRevision = Math.max(p.savedRevision, version);
            }
        }
    }

    private void checkpointNow(UUID onlyPlayer) {
        Snapshot snapshot = snapshot(onlyPlayer);
        try { repository.saveProgress(snapshot.daily, snapshot.global, snapshot.cooldowns); markSaved(snapshot); }
        catch (SQLException error) { plugin.getLogger().log(Level.SEVERE, "Quest-Fortschritt konnte nicht gesichert werden", error); }
    }

    public void shutdown() {
        if (flushTask != null) flushTask.cancel();
        try { if (!inFlight.await(10, TimeUnit.SECONDS))
            plugin.getLogger().warning("Quest-Checkpoint läuft beim Shutdown noch; synchroner Flush folgt."); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        checkpointNow(null);
        long now = System.currentTimeMillis();
        for (int i = 0; i < activeRows * 9; i++) {
            QuestState.Global slot = slots[i];
            if (slot == null) continue;
            if (slot.cooling()) slot.cooldownLeft = Math.max(0, slot.cooldownLeft - (now - slot.activeSince));
            slot.activeSince = 0;
            try { repository.saveSlot(slot, null); }
            catch (SQLException error) { plugin.getLogger().log(Level.SEVERE, "Quest-Slot konnte nicht gespeichert werden", error); }
        }
        activeRows = 0; stopped = true;
    }
}
