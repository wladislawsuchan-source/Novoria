package de.walahi.smpcore.playerdata;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Zugriff auf die bereits von Minecraft verwendete playerdata/&lt;uuid&gt;.dat.
 * Es werden bewusst keine zweiten Inventar-Snapshots im Plugin-Ordner angelegt.
 *
 * Die NBT-Klassen werden nur per Reflection angesprochen, damit novo-common weiterhin
 * ausschließlich gegen die Paper-API kompiliert und keine feste NMS-Abhängigkeit erhält.
 */
public final class NativePlayerDataAccess {
    /** Interne Kennungen für die Ausrüstungsslots im /invsee-GUI. */
    public static final int FEET_SLOT = 100;
    public static final int LEGS_SLOT = 101;
    public static final int CHEST_SLOT = 102;
    public static final int HEAD_SLOT = 103;
    public static final int OFFHAND_SLOT = -106;

    private static final Map<Integer, String> EQUIPMENT_KEYS = Map.of(
            FEET_SLOT, "feet",
            LEGS_SLOT, "legs",
            CHEST_SLOT, "chest",
            HEAD_SLOT, "head",
            OFFHAND_SLOT, "offhand"
    );

    private NativePlayerDataAccess() {
    }

    /** Lädt Hauptinventar und Ausrüstung direkt aus der nativen Minecraft-playerdata. */
    public static Map<Integer, ItemStack> loadInventory(OfflinePlayer player) throws IOException {
        try {
            Object root = requireRoot(player);
            int dataVersion = readDataVersion(root);
            Map<Integer, ItemStack> result = loadItemList(root, "Inventory", dataVersion);

            Object equipment = readCompound(root, "equipment");
            if (equipment != null) {
                for (Map.Entry<Integer, String> mapping : EQUIPMENT_KEYS.entrySet()) {
                    Object itemTag = readTag(equipment, mapping.getValue());
                    if (itemTag == null) continue;
                    ItemStack item = deserializeItem(itemTag, dataVersion);
                    if (item != null && !item.getType().isAir()) result.put(mapping.getKey(), item);
                }
            }
            return result;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            throw asIOException("Native Spielerdaten konnten nicht gelesen werden", exception);
        }
    }

    /** Lädt EnderItems direkt aus derselben Vanilla-playerdata-Datei. */
    public static ItemStack[] loadEnderChest(OfflinePlayer player, int size) throws IOException {
        try {
            Object root = requireRoot(player);
            Map<Integer, ItemStack> slots = loadItemList(root, "EnderItems", readDataVersion(root));
            ItemStack[] contents = new ItemStack[Math.max(0, size)];
            for (Map.Entry<Integer, ItemStack> entry : slots.entrySet()) {
                int slot = entry.getKey();
                if (slot >= 0 && slot < contents.length) contents[slot] = cloneOrNull(entry.getValue());
            }
            return contents;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            throw asIOException("Native Enderchest konnte nicht gelesen werden", exception);
        }
    }

    /**
     * Schreibt ausschließlich die von /invsee verwalteten Slots zurück. Alle anderen
     * playerdata-Felder sowie neue oder unbekannte Inventar-/Ausrüstungsslots bleiben erhalten.
     */
    public static void saveInventory(OfflinePlayer player,
                                     Map<Integer, ItemStack> replacements,
                                     Set<Integer> managedSlots) throws IOException {
        try {
            Object root = requireRoot(player);

            Set<Integer> inventorySlots = new HashSet<>();
            for (Integer slot : managedSlots) {
                if (slot != null && slot >= 0 && slot <= 35) inventorySlots.add(slot);
            }
            replaceItemList(root, "Inventory", replacements, inventorySlots);
            replaceEquipment(root, replacements, managedSlots);
            saveRoot(player, root);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            throw asIOException("Native Spielerdaten konnten nicht gespeichert werden", exception);
        }
    }

    private static Object requireRoot(OfflinePlayer player) throws ReflectiveOperationException, IOException {
        Object root = loadRoot(player);
        if (root == null) throw new IOException("Keine native playerdata für " + player.getUniqueId() + " vorhanden.");
        return root;
    }

    private static Map<Integer, ItemStack> loadItemList(Object root, String listName, int dataVersion)
            throws ReflectiveOperationException {
        List<?> list = readList(root, listName);
        Map<Integer, ItemStack> result = new HashMap<>();
        if (list == null) return result;

        for (Object entry : list) {
            if (entry == null) continue;
            Integer slot = readSlot(entry);
            if (slot == null) continue;
            ItemStack item = deserializeItem(entry, dataVersion);
            if (item != null && !item.getType().isAir()) result.put(slot, item);
        }
        return result;
    }

    private static void replaceItemList(Object root,
                                        String listName,
                                        Map<Integer, ItemStack> replacements,
                                        Set<Integer> managedSlots) throws ReflectiveOperationException {
        List<?> current = readList(root, listName);
        Object replacementList = createListTag(current);
        if (!(replacementList instanceof List<?> rawReplacementList)) {
            throw new IllegalStateException("NBT-ListTag implementiert keine java.util.List.");
        }

        @SuppressWarnings("unchecked")
        List<Object> writable = (List<Object>) rawReplacementList;

        // Nicht durch das GUI verwaltete Einträge unverändert übernehmen.
        if (current != null) {
            for (Object entry : current) {
                Integer slot = readSlot(entry);
                if (slot == null || !managedSlots.contains(slot)) writable.add(copyTag(entry));
            }
        }

        for (Integer slot : managedSlots) {
            ItemStack item = replacements.get(slot);
            if (item == null || item.getType().isAir()) continue;
            Object tag = serializeItem(item);
            writeSlot(tag, slot);
            writable.add(tag);
        }

        putTag(root, listName, replacementList);
    }

    private static void replaceEquipment(Object root,
                                         Map<Integer, ItemStack> replacements,
                                         Set<Integer> managedSlots) throws ReflectiveOperationException {
        Object equipment = readCompound(root, "equipment");
        boolean existed = equipment != null;
        if (equipment == null) equipment = createCompoundTag(root.getClass());

        for (Map.Entry<Integer, String> mapping : EQUIPMENT_KEYS.entrySet()) {
            if (!managedSlots.contains(mapping.getKey())) continue;
            ItemStack item = replacements.get(mapping.getKey());
            if (item == null || item.getType().isAir()) {
                removeTag(equipment, mapping.getValue());
            } else {
                putTag(equipment, mapping.getValue(), serializeItem(item));
            }
        }

        if (isCompoundEmpty(equipment)) {
            if (existed) removeTag(root, "equipment");
        } else {
            putTag(root, "equipment", equipment);
        }
    }

    private static Object loadRoot(OfflinePlayer player) throws ReflectiveOperationException {
        Method method = findMethod(player.getClass(), "getData", 0);
        if (method == null) throw new NoSuchMethodException("CraftOfflinePlayer#getData");
        method.setAccessible(true);
        return unwrapOptional(invoke(method, player));
    }

    private static File dataFile(OfflinePlayer player) throws ReflectiveOperationException {
        Method method = findMethod(player.getClass(), "getDataFile", 0);
        if (method == null) throw new NoSuchMethodException("CraftOfflinePlayer#getDataFile");
        method.setAccessible(true);
        Object value = invoke(method, player);
        if (!(value instanceof File file)) throw new IllegalStateException("getDataFile lieferte keine Datei.");
        return file;
    }

    private static List<?> readList(Object compound, String key) throws ReflectiveOperationException {
        Object result = unwrapOptional(invokeCompatible(compound, "getList", key));
        if (result instanceof List<?> list) return list;

        result = unwrapOptional(invokeCompatible(compound, "getListOrEmpty", key));
        if (result instanceof List<?> list) return list;

        // Rückwärtskompatibilität zu älteren Signaturen: getList(String, TAG_COMPOUND=10)
        result = unwrapOptional(invokeCompatible(compound, "getList", key, 10));
        return result instanceof List<?> list ? list : null;
    }

    private static Object readCompound(Object compound, String key) throws ReflectiveOperationException {
        Object result = unwrapOptional(invokeCompatible(compound, "getCompound", key));
        if (result != null && result != NO_METHOD) return result;
        result = unwrapOptional(invokeCompatible(compound, "getCompoundOrEmpty", key));
        return result == NO_METHOD ? null : result;
    }

    private static Object readTag(Object compound, String key) throws ReflectiveOperationException {
        Object result = invokeCompatible(compound, "get", key);
        return result == NO_METHOD ? null : result;
    }

    private static int readDataVersion(Object root) throws ReflectiveOperationException {
        Object value = unwrapOptional(invokeCompatible(root, "getInt", "DataVersion"));
        if (value instanceof Number number && number.intValue() > 0) return number.intValue();
        value = unwrapOptional(invokeCompatible(root, "getIntOr", "DataVersion", 0));
        if (value instanceof Number number && number.intValue() > 0) return number.intValue();
        Object current = invokeCompatible(Bukkit.getUnsafe(), "getDataVersion");
        return current instanceof Number number ? number.intValue() : 0;
    }

    private static Integer readSlot(Object compound) throws ReflectiveOperationException {
        Object value = unwrapOptional(invokeCompatible(compound, "getByte", "Slot"));
        if (value instanceof Number number) return normalizeSlotByte(number.byteValue());

        // Alte Formate können noch signierte Sonder-Slots wie -106 (Offhand) enthalten.
        value = unwrapOptional(invokeCompatible(compound, "getByteOr", "Slot", (byte) -1));
        if (value instanceof Number number && number.byteValue() != -1) {
            return normalizeSlotByte(number.byteValue());
        }
        return null;
    }

    private static int normalizeSlotByte(byte raw) {
        return raw < 0 ? raw : Byte.toUnsignedInt(raw);
    }

    private static void putInt(Object compound, String key, int value) throws ReflectiveOperationException {
        Object result = invokeCompatible(compound, "putInt", key, value);
        if (result == NO_METHOD) throw new NoSuchMethodException("CompoundTag#putInt");
    }

    private static void writeSlot(Object compound, int slot) throws ReflectiveOperationException {
        Object result = invokeCompatible(compound, "putByte", "Slot", (byte) slot);
        if (result == NO_METHOD) throw new NoSuchMethodException("CompoundTag#putByte");
    }

    private static ItemStack deserializeItem(Object originalCompound, int dataVersion) throws ReflectiveOperationException {
        Object compound = copyTag(originalCompound);
        removeTag(compound, "Slot");
        putInt(compound, "DataVersion", dataVersion);

        // Paper stellt für Item-NBT eine stabile Byte-API bereit. Dadurch hängt /invsee
        // nicht mehr von entfernten CraftMagicNumbers#deserializeItem(CompoundTag)-Interna ab.
        byte[] bytes = writeCompressedToBytes(compound);
        return ItemStack.deserializeBytes(bytes);
    }

    private static Object serializeItem(ItemStack item) throws ReflectiveOperationException {
        // serializeAsBytes() und deserializeBytes() verwenden dasselbe Paper-NBT-Format
        // inklusive DataVersion. Für die Vanilla-Inventarliste wird DataVersion danach
        // wieder vom einzelnen Item entfernt, weil sie am Root der playerdata liegt.
        Object compound = readCompressed(item.serializeAsBytes());
        removeTag(compound, "DataVersion");
        return compound;
    }

    private static byte[] writeCompressedToBytes(Object root) throws ReflectiveOperationException {
        Class<?> nbtIo = Class.forName("net.minecraft.nbt.NbtIo");
        for (Method method : nbtIo.getDeclaredMethods()) {
            if (!method.getName().equals("writeCompressed") || !Modifier.isStatic(method.getModifiers())) continue;
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length != 2 || !parameters[0].isAssignableFrom(root.getClass())) continue;
            if (!OutputStream.class.isAssignableFrom(parameters[1])) continue;

            method.setAccessible(true);
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                invoke(method, null, root, output);
                return output.toByteArray();
            } catch (IOException impossible) {
                throw new ReflectiveOperationException(impossible);
            }
        }
        throw new NoSuchMethodException("NbtIo#writeCompressed(CompoundTag, OutputStream)");
    }

    private static Object readCompressed(byte[] bytes) throws ReflectiveOperationException {
        Class<?> nbtIo = Class.forName("net.minecraft.nbt.NbtIo");
        for (Method method : nbtIo.getDeclaredMethods()) {
            if (!method.getName().equals("readCompressed") || !Modifier.isStatic(method.getModifiers())) continue;
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length < 1 || !InputStream.class.isAssignableFrom(parameters[0])) continue;
            if (parameters.length > 2) continue;

            Object[] arguments = new Object[parameters.length];
            arguments[0] = new ByteArrayInputStream(bytes);
            if (parameters.length == 2) {
                Object accounter = createUnlimitedNbtAccounter(parameters[1]);
                if (accounter == null) continue;
                arguments[1] = accounter;
            }

            method.setAccessible(true);
            Object result = invoke(method, null, arguments);
            if (result != null) return result;
        }
        throw new NoSuchMethodException("NbtIo#readCompressed(InputStream[, NbtAccounter])");
    }

    private static Object createUnlimitedNbtAccounter(Class<?> accounterClass) throws ReflectiveOperationException {
        for (String methodName : List.of("unlimitedHeap", "unlimited")) {
            Method method = findCompatibleDeclaredMethod(accounterClass, methodName);
            if (method == null || !Modifier.isStatic(method.getModifiers())) continue;
            method.setAccessible(true);
            return invoke(method, null);
        }

        // Fallback für Versionen mit create(long) bzw. create(long, int).
        for (Method method : accounterClass.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || !method.getName().equals("create")) continue;
            Class<?>[] parameters = method.getParameterTypes();
            method.setAccessible(true);
            if (parameters.length == 1 && parameters[0] == long.class) {
                return invoke(method, null, Long.MAX_VALUE);
            }
            if (parameters.length == 2 && parameters[0] == long.class && parameters[1] == int.class) {
                return invoke(method, null, Long.MAX_VALUE, Integer.MAX_VALUE);
            }
        }
        return null;
    }

    private static Object createListTag(List<?> current) throws ReflectiveOperationException {
        Class<?> listClass = current != null ? current.getClass() : Class.forName("net.minecraft.nbt.ListTag");
        Constructor<?> constructor = listClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static Object createCompoundTag(Class<?> compoundClass) throws ReflectiveOperationException {
        Constructor<?> constructor = compoundClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static Object copyTag(Object tag) throws ReflectiveOperationException {
        Object copied = invokeCompatible(tag, "copy");
        if (copied == NO_METHOD || copied == null) copied = invokeCompatible(tag, "copyTag");
        if (copied == NO_METHOD || copied == null) throw new NoSuchMethodException(tag.getClass().getName() + "#copy");
        return copied;
    }

    private static void putTag(Object compound, String key, Object tag) throws ReflectiveOperationException {
        Object result = invokeCompatible(compound, "put", key, tag);
        if (result == NO_METHOD) throw new NoSuchMethodException("CompoundTag#put");
    }

    private static void removeTag(Object compound, String key) throws ReflectiveOperationException {
        Object result = invokeCompatible(compound, "remove", key);
        if (result == NO_METHOD) throw new NoSuchMethodException("CompoundTag#remove");
    }

    private static boolean isCompoundEmpty(Object compound) throws ReflectiveOperationException {
        Object result = invokeCompatible(compound, "isEmpty");
        return result instanceof Boolean value && value;
    }

    private static void saveRoot(OfflinePlayer player, Object root) throws ReflectiveOperationException, IOException {
        File file = dataFile(player);
        Path target = file.toPath();
        Path parent = target.getParent();
        if (parent == null) throw new IOException("Ungültiger playerdata-Pfad: " + target);
        Files.createDirectories(parent);

        Path temporary = parent.resolve(player.getUniqueId() + ".dat.novoria-tmp-" + UUID.randomUUID());
        Path backup = parent.resolve(player.getUniqueId() + ".dat_old");
        try {
            writeCompressed(root, temporary);
            if (Files.exists(target)) {
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeCompressed(Object root, Path path) throws ReflectiveOperationException, IOException {
        Class<?> nbtIo = Class.forName("net.minecraft.nbt.NbtIo");
        for (Method method : nbtIo.getDeclaredMethods()) {
            if (!method.getName().equals("writeCompressed") || !Modifier.isStatic(method.getModifiers())) continue;
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length != 2 || !parameters[0].isAssignableFrom(root.getClass())) continue;
            method.setAccessible(true);
            if (Path.class.isAssignableFrom(parameters[1])) {
                invoke(method, null, root, path);
                return;
            }
            if (OutputStream.class.isAssignableFrom(parameters[1])) {
                try (OutputStream output = Files.newOutputStream(path)) {
                    invoke(method, null, root, output);
                }
                return;
            }
        }
        throw new NoSuchMethodException("NbtIo#writeCompressed");
    }

    private static final Object NO_METHOD = new Object();

    private static Object invokeCompatible(Object target, String name, Object... arguments)
            throws ReflectiveOperationException {
        Method method = findCompatibleDeclaredMethod(target.getClass(), name, arguments);
        if (method == null) return NO_METHOD;
        method.setAccessible(true);
        return invoke(method, target, arguments);
    }

    private static Method findCompatibleDeclaredMethod(Class<?> start, String name, Object... arguments) {
        for (Class<?> type = start; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
                Class<?>[] parameterTypes = method.getParameterTypes();
                boolean compatible = true;
                for (int index = 0; index < parameterTypes.length; index++) {
                    if (!isCompatible(parameterTypes[index], arguments[index])) {
                        compatible = false;
                        break;
                    }
                }
                if (compatible) return method;
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> start, String name, int parameterCount) {
        for (Class<?> type = start; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameterCount) return method;
            }
        }
        return null;
    }

    private static boolean isCompatible(Class<?> parameterType, Object argument) {
        if (argument == null) return !parameterType.isPrimitive();
        return box(parameterType).isAssignableFrom(argument.getClass());
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private static Object unwrapOptional(Object value) {
        return value instanceof Optional<?> optional ? optional.orElse(null) : value;
    }

    private static Object invoke(Method method, Object target, Object... arguments)
            throws ReflectiveOperationException {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof ReflectiveOperationException reflective) throw reflective;
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new ReflectiveOperationException(cause);
        }
    }

    private static IOException asIOException(String message, Throwable cause) {
        return cause instanceof IOException io
                ? io
                : new IOException(message + ": " + cause.getMessage(), cause);
    }

    private static ItemStack cloneOrNull(ItemStack item) {
        return item == null ? null : item.clone();
    }
}
