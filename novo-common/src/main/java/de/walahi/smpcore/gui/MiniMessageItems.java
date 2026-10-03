package de.walahi.smpcore.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Creates consistently styled menu items from MiniMessage strings. */
public final class MiniMessageItems {
    private final MiniMessage miniMessage;

    public MiniMessageItems() {
        this(MiniMessage.miniMessage());
    }

    public MiniMessageItems(MiniMessage miniMessage) {
        this.miniMessage = Objects.requireNonNull(miniMessage, "miniMessage");
    }

    public Component component(String input) {
        return miniMessage.deserialize(normalizeLegacyAngleEntities(input));
    }

    public Component component(String input, Map<String, String> placeholders) {
        return component(replace(input, placeholders));
    }

    public List<Component> lore(Collection<String> lines) {
        if (lines == null) return List.of();
        return lines.stream().map(this::component).toList();
    }

    public List<Component> lore(Collection<String> lines, Map<String, String> placeholders) {
        if (lines == null) return List.of();
        return lines.stream().map(line -> component(line, placeholders)).toList();
    }

    public ItemBuilder builder(Material material, String name, Collection<String> lore) {
        return ItemBuilder.of(material)
                .name(component(name))
                .lore(this.lore(lore));
    }

    public ItemBuilder builder(Material material, int amount, String name, Collection<String> lore) {
        return builder(material, name, lore).amount(amount);
    }

    public ItemBuilder builder(Material material, int amount, String name,
                               Collection<String> lore, Map<String, String> placeholders) {
        return ItemBuilder.of(material)
                .amount(amount)
                .name(component(name, placeholders))
                .lore(this.lore(lore, placeholders));
    }

    public ItemStack item(Material material, String name, Collection<String> lore) {
        return builder(material, name, lore).build();
    }

    public ItemStack item(Material material, int amount, String name, Collection<String> lore) {
        return builder(material, amount, name, lore).build();
    }

    public ItemStack item(Material material, int amount, String name,
                          Collection<String> lore, Map<String, String> placeholders) {
        return builder(material, amount, name, lore, placeholders).build();
    }

    public ItemStack fromSection(ConfigurationSection section, Material fallbackMaterial,
                                 String fallbackName, int amount) {
        Objects.requireNonNull(section, "section");
        Material material = MaterialResolver.resolve(section.getString("material"), fallbackMaterial);
        return item(material, amount,
                section.getString("name", fallbackName),
                section.getStringList("lore"));
    }

    public MiniMessage miniMessage() {
        return miniMessage;
    }

    private String replace(String input, Map<String, String> placeholders) {
        String value = input == null ? "" : input;
        if (placeholders == null || placeholders.isEmpty()) return value;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            value = value.replace(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
        }
        return value;
    }

    /**
     * Older menu texts used HTML entities to protect command arguments from
     * MiniMessage. MiniMessage is not an HTML parser, so those entities became
     * visible text. Unknown tags such as {@code <Betrag>} are already treated as
     * literal text by MiniMessage; only genuine formatting tags are resolved.
     */
    private String normalizeLegacyAngleEntities(String input) {
        return (input == null ? "" : input)
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }
}
