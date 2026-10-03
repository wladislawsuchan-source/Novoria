package de.walahi.smpcore.hub;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.gui.Gui;
import de.walahi.smpcore.gui.GuiButton;
import de.walahi.smpcore.gui.MaterialResolver;
import de.walahi.smpcore.gui.MiniMessageItems;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Objects;

/** Owns the protected Hub navigator item and its menu. */
public final class HubCompassController implements Listener {
    private final SMPCorePlugin plugin;
    private final NamespacedKey itemKey;
    private final MiniMessageItems items = new MiniMessageItems();

    public HubCompassController(SMPCorePlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.itemKey = new NamespacedKey(plugin, "hub_compass");
    }

    public void give(Player player) {
        if (player == null
                || !plugin.configs().menus().getBoolean("hub.compass.enabled", true)
                || !isHubWorld(player.getWorld())) {
            return;
        }

        remove(player);
        Material material = material("hub.compass.material", Material.COMPASS);
        ItemStack compass = items.item(
                material,
                plugin.configs().menus().getString(
                        "hub.compass.name",
                        "<gradient:#F6D365:#FDA085><bold>NAVIGATOR</bold></gradient>"
                ),
                plugin.configs().menus().getStringList("hub.compass.lore")
        );
        ItemMeta meta = compass.getItemMeta();
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        compass.setItemMeta(meta);

        int configuredSlot = plugin.configs().menus().getInt("hub.compass.slot", 5);
        int slot = Math.max(0, Math.min(8, configuredSlot - 1));
        player.getInventory().setItem(slot, compass);
        if (plugin.configs().menus().getBoolean("hub.compass.select-on-give", true)) {
            player.getInventory().setHeldItemSlot(slot);
        }
    }

    public void remove(Player player) {
        if (player == null) return;
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            if (isCompass(player.getInventory().getItem(slot))) {
                player.getInventory().setItem(slot, null);
            }
        }
        if (isCompass(player.getInventory().getItemInOffHand())) {
            player.getInventory().setItemInOffHand(null);
        }
    }

    public void synchronize(Player player) {
        if (isHubWorld(player.getWorld())) give(player);
        else remove(player);
    }

    public boolean isCompass(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        Byte value = item.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    public void openMenu(Player player) {
        int rows = Math.max(1, Math.min(6, plugin.configs().menus().getInt("hub.menu.rows", 5)));
        Gui menu = new Gui(rows, items.component(plugin.configs().menus().getString(
                "hub.menu.title",
                "<dark_gray>Wähle einen Spielmodus</dark_gray>"
        )));

        if (plugin.configs().menus().getBoolean("hub.menu.filler.enabled", true)) {
            menu.filler(menuItem(
                    "hub.menu.filler",
                    Material.GRAY_STAINED_GLASS_PANE,
                    "<reset>",
                    List.of()
            ));
        }

        addCommandButton(menu, player, rows, "hub.menu.items.smp", 13, Material.GRASS_BLOCK,
                "<green><bold>SMP</bold></green>",
                List.of("<gray>Klicke, um das SMP zu betreten.</gray>"), "smp");
        addCommandButton(menu, player, rows, "hub.menu.items.hub", 31, Material.NETHER_STAR,
                "<gold><bold>Hub</bold></gold>",
                List.of("<gray>Klicke, um zum Hub zu gelangen.</gray>"), "hub");
        menu.open(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!isHubWorld(event.getPlayer().getWorld()) || !isCompass(event.getItem())) return;
        event.setCancelled(true);
        openMenu(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChanged(PlayerChangedWorldEvent event) {
        synchronize(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (isCompass(event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        boolean protectedCompass = isCompass(event.getCurrentItem()) || isCompass(event.getCursor());
        int hotbarButton = event.getHotbarButton();
        if (hotbarButton >= 0 && isCompass(player.getInventory().getItem(hotbarButton))) {
            protectedCompass = true;
        }
        if (protectedCompass) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (isCompass(event.getOldCursor())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        if (isCompass(event.getMainHandItem()) || isCompass(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    private void addCommandButton(Gui menu, Player player, int rows, String path, int fallbackSlot,
                                  Material fallbackMaterial, String fallbackName,
                                  List<String> fallbackLore, String command) {
        int slot = plugin.configs().menus().getInt(path + ".slot", fallbackSlot);
        if (slot < 0 || slot >= rows * 9) {
            plugin.getLogger().warning("Ungültiger Hub-Menü-Slot für '" + path + "': " + slot);
            return;
        }
        menu.button(slot, new GuiButton(menuItem(path, fallbackMaterial, fallbackName, fallbackLore), event -> {
            player.closeInventory();
            player.performCommand(command);
        }));
    }

    private ItemStack menuItem(String path, Material fallbackMaterial,
                               String fallbackName, List<String> fallbackLore) {
        List<String> lore = plugin.configs().menus().getStringList(path + ".lore");
        if (lore.isEmpty()) lore = fallbackLore;
        return items.item(
                material(path + ".material", fallbackMaterial),
                plugin.configs().menus().getString(path + ".name", fallbackName),
                lore
        );
    }

    private Material material(String path, Material fallback) {
        String configured = plugin.configs().menus().getString(path, fallback.name());
        Material resolved = MaterialResolver.resolve(configured, fallback);
        if (configured != null && Material.matchMaterial(configured.trim()) == null) {
            plugin.getLogger().warning("Ungültiges Material in " + path + ": " + configured);
        }
        return resolved;
    }

    private boolean isHubWorld(World world) {
        return world != null && plugin.isHubServer();
    }
}
