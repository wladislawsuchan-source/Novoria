package de.walahi.novosmp.feature;

import de.walahi.smpcore.SMPCorePlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Öffnet Shulkerbox-Items per Rechtsklick, ohne sie sichtbar aus dem Slot zu entfernen. */
public final class PortableShulkerBoxListener implements Listener {
    private final SMPCorePlugin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public PortableShulkerBoxListener(SMPCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        // Solange genau dieses virtuelle Inventar geöffnet ist, werden zusätzliche
        // Geyser-Interaktionen verworfen. Direkt nach dem Schließen gibt es bewusst
        // keinerlei Cooldown: Die Shulker kann sofort erneut geöffnet oder platziert werden.
        if (sessions.containsKey(playerId)) {
            denyInteraction(event);
            return;
        }

        int sourceSlot = player.getInventory().getHeldItemSlot();
        ItemStack held = player.getInventory().getItem(sourceSlot);
        if (held == null || !isShulkerBox(held.getType())) return;
        if (!(held.getItemMeta() instanceof BlockStateMeta meta)) return;
        if (!(meta.getBlockState() instanceof ShulkerBox state)) return;

        // Boden, Wand und andere angeklickte Blöcke bleiben vollständig Vanilla.
        // Dadurch kann die Shulker ohne Sonderlogik normal platziert werden.
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) return;

        denyInteraction(event);

        ItemStack original = held.clone();
        original.setAmount(1);

        PortableHolder holder = new PortableHolder(playerId);
        Component title = meta.hasCustomName() ? meta.customName() : Component.text("Shulkerbox");
        Inventory inventory = Bukkit.createInventory(
                holder,
                InventoryType.SHULKER_BOX,
                title == null ? Component.text("Shulkerbox") : title
        );
        holder.inventory = inventory;
        inventory.setContents(state.getInventory().getContents());

        sessions.put(playerId, new Session(original, sourceSlot, holder));
        player.openInventory(inventory);
    }

    /**
     * Sollte Geyser während einer noch geöffneten virtuellen Shulker trotzdem eine
     * echte Blockplatzierung senden, wird ausschließlich diese Platzierung zurückgesetzt.
     * Nach dem Schließen existiert keine Zeit- oder Tick-Sperre mehr.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!isShulkerBox(event.getBlockPlaced().getType())) return;
        if (!sessions.containsKey(event.getPlayer().getUniqueId())) return;

        Player player = event.getPlayer();
        BlockState replacedState = event.getBlockReplacedState();
        event.setCancelled(true);
        player.updateInventory();

        // Defensive Rücksetzung, falls ein Bedrock-Transaktionspaket den Block trotz
        // abgebrochenem Event für einen Tick serverseitig hinterlassen hat.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (isShulkerBox(replacedState.getBlock().getType())) {
                replacedState.update(true, false);
            }
        });
    }

    /** Der sichtbare Quellslot bleibt gesperrt, solange sein Inhalt geöffnet ist. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return;

        Inventory top = event.getView().getTopInventory();
        boolean clickedTop = event.getClickedInventory() == top;

        // Shulker dürfen wie bei einer echten Vanilla-Shulkerbox niemals in die
        // geöffnete portable Shulker gelegt werden. Das umfasst Cursor-Klicks,
        // Shift-Klicks, Zahlentasten und Offhand-Tausch.
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        if (clickedTop && cursor != null && isShulkerBox(cursor.getType())) {
            event.setCancelled(true);
            return;
        }
        if (event.isShiftClick() && event.getClickedInventory() == player.getInventory()
                && current != null && isShulkerBox(current.getType())) {
            event.setCancelled(true);
            return;
        }
        if (clickedTop && event.getHotbarButton() >= 0) {
            ItemStack hotbar = player.getInventory().getItem(event.getHotbarButton());
            if (hotbar != null && isShulkerBox(hotbar.getType())) {
                event.setCancelled(true);
                return;
            }
        }
        if (clickedTop && event.getClick() == ClickType.SWAP_OFFHAND) {
            ItemStack offHand = player.getInventory().getItemInOffHand();
            if (offHand != null && isShulkerBox(offHand.getType())) {
                event.setCancelled(true);
                return;
            }
        }

        if (event.getClickedInventory() == player.getInventory() && event.getSlot() == session.sourceSlot) {
            event.setCancelled(true);
            return;
        }

        // Zahlentasten könnten den geöffneten Quellslot indirekt austauschen.
        if (event.getHotbarButton() == session.sourceSlot) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return;

        int topSize = event.getView().getTopInventory().getSize();
        if (event.getOldCursor() != null && isShulkerBox(event.getOldCursor().getType())
                && event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) {
            event.setCancelled(true);
            return;
        }
        int rawSourceSlot = topSize + playerInventoryRawOffset(session.sourceSlot);
        if (event.getRawSlots().contains(rawSourceSlot)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrop(PlayerDropItemEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null) return;
        ItemStack current = event.getPlayer().getInventory().getItem(session.sourceSlot);
        if (current != null && current.isSimilar(event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && session.sourceSlot == event.getPlayer().getInventory().getHeldItemSlot()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof PortableHolder holder)) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        finish(player, holder, event.getInventory());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && session.holder.inventory != null) {
            finish(event.getPlayer(), session.holder, session.holder.inventory);
        }
    }

    private void finish(Player player, PortableHolder holder, Inventory inventory) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null || session.holder != holder) return;

        // Letzte serverseitige Sicherung: Selbst wenn ein Client oder ein anderes Plugin
        // einen verbotenen Shulker-in-Shulker-Transfer durchbekommt, wird er vor dem
        // Speichern entfernt und dem Spieler zurückgegeben.
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack nested = inventory.getItem(slot);
            if (nested == null || !isShulkerBox(nested.getType())) continue;
            inventory.setItem(slot, null);
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(nested);
            overflow.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        }

        ItemStack updated = session.item.clone();
        if (updated.getItemMeta() instanceof BlockStateMeta meta && meta.getBlockState() instanceof ShulkerBox state) {
            state.getInventory().setContents(inventory.getContents());
            meta.setBlockState(state);
            updated.setItemMeta(meta);
        }

        ItemStack current = player.getInventory().getItem(session.sourceSlot);
        if (current != null && current.isSimilar(session.item)) {
            updated.setAmount(current.getAmount());
            player.getInventory().setItem(session.sourceSlot, updated);
        } else {
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(updated);
            overflow.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        }
        player.updateInventory();
    }

    private void denyInteraction(PlayerInteractEvent event) {
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        event.setCancelled(true);
    }

    /** Bukkit-Inventarslots 0-8 liegen im Container nach 27-35. */
    private int playerInventoryRawOffset(int inventorySlot) {
        return inventorySlot < 9 ? inventorySlot + 27 : inventorySlot - 9;
    }

    private boolean isShulkerBox(Material material) {
        return material == Material.SHULKER_BOX || material.name().endsWith("_SHULKER_BOX");
    }

    private record Session(ItemStack item, int sourceSlot, PortableHolder holder) {}

    public static final class PortableHolder implements InventoryHolder {
        private final UUID owner;
        private Inventory inventory;

        private PortableHolder(UUID owner) {
            this.owner = owner;
        }

        public UUID owner() { return owner; }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
