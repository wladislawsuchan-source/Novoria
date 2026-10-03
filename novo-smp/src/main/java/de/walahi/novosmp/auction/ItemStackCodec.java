package de.walahi.novosmp.auction;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

/** Lossless Bukkit ItemStack serialization for database persistence. */
public final class ItemStackCodec {
    private ItemStackCodec() { }

    public static String encode(ItemStack item) throws IOException {
        if (item == null || item.getType().isAir()) throw new IOException("Leeres Item kann nicht gespeichert werden.");
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             BukkitObjectOutputStream output = new BukkitObjectOutputStream(bytes)) {
            output.writeObject(item.clone());
            output.flush();
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        }
    }

    public static ItemStack decode(String encoded) throws IOException {
        if (encoded == null || encoded.isBlank()) throw new IOException("Gespeicherte Item-Daten fehlen.");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Gespeicherte Item-Daten sind ungültig.", exception);
        }
        try (BukkitObjectInputStream input = new BukkitObjectInputStream(new ByteArrayInputStream(bytes))) {
            Object object = input.readObject();
            if (!(object instanceof ItemStack item)) throw new IOException("Gespeicherte Daten enthalten kein ItemStack.");
            return item;
        } catch (ClassNotFoundException exception) {
            throw new IOException("ItemStack-Klasse konnte nicht geladen werden.", exception);
        }
    }
}
