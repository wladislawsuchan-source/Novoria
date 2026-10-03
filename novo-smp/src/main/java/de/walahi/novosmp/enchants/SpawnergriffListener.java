package de.walahi.novosmp.enchants;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Collects only ordinary mob spawners, preserving their entity type on the dropped item. */
public final class SpawnergriffListener implements Listener {
    private record Pending(UUID worldId, int x, int y, int z, EntityType entityType,
                           Material toolType, int uses) {
        boolean matches(Block block) {
            return worldId.equals(block.getWorld().getUID())
                    && x == block.getX() && y == block.getY() && z == block.getZ();
        }
    }

    private final JavaPlugin plugin;
    private final CustomEnchantmentService enchantments;
    private final NamespacedKey entityTypeKey;
    // Transient bridge between the pre-break spawner state and the successful drop event.
    private final Map<UUID, Pending> pending = new HashMap<>();

    public SpawnergriffListener(JavaPlugin plugin, CustomEnchantmentService enchantments) {
        this.plugin = plugin;
        this.enchantments = enchantments;
        this.entityTypeKey = new NamespacedKey(plugin, "spawner_entity_type");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        pending.remove(player.getUniqueId());
        if (event.isCancelled() || !event.isDropItems()
                || player.getGameMode() != GameMode.SURVIVAL
                || event.getBlock().getType() != Material.SPAWNER) return;
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!CustomEnchantmentService.spawnergriffPickaxe(tool.getType())
                || enchantments.level(tool, "spawnergriff") < 1) return;
        int uses = enchantments.spawnerUses(tool);
        if (uses < 1 || !(event.getBlock().getState() instanceof CreatureSpawner spawner)) return;
        // spawnerUses also repairs legacy counters/lore; persist that migration
        // even if the inventory implementation returned a detached ItemStack.
        player.getInventory().setItemInMainHand(tool);
        EntityType type = spawner.getSpawnedType();
        if (type == null) return;
        Block block = event.getBlock();
        pending.put(player.getUniqueId(), new Pending(block.getWorld().getUID(),
                block.getX(), block.getY(), block.getZ(), type, tool.getType(), uses));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent event) {
        Player player = event.getPlayer();
        Pending expected = pending.remove(player.getUniqueId());
        if (expected == null || !expected.matches(event.getBlock())
                || event.getBlockState().getType() != Material.SPAWNER) return;

        ItemStack spawner = createSpawnerItem(expected.entityType());
        Item dropped = event.getBlock().getWorld().dropItemNaturally(
                event.getBlock().getLocation(), spawner);
        if (!dropped.isValid()) return;
        // Vanilla's pending drops have not spawned yet. Replace all of them with one item.
        event.getItems().clear();

        // Vanilla may already have broken the pickaxe before BlockDropItemEvent.
        // In that case the spawner was still collected, but there is no tool to update.
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (tool.getType() != expected.toolType()
                || enchantments.level(tool, "spawnergriff") < 1
                || enchantments.spawnerUses(tool) != expected.uses()) return;
        if (enchantments.consumeSpawnerUse(tool))
            player.getInventory().setItemInMainHand(tool);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!event.canBuild() || event.getBlockPlaced().getType() != Material.SPAWNER) return;
        EntityType type = storedEntityType(event.getItemInHand());
        if (type == null) return;
        Block block = event.getBlockPlaced();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (event.isCancelled() || !event.canBuild() || block.getType() != Material.SPAWNER
                    || !(block.getState() instanceof CreatureSpawner spawner)) return;
            spawner.setSpawnedType(type);
            spawner.update(true, false);
        });
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    public ItemStack createSpawnerItem(EntityType type) {
        ItemStack item = new ItemStack(Material.SPAWNER);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(entityTypeKey, PersistentDataType.STRING, type.name());
        meta.lore(List.of(Component.text("Mob: ", NamedTextColor.GRAY)
                .append(Component.translatable(type.translationKey(), NamedTextColor.YELLOW))
                .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    public EntityType storedEntityType(ItemStack item) {
        if (item == null || item.getType() != Material.SPAWNER || !item.hasItemMeta()) return null;
        String stored = item.getItemMeta().getPersistentDataContainer()
                .get(entityTypeKey, PersistentDataType.STRING);
        if (stored == null) return null;
        try { return EntityType.valueOf(stored); }
        catch (IllegalArgumentException exception) { return null; }
    }
}
