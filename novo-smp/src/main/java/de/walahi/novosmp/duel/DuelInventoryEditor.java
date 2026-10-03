package de.walahi.novosmp.duel;

import de.walahi.novosmp.NovoSMPPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Editable 54-slot representation of a full player loadout. */
public final class DuelInventoryEditor implements Listener {
    private static final Set<Integer> EDITABLE = Set.of(
            1, 2, 3, 4, 7,
            9,10,11,12,13,14,15,16,17,
            18,19,20,21,22,23,24,25,26,
            27,28,29,30,31,32,33,34,35,
            45,46,47,48,49,50,51,52,53
    );

    private final NovoSMPPlugin plugin;
    private final DuelConfig config;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public DuelInventoryEditor(NovoSMPPlugin plugin, DuelConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void openPlayerLayout(Player player, String kitId) {
        DuelKit kit = config.kit(kitId);
        if (kit == null) {
            DuelMessages.send(config, player, config.message("kits.editor-missing", "§cDieses Kit existiert nicht mehr."));
            return;
        }
        DuelLoadout loadout = config.layout(player.getUniqueId(), kit.id());
        open(player, kit, loadout, false);
    }

    public void openAdminEditor(Player player, String kitId) {
        DuelKit kit = config.kit(kitId);
        if (kit == null) {
            DuelMessages.send(config, player, config.message("kits.editor-not-found", "§cDieses Kit existiert nicht."));
            return;
        }
        open(player, kit, kit.loadout(), true);
    }

    private void open(Player player, DuelKit kit, DuelLoadout loadout, boolean admin) {
        player.closeInventory();
        safelyReturnRealCursor(player);
        DuelLoadout adminInventory = admin ? DuelLoadout.capture(player) : null;
        EditorHolder holder = new EditorHolder(player.getUniqueId());
        String titlePath = admin ? "menus.editor.admin-title" : "menus.editor.player-title";
        String titleFallback = admin
                ? "<aqua>Kit bearbeiten – <white>%kit%</white></aqua>"
                : "<aqua>Kit-Sortierung – <white>%kit%</white></aqua>";
        Component title = component(config.text(titlePath, titleFallback, "%kit%", escape(kit.displayName())));
        Inventory inventory = Bukkit.createInventory(holder, 54, title);
        holder.inventory = inventory;
        fill(inventory, loadout);
        Session session = new Session(kit.id(), admin, inventory, adminInventory);
        sessions.put(player.getUniqueId(), session);
        player.openInventory(inventory);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Session session = sessions.get(player.getUniqueId());
        if (session == null || event.getView().getTopInventory() != session.inventory()) return;

        int raw = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();
        boolean top = raw >= 0 && raw < topSize;

        if (top && raw == closeSlot()) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, (Runnable) player::closeInventory);
            return;
        }

        if (event.getClick() == ClickType.DROP || event.getClick() == ClickType.CONTROL_DROP
                || event.getClick() == ClickType.CREATIVE || event.getClick() == ClickType.MIDDLE) {
            event.setCancelled(true);
            return;
        }

        if (!session.admin()) {
            if (!top || !EDITABLE.contains(raw) || event.isShiftClick() || event.getClick().isKeyboardClick()) {
                event.setCancelled(true);
            }
            return;
        }

        if (top && !EDITABLE.contains(raw)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Session session = sessions.get(player.getUniqueId());
        if (session == null || event.getView().getTopInventory() != session.inventory()) return;
        int topSize = event.getView().getTopInventory().getSize();
        for (int raw : event.getRawSlots()) {
            if (raw < topSize && !EDITABLE.contains(raw)) {
                event.setCancelled(true);
                return;
            }
            if (!session.admin() && raw >= topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Session session = sessions.remove(player.getUniqueId());
        if (session == null || event.getInventory() != session.inventory()) return;

        returnCursorToEditor(player, session.inventory());
        DuelLoadout edited = read(session.inventory());
        DuelKit kit = config.kit(session.kitId());

        if (session.admin()) {
            if (kit != null) config.saveKit(kit.id(), kit.displayName(), kit.icon(), edited);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                player.setItemOnCursor(null);
                if (session.adminInventory() != null) session.adminInventory().apply(player);
                DuelMessages.send(config, player, config.message("editor.admin-saved",
                        "<green>Kit <white>%kit%</white> wurde gespeichert.</green>",
                        "%kit%", escape(kit == null ? session.kitId() : kit.displayName())));
            });
            return;
        }

        if (kit != null && kit.loadout().sameItemTotals(edited)) {
            config.saveLayout(player.getUniqueId(), kit.id(), edited);
            DuelMessages.send(config, player, config.message("editor.player-saved",
                    "<green>Deine Sortierung für <white>%kit%</white> wurde gespeichert.</green>",
                    "%kit%", escape(kit.displayName())));
        } else {
            DuelMessages.send(config, player, config.message("kits.invalid-layout", "§cDie Sortierung war ungültig. Es wurden keine Änderungen gespeichert."));
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) player.setItemOnCursor(null);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) return;
        // The editor contains virtual kit items. Never let an item on the cursor
        // survive a disconnect and enter the real player inventory.
        player.setItemOnCursor(null);
        if (session.admin() && session.adminInventory() != null) {
            session.adminInventory().apply(player);
        }
    }

    private void fill(Inventory inventory, DuelLoadout loadout) {
        ItemStack filler = guideItem("filler", Material.GRAY_STAINED_GLASS_PANE, "");
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);

        inventory.setItem(0, guideItem("helmet", Material.NETHERITE_HELMET, "<gray>Helm</gray>"));
        inventory.setItem(5, guideItem("armor", Material.SHIELD, "<gray>Rüstung und Offhand</gray>"));
        inventory.setItem(6, guideItem("offhand", Material.TOTEM_OF_UNDYING, "<gray>Offhand</gray>"));
        inventory.setItem(closeSlot(), guideItem("close", Material.BARRIER, "<red>Schließen = Speichern</red>"));
        for (int slot : EDITABLE) inventory.setItem(slot, null);

        ItemStack[] storage = loadout.storage();
        for (int index = 9; index < 36; index++) inventory.setItem(index, storage[index]);
        for (int index = 0; index < 9; index++) inventory.setItem(45 + index, storage[index]);
        ItemStack[] armor = loadout.armor();
        inventory.setItem(1, armor[3]);
        inventory.setItem(2, armor[2]);
        inventory.setItem(3, armor[1]);
        inventory.setItem(4, armor[0]);
        inventory.setItem(7, loadout.offhand());
    }

    private DuelLoadout read(Inventory inventory) {
        ItemStack[] storage = new ItemStack[36];
        for (int index = 9; index < 36; index++) storage[index] = cloneItem(inventory.getItem(index));
        for (int index = 0; index < 9; index++) storage[index] = cloneItem(inventory.getItem(45 + index));
        ItemStack[] armor = new ItemStack[4];
        armor[3] = cloneItem(inventory.getItem(1));
        armor[2] = cloneItem(inventory.getItem(2));
        armor[1] = cloneItem(inventory.getItem(3));
        armor[0] = cloneItem(inventory.getItem(4));
        return new DuelLoadout(storage, armor, cloneItem(inventory.getItem(7)));
    }

    private void safelyReturnRealCursor(Player player) {
        ItemStack cursor = player.getItemOnCursor();
        if (isEmpty(cursor)) return;
        player.setItemOnCursor(null);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(cursor.clone());
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    private void returnCursorToEditor(Player player, Inventory inventory) {
        ItemStack cursor = player.getItemOnCursor();
        if (isEmpty(cursor)) return;
        for (int slot : EDITABLE) {
            ItemStack current = inventory.getItem(slot);
            if (isEmpty(current)) {
                inventory.setItem(slot, cursor.clone());
                player.setItemOnCursor(null);
                return;
            }
        }
    }

    private int closeSlot() {
        int configured = config.integer("menus.editor.items.close.slot", 8, 0, 53);
        return EDITABLE.contains(configured) ? 8 : configured;
    }

    private ItemStack guideItem(String key, Material fallbackMaterial, String fallbackName) {
        String path = "menus.editor.items." + key;
        Material material = config.configuredMaterial(path + ".material", fallbackMaterial);
        Material resolved = material == null || material.isAir() || !material.isItem() ? fallbackMaterial : material;
        ItemStack item = new ItemStack(resolved);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        String name = config.text(path + ".name", fallbackName);
        meta.displayName(name == null || name.isBlank() ? Component.empty() : component(name));
        Integer customModelData = config.customModelData(path + ".custom-model-data");
        if (customModelData != null) meta.setCustomModelData(customModelData);
        item.setItemMeta(meta);
        return item;
    }

    private Component component(String value) {
        String raw = value == null ? "" : value;
        try {
            return mm.deserialize(raw);
        } catch (RuntimeException ignored) {
            return Component.text(raw);
        }
    }

    private static ItemStack cloneItem(ItemStack item) { return isEmpty(item) ? null : item.clone(); }
    private static boolean isEmpty(ItemStack item) { return item == null || item.getType().isAir() || item.getAmount() <= 0; }
    private static String escape(String value) { return value == null ? "" : value.replace("<", "\\<").replace(">", "\\>"); }
    private record Session(String kitId, boolean admin, Inventory inventory, DuelLoadout adminInventory) {}

    private static final class EditorHolder implements InventoryHolder {
        private final UUID owner;
        private Inventory inventory;
        private EditorHolder(UUID owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
