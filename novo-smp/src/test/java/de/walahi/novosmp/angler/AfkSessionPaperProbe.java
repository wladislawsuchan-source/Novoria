package de.walahi.novosmp.angler;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.novosmp.afk.AfkManager;
import de.walahi.novosmp.enchants.CustomEnchantmentService;
import de.walahi.novosmp.lumi.AfkZoneManager;
import de.walahi.novosmp.professions.ProfessionManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

/** Runs only on an isolated Paper server with NovoSMP and its normal dependencies. */
public final class AfkSessionPaperProbe extends JavaPlugin implements Listener {
    private NovoSMPPlugin smp;
    private AnglerFishingService fishing;
    private ProfessionManager professions;
    private CustomEnchantmentService enchants;
    private Player player;
    private Object owner;
    private Object level;
    private Object nativeHook;
    private FishHook hook;
    private World world;
    private Map<UUID, Object> playersByUuid;
    private int bites;
    private int seconds;
    private boolean stopped;

    @Override public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                setup();
                castAndAfk(false);
                if (System.getProperty("afk.probe.phase", "fixed").equals("diagnose")) {
                    for (int tick = 1; tick <= 1200 && active(); tick++) {
                        tickHook();
                        if (tick % 20 == 0) fishing.fishingHud(player);
                    }
                    require(!active(), "old session termination reproduced");
                    require(smp.getAfkManager().isAfk(player), "global AFK stayed true");
                    getLogger().info("AFK_OLD_STOP_REPRODUCED=PASS (see STOP reason above)");
                    finish(null);
                } else {
                    Bukkit.getScheduler().runTaskTimer(this, () -> {
                        if (stopped) return;
                        try {
                            seconds++;
                            require(active() && hook.isValid(), "session alive at second " + seconds);
                            require(smp.getAfkManager().isAfk(player), "global AFK stays true");
                            Component hud = fishing.fishingHud(player);
                            String text = PlainTextComponentSerializer.plainText().serialize(hud);
                            require(text.contains("AFK-Angeln") && !text.toLowerCase().contains("test")
                                    && !text.toLowerCase().contains("debug") && !text.contains("%"), "clean fishing HUD");
                            if (seconds >= 70) {
                                require(bites >= 4, "multiple real BITE/catch/reset cycles");
                                getLogger().info("AFK_70_SECONDS_STATIONARY=PASS bites=" + bites);
                                checkExits();
                                finish(null);
                            }
                        } catch (Throwable failure) { finish(failure); }
                    }, 20L, 20L);
                }
            } catch (Throwable failure) { finish(failure); }
        }, 40L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFish(PlayerFishEvent event) {
        if (player != null && event.getPlayer().getUniqueId().equals(player.getUniqueId())
                && event.getState() == PlayerFishEvent.State.BITE) bites++;
    }

    @SuppressWarnings("unchecked")
    private void setup() throws Exception {
        smp = (NovoSMPPlugin) Bukkit.getPluginManager().getPlugin("NovoSMP");
        require(smp != null && smp.isEnabled(), "production NovoSMP enabled");
        smp.configs().main().set("debug.afk-fishing", true);
        fishing = (AnglerFishingService) field(smp, "anglerFishingService");
        professions = (ProfessionManager) field(smp, "professionManager");
        enchants = (CustomEnchantmentService) field(smp, "customEnchantments");
        world = Bukkit.getWorlds().getFirst();
        level = world.getClass().getMethod("getHandle").invoke(world);
        Object server = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
        Class<?> serverType = Class.forName("net.minecraft.server.MinecraftServer");
        Class<?> levelType = Class.forName("net.minecraft.server.level.ServerLevel");
        Class<?> profileType = Class.forName("com.mojang.authlib.GameProfile");
        Object profile = profileType.getConstructor(UUID.class, String.class).newInstance(UUID.randomUUID(), "AfkSessionProbe");
        Class<?> infoType = Class.forName("net.minecraft.server.level.ClientInformation");
        Class<?> playerType = Class.forName("net.minecraft.server.level.ServerPlayer");
        owner = playerType.getConstructor(serverType, levelType, profileType, infoType)
                .newInstance(server, level, profile, infoType.getMethod("createDefault").invoke(null));
        Class<?> flowType = Class.forName("net.minecraft.network.protocol.PacketFlow");
        Object flow = flowType.getField("SERVERBOUND").get(null);
        Class<?> connectionType = Class.forName("net.minecraft.network.Connection");
        Object connection = connectionType.getConstructor(flowType).newInstance(flow);
        Class<?> cookieType = Class.forName("net.minecraft.server.network.CommonListenerCookie");
        Object cookie = cookieType.getMethod("createInitial", profileType, boolean.class).invoke(null, profile, false);
        Object listener = Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl")
                .getConstructor(serverType, connectionType, playerType, cookieType)
                .newInstance(server, connection, owner, cookie);
        playerType.getField("connection").set(owner, listener);
        Object playerList = serverType.getMethod("getPlayerList").invoke(server);
        playersByUuid = (Map<UUID, Object>) playerList.getClass().getMethod("getPlayersByUUID").invoke(playerList);
        player = (Player) playerType.getMethod("getBukkitEntity").invoke(owner);
        playersByUuid.put(player.getUniqueId(), owner); // Resolve through real Bukkit.getPlayer; no client/player tick.
        require(player.isOnline(), "test player resolves as online");
        playerType.getMethod("snapTo", double.class, double.class, double.class, float.class, float.class)
                .invoke(owner, 0.5D, 100D, -3D, 0F, 0F);
        player.setGameMode(GameMode.SURVIVAL);
        professions.state(player.getUniqueId()).activeProfessions().add("angler");
        world.getChunkAt(0, 0).load();
        world.addPluginChunkTicket(0, 0, this);
        world.addPluginChunkTicket(0, -1, this);
        world.setChunkForceLoaded(0, 0, true);
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            world.getBlockAt(x, 97, z).setType(Material.STONE);
            world.getBlockAt(x, 98, z).setType(Material.WATER);
            world.getBlockAt(x, 99, z).setType(Material.WATER);
        }
    }

    private void castAndAfk(boolean breakingRod) throws Exception {
        if (smp.getAfkManager().isAfk(player)) smp.getAfkManager().toggle(player);
        ItemStack rod = new ItemStack(Material.FISHING_ROD);
        require(enchants.apply(rod, "ausdauer", 5), "Ausdauer V on test rod");
        if (breakingRod) {
            Damageable meta = (Damageable) rod.getItemMeta();
            meta.setDamage(rod.getType().getMaxDurability() - 1);
            rod.setItemMeta(meta);
        }
        player.getInventory().setItemInMainHand(rod);
        Class<?> hookType = Class.forName("net.minecraft.world.entity.projectile.FishingHook");
        nativeHook = hookType.getConstructor(Class.forName("net.minecraft.world.entity.player.Player"),
                Class.forName("net.minecraft.world.level.Level"), int.class, int.class)
                .newInstance(owner, level, 0, 0);
        hook = (FishHook) hookType.getMethod("getBukkitEntity").invoke(nativeHook);
        hookType.getMethod("setPos", double.class, double.class, double.class).invoke(nativeHook, 0.5D, 99.5D, 0.5D);
        hook.setVelocity(new Vector());
        level.getClass().getMethod("addFreshEntity", Class.forName("net.minecraft.world.entity.Entity"))
                .invoke(level, nativeHook);
        fishing.onFish(new PlayerFishEvent(player, null, hook, EquipmentSlot.HAND, PlayerFishEvent.State.FISHING));
        for (int tick = 0; tick < 100 && !(hook.getState() == FishHook.HookState.BOBBING && hook.isInWater()); tick++)
            tickHook();
        require(hook.getState() == FishHook.HookState.BOBBING && hook.isInWater(), "cast settled in water");
        require(smp.getAfkManager().toggle(player), "global AFK activated");
        require(active(), "real session started");
    }

    private void checkExits() throws Exception {
        // Lifecycle events without player input must keep both fishing and global AFK.
        fishing.onFish(new PlayerFishEvent(player, null, hook, PlayerFishEvent.State.LURED));
        fishing.onFish(new PlayerFishEvent(player, null, hook, PlayerFishEvent.State.FAILED_ATTEMPT));
        require(active() && smp.getAfkManager().isAfk(player), "internal fishing events preserve AFK");
        AfkZoneManager hud = (AfkZoneManager) field(smp, "lumiAfkZoneManager");
        Method balance = AfkZoneManager.class.getDeclaredMethod("balanceHud", Player.class);
        balance.setAccessible(true);
        String lumi = PlainTextComponentSerializer.plainText().serialize((Component) balance.invoke(hud, player));
        require(lumi.startsWith("✦ ") && lumi.endsWith(" Lumis") && !lumi.toLowerCase().contains("test"), "normal Lumi HUD");
        getLogger().info("AFK_CLEAN_LUMI_AND_FISHING_HUD=PASS");

        ((AfkManager) smp.getAfkManager()).onMove(new PlayerMoveEvent(player, player.getLocation(),
                player.getLocation().add(1, 0, 0)));
        ended("MOVEMENT");
        castAndAfk(false);
        smp.getAfkManager().toggle(player);
        ended("AFK_OFF");
        castAndAfk(false);
        hook.remove();
        ended("HOOK_REMOVED");
        castAndAfk(false);
        fishing.onFish(new PlayerFishEvent(player, null, hook, EquipmentSlot.HAND, PlayerFishEvent.State.REEL_IN));
        ended("MANUAL_REEL");
        castAndAfk(false);
        fishing.onDeath(new PlayerDeathEvent(player, DamageSource.builder(DamageType.GENERIC).build(),
                new ArrayList<>(), 0, Component.empty(), true));
        ended("DEATH");
        castAndAfk(false);
        fishing.onWorld(new PlayerChangedWorldEvent(player, world));
        ended("WORLD_CHANGE");
        castAndAfk(false);
        fishing.onQuit(new PlayerQuitEvent(player, Component.empty()));
        ended("QUIT");
        castAndAfk(true);
        hook.setTimeUntilBite(1);
        for (int tick = 0; tick < 100 && active(); tick++) tickHook();
        ended("ROD_BREAK");
    }

    private void tickHook() throws Exception { nativeHook.getClass().getMethod("tick").invoke(nativeHook); }

    private boolean active() throws Exception {
        return ((Map<?, ?>) field(fishing, "sessions")).containsKey(player.getUniqueId());
    }

    private void ended(String reason) throws Exception {
        require(!active() && !hook.isValid(), "cleanup " + reason);
        require(fishing.fishingHud(player) == null, "AFK HUD removed " + reason);
        getLogger().info("AFK_EXIT_" + reason + "=PASS");
    }

    private void finish(Throwable failure) {
        if (stopped) return;
        stopped = true;
        if (failure == null) getLogger().info("AFK_SESSION_RESULT=PASS");
        else getLogger().log(java.util.logging.Level.SEVERE, "AFK_SESSION_RESULT=FAIL", failure);
        if (hook != null && hook.isValid()) hook.remove();
        if (playersByUuid != null && player != null) playersByUuid.remove(player.getUniqueId());
        Bukkit.shutdown();
    }

    private static Object field(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
