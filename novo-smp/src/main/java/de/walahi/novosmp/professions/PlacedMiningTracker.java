package de.walahi.novosmp.professions;

import de.walahi.smpcore.SMPCorePlugin;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Persists mining blocks that must not grant profession XP (player placed or generated farms). */
final class PlacedMiningTracker {
    private final NamespacedKey key;
    private final ProfessionConfig config;

    PlacedMiningTracker(SMPCorePlugin plugin, ProfessionConfig config) {
        this.key = new NamespacedKey(plugin, "profession_non_natural_mining");
        this.config = config;
    }

    boolean eligible(Block block) {
        return block != null && config.xpFor(ProfessionConfig.MINER_ID, block.getType()) > 0D;
    }

    void mark(Block block) {
        if (!eligible(block)) return;
        Chunk chunk = block.getChunk();
        Set<Integer> packed = read(chunk);
        packed.add(pack(block));
        write(chunk, packed);
    }

    boolean consume(Block block) {
        if (block == null) return false;
        Chunk chunk = block.getChunk();
        Set<Integer> packed = read(chunk);
        boolean removed = packed.remove(pack(block));
        if (removed) write(chunk, packed);
        return removed;
    }

    void allow(Block block) {
        consume(block);
    }

    private Set<Integer> read(Chunk chunk) {
        PersistentDataContainer container = chunk.getPersistentDataContainer();
        int[] values = container.get(key, PersistentDataType.INTEGER_ARRAY);
        Set<Integer> result = new HashSet<>();
        if (values != null) Arrays.stream(values).forEach(result::add);
        return result;
    }

    private void write(Chunk chunk, Set<Integer> values) {
        PersistentDataContainer container = chunk.getPersistentDataContainer();
        if (values.isEmpty()) {
            container.remove(key);
            return;
        }
        container.set(key, PersistentDataType.INTEGER_ARRAY,
                values.stream().mapToInt(Integer::intValue).toArray());
    }

    private int pack(Block block) {
        int localX = block.getX() & 15;
        int localZ = block.getZ() & 15;
        int y = block.getY() + 2048;
        return (y << 8) | (localZ << 4) | localX;
    }
}
