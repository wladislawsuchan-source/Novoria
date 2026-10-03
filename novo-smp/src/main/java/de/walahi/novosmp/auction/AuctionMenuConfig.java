package de.walahi.novosmp.auction;

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

/** Typed presentation access for auctionhouse.yml plus compatible business settings. */
final class AuctionMenuConfig {
    private static final String ROOT = "auction-ui.";

    private final FileConfiguration configuration;
    private final MiniMessageItems items = new MiniMessageItems();

    AuctionMenuConfig(SMPCorePlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        this.configuration = plugin.configs().auctionHouse();
    }

    Component component(String path, String fallback) {
        return items.component(string(path, fallback));
    }

    Component component(String path, String fallback, Map<String, String> placeholders) {
        return items.component(string(path, fallback), placeholders);
    }

    List<Component> lore(String path, Collection<String> fallback) {
        return lore(path, fallback, Map.of());
    }

    List<Component> lore(String path, Collection<String> fallback, Map<String, String> placeholders) {
        List<String> configured = configuration.getStringList(key(path));
        Collection<String> source = configured.isEmpty() ? fallback : configured;
        return items.lore(source, placeholders);
    }

    List<String> strings(String path, Collection<String> fallback) {
        List<String> configured = configuration.getStringList(key(path));
        return configured.isEmpty() ? List.copyOf(fallback) : List.copyOf(configured);
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

    long longValue(String path, long fallback) {
        return configuration.getLong(key(path), fallback);
    }

    int rows(String path, int fallback) {
        return Math.max(1, Math.min(6, integer(path, fallback)));
    }

    int slot(String path, int fallback, int inventorySize) {
        int safeFallback = Math.max(0, Math.min(Math.max(0, inventorySize - 1), fallback));
        int configured = integer(path, safeFallback);
        return configured >= 0 && configured < inventorySize ? configured : safeFallback;
    }

    List<Integer> slots(String path, List<Integer> fallback, int inventorySize) {
        List<Integer> configured = configuration.getIntegerList(key(path));
        List<Integer> source = configured.isEmpty() ? fallback : configured;
        List<Integer> valid = source.stream()
                .filter(slot -> slot != null && slot >= 0 && slot < inventorySize)
                .distinct()
                .toList();
        if (!valid.isEmpty()) return valid;
        return fallback.stream()
                .filter(slot -> slot != null && slot >= 0 && slot < inventorySize)
                .distinct()
                .toList();
    }

    String string(String path, String fallback) {
        return configuration.getString(key(path), fallback);
    }

    String collectReason(String reason) {
        String safeReason = reason == null || reason.isBlank() ? "DELIVERY_FALLBACK" : reason;
        return string("icons.collect-reasons." + safeReason,
                switch (safeReason) {
                    case "EXPIRED" -> "Angebot abgelaufen";
                    case "CANCELLED" -> "Angebot zurückgenommen";
                    case "PURCHASE_DELIVERY" -> "Gekaufter Gegenstand";
                    default -> "Nicht zustellbarer Gegenstand";
                });
    }

    long inputTimeoutTicks() {
        return Math.max(20L, longValue("input.timeout-ticks", 1_200L));
    }

    FileConfiguration messagesConfiguration() {
        return configuration;
    }

    private String key(String path) {
        return ROOT + path;
    }
}
