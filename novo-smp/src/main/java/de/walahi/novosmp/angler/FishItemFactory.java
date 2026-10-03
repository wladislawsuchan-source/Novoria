package de.walahi.novosmp.angler;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Objects;

/** Creates stackable fish with identical metadata for every item of one definition. */
public final class FishItemFactory {
    private final FishRegistry registry;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public FishItemFactory(FishRegistry registry) { this.registry = Objects.requireNonNull(registry); }

    public ItemStack create(String id, int amount) {
        FishDefinition fish = registry.find(id);
        if (fish == null || amount <= 0) return null;
        ItemStack item = new ItemStack(fish.material(), Math.min(amount, fish.material().getMaxStackSize()));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        meta.customName(text(fish.color() + fish.displayName()));
        List<Component> lore = fish.lore().stream().map(this::text).toList();
        if (!lore.isEmpty()) meta.lore(lore);
        meta.getPersistentDataContainer().set(registry.fishIdKey(), PersistentDataType.STRING, fish.id());
        item.setItemMeta(meta);
        return item;
    }

    private Component text(String raw) {
        return miniMessage.deserialize(raw).decoration(TextDecoration.ITALIC, false);
    }
}
