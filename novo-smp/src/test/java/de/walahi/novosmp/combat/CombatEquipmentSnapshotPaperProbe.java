package de.walahi.novosmp.combat;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Isolated Paper regression probe for CombatLogRecord's item escrow. */
public final class CombatEquipmentSnapshotPaperProbe extends JavaPlugin {
    @Override public void onEnable() {
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                runChecks();
                getLogger().info("COMBAT_EQUIPMENT_SNAPSHOT_PROBE=PASS");
            } catch (Throwable failure) {
                getLogger().log(java.util.logging.Level.SEVERE, "COMBAT_EQUIPMENT_SNAPSHOT_PROBE=FAIL", failure);
            } finally {
                Bukkit.shutdown();
            }
        });
    }

    private void runChecks() {
        List<ItemStack> drops = new ArrayList<>();
        World world = (World) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "probe";
                    case "dropItemNaturally" -> { drops.add(((ItemStack) args[1]).clone()); yield null; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Location at = new Location(world, 0, 64, 0);

        CombatLogRecord loaded = record();
        DummyEquipment working = new DummyEquipment(true, true, true);
        loaded.applyToDummy(dummy(at, working));
        loaded.refreshFromDummy(dummy(at, working));
        require(loaded.armor[0].getType() == Material.DIAMOND_BOOTS
                && loaded.storage[0].getType() == Material.DIAMOND_SWORD
                && loaded.extra[0].getType() == Material.TOTEM_OF_UNDYING,
                "loaded equipment remains unchanged");
        working.offHand = null;
        loaded.refreshFromDummy(dummy(at, working));
        require(loaded.extra[0] == null, "a confirmed loaded offhand item can still be consumed");
        loaded.dropItems(at);
        require(drops.size() == 3 && count(drops, Material.DIAMOND_SWORD) == 1
                && count(drops, Material.DIAMOND_BOOTS) == 1
                && count(drops, Material.DIAMOND_LEGGINGS) == 1,
                "loaded dummy items drop once without restoring consumed offhand");
        drops.clear();
        getLogger().info("COMBAT_DUMMY_LOADED_EQUIPMENT=PASS");

        CombatLogRecord missing = record();
        DummyEquipment broken = new DummyEquipment(false, false, false);
        LivingEntity blankDummy = dummy(at, broken);
        missing.applyToDummy(blankDummy);
        missing.refreshFromDummy(blankDummy);
        require(missing.armor[0].getType() == Material.DIAMOND_BOOTS
                && missing.armor[1].getType() == Material.DIAMOND_LEGGINGS,
                "missing dummy armor does not erase snapshot");
        require(missing.storage[0].getType() == Material.DIAMOND_SWORD
                && missing.extra[0].getType() == Material.TOTEM_OF_UNDYING,
                "missing mainhand and offhand do not erase snapshot");
        CombatLogRecord partial = record();
        DummyEquipment partlyBroken = new DummyEquipment(true, true, true);
        partlyBroken.dropArmorSlot = 1;
        LivingEntity partialDummy = dummy(at, partlyBroken);
        partial.applyToDummy(partialDummy);
        partial.refreshFromDummy(partialDummy);
        require(partial.armor[0].getType() == Material.DIAMOND_BOOTS
                && partial.armor[1].getType() == Material.DIAMOND_LEGGINGS
                && partial.storage[0].getType() == Material.DIAMOND_SWORD
                && partial.extra[0].getType() == Material.TOTEM_OF_UNDYING,
                "one failed armor slot does not corrupt the other equipment");
        broken.armor[0] = new ItemStack(Material.DIRT);
        broken.mainHand = new ItemStack(Material.STONE);
        broken.offHand = new ItemStack(Material.REDSTONE);
        missing.refreshFromDummy(blankDummy);
        require(missing.armor[0].getType() == Material.DIAMOND_BOOTS
                && missing.storage[0].getType() == Material.DIAMOND_SWORD
                && missing.extra[0].getType() == Material.TOTEM_OF_UNDYING,
                "unconfirmed wrong dummy equipment does not replace escrow items");
        getLogger().info("COMBAT_DUMMY_MISSING_EQUIPMENT=PASS");

        CombatLogStore store = new CombatLogStore(this);
        store.save(List.of(missing));
        CombatLogRecord reloaded = store.load().get(missing.playerId);
        require(reloaded != null && reloaded.armor[0].getType() == Material.DIAMOND_BOOTS
                && reloaded.storage[0].getType() == Material.DIAMOND_SWORD
                && reloaded.extra[0].getType() == Material.TOTEM_OF_UNDYING,
                "equipment escrow survives YAML reload");
        LivingEntity reloadedDummy = dummy(at, new DummyEquipment(false, false, false));
        reloaded.applyToDummy(reloadedDummy);
        reloaded.refreshFromDummy(reloadedDummy, false); // NPC death: life state only.
        reloaded.dropItems(at);
        require(drops.size() == 4
                && count(drops, Material.DIAMOND_SWORD) == 1
                && count(drops, Material.DIAMOND_BOOTS) == 1
                && count(drops, Material.DIAMOND_LEGGINGS) == 1
                && count(drops, Material.TOTEM_OF_UNDYING) == 1,
                "snapshot drops every item exactly once despite empty dummy equipment");
        getLogger().info("COMBAT_DUMMY_RESTART_ESCROW=PASS");
        getLogger().info("COMBAT_DUMMY_NO_MISSING_OR_DOUBLE_DROPS=PASS");

        CombatLogRecord returning = record();
        DummyEquipment returningEquipment = new DummyEquipment(true, true, true);
        LivingEntity returningDummy = dummy(at, returningEquipment);
        returning.applyToDummy(returningDummy);
        returningEquipment.offHand = null;
        returning.refreshFromDummy(returningDummy);
        ItemStack[][] inventory = {new ItemStack[36], new ItemStack[4], new ItemStack[1]};
        returning.restore(player(inventory));
        require(inventory[0][0].getType() == Material.DIAMOND_SWORD
                && inventory[1][0].getType() == Material.DIAMOND_BOOTS
                && inventory[2][0] == null,
                "rejoin restores loaded equipment state, including consumed offhand");
        getLogger().info("COMBAT_DUMMY_REJOIN=PASS");
    }

    private static CombatLogRecord record() {
        ItemStack[] storage = new ItemStack[36];
        storage[0] = new ItemStack(Material.DIAMOND_SWORD);
        ItemStack[] armor = new ItemStack[4];
        armor[0] = new ItemStack(Material.DIAMOND_BOOTS);
        armor[1] = new ItemStack(Material.DIAMOND_LEGGINGS);
        return new CombatLogRecord(UUID.randomUUID(), "Probe", System.currentTimeMillis(),
                System.currentTimeMillis() + 30_000L, CombatLogRecord.Phase.ACTIVE,
                "probe", 0D, 64D, 0D, 0F, 0F, 20D, 20D, 0D,
                20, 5F, 0F, 0, 0, 0F, 0, 0,
                storage, armor, new ItemStack[]{new ItemStack(Material.TOTEM_OF_UNDYING)},
                null, List.of());
    }

    private LivingEntity dummy(Location at, DummyEquipment state) {
        EntityEquipment equipment = (EntityEquipment) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{EntityEquipment.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "setArmorContents" -> {
                        if (state.loadArmor) {
                            state.armor = CombatLogRecord.cloneItems((ItemStack[]) args[0]);
                            if (state.dropArmorSlot >= 0) state.armor[state.dropArmorSlot] = null;
                        }
                        yield null;
                    }
                    case "setItemInMainHand" -> { if (state.loadMain) state.mainHand = CombatLogRecord.cloneItem((ItemStack) args[0]); yield null; }
                    case "setItemInOffHand" -> { if (state.loadOff) state.offHand = CombatLogRecord.cloneItem((ItemStack) args[0]); yield null; }
                    case "getArmorContents" -> CombatLogRecord.cloneItems(state.armor);
                    case "getItemInMainHand" -> CombatLogRecord.cloneItem(state.mainHand);
                    case "getItemInOffHand" -> CombatLogRecord.cloneItem(state.offHand);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (LivingEntity) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{LivingEntity.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getEquipment" -> equipment;
                    case "getAttribute" -> null;
                    case "getLocation" -> at;
                    case "isDead" -> false;
                    case "getHealth" -> 20D;
                    case "getAbsorptionAmount" -> 0D;
                    case "getActivePotionEffects" -> List.of();
                    case "setHealth", "setAbsorptionAmount", "setFireTicks", "setCanPickupItems" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private Player player(ItemStack[][] inventoryContents) {
        PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PlayerInventory.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getStorageContents" -> inventoryContents[0];
                    case "getArmorContents" -> inventoryContents[1];
                    case "getExtraContents" -> inventoryContents[2];
                    case "setStorageContents" -> { inventoryContents[0] = (ItemStack[]) args[0]; yield null; }
                    case "setArmorContents" -> { inventoryContents[1] = (ItemStack[]) args[0]; yield null; }
                    case "setExtraContents" -> { inventoryContents[2] = (ItemStack[]) args[0]; yield null; }
                    case "clear" -> { inventoryContents[0] = new ItemStack[36]; yield null; }
                    case "setHeldItemSlot" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (Player) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getInventory" -> inventory;
                    case "getActivePotionEffects" -> List.of();
                    case "getAttribute" -> null;
                    case "teleport" -> true;
                    case "closeInventory", "setItemOnCursor", "setFireTicks", "setAbsorptionAmount",
                            "setTotalExperience", "setLevel", "setExp", "setFoodLevel", "setSaturation",
                            "setExhaustion", "setHealth", "updateInventory" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static int count(List<ItemStack> drops, Material type) {
        return (int) drops.stream().filter(item -> item.getType() == type).count();
    }

    private static void require(boolean okay, String message) {
        if (!okay) throw new AssertionError(message);
    }

    private static final class DummyEquipment {
        final boolean loadArmor, loadMain, loadOff;
        int dropArmorSlot = -1;
        ItemStack[] armor = new ItemStack[4];
        ItemStack mainHand, offHand;

        DummyEquipment(boolean loadArmor, boolean loadMain, boolean loadOff) {
            this.loadArmor = loadArmor;
            this.loadMain = loadMain;
            this.loadOff = loadOff;
        }
    }
}
