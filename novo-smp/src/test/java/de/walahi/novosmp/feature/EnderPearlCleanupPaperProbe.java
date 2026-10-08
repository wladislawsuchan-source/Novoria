package de.walahi.novosmp.feature;

import de.walahi.novosmp.NovoSMPPlugin;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import java.lang.reflect.*;
import java.util.*;

/** Isolated Paper integration probe; never included in the production plugin. */
public final class EnderPearlCleanupPaperProbe extends JavaPlugin implements Listener {
    private PerformanceCleanupManager cleanup;
    private World world;
    private Player player;
    private Object nativeOwner;
    private Object nativeLevel;
    private int teleports;
    private final List<Entity> created = new ArrayList<>();

    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                setup();
                clear(false);
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    try {
                        require(teleports == 1 && player.getLocation().getX() > 2, "normal player teleport after manual clear");
                        getLogger().info("MANUAL_CLEAR_AND_NATIVE_PEARL_TELEPORT=PASS");
                        clear(true);
                        Bukkit.getScheduler().runTaskLater(this, () -> {
                            try {
                                require(teleports == 2 && player.getLocation().getX() > 6, "normal player teleport after automatic clear");
                                getLogger().info("AUTO_CLEAR_AND_NATIVE_PEARL_TELEPORT=PASS");
                                getLogger().info("ENDER_PEARL_ENTITY_CLEAR_PAPER_26_2=PASS");
                                finish(null);
                            } catch (Throwable failure) { finish(failure); }
                        }, 25L);
                    } catch (Throwable failure) { finish(failure); }
                }, 25L);
            } catch (Throwable failure) { finish(failure); }
        }, 40L);
    }

    private void setup() throws Exception {
        NovoSMPPlugin smp = (NovoSMPPlugin) Bukkit.getPluginManager().getPlugin("NovoSMP");
        require(smp != null && smp.isEnabled(), "production enabled");
        cleanup = (PerformanceCleanupManager) field(smp, "performanceCleanupManager").get(smp);
        world = Bukkit.getWorlds().getFirst();
        require(smp.isSmpGameplayWorld(world), "clear applies to probe world");
        Object server = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
        Object level = world.getClass().getMethod("getHandle").invoke(world);
        Class<?> profileType = type("com.mojang.authlib.GameProfile");
        Object profile = profileType.getConstructor(UUID.class, String.class).newInstance(UUID.randomUUID(), "PearlProbe");
        Class<?> infoType = type("net.minecraft.server.level.ClientInformation");
        Class<?> ownerType = type("net.minecraft.server.level.ServerPlayer");
        Object owner = ownerType.getConstructor(type("net.minecraft.server.MinecraftServer"),
                type("net.minecraft.server.level.ServerLevel"), profileType, infoType)
                .newInstance(server, level, profile, infoType.getMethod("createDefault").invoke(null));
        nativeOwner = owner;
        nativeLevel = level;
        Class<?> flow = type("net.minecraft.network.protocol.PacketFlow");
        Class<?> transportType = type("net.minecraft.network.Connection");
        Object transport = transportType.getConstructor(flow).newInstance(flow.getField("SERVERBOUND").get(null));
        Object handlers = Array.newInstance(type("io.netty.channel.ChannelHandler"), 0);
        Object channel = type("io.netty.channel.embedded.EmbeddedChannel").getConstructor(handlers.getClass()).newInstance(handlers);
        transportType.getField("channel").set(transport, channel);
        Class<?> cookie = type("net.minecraft.server.network.CommonListenerCookie");
        Object connection = type("net.minecraft.server.network.ServerGamePacketListenerImpl")
                .getConstructor(type("net.minecraft.server.MinecraftServer"), transportType, ownerType, cookie)
                .newInstance(server, transport, owner, cookie.getMethod("createInitial", profileType, boolean.class).invoke(null, profile, false));
        ownerType.getField("connection").set(owner, connection);
        ownerType.getField("joining").setBoolean(owner, false);
        level.getClass().getMethod("addNewPlayer", ownerType).invoke(level, owner);
        player = (Player) ownerType.getMethod("getBukkitEntity").invoke(owner);
        player.getInventory().setItemInMainHand(new ItemStack(Material.FISHING_ROD));
        created.add(player);
        Bukkit.getPluginManager().registerEvents(this, this);
        require(player.teleport(new Location(world, .5, 103, .5)), "place player in isolated test area");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) {
        if (event.getPlayer().getUniqueId().equals(player.getUniqueId())
                && event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL) teleports++;
    }

    private void clear(boolean automatic) throws Exception {
        double x = automatic ? 6.5 : 2.5;
        world.getChunkAt((int) x >> 4, 0).load();
        world.getBlockAt((int) x, 100, 0).setType(Material.STONE);
        EnderPearl active = spawn(new Location(world, x, 103, .5), EnderPearl.class);
        active.setShooter(player);
        active.setVelocity(new Vector(0, -1, 0));
        // An ownerless, very old pearl must also be protected without player/distance/PDC conditions.
        EnderPearl old = spawn(new Location(world, x, 110, .5), EnderPearl.class);
        old.setGravity(false);
        old.setVelocity(new Vector());
        old.setTicksLived(1201);
        Item drop = world.spawn(new Location(world, x, 105, .5), Item.class, CreatureSpawnEvent.SpawnReason.DEFAULT,
                item -> item.setItemStack(new ItemStack(Material.ENDER_PEARL)));
        created.add(drop);
        List<Projectile> aged = new ArrayList<>();
        for (Class<? extends Projectile> kind : List.of(Arrow.class, Snowball.class, Egg.class, Trident.class)) {
            Projectile projectile = spawn(new Location(world, x, 110, .5), kind);
            projectile.setTicksLived(1201);
            aged.add(projectile);
        }
        Arrow fresh = spawn(new Location(world, x, 110, .5), Arrow.class);
        FishHook hook = spawnHook(new Location(world, x, 110, .5));
        hook.setTicksLived(1201);
        ArmorStand protectedStand = spawn(new Location(world, x, 110, .5), ArmorStand.class);
        if (automatic) {
            long interval = field(cleanup, "intervalSeconds").getLong(cleanup);
            field(cleanup, "remainingSeconds").setLong(cleanup, 1L);
            Method tick = PerformanceCleanupManager.class.getDeclaredMethod("tick");
            tick.setAccessible(true);
            tick.invoke(cleanup);
            require(field(cleanup, "remainingSeconds").getLong(cleanup) == interval, "existing automatic interval reset");
        } else {
            require(Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "entityclear"), "registered manual command executed");
        }
        require(active.isValid() && old.isValid(), "fresh thrown and ownerless aged pearls survive clear");
        require(!drop.isValid(), "normal dropped ender-pearl item still removed");
        require(aged.stream().noneMatch(Entity::isValid), "aged arrows/snowballs/eggs/tridents still removed");
        require(hook.isValid(), "aged fishing hook survives clear");
        require(fresh.isValid() && protectedStand.isValid(), "young-projectile age rule and existing technical protection unchanged");
        hook.remove(); fresh.remove(); protectedStand.remove(); old.remove();
        getLogger().info((automatic ? "AUTO" : "MANUAL") + "_PEARL_DROP_OTHER_PROJECTILE_POLICY=PASS");
    }

    private FishHook spawnHook(Location at) throws Exception {
        Class<?> hookType = type("net.minecraft.world.entity.projectile.FishingHook");
        Object nativeHook = hookType.getConstructor(type("net.minecraft.world.entity.player.Player"),
                        type("net.minecraft.world.level.Level"), int.class, int.class)
                .newInstance(nativeOwner, nativeLevel, 0, 0);
        hookType.getMethod("setPos", double.class, double.class, double.class)
                .invoke(nativeHook, at.getX(), at.getY(), at.getZ());
        type("net.minecraft.server.level.ServerLevel")
                .getMethod("addFreshEntity", type("net.minecraft.world.entity.Entity"))
                .invoke(nativeLevel, nativeHook);
        FishHook hook = (FishHook) hookType.getMethod("getBukkitEntity").invoke(nativeHook);
        require(hook.isValid(), "real fishing hook added to world");
        created.add(hook);
        return hook;
    }

    private <T extends Entity> T spawn(Location at, Class<T> kind) {
        // Bukkit's default CUSTOM reason already has existing player-relevance protection.
        // Vanilla projectiles and drops instead use DEFAULT; do not alter that existing rule.
        T entity = world.spawn(at, kind, CreatureSpawnEvent.SpawnReason.DEFAULT);
        created.add(entity);
        return entity;
    }
    private void finish(Throwable failure) {
        if (failure != null) getLogger().log(java.util.logging.Level.SEVERE, "ENDER_PEARL_ENTITY_CLEAR_PAPER_26_2=FAIL", failure);
        created.forEach(entity -> { if (!(entity instanceof Player) && entity.isValid()) entity.remove(); });
        Bukkit.shutdown();
    }
    private static Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name); }
    private static Field field(Object owner, String name) throws NoSuchFieldException {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void require(boolean okay, String reason) { if (!okay) throw new AssertionError(reason); }
}
