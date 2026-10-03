package de.walahi.novosmp.angler;

import de.walahi.smpcore.SMPCorePlugin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;

/** Main-thread cache with bounded, persistent snapshots; never silently discards failed saves. */
public final class AnglerCatchStorage {
    private final SMPCorePlugin plugin;
    private final AnglerStorageRepository repository;
    private final Map<UUID, ItemStack[]> cache = new HashMap<>();
    private final Set<UUID> dirty = new HashSet<>();
    private final Map<UUID, Runnable> viewers = new HashMap<>();

    public AnglerCatchStorage(SMPCorePlugin plugin) {
        this.plugin = plugin;
        repository = new AnglerStorageRepository(plugin.storageManager());
    }

    public int rows(int prestige) {
        int safe = Math.max(0, Math.min(5, prestige));
        int fallback = switch (safe) { case 0, 1 -> 3; case 2 -> 4; case 3, 4 -> 5; default -> 6; };
        return Math.max(1, Math.min(6, plugin.configs().angler()
                .getInt("storage.rows-by-prestige." + safe, fallback)));
    }

    public ItemStack[] snapshot(UUID playerId, int prestige) throws IOException, SQLException {
        ItemStack[] source = contents(playerId);
        ItemStack[] result = new ItemStack[rows(prestige) * 9];
        for (int slot = 0; slot < result.length; slot++) {
            if (source[slot] != null) result[slot] = source[slot].clone();
        }
        return result;
    }

    /** The later fishing system uses this; any overflow is returned intact to its caller. */
    public ItemStack storeCatch(Player player, ItemStack caught, IntSupplier prestige) {
        if (caught == null || caught.getType().isAir() || caught.getAmount() <= 0) return null;
        try {
            ItemStack[] slots = contents(player.getUniqueId());
            ItemStack remainder = caught.clone();
            int limit = rows(prestige.getAsInt()) * 9;
            for (int index = 0; index < limit && remainder.getAmount() > 0; index++) {
                ItemStack target = slots[index];
                if (target == null || !target.isSimilar(remainder)) continue;
                int moved = Math.min(remainder.getAmount(), target.getMaxStackSize() - target.getAmount());
                if (moved <= 0) continue;
                target.setAmount(target.getAmount() + moved);
                remainder.setAmount(remainder.getAmount() - moved);
            }
            for (int index = 0; index < limit && remainder.getAmount() > 0; index++) {
                if (slots[index] != null && !slots[index].getType().isAir()) continue;
                int moved = Math.min(remainder.getAmount(), remainder.getMaxStackSize());
                slots[index] = remainder.clone();
                slots[index].setAmount(moved);
                remainder.setAmount(remainder.getAmount() - moved);
            }
            dirty.add(player.getUniqueId());
            Runnable refresh = viewers.get(player.getUniqueId());
            if (refresh != null) refresh.run();
            return remainder.getAmount() == 0 ? null : remainder;
        } catch (IOException | SQLException exception) {
            plugin.getLogger().severe("Fanglager konnte nicht geladen werden: " + exception.getMessage());
            return caught.clone();
        }
    }

    /** Removes a catch only after the resulting snapshot has been durably written. */
    public ItemStack take(UUID playerId, int slot, int amount) {
        return takeMatching(playerId, Map.of(slot, amount), null);
    }

    /** One durable write for all slots collected by a double-click. */
    public ItemStack takeMatching(UUID playerId, Map<Integer, Integer> requested, ItemStack expected) {
        if (requested == null || requested.isEmpty()) return null;
        try {
            ItemStack[] current = contents(playerId);
            ItemStack[] next = Arrays.copyOf(current, current.length);
            ItemStack result = null;
            int total = 0;
            for (Map.Entry<Integer, Integer> entry : new LinkedHashMap<>(requested).entrySet()) {
                int slot = entry.getKey();
                int amount = entry.getValue();
                if (slot < 0 || slot >= current.length || amount <= 0) return null;
                ItemStack original = current[slot];
                if (original == null || original.getType().isAir() || amount > original.getAmount()
                        || expected != null && !original.isSimilar(expected)
                        || result != null && !original.isSimilar(result)) return null;
                if (result == null) result = original.clone();
                total += amount;
                if (total > result.getMaxStackSize()) return null;
                if (amount == original.getAmount()) next[slot] = null;
                else {
                    next[slot] = original.clone();
                    next[slot].setAmount(original.getAmount() - amount);
                }
            }
            result.setAmount(total);
            repository.save(playerId, next);
            cache.put(playerId, next);
            dirty.remove(playerId);
            return result;
        } catch (IOException | SQLException exception) {
            plugin.getLogger().severe("Fanglager-Entnahme abgebrochen: " + exception.getMessage());
            return null;
        }
    }

    public void viewer(UUID playerId, Runnable refresh) {
        if (refresh == null) viewers.remove(playerId);
        else viewers.put(playerId, refresh);
    }

    public boolean flush(UUID playerId) {
        if (!dirty.contains(playerId)) return true;
        try {
            repository.save(playerId, contents(playerId));
            dirty.remove(playerId);
            return true;
        } catch (IOException | SQLException exception) {
            plugin.getLogger().severe("Fanglager konnte nicht gespeichert werden: " + exception.getMessage());
            return false;
        }
    }

    public void flushDirty() {
        for (UUID playerId : Set.copyOf(dirty)) flush(playerId);
    }

    public void forget(UUID playerId) {
        viewers.remove(playerId);
        if (flush(playerId)) cache.remove(playerId);
    }

    private ItemStack[] contents(UUID playerId) throws IOException, SQLException {
        ItemStack[] cached = cache.get(playerId);
        if (cached != null) return cached;
        ItemStack[] loaded = repository.load(playerId);
        cache.put(playerId, loaded);
        return loaded;
    }
}
