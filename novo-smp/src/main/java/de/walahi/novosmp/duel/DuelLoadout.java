package de.walahi.novosmp.duel;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Exact duel loadout: 36 storage slots, four armor slots and offhand. */
public final class DuelLoadout {
    private final ItemStack[] storage;
    private final ItemStack[] armor;
    private final ItemStack offhand;

    public DuelLoadout(ItemStack[] storage, ItemStack[] armor, ItemStack offhand) {
        this.storage = cloneArray(storage, 36);
        this.armor = cloneArray(armor, 4);
        this.offhand = cloneItem(offhand);
    }

    public static DuelLoadout capture(Player player) {
        PlayerInventory inventory = player.getInventory();
        return new DuelLoadout(inventory.getStorageContents(), inventory.getArmorContents(), inventory.getItemInOffHand());
    }

    public void apply(Player player) {
        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        inventory.setStorageContents(storage());
        inventory.setArmorContents(armor());
        inventory.setItemInOffHand(offhand());
        player.updateInventory();
    }

    public ItemStack[] storage() {
        return cloneArray(storage, 36);
    }

    public ItemStack[] armor() {
        return cloneArray(armor, 4);
    }

    public ItemStack offhand() {
        return cloneItem(offhand);
    }

    public DuelLoadout copy() {
        return new DuelLoadout(storage, armor, offhand);
    }

    public void save(ConfigurationSection section) {
        section.set("storage", toList(storage));
        section.set("armor", toList(armor));
        section.set("offhand", offhand == null ? null : offhand.clone());
    }

    public static DuelLoadout load(ConfigurationSection section) {
        if (section == null) return empty();
        return new DuelLoadout(
                fromList(section.getList("storage"), 36),
                fromList(section.getList("armor"), 4),
                section.getItemStack("offhand")
        );
    }

    public static DuelLoadout empty() {
        return new DuelLoadout(new ItemStack[36], new ItemStack[4], null);
    }

    public boolean sameItemTotals(DuelLoadout other) {
        if (other == null) return false;
        List<ItemStack> expected = flattened(this);
        List<ItemStack> actual = flattened(other);
        boolean[] used = new boolean[actual.size()];
        for (ItemStack item : expected) {
            int needed = item.getAmount();
            for (int index = 0; index < actual.size() && needed > 0; index++) {
                if (used[index]) continue;
                ItemStack candidate = actual.get(index);
                if (!item.isSimilar(candidate)) continue;
                if (candidate.getAmount() != needed) return false;
                used[index] = true;
                needed = 0;
            }
            if (needed != 0) return false;
        }
        for (int index = 0; index < actual.size(); index++) {
            if (!used[index]) return false;
        }
        return true;
    }

    private static List<ItemStack> flattened(DuelLoadout loadout) {
        List<ItemStack> grouped = new ArrayList<>();
        List<ItemStack> all = new ArrayList<>();
        all.addAll(Arrays.asList(loadout.storage));
        all.addAll(Arrays.asList(loadout.armor));
        all.add(loadout.offhand);
        for (ItemStack item : all) {
            if (isEmpty(item)) continue;
            ItemStack existing = null;
            for (ItemStack candidate : grouped) {
                if (candidate.isSimilar(item)) {
                    existing = candidate;
                    break;
                }
            }
            if (existing == null) {
                grouped.add(item.clone());
            } else {
                long total = (long) existing.getAmount() + item.getAmount();
                if (total > Integer.MAX_VALUE) return List.of();
                existing.setAmount((int) total);
            }
        }
        return grouped;
    }

    private static List<ItemStack> toList(ItemStack[] items) {
        List<ItemStack> result = new ArrayList<>(items.length);
        for (ItemStack item : items) result.add(cloneItem(item));
        return result;
    }

    private static ItemStack[] fromList(List<?> source, int size) {
        ItemStack[] result = new ItemStack[size];
        if (source == null) return result;
        for (int index = 0; index < Math.min(size, source.size()); index++) {
            Object value = source.get(index);
            if (value instanceof ItemStack item && !isEmpty(item)) result[index] = item.clone();
        }
        return result;
    }

    private static ItemStack[] cloneArray(ItemStack[] source, int size) {
        ItemStack[] result = new ItemStack[size];
        if (source == null) return result;
        for (int index = 0; index < Math.min(size, source.length); index++) result[index] = cloneItem(source[index]);
        return result;
    }

    private static ItemStack cloneItem(ItemStack item) {
        return isEmpty(item) ? null : item.clone();
    }

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }
}
