package de.walahi.novosmp.feature;

import de.walahi.novosmp.NovoSMPPlugin;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;

/** Vergibt das Starterset genau beim allerersten Join auf NovoSMP. */
public final class StarterGearListener implements Listener {
    private final NovoSMPPlugin plugin;
    private final NamespacedKey receivedKey;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public StarterGearListener(NovoSMPPlugin plugin) {
        this.plugin = plugin;
        this.receivedKey = new NamespacedKey(plugin, "starter_gear_received");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPlayedBefore()) return;
        if (player.getPersistentDataContainer().has(receivedKey, PersistentDataType.BYTE)) return;
        if (!plugin.configs().kits().getBoolean("starter-kit.enabled", true)) return;

        player.getPersistentDataContainer().set(receivedKey, PersistentDataType.BYTE, (byte) 1);
        plugin.getServer().getScheduler().runTask(plugin, () -> giveStarterGear(player));
    }

    private void giveStarterGear(Player player) {
        if (!player.isOnline()) return;
        Color armorColor = configuredColor();
        player.getInventory().setHelmet(configuredItem("starter-kit.armor.helmet", armorColor));
        player.getInventory().setChestplate(configuredItem("starter-kit.armor.chestplate", armorColor));
        player.getInventory().setLeggings(configuredItem("starter-kit.armor.leggings", armorColor));
        player.getInventory().setBoots(configuredItem("starter-kit.armor.boots", armorColor));

        ConfigurationSection items = plugin.configs().kits().getConfigurationSection("starter-kit.items");
        if (items != null) {
            for (String id : items.getKeys(false)) {
                ItemStack item = configuredItem("starter-kit.items." + id, null);
                if (item != null) add(player, item);
            }
        }
        player.updateInventory();
    }

    private ItemStack configuredItem(String path, Color leatherColor) {
        ConfigurationSection section = plugin.configs().kits().getConfigurationSection(path);
        if (section == null || !section.getBoolean("enabled", true)) return null;

        String materialName = section.getString("material", "").trim();
        Material material = Material.matchMaterial(materialName);
        if (material == null || !material.isItem() || material.isAir()) {
            plugin.getLogger().warning("Ungültiges Starter-Kit-Material bei " + path + ": " + materialName);
            return null;
        }

        int amount = Math.max(1, Math.min(material.getMaxStackSize(), section.getInt("amount", 1)));
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        if (leatherColor != null && meta instanceof LeatherArmorMeta leatherMeta) {
            leatherMeta.setColor(leatherColor);
        }
        String name = section.getString("name", "").trim();
        if (!name.isEmpty()) {
            meta.displayName(mm.deserialize(name).decoration(TextDecoration.ITALIC, false));
        }
        item.setItemMeta(meta);
        return item;
    }

    private Color configuredColor() {
        String value = plugin.configs().kits().getString("starter-kit.armor-color", "#9646FF").trim();
        try {
            String hex = value.startsWith("#") ? value.substring(1) : value;
            if (hex.length() != 6) throw new NumberFormatException("expected RRGGBB");
            return Color.fromRGB(Integer.parseInt(hex, 16));
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Ungültige Starter-Kit-Rüstungsfarbe '" + value
                    + "'; verwende #9646FF.");
            return Color.fromRGB(150, 70, 255);
        }
    }

    private void add(Player player, ItemStack item) {
        player.getInventory().addItem(item).values().forEach(leftover ->
                player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }
}
