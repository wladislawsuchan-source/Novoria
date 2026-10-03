package de.walahi.novosmp.angler;

import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Same modern ItemStack byte serialization pattern as the expandable Enderchest. */
public final class AnglerStorageCodec {
    private static final int MAGIC = 0x4E464C31; // NFL1
    private static final int MAX_SLOTS = 54;
    private static final int MAX_ITEM_BYTES = 16 * 1024 * 1024;

    public byte[] serialize(ItemStack[] contents) throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            int length = contents == null ? 0 : Math.min(MAX_SLOTS, contents.length);
            out.writeInt(length);
            for (int slot = 0; slot < length; slot++) {
                ItemStack item = contents[slot];
                if (item == null || item.getType().isAir()) {
                    out.writeInt(-1);
                    continue;
                }
                byte[] encoded = item.serializeAsBytes();
                if (encoded.length > MAX_ITEM_BYTES) throw new IOException("Fanglager-Item ist zu groß");
                out.writeInt(encoded.length);
                out.write(encoded);
            }
            return bytes.toByteArray();
        }
    }

    public ItemStack[] deserialize(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) return new ItemStack[MAX_SLOTS];
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC) throw new IOException("Ungültiges Fanglager-Format");
            int length = in.readInt();
            if (length < 0 || length > MAX_SLOTS) throw new IOException("Ungültige Fanglager-Größe");
            ItemStack[] contents = new ItemStack[MAX_SLOTS];
            for (int slot = 0; slot < length; slot++) {
                int size = in.readInt();
                if (size == -1) continue;
                if (size <= 0 || size > MAX_ITEM_BYTES) throw new IOException("Ungültige Fanglager-Itemgröße");
                byte[] encoded = in.readNBytes(size);
                if (encoded.length != size) throw new IOException("Fanglager-Daten sind unvollständig");
                try { contents[slot] = ItemStack.deserializeBytes(encoded); }
                catch (RuntimeException exception) { throw new IOException("Fanglager-Item ist ungültig", exception); }
            }
            return contents;
        }
    }
}
