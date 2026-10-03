package de.walahi.novosmp.feature;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.novosmp.angler.AnglerLootFoundation;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.*;
import java.util.*;

/** Runs real container-click packets on an isolated Paper 26.2 server, not in the shipped JAR. */
public final class DragonEggBundlePaperProbe extends JavaPlugin implements Listener {
    private Player player;
    private Object owner, connection, channel;
    private InventoryClickEvent last;
    private int cases;
    private Listener restriction;

    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                setup();
                // Reproduce the original defect through both native insertion methods.
                InventoryClickEvent.getHandlerList().unregister(restriction);
                insert(Material.BUNDLE, false, new ItemStack(Material.DRAGON_EGG), List.of(), false);
                insert(Material.BUNDLE, true, new ItemStack(Material.DRAGON_EGG), List.of(), false);
                Bukkit.getPluginManager().registerEvents(restriction, Bukkit.getPluginManager().getPlugin("NovoSMP"));
                getLogger().info("ORIGINAL_BOTH_DIRECTIONS_REPRODUCED=PASS");
                for (Material material : Material.values()) {
                    if (material != Material.BUNDLE && !material.name().endsWith("_BUNDLE")) continue;
                    for (boolean bundleCursor : new boolean[]{false, true}) {
                        insert(material, bundleCursor, new ItemStack(Material.DRAGON_EGG), List.of(), true);
                        insert(material, bundleCursor, new ItemStack(Material.DRAGON_EGG, 2),
                                List.of(new ItemStack(Material.COBBLESTONE, 63)), true);
                        insert(material, bundleCursor, new ItemStack(Material.COBBLESTONE, 8), List.of(), false);
                    }
                }
                ItemStack custom = new ItemStack(Material.PAPER, 3);
                custom.editMeta(meta -> meta.getPersistentDataContainer().set(new NamespacedKey(this, "custom"),
                        org.bukkit.persistence.PersistentDataType.STRING, "preserve-me"));
                for (boolean direction : new boolean[]{false, true})
                    insert(Material.BUNDLE, direction, custom, List.of(), false);
                insert(Material.BUNDLE, false, new ItemStack(Material.COBBLESTONE, 8),
                        List.of(new ItemStack(Material.COBBLESTONE, 63)), false);
                for (boolean direction : new boolean[]{false, true}) rightClick(direction);
                otherInputs();
                require(AnglerLootFoundation.bundleContents(AnglerLootFoundation.createBundle(List.of(custom))).getFirst()
                        .equals(custom), "Angler reward construction and custom data preserved");
                getLogger().info("DRAGON_EGG_BUNDLE_PAPER_26_2=PASS cases=" + cases);
            } catch (Throwable failure) {
                getLogger().log(java.util.logging.Level.SEVERE, "DRAGON_EGG_BUNDLE_PAPER_26_2=FAIL", failure);
            } finally {
                if (player != null) { player.getInventory().clear(); player.setItemOnCursor(null); }
                Bukkit.shutdown();
            }
        }, 40L);
    }

    private void setup() throws Exception {
        require(Bukkit.getPluginManager().getPlugin("NovoSMP") instanceof NovoSMPPlugin smp && smp.isEnabled(), "production enabled");
        for (var registered : InventoryClickEvent.getHandlerList().getRegisteredListeners())
            if (registered.getListener() instanceof DragonEggBundleListener) restriction = registered.getListener();
        require(restriction != null, "production listener registered");
        Bukkit.getPluginManager().registerEvents(this, this);
        Object server = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
        Object level = Bukkit.getWorlds().getFirst().getClass().getMethod("getHandle").invoke(Bukkit.getWorlds().getFirst());
        Class<?> profileType = type("com.mojang.authlib.GameProfile");
        Object profile = profileType.getConstructor(UUID.class, String.class).newInstance(UUID.randomUUID(), "BundleProbe");
        Class<?> infoType = type("net.minecraft.server.level.ClientInformation");
        owner = type("net.minecraft.server.level.ServerPlayer").getConstructor(type("net.minecraft.server.MinecraftServer"),
                type("net.minecraft.server.level.ServerLevel"), profileType, infoType)
                .newInstance(server, level, profile, infoType.getMethod("createDefault").invoke(null));
        Class<?> flow = type("net.minecraft.network.protocol.PacketFlow");
        Object transport = type("net.minecraft.network.Connection").getConstructor(flow).newInstance(flow.getField("SERVERBOUND").get(null));
        Class<?> handlers = Array.newInstance(type("io.netty.channel.ChannelHandler"), 0).getClass();
        channel = type("io.netty.channel.embedded.EmbeddedChannel").getConstructor(handlers)
                .newInstance(Array.newInstance(type("io.netty.channel.ChannelHandler"), 0));
        type("net.minecraft.network.Connection").getField("channel").set(transport, channel);
        Class<?> cookie = type("net.minecraft.server.network.CommonListenerCookie");
        connection = type("net.minecraft.server.network.ServerGamePacketListenerImpl")
                .getConstructor(type("net.minecraft.server.MinecraftServer"), type("net.minecraft.network.Connection"), owner.getClass(), cookie)
                .newInstance(server, transport, owner, cookie.getMethod("createInitial", profileType, boolean.class).invoke(null, profile, false));
        owner.getClass().getField("connection").set(owner, connection);
        owner.getClass().getField("valid").setBoolean(owner, true);
        owner.getClass().getField("joining").setBoolean(owner, false);
        player = (Player) owner.getClass().getMethod("getBukkitEntity").invoke(owner);
        require(!(boolean) owner.getClass().getMethod("isImmobile").invoke(owner), "actor can click");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void observe(InventoryClickEvent event) {
        if (event.getWhoClicked().getUniqueId().equals(player.getUniqueId())) last = event;
    }

    private void insert(Material material, boolean bundleCursor, ItemStack item, List<ItemStack> seed, boolean blocked) throws Exception {
        ItemStack bundle = AnglerLootFoundation.createBundle(seed);
        bundle.setType(material);
        InventoryView view = prepare(bundleCursor ? bundle : item, bundleCursor ? item : bundle);
        ItemStack beforeCursor = view.getCursor().clone(), beforeSlot = view.getItem(9).clone();
        drainMessages();
        click(9, 0, "PICKUP");
        require(last != null && last.getAction().name().endsWith("INTO_BUNDLE"), "native insert action");
        require(last.isCancelled() == blocked, "cancel only eggs: " + last.getAction());
        if (blocked) {
            require(beforeCursor.equals(view.getCursor()) && beforeSlot.equals(view.getItem(9)), "both complete stacks unchanged");
            require(drainMessages() == 1, "exactly one rejection message");
        } else {
            ItemStack result = bundleCursor ? view.getCursor() : view.getItem(9);
            var contents = result.getData(io.papermc.paper.datacomponent.DataComponentTypes.BUNDLE_CONTENTS).contents();
            require(contents.stream().anyMatch(stored -> stored.isSimilar(item)), "native insertion including custom metadata succeeds");
            require(drainMessages() == 0, "no rejection for permitted items");
            if (!seed.isEmpty() && item.getType() == Material.COBBLESTONE)
                require(view.getCursor().getAmount() == 7, "vanilla capacity keeps the remaining seven items");
        }
        cases++;
    }

    private InventoryView prepare(ItemStack cursor, ItemStack slot) {
        player.getInventory().clear();
        InventoryView view = player.getOpenInventory();
        view.setCursor(cursor.clone());
        view.setItem(9, slot.clone());
        last = null;
        return view;
    }

    private void rightClick(boolean bundleCursor) throws Exception {
        InventoryView view = prepare(new ItemStack(bundleCursor ? Material.BUNDLE : Material.DRAGON_EGG),
                new ItemStack(bundleCursor ? Material.DRAGON_EGG : Material.BUNDLE));
        click(9, 1, "PICKUP");
        require(last != null && !last.isCancelled(), "26.2 right click is a swap, not insertion");
        ItemStack bundle = view.getCursor().getType() == Material.BUNDLE ? view.getCursor() : view.getItem(9);
        require(AnglerLootFoundation.bundleContents(bundle).isEmpty(), "right click never stores egg");
        require((view.getCursor().getType() == Material.DRAGON_EGG ? view.getCursor().getAmount() : 0)
                + (view.getItem(9).getType() == Material.DRAGON_EGG ? view.getItem(9).getAmount() : 0) == 1, "right click preserves egg");
        cases++;
    }

    private void otherInputs() throws Exception {
        InventoryView view = prepare(new ItemStack(Material.DRAGON_EGG), new ItemStack(Material.BUNDLE));
        click(-999, 0, "QUICK_CRAFT"); click(9, 1, "QUICK_CRAFT"); click(-999, 2, "QUICK_CRAFT");
        require(AnglerLootFoundation.bundleContents(view.getItem(9)).isEmpty() && view.getCursor().getAmount() == 1,
                "drag cannot insert into an occupied bundle slot");
        prepare(new ItemStack(Material.AIR), new ItemStack(Material.BUNDLE));
        player.getInventory().setItem(0, new ItemStack(Material.DRAGON_EGG));
        click(9, 0, "SWAP");
        require(last != null && !last.isCancelled() && view.getItem(9).getType() == Material.DRAGON_EGG
                && AnglerLootFoundation.bundleContents(player.getInventory().getItem(0)).isEmpty(), "hotbar swaps whole stacks");
        Inventory chest = Bukkit.createInventory(player, 27);
        view = player.openInventory(chest);
        chest.setItem(0, new ItemStack(Material.BUNDLE));
        player.getInventory().setItem(9, new ItemStack(Material.DRAGON_EGG));
        click(27, 0, "QUICK_MOVE");
        require(last != null && !last.isCancelled() && AnglerLootFoundation.bundleContents(chest.getItem(0)).isEmpty()
                && chest.getItem(1).getType() == Material.DRAGON_EGG, "shift click moves to a free slot, not bundle contents");
        // The same registered guard covers top-inventory slots, not just player slots.
        chest.setItem(0, new ItemStack(Material.BUNDLE));
        view.setCursor(new ItemStack(Material.DRAGON_EGG));
        click(0, 0, "PICKUP");
        require(last.isCancelled() && view.getCursor().getType() == Material.DRAGON_EGG
                && AnglerLootFoundation.bundleContents(chest.getItem(0)).isEmpty(), "chest top-slot insertion blocked");
        cases += 4;
    }

    private void click(int slot, int button, String input) throws Exception {
        Object menu = owner.getClass().getField("containerMenu").get(owner);
        Class<?> inputType = type("net.minecraft.world.inventory.ContainerInput");
        Class<?> packetType = type("net.minecraft.network.protocol.game.ServerboundContainerClickPacket");
        Object packet = packetType.getConstructors()[0].newInstance(menu.getClass().getField("containerId").getInt(menu),
                menu.getClass().getMethod("getStateId").invoke(menu), (short) slot, (byte) button, inputType.getField(input).get(null),
                type("it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap").getConstructor().newInstance(),
                type("net.minecraft.network.HashedStack").getField("EMPTY").get(null));
        try { connection.getClass().getMethod("handleContainerClick", packetType).invoke(connection, packet); }
        catch (InvocationTargetException exception) { throw new RuntimeException(exception.getCause()); }
    }

    private int drainMessages() throws Exception {
        channel.getClass().getMethod("runPendingTasks").invoke(channel);
        int messages = 0;
        Object packet;
        while ((packet = channel.getClass().getMethod("readOutbound").invoke(channel)) != null) {
            if (!packet.getClass().getSimpleName().equals("ClientboundSystemChatPacket")) continue;
            Object content = packet.getClass().getMethod("content").invoke(packet);
            String text = (String) type("net.minecraft.network.chat.Component").getMethod("getString").invoke(content);
            if (text.contains("Das Drachenei kann nicht in einem Bundle verstaut werden.")) messages++;
        }
        return messages;
    }
    private static Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name); }
    private static void require(boolean okay, String reason) { if (!okay) throw new AssertionError(reason); }
}
