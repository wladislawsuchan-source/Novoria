package de.walahi.novosmp.enderchest;

import de.walahi.smpcore.storage.StorageManager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.*;
import java.sql.*;
import java.util.UUID;

final class EnderChestUpgradeRepository {
    private static final int FORMAT_MAGIC = 0x4E454332; // "NEC2"
    private static final int MAX_SLOTS = 54;

    record Data(int level, ItemStack[] contents, boolean exists) {}
    private final StorageManager storage;
    EnderChestUpgradeRepository(StorageManager storage) { this.storage = storage; }

    Data load(UUID uuid) throws SQLException, IOException, ClassNotFoundException {
        String sql = "SELECT upgrade_level, contents FROM " + storage.table("enderchest_upgrades") + " WHERE player_uuid=?";
        try (Connection c = storage.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return new Data(0, new ItemStack[0], false);
                byte[] bytes = rs.getBytes("contents");
                return new Data(Math.max(0, Math.min(3, rs.getInt("upgrade_level"))), deserialize(bytes), true);
            }
        }
    }

    void save(UUID uuid, int level, ItemStack[] contents) throws SQLException, IOException {
        String table = storage.table("enderchest_upgrades");
        try (Connection c = storage.connection()) {
            String update = "UPDATE " + table + " SET upgrade_level=?, contents=?, updated_at=? WHERE player_uuid=?";
            try (PreparedStatement ps = c.prepareStatement(update)) {
                ps.setInt(1, level); ps.setBytes(2, serialize(contents)); ps.setLong(3, System.currentTimeMillis()); ps.setString(4, uuid.toString());
                if (ps.executeUpdate() > 0) return;
            }
            String insert = "INSERT INTO " + table + " (player_uuid, upgrade_level, contents, updated_at) VALUES (?,?,?,?)";
            try (PreparedStatement ps = c.prepareStatement(insert)) {
                ps.setString(1, uuid.toString()); ps.setInt(2, level); ps.setBytes(3, serialize(contents)); ps.setLong(4, System.currentTimeMillis()); ps.executeUpdate();
            }
        }
    }

    /**
     * Uses Bukkit's modern binary ItemStack format. It preserves current data components
     * without the legacy Java-object metadata differences that can make visually identical
     * vanilla items fail ItemStack#isSimilar and therefore refuse to stack.
     */
    private byte[] serialize(ItemStack[] contents) throws IOException {
        ItemStack[] safe = contents == null ? new ItemStack[0] : contents;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(FORMAT_MAGIC);
            out.writeInt(Math.min(MAX_SLOTS, safe.length));
            for (int i = 0; i < Math.min(MAX_SLOTS, safe.length); i++) {
                ItemStack item = safe[i];
                if (item == null || item.getType().isAir()) {
                    out.writeInt(-1);
                    continue;
                }
                byte[] itemBytes = item.serializeAsBytes();
                out.writeInt(itemBytes.length);
                out.write(itemBytes);
            }
            return bytes.toByteArray();
        }
    }

    private ItemStack[] deserialize(byte[] bytes) throws IOException, ClassNotFoundException {
        if (bytes == null || bytes.length == 0) return new ItemStack[0];

        if (isModernFormat(bytes)) {
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
                in.readInt(); // magic
                int length = Math.max(0, Math.min(MAX_SLOTS, in.readInt()));
                ItemStack[] contents = new ItemStack[length];
                for (int i = 0; i < length; i++) {
                    int itemLength = in.readInt();
                    if (itemLength < 0) continue;
                    if (itemLength > 16 * 1024 * 1024) throw new IOException("Ungültige EC-Itemgröße: " + itemLength);
                    contents[i] = ItemStack.deserializeBytes(in.readNBytes(itemLength));
                }
                return contents;
            }
        }

        // Backward compatibility for existing 1.28.4 and older database rows.
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(bytes))) {
            int length = Math.max(0, Math.min(MAX_SLOTS, in.readInt()));
            ItemStack[] contents = new ItemStack[length];
            for (int i = 0; i < length; i++) contents[i] = (ItemStack) in.readObject();
            return contents;
        }
    }

    private boolean isModernFormat(byte[] bytes) {
        if (bytes.length < Integer.BYTES) return false;
        int magic = ((bytes[0] & 0xFF) << 24)
                | ((bytes[1] & 0xFF) << 16)
                | ((bytes[2] & 0xFF) << 8)
                | (bytes[3] & 0xFF);
        return magic == FORMAT_MAGIC;
    }
}
