package de.walahi.novosmp.enchants;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/** Drops, Schmelzer-Ergebnisse und Erfahrung für die besonderen Spitzhacken. */
public final class MiningDropService {
    private final MiningEnchantConfig config;
    private final CustomEnchantmentService enchantments;

    public MiningDropService(MiningEnchantConfig config, CustomEnchantmentService enchantments) {
        this.config = config;
        this.enchantments = enchantments;
    }

    public boolean canSmelt(Block block, ItemStack tool, Player player) {
        return prepareSmelter(block, tool, player) != null;
    }

    /**
     * Berechnet den Schmelzer-Drop, ohne Welt oder Event bereits zu verändern.
     * Dadurch kann die Zustellung erst erfolgen, nachdem sicher feststeht, dass
     * der ursprüngliche BlockBreakEvent nicht von einem späteren Listener abgebrochen wurde.
     */
    public SmeltedDrops prepareSmelter(Block block, ItemStack tool, Player player) {
        return smelt(block, tool, player);
    }

    /** Unterdrückt beim ursprünglichen Block nur die Vanilla-Drops und -Erfahrung. */
    public void suppressOriginDrops(BlockBreakEvent event) {
        event.setDropItems(false);
        event.setExpToDrop(0);
    }

    /** Stellt einen zuvor berechneten Schmelzer-Drop sicher an der Blockposition zu. */
    public void deliver(Player player, ItemStack tool, Location location, SmeltedDrops smelted) {
        if (location == null || smelted == null) return;
        drop(player, tool, location, smelted.items());
        spawnExperience(location, smelted.experience());
    }

    /**
     * Bricht einen zusätzlichen Block nach einem eigenen BlockBreakEvent.
     *
     * @return true, wenn der Block tatsächlich entfernt wurde
     */
    public boolean breakAdditional(Player player, ItemStack tool, Block block, boolean smelter, boolean applyPhysics) {
        if (block == null || block.getType().isAir()) return false;

        BlockBreakEvent synthetic = new BlockBreakEvent(block, player);
        if (config.respectProtection()) {
            Bukkit.getPluginManager().callEvent(synthetic);
            if (synthetic.isCancelled()) return false;
        }

        Collection<ItemStack> blockDrops = List.of();
        int experience = 0;
        if (synthetic.isDropItems()) {
            SmeltedDrops smelted = smelter ? smelt(block, tool, player) : null;
            if (smelted != null) {
                blockDrops = smelted.items();
                experience = smelted.experience();
            } else {
                blockDrops = new ArrayList<>(block.getDrops(tool, player));
                experience = normalExperience(block.getType());
            }
        }

        Location location = block.getLocation();
        block.setType(Material.AIR, applyPhysics);
        drop(player, tool, location, blockDrops);
        spawnExperience(location, experience);
        return true;
    }

    private SmeltedDrops smelt(Block block, ItemStack tool, Player player) {
        if (block == null || tool == null || tool.getEnchantmentLevel(Enchantment.SILK_TOUCH) > 0) return null;
        MiningEnchantConfig.SmeltRecipe recipe = config.smeltRecipe(block.getType());
        if (recipe == null) return null;

        Collection<ItemStack> vanillaDrops = block.getDrops(tool, player);
        int amount = vanillaDrops.stream().mapToInt(ItemStack::getAmount).sum();
        if (amount <= 0) return null;

        ItemStack result = new ItemStack(recipe.result(), amount);
        int experience = randomizedExperience(recipe.experiencePerItem() * amount);
        return new SmeltedDrops(List.of(result), experience);
    }

    private void drop(Player player, ItemStack tool, Location blockLocation, Collection<ItemStack> items) {
        if (items == null || items.isEmpty() || blockLocation.getWorld() == null) return;
        Location dropLocation = blockLocation.clone().add(0.5D, 0.5D, 0.5D);
        if (player != null && enchantments.level(tool, MagnetListener.ID) > 0) {
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(items.stream()
                    .filter(Objects::nonNull).map(ItemStack::clone).toArray(ItemStack[]::new));
            overflow.values().forEach(item -> blockLocation.getWorld().dropItemNaturally(dropLocation, item));
            return;
        }
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir() || item.getAmount() <= 0) continue;
            blockLocation.getWorld().dropItemNaturally(dropLocation, item);
        }
    }

    private void spawnExperience(Location blockLocation, int experience) {
        if (experience <= 0 || blockLocation.getWorld() == null) return;
        Location spawn = blockLocation.clone().add(0.5D, 0.5D, 0.5D);
        blockLocation.getWorld().spawn(spawn, ExperienceOrb.class, orb -> orb.setExperience(experience));
    }

    private int randomizedExperience(double exact) {
        if (exact <= 0.0D) return 0;
        int base = (int) Math.floor(exact);
        double remainder = exact - base;
        return base + (ThreadLocalRandom.current().nextDouble() < remainder ? 1 : 0);
    }

    /** Vanilla-Erfahrung der Erze, die beim normalen Abbau Erfahrung geben. */
    private int normalExperience(Material material) {
        return switch (material) {
            case COAL_ORE, DEEPSLATE_COAL_ORE -> randomInclusive(0, 2);
            case DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE,
                    EMERALD_ORE, DEEPSLATE_EMERALD_ORE -> randomInclusive(3, 7);
            case LAPIS_ORE, DEEPSLATE_LAPIS_ORE,
                    NETHER_QUARTZ_ORE -> randomInclusive(2, 5);
            case REDSTONE_ORE, DEEPSLATE_REDSTONE_ORE -> randomInclusive(1, 5);
            default -> 0;
        };
    }

    private int randomInclusive(int minimum, int maximum) {
        return ThreadLocalRandom.current().nextInt(minimum, maximum + 1);
    }

    public record SmeltedDrops(Collection<ItemStack> items, int experience) {
        public SmeltedDrops {
            items = items == null ? List.of() : List.copyOf(items);
            experience = Math.max(0, experience);
        }
    }
}
