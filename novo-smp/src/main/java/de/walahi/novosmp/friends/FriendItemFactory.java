package de.walahi.novosmp.friends;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Central item/PDC creation for all friend menus. */
public final class FriendItemFactory {
    private final FriendConfiguration config;
    private final NamespacedKey actionKey;
    private final NamespacedKey uuidKey;
    private final NamespacedKey homeKey;

    public FriendItemFactory(JavaPlugin plugin, FriendConfiguration config) {
        this.config = config;
        this.actionKey = new NamespacedKey(plugin, "friend_action");
        this.uuidKey = new NamespacedKey(plugin, "friend_uuid");
        this.homeKey = new NamespacedKey(plugin, "friend_home");
    }

    public String action(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        return meta == null ? null : meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
    }

    public UUID uuid(ItemStack item, UUID fallback) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta == null) return fallback;
        String value = meta.getPersistentDataContainer().get(uuidKey, PersistentDataType.STRING);
        if (value == null) return fallback;
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException ignored) { return fallback; }
    }

    public String home(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        return meta == null ? null : meta.getPersistentDataContainer().get(homeKey, PersistentDataType.STRING);
    }

    public ItemStack item(String path, Material fallbackMaterial, String fallbackName, List<String> fallbackLore,
                          String action, UUID uuid, String home, Map<String, String> placeholders) {
        Material material = config.material(path + ".material", fallbackMaterial);
        ItemStack item = new ItemStack(material);
        applyMeta(item, path, fallbackName, fallbackLore, action, uuid, home, placeholders);
        return item;
    }

    public ItemStack item(Material material, String path, String fallbackName, List<String> fallbackLore,
                          String action, UUID uuid, String home, Map<String, String> placeholders) {
        ItemStack item = new ItemStack(material);
        applyMeta(item, path, fallbackName, fallbackLore, action, uuid, home, placeholders);
        return item;
    }

    public ItemStack head(UUID skin, String path, String fallbackName, List<String> fallbackLore,
                          String action, UUID uuid, String home, Map<String, String> placeholders) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        applyMeta(item, path, fallbackName, fallbackLore, action, uuid, home, placeholders);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        // UUID-only offline profiles are intentionally not created here. Rendering many of them
        // makes Paper request missing skin properties from Mojang and can hit HTTP 429 rate limits.
        Player online = Bukkit.getPlayer(skin);
        if (online != null) meta.setOwnerProfile(online.getPlayerProfile());
        item.setItemMeta(meta);
        item.setData(DataComponentTypes.TOOLTIP_DISPLAY,
                TooltipDisplay.tooltipDisplay().addHiddenComponents(DataComponentTypes.PROFILE).build());
        return item;
    }

    private void applyMeta(ItemStack item, String path, String fallbackName, List<String> fallbackLore,
                           String action, UUID uuid, String home, Map<String, String> placeholders) {
        ItemMeta meta = item.getItemMeta();
        Component name = config.component(path + ".name", fallbackName, placeholders);
        List<Component> lore = config.components(path + ".lore", fallbackLore, placeholders);
        meta.displayName(name);
        meta.lore(lore);
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        if (uuid != null) meta.getPersistentDataContainer().set(uuidKey, PersistentDataType.STRING, uuid.toString());
        if (home != null) meta.getPersistentDataContainer().set(homeKey, PersistentDataType.STRING, home);
        item.setItemMeta(meta);
    }
}
