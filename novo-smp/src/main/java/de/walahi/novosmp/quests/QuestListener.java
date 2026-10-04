package de.walahi.novosmp.quests;

import de.walahi.novosmp.NovoSMPPlugin;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Egg;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** One event bridge for daily and global quests. No per-quest listeners or movement tasks. */
public final class QuestListener implements Listener {
    private final QuestService quests;
    private final NamespacedKey nonNaturalMining;
    private final Map<BlockBreakEvent, Boolean> pendingBreaks = new IdentityHashMap<>();
    private final Map<UUID, LinkedHashMap<BlockKey, Long>> recentPlaced = new java.util.HashMap<>();
    private record BlockKey(UUID world, int x, int y, int z) {
        static BlockKey of(Block block) { return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()); }
    }

    public QuestListener(NovoSMPPlugin plugin, QuestService quests) {
        this.quests = quests;
        this.nonNaturalMining = new NamespacedKey(plugin, "profession_non_natural_mining");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) { quests.join(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        quests.quit(event.getPlayer());
        recentPlaced.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void place(BlockPlaceEvent event) {
        if (!quests.enabled()) return;
        LinkedHashMap<BlockKey, Long> placed = recentPlaced.computeIfAbsent(event.getPlayer().getUniqueId(),
                ignored -> new LinkedHashMap<>());
        placed.put(BlockKey.of(event.getBlockPlaced()), System.currentTimeMillis());
        int limit = Math.max(16, quests.config().yaml.getInt("anti-farm.recent-placements-per-player", 256));
        while (placed.size() > limit) placed.remove(placed.keySet().iterator().next());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void death(EntityDeathEvent event) {
        if (!quests.enabled() || !(event.getEntity() instanceof Mob || event.getEntity() instanceof EnderDragon)
                || event.getEntity().hasMetadata("NPC")) return;
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        quests.progress(killer, QuestType.ENTITY_KILL, null, event.getEntityType(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void tame(EntityTameEvent event) {
        if (event.getOwner() instanceof Player player)
            quests.progress(player, QuestType.TAME, null, event.getEntityType(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void breed(EntityBreedEvent event) {
        if (event.getBreeder() instanceof Player player)
            quests.progress(player, QuestType.BREED, null, event.getEntityType(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void egg(ProjectileLaunchEvent event) {
        if (event.getEntity() instanceof Egg egg && egg.getShooter() instanceof Player player)
            quests.progress(player, QuestType.EGG_THROW, null, null, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void vanillaFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item caught)) return;
        if (isFish(caught.getItemStack().getType()))
            quests.progress(event.getPlayer(), QuestType.FISH_CATCH, null, null, 1);
    }

    public void customFish(Player player) { quests.progress(player, QuestType.FISH_CATCH, null, null, 1); }

    private boolean isFish(Material item) {
        return item == Material.COD || item == Material.SALMON || item == Material.TROPICAL_FISH
                || item == Material.PUFFERFISH;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void inspectBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!quests.relevantForBlock(event.getPlayer(), block.getType())) return;
        // Capture before MinerListener consumes this marker at MONITOR.
        boolean recent = recentlyPlaced(event.getPlayer(), block);
        pendingBreaks.put(event, !markedByProfession(block) && !recent);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void countBreak(BlockBreakEvent event) {
        Boolean natural = pendingBreaks.remove(event);
        if (!event.isCancelled()) {
            LinkedHashMap<BlockKey, Long> placed = recentPlaced.get(event.getPlayer().getUniqueId());
            if (placed != null) placed.remove(BlockKey.of(event.getBlock()));
        }
        if (Boolean.TRUE.equals(natural) && !event.isCancelled())
            quests.progress(event.getPlayer(), QuestType.MATERIAL_BREAK, event.getBlock().getType(), null, 1);
    }

    private boolean recentlyPlaced(Player player, Block block) {
        LinkedHashMap<BlockKey, Long> placed = recentPlaced.get(player.getUniqueId());
        if (placed == null) return false;
        Long when = placed.get(BlockKey.of(block));
        long keep = Math.max(1, quests.config().yaml.getLong("anti-farm.recent-place-seconds", 300)) * 1000L;
        return when != null && System.currentTimeMillis() - when <= keep;
    }

    private boolean markedByProfession(Block block) {
        int[] packed = block.getChunk().getPersistentDataContainer().get(nonNaturalMining, PersistentDataType.INTEGER_ARRAY);
        if (packed == null) return false;
        int value = ((block.getY() + 2048) << 8) | ((block.getZ() & 15) << 4) | (block.getX() & 15);
        for (int entry : packed) if (entry == value) return true;
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void enchant(EnchantItemEvent event) {
        quests.progress(event.getEnchanter(), QuestType.ENCHANT, null, null, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void move(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) return;
        Player player = event.getPlayer();
        boolean gliding = player.isGliding() && quests.relevant(player, QuestType.ELYTRA_DISTANCE);
        Entity vehicle = player.getVehicle();
        boolean boating = vehicle instanceof Boat boat && !boat.getPassengers().isEmpty()
                && boat.getPassengers().getFirst() == player && quests.relevant(player, QuestType.BOAT_DISTANCE);
        if (!gliding && !boating) return;
        if (event.getTo() == null || event.getFrom().getWorld() != event.getTo().getWorld()) return;
        double dx = event.getTo().getX() - event.getFrom().getX();
        double dy = event.getTo().getY() - event.getFrom().getY();
        double dz = event.getTo().getZ() - event.getFrom().getZ();
        double squared = dx * dx + dy * dy + dz * dz;
        double max = Math.max(1, quests.config().yaml.getDouble("movement.maximum-step-blocks", 16));
        if (squared <= 0 || squared > max * max) return;
        quests.progress(player, gliding ? QuestType.ELYTRA_DISTANCE : QuestType.BOAT_DISTANCE,
                null, null, Math.sqrt(squared));
    }
}
