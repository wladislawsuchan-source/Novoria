package de.walahi.novosmp.heads;

import de.walahi.novosmp.items.CustomItemManager;
import de.walahi.novosmp.professions.ProfessionConfig;
import de.walahi.novosmp.professions.ProfessionManager;
import de.walahi.novosmp.professions.ProfessionToolService;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.StatType;
import de.walahi.smpcore.api.event.ActionContext;
import de.walahi.smpcore.api.event.ActionSource;
import de.walahi.smpcore.economy.EconomyOperationResult;
import de.walahi.smpcore.services.EconomyService;
import de.walahi.smpcore.stats.StatsAccess;
import de.walahi.smpcore.gui.ConfirmGui;
import de.walahi.smpcore.gui.Gui;
import de.walahi.smpcore.gui.GuiButton;
import de.walahi.smpcore.gui.MenuFormat;
import de.walahi.smpcore.gui.MiniMessageItems;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.BlockState;
import org.bukkit.block.Skull;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Creaking;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import org.bukkit.scheduler.BukkitTask;

/** Global signed mob-head drops and the personal collection GUI. */
public final class HeadCollectionManager implements Listener {
    private static final String HUNTER_BOW_ID = "jaeger_jagdbogen";
    private static final long HIT_CONTEXT_MILLIS = 15_000L;
    private static final long RUNTIME_CLEANUP_TICKS = 20L * 30L;
    private static final List<Integer> GRID = List.of(
            10,11,12,13,14,15,16,
            19,20,21,22,23,24,25,
            28,29,30,31,32,33,34,
            37,38,39,40,41,42,43);

    private final SMPCorePlugin plugin;
    private final HeadRepository repository;
    private final ProfessionManager professions;
    private final ProfessionConfig professionConfig;
    private final CustomItemManager customItems;
    private final ProfessionToolService serialItems;
    private final StatsAccess stats;
    private final EconomyService economy;
    private final HeadCatalog catalog = new HeadCatalog();
    private final MoreMobHeadsProvider headProvider;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final GsonComponentSerializer gson = GsonComponentSerializer.gson();
    private final MiniMessageItems menuItems = new MiniMessageItems();
    private final NamespacedKey headIdKey;
    private final NamespacedKey killerUuidKey;
    private final NamespacedKey killerNameKey;
    private final NamespacedKey signatureLineKey;
    private final Map<UUID, Set<String>> collections = new ConcurrentHashMap<>();
    private final Map<UUID, HitContext> hitContexts = new ConcurrentHashMap<>();
    private final Map<UUID, ItemStack> projectileWeapons = new ConcurrentHashMap<>();
    /** Prevents a heart-linked Creaking from rolling a second head if Paper also emits a death event. */
    private final Set<UUID> creakingHeartHandled = ConcurrentHashMap.newKeySet();
    private BukkitTask runtimeCleanupTask;
    /** Cancels MoreMobHeads' own drop pipeline; the plugin is texture catalogue only on Novoria. */
    private Listener providerDropBlocker;

    public HeadCollectionManager(SMPCorePlugin plugin, HeadRepository repository,
                                 ProfessionManager professions, CustomItemManager customItems,
                                 StatsAccess stats, EconomyService economy) {
        this.plugin = plugin;
        this.repository = repository;
        this.professions = professions;
        this.professionConfig = professions.config();
        this.customItems = customItems;
        this.serialItems = professions.tools();
        this.stats = stats;
        this.economy = economy;
        this.headProvider = new MoreMobHeadsProvider(plugin);
        this.headIdKey = new NamespacedKey(plugin, "mob_head_id");
        this.killerUuidKey = new NamespacedKey(plugin, "mob_head_killer_uuid");
        this.killerNameKey = new NamespacedKey(plugin, "mob_head_killer_name");
        this.signatureLineKey = new NamespacedKey(plugin, "mob_head_signature_line");
    }

    public void start() {
        headProvider.load();
        registerProviderDropSuppression();
        if (headProvider.ready()) {
            Set<String> validIds = new HashSet<>();
            for (HeadDefinition definition : availableDefinitions()) validIds.add(definition.id());
            repository.collectedCounts(validIds).forEach((uuid, count) ->
                    stats.setStat(uuid, StatType.HEADS_COLLECTED, count));
            Bukkit.getOnlinePlayers().forEach(this::evaluateCollectionRewards);
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        runtimeCleanupTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::cleanupRuntimeState, RUNTIME_CLEANUP_TICKS, RUNTIME_CLEANUP_TICKS);
    }

    public void stop() {
        if (runtimeCleanupTask != null) {
            runtimeCleanupTask.cancel();
            runtimeCleanupTask = null;
        }
        if (providerDropBlocker != null) {
            HandlerList.unregisterAll(providerDropBlocker);
            providerDropBlocker = null;
        }
        collections.clear();
        hitContexts.clear();
        projectileWeapons.clear();
        creakingHeartHandled.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player) || !(event.getProjectile() instanceof Projectile projectile)) return;
        ItemStack bow = event.getBow();
        if (bow != null && !bow.getType().isAir()) projectileWeapons.put(projectile.getUniqueId(), bow.clone());
    }

    /** A projectile embedded in a block can never damage an entity afterwards. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (event.getHitBlock() != null) projectileWeapons.remove(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        // Collection data is persisted in the repository and can be loaded again on demand.
        collections.remove(playerId);
        hitContexts.entrySet().removeIf(entry -> entry.getValue().killerId().equals(playerId));
    }

    private void cleanupRuntimeState() {
        long oldestUsefulHit = System.currentTimeMillis() - HIT_CONTEXT_MILLIS;
        // Contexts older than HIT_CONTEXT_MILLIS are already ignored by onDeath, so removing
        // them cannot change which weapon receives credit.
        hitContexts.entrySet().removeIf(entry -> entry.getValue().at() < oldestUsefulHit);
        // Remove weapon snapshots after the corresponding projectile no longer exists. Loaded
        // projectiles remain tracked for their complete lifetime.
        projectileWeapons.keySet().removeIf(projectileId -> Bukkit.getEntity(projectileId) == null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)) return;
        Player player = causingPlayer(event.getDamager());
        if (player == null) return;
        ItemStack weapon;
        if (event.getDamager() instanceof Projectile projectile) {
            weapon = projectileWeapons.remove(projectile.getUniqueId());
            if (weapon == null) weapon = player.getInventory().getItemInMainHand();
        } else {
            weapon = player.getInventory().getItemInMainHand();
        }
        hitContexts.put(victim.getUniqueId(), new HitContext(player.getUniqueId(),
                weapon == null ? null : weapon.clone(), System.currentTimeMillis()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        // MoreMobHeads is only our texture catalogue. If its own chance was reloaded at runtime,
        // strip any unsigned provider drop before NovoSMP applies the Novoria chance/signature.
        removeUnsignedProviderHeads(event.getDrops());
        LivingEntity victim = event.getEntity();
        if (victim.hasMetadata("novosmp-combat-dummy")
                || victim.hasMetadata("novosmp-finalized-combat-death")) {
            hitContexts.remove(victim.getUniqueId());
            return;
        }

        // Destroying the linked Creaking Heart is the canonical way to defeat a natural Creaking.
        // Some server builds may additionally emit an EntityDeathEvent while removing it; never
        // allow that to produce a second independent head roll.
        if ("CREAKING".equals(victim.getType().name()) && creakingHeartHandled.remove(victim.getUniqueId())) {
            hitContexts.remove(victim.getUniqueId());
            return;
        }

        Player killer = victim.getKiller();
        HitContext context = hitContexts.remove(victim.getUniqueId());
        if (killer == null) return;

        // Player heads are owned entirely by Novoria. MoreMobHeads' native drop pipeline is
        // cancelled globally and is used only as a mob-head texture catalogue. PvP heads have
        // their own high, weapon-independent chance and always drop naturally at the death spot.
        if (victim instanceof Player playerVictim) {
            ItemStack playerHead = rollPlayerHead(playerVictim, killer);
            if (playerHead != null) event.getDrops().add(playerHead);
            return;
        }

        ItemStack weapon = context != null && context.killerId().equals(killer.getUniqueId())
                && System.currentTimeMillis() - context.at() <= HIT_CONTEXT_MILLIS
                ? context.weapon() : killer.getInventory().getItemInMainHand();

        ItemStack head = rollSignedHead(victim, killer, weapon, false);
        if (head != null) event.getDrops().add(head);
    }

    /**
     * Creakings do not normally die to a sword. Breaking the Creaking Heart linked through
     * {@link Creaking#getHome()} performs the Novoria head roll for that exact Creaking.
     * The normal sword restriction is intentionally bypassed for this one acquisition method.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreakingHeartBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() != Material.CREAKING_HEART) return;

        Location heartLocation = event.getBlock().getLocation();
        Creaking creaking = linkedCreaking(heartLocation);
        if (creaking == null) return;

        UUID creakingId = creaking.getUniqueId();
        if (!creakingHeartHandled.add(creakingId)) return;
        // The Creaking is normally removed immediately after the heart breaks. Keep the marker only
        // briefly so UUIDs cannot accumulate if a particular server build does not emit a death event.
        Bukkit.getScheduler().runTaskLater(plugin, () -> creakingHeartHandled.remove(creakingId), 100L);

        Player killer = event.getPlayer();
        ItemStack weapon = killer.getInventory().getItemInMainHand();
        ItemStack head = rollSignedHead(creaking, killer, weapon, true);
        if (head != null) {
            Location dropAt = creaking.getLocation().clone().add(0D, 0.5D, 0D);
            dropAt.getWorld().dropItemNaturally(dropAt, head);
        }
    }

    private Creaking linkedCreaking(Location heartLocation) {
        if (heartLocation.getWorld() == null) return null;
        for (Creaking creaking : heartLocation.getWorld().getEntitiesByClass(Creaking.class)) {
            Location home = creaking.getHome();
            if (sameBlock(home, heartLocation)) return creaking;
        }
        return null;
    }

    private boolean sameBlock(Location first, Location second) {
        if (first == null || second == null || first.getWorld() == null || second.getWorld() == null) return false;
        return first.getWorld().getUID().equals(second.getWorld().getUID())
                && first.getBlockX() == second.getBlockX()
                && first.getBlockY() == second.getBlockY()
                && first.getBlockZ() == second.getBlockZ();
    }

    /**
     * Cancels every native MoreMobHeads head drop without taking a compile-time dependency on it.
     * The provider remains enabled so its maintained mob-head catalogue can still be read by reflection.
     */
    @SuppressWarnings("unchecked")
    private void registerProviderDropSuppression() {
        if (providerDropBlocker != null) return;
        try {
            org.bukkit.plugin.Plugin provider = Bukkit.getPluginManager().getPlugin("MoreMobHeads");
            if (provider == null || !provider.isEnabled()) return;
            Class<?> raw = Class.forName("com.github.joelgodofwar.mmh.events.MobHeadDropEvent", true,
                    provider.getClass().getClassLoader());
            if (!Event.class.isAssignableFrom(raw)) {
                plugin.getLogger().warning("[Kopfsammlung] MoreMobHeads MobHeadDropEvent ist kein Bukkit-Event; native Drops konnten nicht blockiert werden.");
                return;
            }
            Class<? extends Event> eventClass = (Class<? extends Event>) raw.asSubclass(Event.class);
            providerDropBlocker = new Listener() { };
            Bukkit.getPluginManager().registerEvent(eventClass, providerDropBlocker, EventPriority.HIGHEST,
                    (listener, event) -> {
                        try {
                            event.getClass().getMethod("setCancelled", boolean.class).invoke(event, true);
                        } catch (ReflectiveOperationException exception) {
                            plugin.getLogger().log(java.util.logging.Level.WARNING,
                                    "[Kopfsammlung] MoreMobHeads-Drop konnte nicht gecancelt werden.", exception);
                        }
                    }, plugin, false);
        } catch (ClassNotFoundException exception) {
            plugin.getLogger().warning("[Kopfsammlung] MoreMobHeads MobHeadDropEvent nicht gefunden; native Drops konnten nicht explizit blockiert werden.");
        }
    }

    /** PvP player-head roll: independent of weapon, Looting and Hunter prestige. */
    private ItemStack rollPlayerHead(Player victim, Player killer) {
        double chance = Math.max(0D, Math.min(1D, professionConfig.playerKillHeadChance()));
        if (chance <= 0D || ThreadLocalRandom.current().nextDouble() >= chance) return null;

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (!(head.getItemMeta() instanceof SkullMeta meta)) return head;
        try {
            meta.setOwnerProfile(victim.getPlayerProfile());
        } catch (IllegalArgumentException ignored) {
            meta.setOwningPlayer(victim);
        }

        Component killerDisplay = plugin.getRankManager() == null
                ? Component.text(killer.getName(), NamedTextColor.GRAY)
                : plugin.getRankManager().coloredName(killer);
        meta.displayName(Component.text(victim.getName(), NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                signatureLine(killerDisplay),
                Component.empty(),
                Component.text("Novoria-Kopfsammlung", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)
        ));
        head.setItemMeta(meta);
        return head;
    }

    /** Runs the common Novoria chance/variant/signature/collection pipeline and returns the head. */
    private ItemStack rollSignedHead(LivingEntity victim, Player killer, ItemStack weapon, boolean bypassSwordRequirement) {
        boolean hunter = professions.isHunterActive(killer.getUniqueId());
        int prestige = hunter ? professions.hunter(killer.getUniqueId()).prestige() : 0;
        if (!hunter && !bypassSwordRequirement && !isSword(weapon)) return null;

        double chance = hunter ? professionConfig.hunterHeadChance(prestige)
                : professionConfig.globalSwordHeadChance();
        int looting = lootingLevel(weapon);
        if (HUNTER_BOW_ID.equals(customItems.identify(weapon))) looting = Math.max(looting, 3);
        chance *= 1D + professionConfig.headLootingMultiplierPerLevel() * looting;
        chance *= professionConfig.headBossMultiplier(victim.getType().name());
        chance = Math.max(0D, Math.min(1D, chance));
        if (ThreadLocalRandom.current().nextDouble() >= chance) return null;

        HeadDefinition actual = catalog.resolve(victim);
        if (actual == null) return null;
        if (actual.baby() && !headProvider.has(actual)) {
            HeadDefinition original = actual;
            actual = catalog.family(original.familyId()).stream()
                    .filter(def -> !def.baby() && def.variant().equals(original.variant()))
                    .findFirst().orElse(original);
        }
        HeadDefinition dropped = hunter ? rerollVariant(killer, actual, prestige) : actual;
        ItemStack head = createSignedHead(dropped, killer);
        unlockCollectionHead(killer, dropped);
        return head;
    }

    private void unlockCollectionHead(Player killer, HeadDefinition dropped) {
        Set<String> collection = collection(killer.getUniqueId());
        boolean first = repository.unlock(killer.getUniqueId(), dropped.id());
        if (!first) return;

        collection.add(dropped.id());
        int collected = collectedAvailableCount(collection);
        int total = availableDefinitions().size();
        stats.setStat(killer.getUniqueId(), StatType.HEADS_COLLECTED, collected);
        String raw = professionConfig.string("heads.new-head-actionbar",
                "<gold>✦ Neuer Kopf in deiner Kopfsammlung:</gold> <yellow>%head%</yellow> <dark_gray>•</dark_gray> <gray>%current%/%total%</gray>")
                .replace("%head%", dropped.displayName())
                .replace("%current%", Integer.toString(collected))
                .replace("%total%", Integer.toString(total));
        killer.sendActionBar(miniMessage.deserialize(raw));
        if (total > 0 && collected >= total) {
            String complete = professionConfig.string("heads.collection-complete-message",
                    "<light_purple><bold>✦ Kopfsammlung vollständig!</bold></light_purple> <gray>Deine 100%-Belohnung ist jetzt freigeschaltet.</gray>");
            killer.sendRichMessage(complete);
        }
        evaluateCollectionRewards(killer);
    }

    private HeadDefinition rerollVariant(Player player, HeadDefinition actual, int prestige) {
        if (prestige < 3 || ThreadLocalRandom.current().nextDouble() >= professionConfig.variantRerollChance(prestige)) return actual;
        if (professionConfig.stringList("heads.variant-reroll-disabled-families").stream()
                .anyMatch(id -> id.equalsIgnoreCase(actual.familyId()))) return actual;

        // Collection families are only a GUI grouping. Variant rerolls must never jump
        // to another mob type (e.g. Zombie -> Drowned or Skeleton -> Stray).
        // Adult and baby appearances of the SAME mob type are deliberately related.
        List<HeadDefinition> candidates = catalog.family(actual.familyId()).stream()
                .filter(def -> def.entityType().equals(actual.entityType()))
                .filter(def -> !def.id().equals(actual.id()))
                .filter(headProvider::has)
                .toList();
        if (candidates.isEmpty()) return actual;
        Set<String> collected = collection(player.getUniqueId());
        double missingWeight = professionConfig.missingVariantWeight(prestige);
        double total = 0D;
        for (HeadDefinition candidate : candidates) total += collected.contains(candidate.id()) ? 1D : missingWeight;
        double roll = ThreadLocalRandom.current().nextDouble(total);
        for (HeadDefinition candidate : candidates) {
            roll -= collected.contains(candidate.id()) ? 1D : missingWeight;
            if (roll <= 0D) return candidate;
        }
        return candidates.get(candidates.size() - 1);
    }

    public ItemStack createSignedHead(HeadDefinition definition, Player killer) {
        Component killerDisplay = plugin.getRankManager() == null
                ? Component.text(killer.getName(), NamedTextColor.GRAY)
                : plugin.getRankManager().coloredName(killer);
        Component signatureLine = signatureLine(killerDisplay);
        return createSignedHead(definition, killer.getUniqueId(), killer.getName(), signatureLine);
    }

    /** Mob-Typen, fuer die der aktuell geladene Provider wirklich einen Novoria-Kopf kennt. */
    public List<String> availableAdminMobTypes() {
        return availableDefinitions().stream()
                .map(HeadDefinition::entityType)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * Erzeugt fuer eine administrative Wiederherstellung exakt dasselbe signierte Item
     * wie der normale Drop-Pfad, ohne Chance zu rollen oder die Sammlung freizuschalten.
     */
    public ItemStack createAdminSignedHead(String rawEntityType, Player signaturePlayer) {
        if (!headProvider.ready() || rawEntityType == null || signaturePlayer == null) return null;
        String entityType = rawEntityType.trim().toUpperCase(java.util.Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        List<HeadDefinition> candidates = availableDefinitions().stream()
                .filter(definition -> definition.entityType().equals(entityType))
                .toList();
        if (candidates.isEmpty()) return null;

        HeadDefinition selected = candidates.stream()
                .filter(definition -> !definition.baby() && definition.variant().equalsIgnoreCase("BASE"))
                .findFirst()
                .orElseGet(() -> candidates.stream()
                        .filter(definition -> !definition.baby() && definition.variant().equalsIgnoreCase("NORMAL"))
                        .findFirst()
                        .orElseGet(() -> candidates.stream()
                                .filter(definition -> !definition.baby())
                                .findFirst()
                                .orElse(candidates.get(0))));
        return createSignedHead(selected, signaturePlayer);
    }

    public boolean headProviderReady() {
        return headProvider.ready();
    }

    private ItemStack createSignedHead(HeadDefinition definition, UUID killerId, String killerName, Component signatureLine) {
        ItemStack provided = headProvider.head(definition);
        Material material = fallbackMaterial(definition.entityType());
        ItemStack item = provided != null ? provided : new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(Component.text(definition.displayName(), NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                signatureLine,
                Component.empty(),
                Component.text("Novoria-Kopfsammlung", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(headIdKey, PersistentDataType.STRING, definition.id());
        meta.getPersistentDataContainer().set(killerUuidKey, PersistentDataType.STRING, killerId.toString());
        meta.getPersistentDataContainer().set(killerNameKey, PersistentDataType.STRING, killerName);
        meta.getPersistentDataContainer().set(signatureLineKey, PersistentDataType.STRING, gson.serialize(signatureLine));
        item.setItemMeta(meta);

        return item;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignedHeadPlace(BlockPlaceEvent event) {
        ItemStack placed = event.getItemInHand();
        if (!isSignedMobHead(placed)) return;
        BlockState state = event.getBlockPlaced().getState();
        if (!(state instanceof Skull skull)) return;
        ItemMeta meta = placed.getItemMeta();
        if (meta == null) return;
        copyString(meta, skull, headIdKey);
        copyString(meta, skull, killerUuidKey);
        copyString(meta, skull, killerNameKey);
        copyString(meta, skull, signatureLineKey);
        if (!skull.getPersistentDataContainer().has(signatureLineKey, PersistentDataType.STRING)) {
            List<Component> lore = meta.lore();
            if (lore != null && !lore.isEmpty()) {
                skull.getPersistentDataContainer().set(signatureLineKey, PersistentDataType.STRING, gson.serialize(lore.get(0)));
            }
        }
        skull.update(true, false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignedHeadBreak(BlockBreakEvent event) {
        BlockState state = event.getBlock().getState();
        if (!(state instanceof Skull skull)) return;
        String id = skull.getPersistentDataContainer().get(headIdKey, PersistentDataType.STRING);
        if (id == null || id.isBlank()) return;

        event.setDropItems(false);
        if (event.getPlayer().getGameMode() == GameMode.CREATIVE) return;
        HeadDefinition definition = catalog.byId(id);
        if (definition == null) return;

        String uuidRaw = skull.getPersistentDataContainer().get(killerUuidKey, PersistentDataType.STRING);
        String killerName = skull.getPersistentDataContainer().get(killerNameKey, PersistentDataType.STRING);
        String signatureJson = skull.getPersistentDataContainer().get(signatureLineKey, PersistentDataType.STRING);
        UUID killerId;
        try { killerId = uuidRaw == null ? new UUID(0L, 0L) : UUID.fromString(uuidRaw); }
        catch (IllegalArgumentException ignored) { killerId = new UUID(0L, 0L); }
        if (killerName == null || killerName.isBlank()) killerName = "Unbekannt";
        Component signature;
        try { signature = signatureJson == null ? signatureLine(Component.text(killerName, NamedTextColor.GRAY)) : gson.deserialize(signatureJson); }
        catch (RuntimeException ignored) { signature = signatureLine(Component.text(killerName, NamedTextColor.GRAY)); }

        ItemStack restored = createSignedHead(definition, killerId, killerName, signature);
        event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation().add(0.5D, 0.35D, 0.5D), restored);
    }

    private void copyString(ItemMeta from, Skull to, NamespacedKey key) {
        String value = from.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (value != null) to.getPersistentDataContainer().set(key, PersistentDataType.STRING, value);
    }

    /** Removes collection ownership from a head after it has been consumed as Merge-Helm appearance. */
    public void clearSignature(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().remove(headIdKey);
        meta.getPersistentDataContainer().remove(killerUuidKey);
        meta.getPersistentDataContainer().remove(killerNameKey);
        meta.getPersistentDataContainer().remove(signatureLineKey);
        item.setItemMeta(meta);
    }

    private Component signatureLine(Component killerDisplay) {
        return Component.text("Gekillt von: ", NamedTextColor.GRAY)
                .append(killerDisplay)
                .decoration(TextDecoration.ITALIC, false);
    }

    public boolean isSignedMobHead(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(headIdKey, PersistentDataType.STRING);
    }

    public String headId(ItemStack item) {
        return !isSignedMobHead(item) ? null
                : item.getItemMeta().getPersistentDataContainer().get(headIdKey, PersistentDataType.STRING);
    }

    public void open(Player player) {
        if (!headProvider.ready()) {
            player.sendMessage(Component.text("Die Kopfsammlung benötigt MoreMobHeads als Kopf-Provider.", NamedTextColor.RED));
            return;
        }
        syncHeadStat(player.getUniqueId());
        evaluateCollectionRewards(player);
        openMain(player, null);
    }

    /** Opens the normal collection UI as a free appearance wardrobe for the Master Merge Helm. */
    public void openWardrobe(Player player, Consumer<HeadDefinition> selection) {
        if (player == null || selection == null) return;
        if (!headProvider.ready()) {
            player.sendMessage(Component.text("Die Kopfsammlung ist gerade nicht verfügbar.", NamedTextColor.RED));
            return;
        }
        syncHeadStat(player.getUniqueId());
        openMain(player, selection);
    }

    private void openMain(Player player, Consumer<HeadDefinition> selection) {
        Gui gui = withSounds(new Gui(4, menuItems.component("<dark_gray>Kopfsammlung</dark_gray>")));
        gui.filler(Material.BLACK_STAINED_GLASS_PANE);
        List<Integer> slots = List.of(10,11,12,14,15,16);
        Set<String> collected = collection(player.getUniqueId());
        int index = 0;
        for (HeadCategory category : HeadCategory.values()) {
            List<HeadDefinition> definitions = availableDefinitions(category);
            long current = definitions.stream().filter(def -> collected.contains(def.id())).count();
            HeadCategory selected = category;
            ItemStack categoryIcon = categoryHeadIcon(category);
            ItemMeta categoryMeta = categoryIcon.getItemMeta();
            if (categoryMeta != null) {
                categoryMeta.displayName(Component.text(category.display(), NamedTextColor.GOLD)
                        .decoration(TextDecoration.ITALIC, false));
                categoryMeta.lore(List.of(
                        Component.text("Gesammelt: ", NamedTextColor.GRAY)
                                .append(Component.text(current + "/" + definitions.size(), NamedTextColor.YELLOW))
                                .decoration(TextDecoration.ITALIC, false),
                        Component.empty(),
                        Component.text("Klicken: Kategorie öffnen", NamedTextColor.YELLOW)
                                .decoration(TextDecoration.ITALIC, false)));
                categoryIcon.setItemMeta(categoryMeta);
            }
            gui.button(slots.get(index++), GuiButton.of(categoryIcon, event -> openCategory(player, selected, 0, selection)));
        }
        int total = availableDefinitions().size();
        int current = collectedAvailableCount(collected);
        if (selection == null) {
            gui.button(30, GuiButton.of(menuItems.item(Material.NETHER_STAR, "<gold>Sammlungsbelohnungen</gold>",
                    List.of("<gray>Merge-Helme, Master-Helm und Ersatzkäufe.</gray>", "", "<yellow>Klicken: Belohnungen öffnen</yellow>")),
                    event -> openRewards(player)));
        } else {
            gui.item(30, menuItems.item(Material.NETHERITE_HELMET, "<light_purple>Master-Merge-Helm</light_purple>",
                    List.of("<gray>Wähle einen bereits gesammelten Kopf.</gray>", "<gray>Die Optik wird kostenlos übernommen.</gray>")));
        }
        gui.item(31, menuItems.item(Material.BOOK, "<light_purple>Deine Sammlung</light_purple>",
                List.of("<gray>Gesamt: <yellow>" + current + "/" + total + "</yellow></gray>", "",
                        "<dark_gray>Nur selbst gedroppte, signierte Köpfe zählen.</dark_gray>",
                        "<dark_gray>Handelbare Köpfe schalten beim Käufer nichts frei.</dark_gray>")));
        gui.open(player);
    }

    private void openCategory(Player player, HeadCategory category, int page, Consumer<HeadDefinition> selection) {
        List<String> families = availableFamilyIds(category);
        int maxPage = Math.max(0, (families.size() - 1) / GRID.size());
        int safePage = Math.max(0, Math.min(maxPage, page));
        Gui gui = withSounds(new Gui(6, menuItems.component("<dark_gray>Köpfe • " + category.display() + "</dark_gray>")));
        gui.filler(Material.BLACK_STAINED_GLASS_PANE);
        Set<String> collected = collection(player.getUniqueId());
        int start = safePage * GRID.size();
        for (int i = 0; i < GRID.size() && start + i < families.size(); i++) {
            String familyId = families.get(start + i);
            List<HeadDefinition> defs = availableFamily(familyId);
            long current = defs.stream().filter(def -> collected.contains(def.id())).count();
            HeadDefinition first = familyRepresentative(familyId, defs);
            ItemStack icon = previewHead(first, current > 0);
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.displayName(Component.text(familyDisplay(first), current >= defs.size() ? NamedTextColor.GREEN : NamedTextColor.GOLD)
                        .decoration(TextDecoration.ITALIC, false));
                meta.lore(List.of(Component.text("Gesammelt: " + current + "/" + defs.size(), NamedTextColor.GRAY)
                                .decoration(TextDecoration.ITALIC, false),
                        Component.empty(), Component.text("Klicken: Varianten öffnen", NamedTextColor.YELLOW)
                                .decoration(TextDecoration.ITALIC, false)));
                icon.setItemMeta(meta);
            }
            gui.button(GRID.get(i), GuiButton.of(icon, event -> openFamily(player, category, familyId, 0, selection)));
        }
        if (safePage > 0) gui.button(45, GuiButton.of(menuItems.item(Material.ARROW, "<yellow>Vorherige Seite</yellow>", List.of()),
                event -> openCategory(player, category, safePage - 1, selection)));
        gui.button(49, GuiButton.of(menuItems.item(Material.BARRIER, "<red>Zurück</red>", List.of()), event -> openMain(player, selection)));
        if (safePage < maxPage) gui.button(53, GuiButton.of(menuItems.item(Material.ARROW, "<yellow>Nächste Seite</yellow>", List.of()),
                event -> openCategory(player, category, safePage + 1, selection)));
        gui.open(player);
    }

    private void openFamily(Player player, HeadCategory category, String familyId, int page, Consumer<HeadDefinition> selection) {
        List<HeadDefinition> defs = availableFamily(familyId);
        int maxPage = Math.max(0, (defs.size() - 1) / GRID.size());
        int safePage = Math.max(0, Math.min(maxPage, page));
        Gui gui = withSounds(new Gui(6, menuItems.component("<dark_gray>Köpfe • " + familyDisplay(defs.get(0)) + "</dark_gray>")));
        gui.filler(Material.BLACK_STAINED_GLASS_PANE);
        Set<String> collected = collection(player.getUniqueId());
        int start = safePage * GRID.size();
        for (int i = 0; i < GRID.size() && start + i < defs.size(); i++) {
            HeadDefinition def = defs.get(start + i);
            boolean unlocked = collected.contains(def.id());
            ItemStack icon = previewHead(def, unlocked);
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.displayName(Component.text(def.displayName(), unlocked ? NamedTextColor.GREEN : NamedTextColor.RED)
                        .decoration(TextDecoration.ITALIC, false));
                List<Component> lore = new ArrayList<>();
                lore.add(Component.text(unlocked ? "✓ Vorhanden" : "✗ Nicht vorhanden",
                        unlocked ? NamedTextColor.GREEN : NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
                if (selection != null && unlocked) {
                    lore.add(Component.empty());
                    lore.add(Component.text("Klicken: als Helm-Optik wählen", NamedTextColor.YELLOW)
                            .decoration(TextDecoration.ITALIC, false));
                }
                meta.lore(lore);
                icon.setItemMeta(meta);
            }
            if (selection != null && unlocked) {
                gui.button(GRID.get(i), GuiButton.of(icon, event -> selection.accept(def)));
            } else gui.item(GRID.get(i), icon);
        }
        if (safePage > 0) gui.button(45, GuiButton.of(menuItems.item(Material.ARROW, "<yellow>Vorherige Seite</yellow>", List.of()),
                event -> openFamily(player, category, familyId, safePage - 1, selection)));
        gui.button(49, GuiButton.of(menuItems.item(Material.ARROW, "<yellow>Zur Kategorie</yellow>", List.of()),
                event -> openCategory(player, category, 0, selection)));
        if (safePage < maxPage) gui.button(53, GuiButton.of(menuItems.item(Material.ARROW, "<yellow>Nächste Seite</yellow>", List.of()),
                event -> openFamily(player, category, familyId, safePage + 1, selection)));
        gui.open(player);
    }

    private void openRewards(Player player) {
        evaluateCollectionRewards(player);
        List<CollectionReward> rewards = collectionRewards();
        Gui gui = withSounds(new Gui(3, menuItems.component("<dark_gray>Kopfsammlung • Belohnungen</dark_gray>")));
        gui.filler(Material.BLACK_STAINED_GLASS_PANE);
        List<Integer> slots = List.of(10, 12, 14, 16);
        Set<String> unlocked = repository.unlockedRewards(player.getUniqueId());
        Set<String> claimed = repository.claimedRewards(player.getUniqueId());
        int collected = collectedAvailableCount(collection(player.getUniqueId()));
        int total = availableDefinitions().size();
        for (int i = 0; i < rewards.size() && i < slots.size(); i++) {
            CollectionReward reward = rewards.get(i);
            boolean eligible = unlocked.contains(reward.id()) || reward.eligible(collected, total);
            boolean has = claimed.contains(reward.id());
            List<String> lore = new ArrayList<>();
            lore.add("<dark_gray>Sammlungs-Meilenstein</dark_gray>");
            lore.add("");
            lore.add("<gray>Voraussetzung: <yellow>" + reward.requirementText(total) + "</yellow></gray>");
            lore.add("<gray>Fortschritt: <white>" + collected + "/" + total + "</white></gray>");
            lore.add("");
            if (!eligible) {
                lore.add("<red>✗ Noch nicht freigeschaltet</red>");
            } else if (!has) {
                lore.add("<green>✓ Freigeschaltet</green>");
                lore.add("<yellow>Klicken: Belohnung abholen</yellow>");
            } else {
                lore.add("<green>✓ Abgeholt</green>");
                lore.add("<gray>Ersatzpreis: <gold>" + MenuFormat.integer(reward.replacementPrice()) + " Coins</gold></gray>");
                lore.add("<yellow>Klicken: Ersatz kaufen</yellow>");
            }
            Material material = reward.master() ? Material.NETHERITE_HELMET : Material.DIAMOND_HELMET;
            String title = reward.master()
                    ? "<light_purple><bold>Master-Merge-Helm</bold></light_purple> <dark_gray>•</dark_gray> <gray>100%</gray>"
                    : "<aqua>Merge-Helm</aqua> <dark_gray>•</dark_gray> <gray>" + reward.requirementText(total) + "</gray>";
            ItemStack icon = menuItems.item(material, title, lore);
            if (eligible) {
                gui.button(slots.get(i), GuiButton.of(icon, event -> {
                    if (repository.claimedRewards(player.getUniqueId()).contains(reward.id())) {
                        openCollectionReplacementConfirmation(player, reward);
                    } else {
                        claimCollectionReward(player, reward, true);
                        openRewards(player);
                    }
                }));
            } else gui.item(slots.get(i), icon);
        }
        gui.button(22, GuiButton.of(menuItems.item(Material.ARROW, "<yellow>Zurück</yellow>", List.of()), event -> openMain(player, null)));
        gui.open(player);
    }

    public ItemStack appearanceHead(HeadDefinition definition) {
        if (definition == null || !available(definition)) return null;
        ItemStack item = headProvider.head(definition);
        return item != null ? item.clone() : new ItemStack(fallbackMaterial(definition.entityType()));
    }

    public boolean hasCollected(UUID playerId, HeadDefinition definition) {
        return definition != null && available(definition) && collection(playerId).contains(definition.id());
    }

    public int collectedAvailableCount(UUID playerId) {
        return collectedAvailableCount(collection(playerId));
    }

    public int totalAvailableCount() {
        return availableDefinitions().size();
    }

    public boolean collectionComplete(UUID playerId) {
        int total = totalAvailableCount();
        return total > 0 && collectedAvailableCount(playerId) >= total;
    }

    private ItemStack previewHead(HeadDefinition def, boolean unlocked) {
        // MoreMobHeads is the canonical mob-only texture catalogue. Never guess a texture
        // from a decorative-head database: if no exact/provider-safe match exists, the
        // definition is filtered out by available() instead of showing a wrong head.
        ItemStack provided = headProvider.head(def);
        return provided != null ? provided : new ItemStack(fallbackMaterial(def.entityType()));
    }

    private Set<String> collection(UUID playerId) {
        Set<String> loaded = collections.computeIfAbsent(playerId, repository::collected);
        if (headProvider.ready()) stats.setStat(playerId, StatType.HEADS_COLLECTED, collectedAvailableCount(loaded));
        return loaded;
    }

    private int collectedAvailableCount(Set<String> collected) {
        if (collected == null || collected.isEmpty()) return 0;
        int count = 0;
        for (HeadDefinition definition : availableDefinitions()) if (collected.contains(definition.id())) count++;
        return count;
    }

    private void syncHeadStat(UUID playerId) {
        stats.setStat(playerId, StatType.HEADS_COLLECTED, collectedAvailableCount(collection(playerId)));
    }

    private void evaluateCollectionRewards(Player player) {
        if (player == null || !headProvider.ready()) return;
        int collected = collectedAvailableCount(player.getUniqueId());
        int total = totalAvailableCount();
        List<CollectionReward> rewards = collectionRewards();
        repair1534FalseUnlocks(player.getUniqueId(), rewards, collected, total);
        Set<String> unlocked = repository.unlockedRewards(player.getUniqueId());
        Set<String> claimed = repository.claimedRewards(player.getUniqueId());
        for (CollectionReward reward : rewards) {
            if (!unlocked.contains(reward.id()) && reward.eligible(collected, total)) {
                if (repository.unlockReward(player.getUniqueId(), reward.id())) {
                    unlocked.add(reward.id());
                    if (reward.master()) announceMasterUnlock(player);
                }
            }
            if (!reward.autoDelivery() || claimed.contains(reward.id()) || !unlocked.contains(reward.id())) continue;
            if (claimCollectionReward(player, reward, false)) claimed.add(reward.id());
        }
    }

    private void repair1534FalseUnlocks(UUID playerId, List<CollectionReward> rewards, int collected, int total) {
        Set<String> affectedIds = Set.of("merge-25", "merge-60", "master");
        Set<String> remove = new LinkedHashSet<>();
        for (CollectionReward reward : rewards) {
            if (affectedIds.contains(reward.id()) && !reward.eligible(collected, total)) remove.add(reward.id());
        }
        repository.repairUnclaimedRewardsOnce(playerId, "v1.53.4-default-trigger-fallback", remove);
    }

    private boolean claimCollectionReward(Player player, CollectionReward reward, boolean feedback) {
        if (reward == null) return false;
        int collected = collectedAvailableCount(player.getUniqueId());
        int total = totalAvailableCount();
        Set<String> unlocked = repository.unlockedRewards(player.getUniqueId());
        if (!unlocked.contains(reward.id())) {
            if (!reward.eligible(collected, total)) return false;
            if (repository.unlockReward(player.getUniqueId(), reward.id()) && reward.master()) announceMasterUnlock(player);
        }
        if (!repository.claimReward(player.getUniqueId(), reward.id())) return false;

        String serial = ProfessionToolService.newSerial();
        ItemStack item = serialItems.createHeadReward(reward.itemId(), serial, reward.serialSlot(),
                reward.master() ? "Kopfsammlung • 100%" : "Kopfsammlung • " + reward.requirementText(total));
        if (item == null) {
            repository.unclaimReward(player.getUniqueId(), reward.id());
            return false;
        }
        try {
            if (!serialItems.registerSerial(player.getUniqueId(), ProfessionToolService.HEAD_REWARD_GROUP, reward.serialSlot(), serial)) {
                repository.unclaimReward(player.getUniqueId(), reward.id());
                serialItems.markInactive(serial);
                return false;
            }
        } catch (RuntimeException exception) {
            repository.unclaimReward(player.getUniqueId(), reward.id());
            serialItems.markInactive(serial);
            throw exception;
        }
        serialItems.markActive(serial);
        give(player, item);
        if (feedback) player.sendRichMessage("<green>Sammlungsbelohnung abgeholt: <light_purple>" + (reward.master() ? "Master-Merge-Helm" : "Merge-Helm") + "</light_purple>.</green>");
        return true;
    }

    private void announceMasterUnlock(Player player) {
        Bukkit.broadcast(miniMessage.deserialize("<light_purple><bold>✦ " + player.getName()
                + " hat die gesamte Novoria-Kopfsammlung vervollständigt! ✦</bold></light_purple>"));
    }

    private void openCollectionReplacementConfirmation(Player player, CollectionReward reward) {
        if (reward == null || !repository.claimedRewards(player.getUniqueId()).contains(reward.id())) {
            openRewards(player);
            return;
        }
        long price = reward.replacementPrice();
        String rewardName = reward.master() ? "Master-Merge-Helm" : "Merge-Helm";
        List<String> lore = new ArrayList<>();
        lore.add("<red><bold>⚠ Achtung!</bold></red>");
        lore.add("<gray>Die bisher registrierte Kopie genau dieser Sammlungsbelohnung</gray>");
        lore.add("<gray>wird dauerhaft deaktiviert und verliert ihre Merge-Funktion.</gray>");
        lore.add("");
        lore.add("<gray>Belohnung: <light_purple>" + rewardName + "</light_purple></gray>");
        lore.add("<gray>Preis: <gold>" + MenuFormat.integer(price) + " Coins</gold></gray>");
        lore.add("<green>Klicken zum endgültigen Kauf</green>");
        ItemStack confirm = menuItems.item(Material.LIME_DYE, "<green>Ersatz kaufen</green>", lore);
        ItemStack cancel = menuItems.item(Material.RED_DYE, "<red>Abbrechen</red>",
                List.of("<gray>Dein aktuelles Belohnungsitem bleibt aktiv.</gray>"));
        ConfirmGui.open(player, menuItems.component("<dark_red>Sammlungsbelohnung ersetzen?</dark_red>"),
                confirm, cancel,
                () -> { replaceCollectionReward(player, reward); openRewards(player); },
                () -> openRewards(player));
    }

    private boolean replaceCollectionReward(Player player, CollectionReward reward) {
        int slot = firstEmpty(player);
        if (slot < 0) {
            player.sendRichMessage("<red>Du benötigst einen freien Inventarplatz.</red>");
            return false;
        }
        long price = reward.replacementPrice();
        EconomyOperationResult result = economy.withdraw(player.getUniqueId(), price,
                "Kopfsammlung-Ersatz-" + reward.id(), ActionContext.player(ActionSource.GUI, player.getUniqueId()));
        if (result == EconomyOperationResult.INSUFFICIENT_FUNDS) {
            player.sendRichMessage("<red>Für den Ersatz benötigst du <gold>" + MenuFormat.integer(price) + " Coins</gold>.</red>");
            return false;
        }
        if (result != EconomyOperationResult.SUCCESS) return false;

        String serial = ProfessionToolService.newSerial();
        ItemStack item = serialItems.createHeadReward(reward.itemId(), serial, reward.serialSlot(),
                reward.master() ? "Kopfsammlung • 100%" : "Kopfsammlung • Ersatz");
        if (item == null) {
            economy.deposit(player.getUniqueId(), price, "Kopfsammlung-Ersatz-Rückerstattung", ActionContext.system(player.getUniqueId()));
            return false;
        }
        try {
            serialItems.replaceSerial(player.getUniqueId(), ProfessionToolService.HEAD_REWARD_GROUP, reward.serialSlot(), serial);
        } catch (RuntimeException exception) {
            economy.deposit(player.getUniqueId(), price, "Kopfsammlung-Ersatz-Rückerstattung", ActionContext.system(player.getUniqueId()));
            serialItems.markInactive(serial);
            throw exception;
        }
        player.getInventory().setItem(slot, item);
        for (ItemStack inventoryItem : player.getInventory().getContents()) serialItems.sanitizeForPlayer(player, inventoryItem);
        player.sendRichMessage("<green>Ersatz gekauft. Die vorherige registrierte Kopie dieser Belohnung wurde deaktiviert.</green>");
        return true;
    }

    private void give(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        leftovers.values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
    }

    private Gui withSounds(Gui gui) {
        return gui.sounds(
                "",
                professionConfig.string("gui-sounds.heads.click", "minecraft:ui.button.click"),
                (float) plugin.configs().professions().getDouble("gui-sounds.heads.volume", 0.55D),
                (float) plugin.configs().professions().getDouble("gui-sounds.heads.open-pitch", 1.1D),
                (float) plugin.configs().professions().getDouble("gui-sounds.heads.click-pitch", 1.25D));
    }

    private int firstEmpty(Player player) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || item.getType().isAir()) return slot;
        }
        return -1;
    }

    private List<CollectionReward> collectionRewards() {
        var config = plugin.configs().professions();
        if (!config.getBoolean("heads.collection-rewards.enabled", true)) return List.of();

        java.util.LinkedHashSet<String> rewardIds = new java.util.LinkedHashSet<>();
        var section = config.getConfigurationSection("heads.collection-rewards.rewards");
        if (section != null) rewardIds.addAll(section.getKeys(false));
        var defaults = config.getDefaults();
        if (defaults != null) {
            var defaultSection = defaults.getConfigurationSection("heads.collection-rewards.rewards");
            if (defaultSection != null) rewardIds.addAll(defaultSection.getKeys(false));
        }
        if (rewardIds.isEmpty()) return defaultCollectionRewards();

        Map<String, CollectionReward> bundled = new LinkedHashMap<>();
        for (CollectionReward reward : defaultCollectionRewards()) bundled.put(reward.id(), reward);

        List<CollectionReward> rewards = new ArrayList<>();
        for (String id : rewardIds) {
            String path = "heads.collection-rewards.rewards." + id;
            CollectionReward fallback = bundled.getOrDefault(id,
                    new CollectionReward(id, rewards.size() + 1, "COUNT", 10D, false,
                            "CLAIM", Math.max(1, rewards.size() + 1), "merge_helm", 100_000L));
            int order = directInt(config, path + ".order", fallback.order());
            String type = directString(config, path + ".trigger.type", fallback.type());
            double value = directDouble(config, path + ".trigger.value", fallback.value());
            boolean requireAll = directBoolean(config, path + ".require-all", fallback.requireAll());
            String delivery = directString(config, path + ".delivery", fallback.delivery());
            int serialSlot = directInt(config, path + ".serial-slot", fallback.serialSlot());
            String itemId = directString(config, path + ".item-id", fallback.itemId());
            long replacementPrice = Math.max(0L, directLong(config, path + ".replacement-price", fallback.replacementPrice()));
            rewards.add(new CollectionReward(id, order, type, value, requireAll, delivery, serialSlot, itemId, replacementPrice));
        }
        rewards.sort(java.util.Comparator.comparingInt(CollectionReward::order));
        return List.copyOf(rewards);
    }

    private int directInt(org.bukkit.configuration.file.FileConfiguration config, String path, int fallback) {
        return config.contains(path, true) ? config.getInt(path) : fallback;
    }

    private long directLong(org.bukkit.configuration.file.FileConfiguration config, String path, long fallback) {
        return config.contains(path, true) ? config.getLong(path) : fallback;
    }

    private double directDouble(org.bukkit.configuration.file.FileConfiguration config, String path, double fallback) {
        return config.contains(path, true) ? config.getDouble(path) : fallback;
    }

    private boolean directBoolean(org.bukkit.configuration.file.FileConfiguration config, String path, boolean fallback) {
        return config.contains(path, true) ? config.getBoolean(path) : fallback;
    }

    private String directString(org.bukkit.configuration.file.FileConfiguration config, String path, String fallback) {
        return config.contains(path, true) ? config.getString(path, fallback) : fallback;
    }

    private List<CollectionReward> defaultCollectionRewards() {
        return List.of(
                new CollectionReward("merge-10", 1, "COUNT", 10D, false, "CLAIM", 1, "merge_helm", 100_000L),
                new CollectionReward("merge-25", 2, "PERCENT", 25D, false, "CLAIM", 2, "merge_helm", 100_000L),
                new CollectionReward("merge-60", 3, "PERCENT", 60D, false, "CLAIM", 3, "merge_helm", 100_000L),
                new CollectionReward("master", 4, "PERCENT", 100D, true, "CLAIM", 100, "master_merge_helm", 100_000L));
    }

    private List<HeadDefinition> availableDefinitions() {
        return catalog.all().stream().filter(this::available).toList();
    }

    private List<HeadDefinition> availableDefinitions(HeadCategory category) {
        return catalog.category(category).stream().filter(this::available).toList();
    }

    private List<HeadDefinition> availableFamily(String familyId) {
        return catalog.family(familyId).stream().filter(this::available).toList();
    }

    private List<String> availableFamilyIds(HeadCategory category) {
        LinkedHashMap<String, Boolean> ids = new LinkedHashMap<>();
        for (HeadDefinition def : availableDefinitions(category)) ids.put(def.familyId(), Boolean.TRUE);
        return List.copyOf(ids.keySet());
    }

    private boolean available(HeadDefinition def) {
        // Baby entries only become collectible when the configured runtime dataset actually provides a texture.
        return headProvider.has(def);
    }

    private String familyDisplay(HeadDefinition def) {
        return switch (def.familyId()) {
            case "cow" -> "Kuh";
            case "horse" -> "Pferd";
            case "camel" -> "Kamel";
            case "zombie" -> "Zombie";
            case "skeleton" -> "Skelett";
            case "ghast" -> "Ghast";
            case "spider" -> "Spinne";
            case "piglin" -> "Piglin";
            case "llama" -> "Lama";
            case "nautilus" -> "Nautilus";
            default -> {
                String display = def.displayName();
                display = display.replaceFirst("(?i)^Baby[ •-]+", "");
                int marker = display.lastIndexOf(" • ");
                if (marker >= 0) yield display.substring(marker + 3);
                String[] words = display.split(" ");
                yield words.length == 0 ? display : words[words.length - 1];
            }
        };
    }

    /** Uses a recognizable real mob head as the icon for every top-level collection category. */
    private ItemStack categoryHeadIcon(HeadCategory category) {
        String entityType = switch (category) {
            case ANIMALS -> "COW";
            case MONSTERS -> "ZOMBIE";
            case NETHER -> "GHAST";
            case END -> "ENDERMAN";
            case WATER -> "GUARDIAN";
            case SPECIAL -> "WITHER";
        };
        for (HeadDefinition def : availableDefinitions(category)) {
            if (def.entityType().equals(entityType) && !def.baby()) {
                ItemStack head = headProvider.head(def);
                if (head != null) return head;
            }
        }
        return new ItemStack(category.icon());
    }

    private HeadDefinition familyRepresentative(String familyId, List<HeadDefinition> defs) {
        String preferredType = switch (familyId) {
            case "cow" -> "COW";
            case "horse" -> "HORSE";
            case "camel" -> "CAMEL";
            case "zombie" -> "ZOMBIE";
            case "skeleton" -> "SKELETON";
            case "ghast" -> "GHAST";
            case "spider" -> "SPIDER";
            case "piglin" -> "PIGLIN";
            case "llama" -> "LLAMA";
            case "nautilus" -> "NAUTILUS";
            default -> null;
        };
        if (preferredType != null) {
            for (HeadDefinition def : defs) {
                if (def.entityType().equals(preferredType) && !def.baby()) return def;
            }
        }
        for (HeadDefinition def : defs) if (!def.baby()) return def;
        return defs.get(0);
    }


    private void removeUnsignedProviderHeads(List<ItemStack> drops) {
        NamespacedKey providerTexture = NamespacedKey.fromString("moremobheads:head_texture");
        if (providerTexture == null) return;
        drops.removeIf(item -> item != null && item.hasItemMeta()
                && !isSignedMobHead(item)
                && item.getItemMeta().getPersistentDataContainer().has(providerTexture, PersistentDataType.STRING));
    }

    private Player causingPlayer(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        return null;
    }

    private boolean isSword(ItemStack weapon) {
        return weapon != null && !weapon.getType().isAir() && weapon.getType().name().endsWith("_SWORD");
    }

    private int lootingLevel(ItemStack weapon) {
        if (weapon == null || !weapon.hasItemMeta()) return 0;
        NamespacedKey key = NamespacedKey.fromString("minecraft:looting");
        Enchantment looting = key == null ? null : Registry.ENCHANTMENT.get(key);
        return looting == null ? 0 : weapon.getEnchantmentLevel(looting);
    }

    private Material fallbackMaterial(String entityType) {
        return switch (entityType) {
            case "ZOMBIE" -> Material.ZOMBIE_HEAD;
            case "SKELETON" -> Material.SKELETON_SKULL;
            case "WITHER_SKELETON" -> Material.WITHER_SKELETON_SKULL;
            case "CREEPER" -> Material.CREEPER_HEAD;
            case "ENDER_DRAGON" -> Material.DRAGON_HEAD;
            case "PIGLIN" -> Material.PIGLIN_HEAD;
            default -> Material.PLAYER_HEAD;
        };
    }

    private record CollectionReward(String id, int order, String type, double value, boolean requireAll,
                                    String delivery, int serialSlot, String itemId, long replacementPrice) {
        boolean autoDelivery() { return "AUTO".equalsIgnoreCase(delivery); }
        boolean master() { return "master_merge_helm".equalsIgnoreCase(itemId); }
        boolean eligible(int collected, int total) {
            if (total <= 0) return false;
            // The Master Merge Helm is always the true 100%-reward, even if its trigger is accidentally
            // configured lower. require-all can additionally force the same rule for any other reward.
            if ((requireAll || master()) && collected < total) return false;
            if ("PERCENT".equalsIgnoreCase(type)) return collected * 100D / total >= value;
            return collected >= Math.max(0D, value);
        }
        String requirementText(int total) {
            if (requireAll || master()) return "Alle Köpfe (100%)";
            if ("PERCENT".equalsIgnoreCase(type)) return Math.round(value) + "% der Sammlung";
            return Math.round(value) + " Köpfe";
        }
    }

    private record HitContext(UUID killerId, ItemStack weapon, long at) { }
}
