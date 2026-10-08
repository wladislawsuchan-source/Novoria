package de.walahi.novosmp.enchants;

import de.walahi.novosmp.feature.PerformanceCleanupManager;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.network.ServerType;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Isolated Paper runtime checks for the two small cleanup and Totembindung fixes. */
public final class TotemAndHookPaperProbe extends SMPCorePlugin {
    @Override protected ServerType forcedServerType() { return ServerType.SMP; }
    @Override public void onDisable() { }

    @Override public void onEnable() {
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                checkHook();
                checkTotems();
                getLogger().info("TOTEM_AND_HOOK_PROBE=PASS");
            } catch (Throwable failure) {
                getLogger().log(java.util.logging.Level.SEVERE, "TOTEM_AND_HOOK_PROBE=FAIL", failure);
            } finally {
                Bukkit.shutdown();
            }
        });
    }

    private void checkHook() throws Exception {
        PerformanceCleanupManager cleanup = new PerformanceCleanupManager(this);
        FishHook hook = (FishHook) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{FishHook.class}, (proxy, method, args) -> {
                    throw new AssertionError("Technical FishHook protection must not inspect hook state");
                });
        Method technical = PerformanceCleanupManager.class
                .getDeclaredMethod("isTechnicallyProtected", org.bukkit.entity.Entity.class);
        technical.setAccessible(true);
        require((boolean) technical.invoke(cleanup, hook), "FishHook is technically protected");
        getLogger().info("FISHHOOK_TECHNICAL_PROTECTION=PASS");
    }

    private void checkTotems() {
        CustomEnchantmentService enchantments = new CustomEnchantmentService(this, YamlConfiguration::new);
        AnglerEnchantmentDefinitions.register(enchantments);
        TotemBindingListener listener = new TotemBindingListener(enchantments);

        ItemStack offShield = shield(enchantments);
        ItemStack[] offHands = {new ItemStack(Material.TOTEM_OF_UNDYING, 3), offShield};
        PlayerSwapHandItemsEvent offSwap = swap(offHands);
        listener.onSwapHands(offSwap);
        require(offSwap.isCancelled() && offHands[0].getAmount() == 2
                && offHands[0].getType() == Material.TOTEM_OF_UNDYING
                && enchantments.totemCharge(offHands[1]) == 1, "offhand shield loads exactly one");

        PlayerSwapHandItemsEvent fullSwap = swap(offHands);
        listener.onSwapHands(fullSwap);
        require(!fullSwap.isCancelled() && offHands[0].getAmount() == 2
                && enchantments.totemCharge(offHands[1]) == 1, "full shield consumes nothing");

        ItemStack[] mainHands = {shield(enchantments), new ItemStack(Material.TOTEM_OF_UNDYING)};
        PlayerSwapHandItemsEvent mainSwap = swap(mainHands);
        listener.onSwapHands(mainSwap);
        require(mainSwap.isCancelled() && enchantments.totemCharge(mainHands[0]) == 1
                && mainHands[1].getType().isAir(), "mainhand shield loads only offhand totem");

        ItemStack[] plainHands = {new ItemStack(Material.TOTEM_OF_UNDYING, 2),
                new ItemStack(Material.SHIELD)};
        PlayerSwapHandItemsEvent plainSwap = swap(plainHands);
        listener.onSwapHands(plainSwap);
        require(!plainSwap.isCancelled() && plainHands[0].getAmount() == 2,
                "ordinary shield keeps vanilla swap");

        ItemStack[] unrelatedHands = {new ItemStack(Material.STONE), new ItemStack(Material.SHIELD)};
        PlayerSwapHandItemsEvent unrelatedSwap = swap(unrelatedHands);
        listener.onSwapHands(unrelatedSwap);
        require(!unrelatedSwap.isCancelled(), "unrelated F swap remains vanilla");

        ItemStack cursorShield = shield(enchantments);
        ItemStack cursorTotems = new ItemStack(Material.TOTEM_OF_UNDYING, 2);
        require(listener.loadOne(cursorShield, cursorTotems) && cursorTotems.getAmount() == 1
                && enchantments.totemCharge(cursorShield) == 1,
                "existing inventory-click loading path still works");
        getLogger().info("TOTEMBINDUNG_SWAP_AND_CURSOR=PASS");
    }

    private ItemStack shield(CustomEnchantmentService enchantments) {
        ItemStack shield = new ItemStack(Material.SHIELD);
        require(enchantments.applyDetailed(shield, "totembindung", 1)
                == CustomEnchantmentService.ApplyResult.SUCCESS, "test shield enchanted");
        return shield;
    }

    private PlayerSwapHandItemsEvent swap(ItemStack[] hands) {
        PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PlayerInventory.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getItemInMainHand" -> hands[0];
                    case "getItemInOffHand" -> hands[1];
                    case "setItemInMainHand" -> { hands[0] = (ItemStack) args[0]; yield null; }
                    case "setItemInOffHand" -> { hands[1] = (ItemStack) args[0]; yield null; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Player player = (Player) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getInventory")) return inventory;
                    throw new UnsupportedOperationException(method.getName());
                });
        return new PlayerSwapHandItemsEvent(player, hands[1], hands[0]);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
