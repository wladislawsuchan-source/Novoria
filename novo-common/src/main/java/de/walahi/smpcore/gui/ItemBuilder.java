package de.walahi.smpcore.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Shared builder for menu items. It always clones source items and disables
 * vanilla italic formatting on names and lore by default.
 */
public final class ItemBuilder {
    private final ItemStack item;

    private ItemBuilder(ItemStack item) {
        this.item = Objects.requireNonNull(item, "item").clone();
    }

    public static ItemBuilder of(Material material) {
        return new ItemBuilder(new ItemStack(Objects.requireNonNull(material, "material")));
    }

    public static ItemBuilder from(ItemStack item) {
        return new ItemBuilder(item);
    }

    public ItemBuilder amount(int amount) {
        int maximum = Math.max(1, item.getMaxStackSize());
        item.setAmount(Math.max(1, Math.min(maximum, amount)));
        return this;
    }

    public ItemBuilder name(Component name) {
        editMeta(meta -> meta.displayName(plainStyle(name)));
        return this;
    }

    public ItemBuilder lore(Collection<Component> lines) {
        List<Component> normalized = lines == null
                ? List.of()
                : lines.stream().filter(Objects::nonNull).map(ItemBuilder::plainStyle).toList();
        editMeta(meta -> meta.lore(normalized));
        return this;
    }

    public ItemBuilder appendLore(Component... lines) {
        if (lines == null || lines.length == 0) return this;
        editMeta(meta -> {
            List<Component> lore = meta.hasLore() && meta.lore() != null
                    ? new ArrayList<>(meta.lore())
                    : new ArrayList<>();
            for (Component line : lines) {
                if (line != null) lore.add(plainStyle(line));
            }
            meta.lore(lore);
        });
        return this;
    }

    public ItemBuilder glint(boolean enabled) {
        editMeta(meta -> meta.setEnchantmentGlintOverride(enabled));
        return this;
    }

    public ItemBuilder flags(ItemFlag... flags) {
        if (flags == null || flags.length == 0) return this;
        editMeta(meta -> meta.addItemFlags(flags));
        return this;
    }

    public ItemBuilder customModelData(Integer customModelData) {
        editMeta(meta -> meta.setCustomModelData(customModelData));
        return this;
    }

    public ItemBuilder editMeta(Consumer<ItemMeta> editor) {
        Objects.requireNonNull(editor, "editor");
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return this;
        editor.accept(meta);
        item.setItemMeta(meta);
        return this;
    }

    public <T extends ItemMeta> ItemBuilder editMeta(Class<T> type, Consumer<T> editor) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(editor, "editor");
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return this;
        if (type.isInstance(meta)) {
            editor.accept(type.cast(meta));
            item.setItemMeta(meta);
        }
        return this;
    }

    public ItemStack build() {
        return item.clone();
    }

    public static Component plainStyle(Component component) {
        return Objects.requireNonNull(component, "component")
                .decoration(TextDecoration.ITALIC, false);
    }
}
