package de.walahi.novosmp.orders;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.gui.MaterialResolver;
import de.walahi.smpcore.gui.MiniMessageItems;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Typed presentation access for the standalone orders.yml file. */
final class OrderMenuConfig {
    private static final String ROOT = "order-ui.";

    private final FileConfiguration configuration;
    private final MiniMessageItems items = new MiniMessageItems();

    OrderMenuConfig(SMPCorePlugin plugin) {
        this.configuration = Objects.requireNonNull(plugin, "plugin").configs().orders();
    }

    Component component(String path, String fallback) {
        return items.component(string(path, fallback));
    }

    Component component(String path, String fallback, Map<String, String> placeholders) {
        return items.component(string(path, fallback), placeholders);
    }

    List<Component> lore(String path, Collection<String> fallback) {
        List<String> configured = configuration.getStringList(key(path));
        return items.lore(configured.isEmpty() ? fallback : configured);
    }

    List<Component> lore(String path, Collection<String> fallback, Map<String, String> placeholders) {
        List<String> configured = configuration.getStringList(key(path));
        return items.lore(configured.isEmpty() ? fallback : configured, placeholders);
    }

    ItemStack item(String path, Material fallbackMaterial, String fallbackName,
                   Collection<String> fallbackLore) {
        return item(path, fallbackMaterial, fallbackName, fallbackLore, Map.of());
    }

    ItemStack item(String path, Material fallbackMaterial, String fallbackName,
                   Collection<String> fallbackLore, Map<String, String> placeholders) {
        ConfigurationSection section = configuration.getConfigurationSection(key(path));
        if (section == null) {
            return items.item(fallbackMaterial, 1, fallbackName, fallbackLore, placeholders);
        }
        Material material = MaterialResolver.resolve(section.getString("material"), fallbackMaterial);
        String name = section.getString("name", fallbackName);
        List<String> lore = section.getStringList("lore");
        if (lore.isEmpty()) lore = List.copyOf(fallbackLore);
        return items.item(material, 1, name, lore, placeholders);
    }

    Material material(String path, Material fallback) {
        return MaterialResolver.resolve(configuration.getString(key(path)), fallback);
    }

    int integer(String path, int fallback) {
        return configuration.getInt(key(path), fallback);
    }

    int rows(String path, int fallback) {
        return Math.max(1, Math.min(6, integer(path, fallback)));
    }

    int slot(String path, int fallback, int inventorySize) {
        int safeFallback = Math.max(0, Math.min(Math.max(0, inventorySize - 1), fallback));
        int slot = integer(path, safeFallback);
        return slot >= 0 && slot < inventorySize ? slot : safeFallback;
    }

    List<Integer> slots(String path, List<Integer> fallback, int inventorySize) {
        List<Integer> configured = configuration.getIntegerList(key(path));
        List<Integer> source = configured.isEmpty() ? fallback : configured;
        List<Integer> valid = source.stream()
                .filter(slot -> slot != null && slot >= 0 && slot < inventorySize)
                .distinct()
                .toList();
        return valid.isEmpty() ? fallback : valid;
    }

    String string(String path, String fallback) {
        return configuration.getString(key(path), fallback);
    }


    MiniMessageItems items() {
        return items;
    }

    private String key(String path) {
        return ROOT + path;
    }
}
