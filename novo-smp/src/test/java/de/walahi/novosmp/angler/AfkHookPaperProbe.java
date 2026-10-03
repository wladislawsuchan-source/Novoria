package de.walahi.novosmp.angler;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.UUID;

/** Isolated Paper 26.2 probe. Reflection is test-only; production uses Bukkit APIs. */
public final class AfkHookPaperProbe extends JavaPlugin implements Listener {
    private FishHook testingHook;
    private int bites;
    private int interval;
    private boolean resolving;

    @Override public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                runChecks();
                getLogger().info("AFK_PAPER_RESULT=PASS");
            } catch (Throwable error) {
                getLogger().log(java.util.logging.Level.SEVERE, "AFK_PAPER_RESULT=FAIL", error);
            } finally {
                if (testingHook != null) testingHook.remove();
                Bukkit.shutdown();
            }
        });
    }

    @EventHandler public void onBite(PlayerFishEvent event) {
        if (testingHook == null || !event.getHook().getUniqueId().equals(testingHook.getUniqueId())) return;
        if (event.getState() == PlayerFishEvent.State.BITE) {
            event.setCancelled(true);
            require(!resolving && testingHook.getTimeUntilBite() <= 0, "BITE state");
            resolving = true;
            bites++;
            AnglerFishingService.prepareAfkHook(testingHook, interval);
            resolving = false;
        } else if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
            throw new AssertionError("Second catch source");
        }
    }

    private void runChecks() throws Exception {
        World world = Bukkit.getWorlds().getFirst();
        Object level = world.getClass().getMethod("getHandle").invoke(world);
        Object server = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
        Class<?> serverClass = Class.forName("net.minecraft.server.MinecraftServer");
        Class<?> levelClass = Class.forName("net.minecraft.server.level.ServerLevel");
        Class<?> profileClass = Class.forName("com.mojang.authlib.GameProfile");
        Object profile = profileClass.getConstructor(UUID.class, String.class)
                .newInstance(UUID.randomUUID(), "AfkHookProbe");
        Class<?> clientClass = Class.forName("net.minecraft.server.level.ClientInformation");
        Object info = clientClass.getMethod("createDefault").invoke(null);
        Object owner = Class.forName("net.minecraft.server.level.ServerPlayer")
                .getConstructor(serverClass, levelClass, profileClass, clientClass)
                .newInstance(server, level, profile, info);
        Player player = (Player) owner.getClass().getMethod("getBukkitEntity").invoke(owner);
        player.getInventory().setItemInMainHand(new ItemStack(Material.FISHING_ROD));
        Class<?> hookClass = Class.forName("net.minecraft.world.entity.projectile.FishingHook");
        Object nativeHook = hookClass.getConstructor(Class.forName("net.minecraft.world.entity.player.Player"),
                        Class.forName("net.minecraft.world.level.Level"), int.class, int.class)
                .newInstance(owner, level, 0, 5); // Lure V on the native hook.
        testingHook = (FishHook) hookClass.getMethod("getBukkitEntity").invoke(nativeHook);
        world.getChunkAt(0, 0).load();
        world.addPluginChunkTicket(0, 0, this);
        hookClass.getMethod("setPos", double.class, double.class, double.class)
                .invoke(nativeHook, 0.5D, 100D, 0.5D);
        levelClass.getMethod("addFreshEntity", Class.forName("net.minecraft.world.entity.Entity"))
                .invoke(level, nativeHook);
        require(testingHook.isValid(), "real hook added to world");
        UUID sameEntity = testingHook.getUniqueId();
        Class<?> positionClass = Class.forName("net.minecraft.core.BlockPos");
        Object position = positionClass.getConstructor(int.class, int.class, int.class).newInstance(0, 100, 0);
        Method tickFishing = hookClass.getDeclaredMethod("catchingFish", positionClass);
        tickFishing.setAccessible(true);

        int scenario = 0;
        for (int seconds : new int[]{30, 27, 24, 21, 18, 15, 15}) {
            interval = seconds;
            bites = 0;
            boolean rainAndRoof = scenario++ % 2 == 0;
            world.setStorm(rainAndRoof);
            world.getBlockAt(0, 101, 0).setType(rainAndRoof ? Material.STONE : Material.AIR);
            AnglerFishingService.prepareAfkHook(testingHook, interval);
            require(!testingHook.getApplyLure() && !testingHook.isRainInfluenced()
                    && !testingHook.isSkyInfluenced(), "modifiers disabled");
            require(testingHook.getWaitTime() == 0, "random idle wait overridden after reset");
            for (int tick = 1; tick <= seconds * 20 * 3; tick++) {
                tickFishing.invoke(nativeHook, position);
                int remaining = seconds * 20 - tick % (seconds * 20);
                require(testingHook.getTimeUntilBite() == remaining, "real approach countdown tick " + tick);
                require(AnglerFishingService.afkSecondsRemaining(testingHook) == (remaining + 19L) / 20L,
                        "HUD follows actual timer");
                require(bites == tick / (seconds * 20), "one BITE per interval");
                require(sameEntity.equals(testingHook.getUniqueId()) && testingHook.isValid(), "same hook survives");
            }
            getLogger().info("AFK_REAL_HOOK_" + seconds + "S=PASS (3 cycles, Lure V, weather/roof variants)");
        }
        checkDamageAndMending(player);
        testingHook.remove();
        AnglerFishingService.prepareAfkHook(testingHook, 15);
        require(!testingHook.isValid(), "removed hook cannot restart");
        require(!AnglerFishingService.playerFishingAction(PlayerFishEvent.State.BITE, null)
                && !AnglerFishingService.playerFishingAction(PlayerFishEvent.State.LURED, null)
                && AnglerFishingService.playerFishingAction(PlayerFishEvent.State.REEL_IN,
                org.bukkit.inventory.EquipmentSlot.HAND), "automatic events are not activity");
        getLogger().info("AFK_REMOVAL_AND_ACTIVITY=PASS");
    }

    private void checkDamageAndMending(Player player) {
        NamespacedKey marker = new NamespacedKey(this, "custom_enchant_marker");
        var unbreaking = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking"));
        int damagedCount = 0;
        for (int trial = 0; trial < 1000; trial++) {
            ItemStack rod = new ItemStack(Material.FISHING_ROD);
            rod.addUnsafeEnchantment(unbreaking, 3);
            Damageable meta = (Damageable) rod.getItemMeta();
            meta.getPersistentDataContainer().set(marker, PersistentDataType.INTEGER, 5);
            rod.setItemMeta(meta);
            ItemStack result = rod.damage(1, player);
            damagedCount += ((Damageable) result.getItemMeta()).getDamage();
            require(result.getEnchantmentLevel(unbreaking) == 3
                    && result.getItemMeta().getPersistentDataContainer().get(marker, PersistentDataType.INTEGER) == 5,
                    "damage retains enchants and PDC");
        }
        require(damagedCount > 100 && damagedCount < 400, "Unbreaking III statistically effective");
        getLogger().info("AFK_UNBREAKING_AND_PDC=PASS damaged=" + damagedCount + "/1000");

        ItemStack rod = new ItemStack(Material.FISHING_ROD);
        Damageable meta = (Damageable) rod.getItemMeta();
        meta.setDamage(20);
        rod.setItemMeta(meta);
        rod.addUnsafeEnchantment(Registry.ENCHANTMENT.get(NamespacedKey.minecraft("mending")), 1);
        player.getInventory().setItemInMainHand(rod);
        player.giveExp(6, true);
        require(((Damageable) player.getInventory().getItemInMainHand().getItemMeta()).getDamage() < 20,
                "Paper Minecraft XP applies Mending");
        getLogger().info("AFK_MENDING_FROM_MINECRAFT_XP=PASS");

        rod = new ItemStack(Material.FISHING_ROD);
        meta = (Damageable) rod.getItemMeta();
        meta.setDamage(rod.getType().getMaxDurability() - 1);
        rod.setItemMeta(meta);
        require(rod.damage(1, player).isEmpty(), "rod breaks through Paper damage semantics");
        getLogger().info("AFK_ROD_BREAK=PASS");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
