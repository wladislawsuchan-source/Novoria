package de.walahi.novosmp.crates;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Map;

/** Verwaltet handelbare Crate-Keys. Die PDC-ID ist maßgeblich, nicht das sichtbare Material. */
public final class KeyManager {
    private final JavaPlugin plugin;
    private final NamespacedKey crateIdKey;

    public KeyManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.crateIdKey = new NamespacedKey(plugin, "crate_key_id");
    }

    public int get(Player player, String crateId) {
        int amount = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (isKey(stack, crateId)) amount += stack.getAmount();
        }
        return amount;
    }

    /** Gibt echte Key-Items. Nicht passende Restmengen werden sicher beim Spieler gedroppt. */
    public void add(Player player, CrateDefinition crate, int amount) {
        addAndCountDropped(player, crate, amount);
    }

    /**
     * Gibt echte Key-Items und liefert zurück, wie viele Keys wegen eines vollen
     * Inventars vor dem Spieler gedroppt werden mussten.
     */
    public int addAndCountDropped(Player player, CrateDefinition crate, int amount) {
        if (amount <= 0) return 0;
        int remaining = amount;
        int dropped = 0;
        while (remaining > 0) {
            int stackAmount = Math.min(64, remaining);
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(create(crate, stackAmount));
            for (ItemStack item : leftovers.values()) {
                dropped += item.getAmount();
                player.getWorld().dropItemNaturally(player.getLocation(), item);
            }
            remaining -= stackAmount;
        }
        return dropped;
    }

    /** Checks all requested key types together against the player's storage slots. */
    public boolean canFit(Player player, Map<CrateDefinition, Integer> requested) {
        ItemStack[] storage = player.getInventory().getStorageContents();
        int freeSlots = 0;
        for (ItemStack stack : storage) {
            if (stack == null || stack.getType().isAir()) freeSlots++;
        }

        for (Map.Entry<CrateDefinition, Integer> entry : requested.entrySet()) {
            CrateDefinition crate = entry.getKey();
            int remaining = Math.max(0, entry.getValue());
            if (crate == null || remaining == 0) continue;
            ItemStack prototype = create(crate, 1);
            for (ItemStack stack : storage) {
                if (stack == null || !stack.isSimilar(prototype)) continue;
                remaining -= Math.max(0, stack.getMaxStackSize() - stack.getAmount());
                if (remaining <= 0) break;
            }
            if (remaining <= 0) continue;
            int neededSlots = (remaining + prototype.getMaxStackSize() - 1) / prototype.getMaxStackSize();
            freeSlots -= neededSlots;
            if (freeSlots < 0) return false;
        }
        return true;
    }

    /** Adds keys atomically to storage and never drops them into the world. */
    public boolean addWithoutDrop(Player player, Map<CrateDefinition, Integer> requested) {
        if (!canFit(player, requested)) return false;
        ItemStack[] original = cloneContents(player.getInventory().getStorageContents());
        try {
            for (Map.Entry<CrateDefinition, Integer> entry : requested.entrySet()) {
                CrateDefinition crate = entry.getKey();
                int remaining = Math.max(0, entry.getValue());
                if (crate == null) continue;
                while (remaining > 0) {
                    int stackAmount = Math.min(crate.keyMaterial().getMaxStackSize(), remaining);
                    if (!player.getInventory().addItem(create(crate, stackAmount)).isEmpty()) {
                        player.getInventory().setStorageContents(original);
                        return false;
                    }
                    remaining -= stackAmount;
                }
            }
            return true;
        } catch (RuntimeException exception) {
            player.getInventory().setStorageContents(original);
            return false;
        }
    }

    private ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            copy[index] = contents[index] == null ? null : contents[index].clone();
        }
        return copy;
    }

    public boolean take(Player player, String crateId, int amount) {
        if (amount <= 0 || get(player, crateId) < amount) return false;
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (!isKey(stack, crateId)) continue;
            int removed = Math.min(stack.getAmount(), remaining);
            if (removed >= stack.getAmount()) player.getInventory().setItem(slot, null);
            else stack.setAmount(stack.getAmount() - removed);
            remaining -= removed;
        }
        return remaining == 0;
    }

    public ItemStack create(CrateDefinition crate, int amount) {
        Material material = crate == null || crate.keyMaterial() == null ? Material.TRIPWIRE_HOOK : crate.keyMaterial();
        ItemStack item = new ItemStack(material, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(keyDisplayName(crate), NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(crateIdKey, PersistentDataType.STRING, crate.id().toLowerCase(Locale.ROOT));
        item.setItemMeta(meta);
        return item;
    }

    /** Der Anzeigename eines Keys, z. B. "Vanta-Key". Wird auch für Hand-Prüfungsmeldungen genutzt. */
    public String keyDisplayName(CrateDefinition crate) {
        if (crate != null && crate.keyDisplayName() != null && !crate.keyDisplayName().isBlank()) {
            return crate.keyDisplayName();
        }
        return crate.displayName().replaceFirst("(?i)-?Kiste$", "") + "-Key";
    }

    /**
     * Entscheidend ist ausschließlich die persistente crate_key_id. Dadurch bleiben alte
     * Tripwire-Hook-Keys aus früheren Versionen gültig, während neue Keys ihr eigenes Material nutzen.
     */
    public boolean isKey(ItemStack item, String crateId) {
        if (item == null || !item.hasItemMeta()) return false;
        String stored = item.getItemMeta().getPersistentDataContainer().get(crateIdKey, PersistentDataType.STRING);
        return stored != null && stored.equalsIgnoreCase(crateId);
    }

    /** Prüft unabhängig von der Crate-ID, ob das Item irgendein echter Crate-Key ist. */
    public boolean isAnyKey(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        String stored = item.getItemMeta().getPersistentDataContainer().get(crateIdKey, PersistentDataType.STRING);
        return stored != null && !stored.isBlank();
    }

    /** Prüft, ob der Spieler den passenden Key gerade in der Haupthand hält. */
    public boolean isHoldingKey(Player player, String crateId) {
        return isKey(player.getInventory().getItemInMainHand(), crateId);
    }

    public void reload() {
        // Item-Keys benötigen keine separate Datendatei.
    }
}
