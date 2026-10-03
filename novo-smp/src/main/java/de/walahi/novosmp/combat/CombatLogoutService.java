package de.walahi.novosmp.combat;

import de.walahi.novosmp.NovoSMPPlugin;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCDamageByEntityEvent;
import net.citizensnpcs.api.event.NPCDeathEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.projectiles.ProjectileSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/** Owns the Citizens dummy and the authoritative offline item escrow. */
final class CombatLogoutService implements Listener {
    static final String DUMMY_METADATA = "novosmp-combat-dummy";
    private static final long KILLER_CREDIT_MILLIS = 20_000L;

    private record LastAttacker(UUID id, String name, long at) { }
    private record ActiveDummy(NPC npc, UUID owner, LastAttacker lastAttacker) {
        ActiveDummy withAttacker(LastAttacker attacker) { return new ActiveDummy(npc, owner, attacker); }
    }

    private final NovoSMPPlugin plugin;
    private final ValidPlayerKillService kills;
    private final CombatRelationshipService relationships;
    private final CombatLogStore store;
    private final Map<UUID, CombatLogRecord> records;
    private final Map<Integer, ActiveDummy> dummies = new HashMap<>();
    private final Map<UUID, Integer> dummyByOwner = new HashMap<>();
    private final Set<UUID> pendingJoinDeaths = new HashSet<>();
    private final NPCRegistry registry;

    CombatLogoutService(NovoSMPPlugin plugin, ValidPlayerKillService kills,
                        CombatRelationshipService relationships) {
        this.plugin = plugin;
        this.kills = kills;
        this.relationships = relationships;
        this.store = new CombatLogStore(plugin);
        this.records = store.load();
        if (!CitizensAPI.hasImplementation()) {
            throw new IllegalStateException("Citizens ist nicht vollständig initialisiert.");
        }
        this.registry = CitizensAPI.createInMemoryNPCRegistry("novosmp-combat");
    }

    void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (CombatLogRecord record : new ArrayList<>(records.values())) {
            if (record.phase != CombatLogRecord.Phase.ACTIVE) continue;
            if (record.expiresAt <= now) {
                record.phase = CombatLogRecord.Phase.SURVIVED;
                changed = true;
            } else if (!spawnDummy(record)) {
                record.phase = CombatLogRecord.Phase.SURVIVED;
                changed = true;
                plugin.getLogger().severe("Combat-Dummy für " + record.playerName
                        + " konnte nach dem Neustart nicht erzeugt werden; der Zustand bleibt zur Rückgabe verwahrt.");
            }
        }
        if (changed) persist();
    }

    void createFor(Player player, long durationMillis) {
        UUID playerId = player.getUniqueId();
        if (records.containsKey(playerId)) return;
        player.closeInventory();
        long now = System.currentTimeMillis();
        CombatLogRecord record = CombatLogRecord.capture(player, now, now + Math.max(1_000L, durationMillis));
        records.put(playerId, record);
        try {
            // Write-ahead escrow: after this succeeds, the snapshot is authoritative and a join
            // always overwrites native player data rather than adding to it.
            persist();
        } catch (RuntimeException exception) {
            records.remove(playerId);
            plugin.getLogger().log(Level.SEVERE,
                    "Combat-Logout konnte nicht abgesichert werden; der Spieler wird stattdessen normal getötet.", exception);
            if (!player.isDead()) player.setHealth(0D);
            return;
        }

        CombatLogRecord.clearOwnedState(player);
        player.saveData();
        if (!spawnDummy(record)) {
            record.phase = CombatLogRecord.Phase.SURVIVED;
            persist();
            plugin.getLogger().severe("Citizens konnte den Combat-Dummy von " + player.getName()
                    + " nicht spawnen. Das verwahrte Inventar wird beim nächsten Join zurückgegeben.");
        }
    }

    void tick(long now) {
        boolean changed = false;
        for (CombatLogRecord record : new ArrayList<>(records.values())) {
            if (record.phase != CombatLogRecord.Phase.ACTIVE || record.expiresAt > now) continue;
            ActiveDummy active = active(record.playerId);
            if (active != null) captureAndDestroy(active, record);
            record.phase = CombatLogRecord.Phase.SURVIVED;
            changed = true;
        }
        if (changed) persist();
    }

    void handleJoin(Player player) {
        CombatLogRecord record = records.get(player.getUniqueId());
        if (record == null) return;

        if (record.phase == CombatLogRecord.Phase.ACTIVE) {
            ActiveDummy active = active(record.playerId);
            if (active != null) captureAndDestroy(active, record);
            record.phase = CombatLogRecord.Phase.SURVIVED;
            persist();
        }

        if (record.phase == CombatLogRecord.Phase.SURVIVED) {
            record.restore(player);
            player.saveData();
            records.remove(record.playerId);
            persist();
            return;
        }

        record.prepareKilledPlayer(player);
        player.setMetadata(CombatManager.FINALIZED_DEATH_METADATA, new FixedMetadataValue(plugin, true));
        player.saveData();
        pendingJoinDeaths.add(record.playerId);
        String message = plugin.configs().server().getString("combat.messages.dummy-killed",
                "<dark_gray>[<red>Combat</red>]</dark_gray> <red>Dein Combat-Dummy wurde getötet.</red>");
        player.sendRichMessage(message);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !pendingJoinDeaths.contains(record.playerId)) return;
            player.setInvulnerable(false);
            player.setHealth(0D);
        });
    }

    boolean consumePendingDeath(PlayerDeathEvent event) {
        UUID playerId = event.getEntity().getUniqueId();
        if (!pendingJoinDeaths.remove(playerId)) return false;
        records.remove(playerId);
        persist();
        Bukkit.getScheduler().runTask(plugin,
                () -> event.getEntity().removeMetadata(CombatManager.FINALIZED_DEATH_METADATA, plugin));
        return true;
    }

    void shutdown() {
        // Remove ownership maps first, so Citizens cleanup cannot be mistaken for a combat kill.
        for (ActiveDummy active : new ArrayList<>(dummies.values())) {
            CombatLogRecord record = records.get(active.owner());
            if (record != null && active.npc().isSpawned() && active.npc().getEntity() instanceof LivingEntity living) {
                record.refreshFromDummy(living);
            }
        }
        dummies.clear();
        dummyByOwner.clear();
        pendingJoinDeaths.clear();
        persist();
        registry.deregisterAll();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDummyDamage(NPCDamageByEntityEvent event) {
        ActiveDummy active = dummies.get(event.getNPC().getId());
        if (active == null) return;
        Player attacker = causingPlayer(event.getDamager());
        if (attacker == null && event.getEvent().getDamageSource().getCausingEntity() instanceof Player player) {
            attacker = player;
        }
        if (attacker == null || attacker.getUniqueId().equals(active.owner())) return;
        if (relationships.isFriendly(attacker.getUniqueId(), active.owner())) {
            event.setCancelled(true);
            attacker.sendRichMessage(plugin.configs().server().getString("combat.messages.dummy-friendly",
                    "<dark_gray>[<red>Combat</red>]</dark_gray> <red>Du kannst den Combat-Dummy eines Freundes nicht angreifen.</red>"));
            return;
        }
        dummies.put(event.getNPC().getId(), active.withAttacker(
                new LastAttacker(attacker.getUniqueId(), attacker.getName(), System.currentTimeMillis())));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDummyDeath(NPCDeathEvent event) {
        ActiveDummy active = dummies.remove(event.getNPC().getId());
        if (active == null) return;
        dummyByOwner.remove(active.owner());
        CombatLogRecord record = records.get(active.owner());
        if (record == null || record.phase != CombatLogRecord.Phase.ACTIVE) return;

        event.getDrops().clear();
        event.setDroppedExp(0);
        LivingEntity dead = event.getEvent().getEntity();
        // Citizens may already have cleared equipment when its death callback fires. Keep the
        // authoritative logout snapshot so armor/main/offhand cannot disappear or duplicate.
        record.refreshFromDummy(dead, false);
        record.health = 0D;
        record.phase = CombatLogRecord.Phase.KILLED;

        // Persist terminal ownership before emitting physical drops. A crash can therefore never
        // restore or drop this inventory a second time.
        persist();
        Location death = dead.getLocation();
        record.dropItems(death);
        int experience = record.droppedExperience();
        if (experience > 0 && death.getWorld() != null) {
            death.getWorld().spawn(death, ExperienceOrb.class).setExperience(experience);
        }

        Player directKiller = dead.getKiller();
        LastAttacker credited = directKiller == null ? active.lastAttacker()
                : new LastAttacker(directKiller.getUniqueId(), directKiller.getName(), System.currentTimeMillis());
        if (credited != null && System.currentTimeMillis() - credited.at() <= KILLER_CREDIT_MILLIS) {
            Player onlineKiller = Bukkit.getPlayer(credited.id());
            kills.processCombatDummyDeath(credited.id(), credited.name(), onlineKiller,
                    record.playerId, record.playerName, dead, death);
        } else {
            kills.processCombatDummyDeath(null, null, null,
                    record.playerId, record.playerName, dead, death);
        }
        record.phase = CombatLogRecord.Phase.FINALIZED;
        persist();
        Bukkit.getScheduler().runTask(plugin, () -> event.getNPC().destroy());
    }

    private boolean spawnDummy(CombatLogRecord record) {
        Location location = record.location();
        if (location == null) return false;
        NPC npc = registry.createNPC(EntityType.PLAYER, record.playerName);
        npc.setProtected(false);
        npc.setUseMinecraftAI(false);
        SkinTrait skin = npc.getOrAddTrait(SkinTrait.class);
        skin.setShouldUpdateSkins(false);
        skin.setSkinName(record.playerName);
        if (!npc.spawn(location)) {
            npc.destroy();
            return false;
        }
        if (!(npc.getEntity() instanceof LivingEntity living)) {
            npc.destroy();
            return false;
        }
        living.setMetadata(DUMMY_METADATA, new FixedMetadataValue(plugin, record.playerId.toString()));
        living.setInvulnerable(false);
        record.applyToDummy(living);
        ActiveDummy active = new ActiveDummy(npc, record.playerId, null);
        dummies.put(npc.getId(), active);
        dummyByOwner.put(record.playerId, npc.getId());
        return true;
    }

    private ActiveDummy active(UUID owner) {
        Integer npcId = dummyByOwner.get(owner);
        return npcId == null ? null : dummies.get(npcId);
    }

    private void captureAndDestroy(ActiveDummy active, CombatLogRecord record) {
        dummies.remove(active.npc().getId());
        dummyByOwner.remove(record.playerId);
        if (active.npc().isSpawned() && active.npc().getEntity() instanceof LivingEntity living) {
            record.refreshFromDummy(living);
        }
        active.npc().destroy();
    }

    private Player causingPlayer(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        if (damager instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player player) return player;
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player) return player;
        if (damager instanceof Tameable tameable && tameable.getOwner() instanceof Player player) return player;
        return null;
    }

    private void persist() {
        store.save(records.values());
    }
}
