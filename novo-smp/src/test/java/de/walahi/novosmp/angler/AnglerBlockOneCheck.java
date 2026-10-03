package de.walahi.novosmp.angler;

import de.walahi.novosmp.professions.MilestoneRequirement;
import de.walahi.novosmp.professions.PlayerProfessionState;
import de.walahi.smpcore.database.migration.CreateAnglerCatchStorageMigration;
import de.walahi.smpcore.database.migration.CreateProfessionsMigration;
import de.walahi.smpcore.storage.StorageDialect;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Standalone Angler checks; run with the Maven test classpath after test-compile. */
public final class AnglerBlockOneCheck {
    public static void main(String[] args) throws Exception {
        YamlConfiguration angler = YamlConfiguration.loadConfiguration(
                new File("novo-smp/src/main/resources/angler.yml"));
        List<String> warnings = new ArrayList<>();
        Map<String, FishDefinition> fish = FishRegistry.parse(
                angler.getConfigurationSection("fish"), warnings::add, material -> true);
        check(warnings.isEmpty() && fish.size() == 14, "14 valid fish definitions");
        YamlConfiguration oldAngler = new YamlConfiguration();
        oldAngler.set("fish.river_perch.material", "COD");
        oldAngler.setDefaults(angler);
        Map<String, FishDefinition> oldFish = FishRegistry.parse(
                oldAngler.getConfigurationSection("fish"), warnings::add, material -> true);
        check(oldFish.get("river_perch").weight() == 100D
                        && oldFish.get("river_perch").difficulty().equals("easy"),
                "existing physical angler.yml inherits new bundled fish defaults");
        check(new FishingPool(oldAngler).weight(oldFish.get("river_perch"),
                new FishingPool.Conditions("river", false, false)) == 200D,
                "existing physical angler.yml inherits new biome groups");
        check(fish.get("river_perch").sellPrice() == 4L, "river perch sell price");
        check(fish.get("deep_mackerel").sellPrice() == 22L, "deep mackerel sell price");
        check(fish.get("storm_mackerel").conditionTags().containsAll(List.of("night", "rain")),
                "technical weather filter tags");
        PlayerProfessionState slots = new PlayerProfessionState(UUID.randomUUID());
        slots.activeProfessions().add("miner");
        slots.activeProfessions().add("angler");
        check(slots.isActive("angler"), "Angler is found in any occupied profession slot");
        slots.activeProfessions().remove("angler");
        check(!slots.isActive("angler"), "removed Angler slot locks profession-only storage");

        FishingPool pool = new FishingPool(angler);
        check(pool.weight(fish.get("river_perch"), new FishingPool.Conditions("river", false, false)) == 200D,
                "river biome multiplier");
        check(pool.weight(fish.get("storm_mackerel"), new FishingPool.Conditions("ocean", true, true)) == 91.875D,
                "ocean/night/rain multipliers compose");
        check(pool.weight(fish.get("coral_fish"), new FishingPool.Conditions("plains", false, false)) == 0D
                && pool.weight(fish.get("coral_fish"), new FishingPool.Conditions("warm_ocean", false, false)) == 30D,
                "exclusive warm-ocean fish cannot appear elsewhere");
        check(pool.weight(fish.get("mangrove_catfish"), new FishingPool.Conditions("swamp", false, false)) == 0D
                && pool.weight(fish.get("mangrove_catfish"), new FishingPool.Conditions("mangrove_swamp", false, false)) == 25D,
                "exclusive mangrove fish");
        check(pool.weight(fish.get("deep_mackerel"), new FishingPool.Conditions("ocean", false, false)) == 0D
                && pool.weight(fish.get("deep_mackerel"), new FishingPool.Conditions("deep_ocean", false, false)) == 20D,
                "exclusive deep-ocean fish");
        check(pool.choose(List.of(fish.get("coral_fish"), fish.get("river_perch")),
                new FishingPool.Conditions("plains", false, false), new Random(1)) == fish.get("river_perch"),
                "weighted picker excludes zero-weight fish");

        FishingLootPoolSelector loot = new FishingLootPoolSelector(angler);
        double[][] expected = {
                {70, 20, 10, 0, 0, 0}, {90, 7.5, 2.5, 0, 0, 0},
                {89.4, 7, 3, 0.6, 0, 0}, {88.7, 6.5, 3.2, 1.6, 0, 0},
                {88, 6, 3.3, 2.1, 0.6, 0}, {87, 5.5, 3.5, 2.8, 1.2, 0},
                {86, 5, 3.5, 3.4, 2.09, 0.01}
        };
        var treasureEntries = angler.getConfigurationSection("loot.treasure.entries");
        check(treasureEntries != null && treasureEntries.getKeys(false).size() == 8,
                "eight Treasure entries configured");
        double treasureTotal = treasureEntries.getKeys(false).stream()
                .mapToDouble(id -> treasureEntries.getDouble(id + ".weight")).sum();
        check(Math.abs(treasureTotal - 100D) < 0.000001D, "internal Treasure weights total 100");
        check(angler.getInt("loot.treasure.entries.xp_bottles.amount") == 16
                        && angler.getInt("loot.treasure.entries.ender_pearls.amount") == 4,
                "Treasure XP bottles and pearls use approved quantities");
        check(angler.getMapList("loot.treasure.entries.small_ore_bundle.contents").size() == 6,
                "small ore bundle has six configured materials");
        check(angler.getInt("loot.treasure.entries.small_trim_bundle.count") == 2
                        && angler.getConfigurationSection("loot.treasure.entries.small_trim_bundle.templates")
                        .getKeys(false).size() == 4,
                "two trim draws from four configured templates");
        check(angler.getDouble("loot.treasure.enchanted-books.groups.useful.weight") == 60D
                        && angler.getDouble("loot.treasure.enchanted-books.groups.medium.weight") == 30D
                        && angler.getDouble("loot.treasure.enchanted-books.groups.niche.weight") == 10D,
                "Treasure book-group split remains 60/30/10");
        var junkEntries = angler.getConfigurationSection("loot.junk.entries");
        check(junkEntries != null && junkEntries.getKeys(false).size() == 11,
                "eleven Junk entries configured");
        double junkTotal = junkEntries.getKeys(false).stream()
                .mapToDouble(id -> junkEntries.getDouble(id + ".weight")).sum();
        check(Math.abs(junkTotal - 100D) < 0.000001D, "internal Junk weights total 100");
        check(junkEntries.getInt("stick.min-amount") == 1
                        && junkEntries.getInt("stick.max-amount") == 3
                        && junkEntries.getInt("kelp.min-amount") == 2
                        && junkEntries.getInt("kelp.max-amount") == 6
                        && junkEntries.getInt("damaged_fishing_rod.min-durability-percent") == 10
                        && junkEntries.getInt("damaged_fishing_rod.max-durability-percent") == 35
                        && junkEntries.getInt("damaged_leather_boots.min-durability-percent") == 10
                        && junkEntries.getInt("damaged_leather_boots.max-durability-percent") == 35,
                "Junk amounts and damage limits are configured");
        check(oldAngler.getDefaults().getConfigurationSection("loot.junk.entries") != null
                        && oldAngler.getDefaults().getConfigurationSection("loot.junk.entries")
                        .getKeys(false).size() == 11
                        && oldAngler.getDouble("loot.junk.entries.stick.weight") == 18D,
                "existing physical Angler YAML inherits Junk defaults");
        var rareEntries = angler.getConfigurationSection("loot.rare.entries");
        check(rareEntries != null && rareEntries.getKeys(false).size() == 17,
                "seventeen Rare entries configured");
        double rareTotal = rareEntries.getKeys(false).stream()
                .mapToDouble(id -> rareEntries.getDouble(id + ".weight")).sum();
        check(Math.abs(rareTotal - 100D) < 0.000001D, "internal Rare weights total 100");
        check(angler.getMapList("loot.rare.entries.ore_bundle_1.contents").size() == 5
                        && angler.getMapList("loot.rare.entries.build_bundle.contents").size() == 4
                        && angler.getInt("loot.rare.entries.vanilla_book_bundle.count") == 2
                        && angler.getInt("loot.rare.entries.trim_bundle.min-types") == 3
                        && angler.getInt("loot.rare.entries.trim_bundle.max-types") == 5,
                "Rare bundle contents and trim rules configured");
        for (int tier = 0; tier < expected.length; tier++) {
            double total = 0D;
            for (FishingLootPoolSelector.Pool category : FishingLootPoolSelector.Pool.values()) {
                double weight = loot.weight(category, tier != 0, tier - 1, FishingGame.Quality.RED);
                check(weight == expected[tier][category.ordinal()], "RED base weight tier " + tier + " " + category);
                total += weight;
            }
            check(Math.abs(total - 100D) < 0.000001D, "base tier " + tier + " totals 100");
        }
        check(loot.choose(false, -1, FishingGame.Quality.RED, fixedRandom(0D))
                == FishingLootPoolSelector.Pool.FISH, "non-Angler rolls fish");
        check(loot.choose(false, -1, FishingGame.Quality.RED, fixedRandom(0.71D))
                == FishingLootPoolSelector.Pool.JUNK, "non-Angler rolls junk");
        check(loot.choose(false, -1, FishingGame.Quality.RED, fixedRandom(0.99D))
                == FishingLootPoolSelector.Pool.TREASURE, "non-Angler rolls treasure");
        for (FishingLootPoolSelector.Pool rarity : List.of(FishingLootPoolSelector.Pool.RARE,
                FishingLootPoolSelector.Pool.EPIC, FishingLootPoolSelector.Pool.LEGENDARY)) {
            check(loot.weight(rarity, false, -1, FishingGame.Quality.GREEN) == 0D
                            && loot.weight(rarity, true, 0, FishingGame.Quality.GREEN) == 0D,
                    "non-Angler and P0 never unlock " + rarity);
        }
        check(loot.unlocked(FishingLootPoolSelector.Pool.RARE, true, 1)
                        && !loot.unlocked(FishingLootPoolSelector.Pool.EPIC, true, 2)
                        && loot.unlocked(FishingLootPoolSelector.Pool.EPIC, true, 3)
                        && !loot.unlocked(FishingLootPoolSelector.Pool.LEGENDARY, true, 4)
                        && loot.unlocked(FishingLootPoolSelector.Pool.LEGENDARY, true, 5),
                "P1/P3/P5 unlock gates");
        for (int tier = 1; tier <= 6; tier++) {
            int prestige = tier - 1;
            double redJunk = loot.weight(FishingLootPoolSelector.Pool.JUNK, true, prestige, FishingGame.Quality.RED);
            double orangeJunk = loot.weight(FishingLootPoolSelector.Pool.JUNK, true, prestige, FishingGame.Quality.ORANGE);
            double yellowJunk = loot.weight(FishingLootPoolSelector.Pool.JUNK, true, prestige, FishingGame.Quality.YELLOW);
            double greenJunk = loot.weight(FishingLootPoolSelector.Pool.JUNK, true, prestige, FishingGame.Quality.GREEN);
            check(redJunk > orangeJunk && orangeJunk > yellowJunk && yellowJunk > greenJunk,
                    "quality progressively reduces junk P" + prestige);
        }
        check(loot.weight(FishingLootPoolSelector.Pool.FISH, true, 3, FishingGame.Quality.GREEN) == 88D * 0.94D
                        && loot.weight(FishingLootPoolSelector.Pool.JUNK, true, 3, FishingGame.Quality.GREEN) == 3D
                        && loot.weight(FishingLootPoolSelector.Pool.TREASURE, true, 3, FishingGame.Quality.GREEN) == 3.3D * 1.35D
                        && loot.weight(FishingLootPoolSelector.Pool.RARE, true, 3, FishingGame.Quality.GREEN) == 2.1D * 1.65D
                        && loot.weight(FishingLootPoolSelector.Pool.EPIC, true, 3, FishingGame.Quality.GREEN) == 1.2D,
                "P3 Green weights before normalization");
        for (FishingLootPoolSelector.Pool category : List.of(FishingLootPoolSelector.Pool.TREASURE,
                FishingLootPoolSelector.Pool.RARE, FishingLootPoolSelector.Pool.EPIC,
                FishingLootPoolSelector.Pool.LEGENDARY)) {
            int prestige = category == FishingLootPoolSelector.Pool.LEGENDARY ? 5 : 3;
            check(loot.weight(category, true, prestige, FishingGame.Quality.GREEN)
                    > loot.weight(category, true, prestige, FishingGame.Quality.RED),
                    "Green boosts quality pool " + category);
        }
        for (int level = 1; level <= 5; level++)
            check(loot.extraRollChance(level) == level * 0.02D, "Luck " + level + " chance");
        check(loot.extraRollChance(0) == 0D, "no Luck means no extra roll");
        CountingRandom noLuckRandom = new CountingRandom(0D);
        check(loot.roll(true, 5, FishingGame.Quality.GREEN, 0, noLuckRandom).extra() == null
                        && noLuckRandom.calls == 1,
                "without Luck only the primary pool is rolled");
        CountingRandom luckRandom = new CountingRandom(0D);
        FishingLootPoolSelector.Result doubleFish = loot.roll(true, 5, FishingGame.Quality.GREEN, 5, luckRandom);
        check(doubleFish.main() == FishingLootPoolSelector.Pool.FISH
                        && doubleFish.extra() == FishingLootPoolSelector.Pool.FISH
                        && luckRandom.calls == 3,
                "extra roll may match main pool and never chains");
        CountingRandom grayRandom = new CountingRandom(0D);
        check(loot.roll(true, 5, FishingGame.Quality.GRAY, 5, grayRandom)
                        .equals(new FishingLootPoolSelector.Result(null, null)) && grayRandom.calls == 0,
                "Gray does not roll loot or Luck");
        YamlConfiguration testLoot = new YamlConfiguration();
        testLoot.setDefaults(angler);
        testLoot.set("loot.pools.no-angler.rare", 50D);
        testLoot.set("loot.pools.prestige-0.rare", 50D);
        testLoot.set("loot.pools.prestige-2.epic", 50D);
        testLoot.set("loot.pools.prestige-4.legendary", 50D);
        FishingLootPoolSelector configuredLoot = new FishingLootPoolSelector(testLoot);
        check(configuredLoot.weight(FishingLootPoolSelector.Pool.RARE, false, -1, FishingGame.Quality.GREEN) == 0D
                        && configuredLoot.weight(FishingLootPoolSelector.Pool.RARE, true, 0, FishingGame.Quality.GREEN) == 0D
                        && configuredLoot.weight(FishingLootPoolSelector.Pool.EPIC, true, 2, FishingGame.Quality.GREEN) == 0D
                        && configuredLoot.weight(FishingLootPoolSelector.Pool.LEGENDARY, true, 4, FishingGame.Quality.GREEN) == 0D,
                "misconfigured YAML cannot bypass prestige gates");
        check(loot.choose(true, 1, FishingGame.Quality.RED, fixedRandom(0.999D))
                        == FishingLootPoolSelector.Pool.RARE
                        && loot.choose(true, 3, FishingGame.Quality.RED, fixedRandom(0.999D))
                        == FishingLootPoolSelector.Pool.EPIC
                        && loot.choose(true, 5, FishingGame.Quality.RED, fixedRandom(0.999999D))
                        == FishingLootPoolSelector.Pool.LEGENDARY,
                "P1/P3/P5 unlocked pools can be selected");
        FishingLootPoolSelector.Result gatedExtra = loot.roll(false, -1, FishingGame.Quality.GREEN, 5,
                new SequenceRandom(0.99D, 0D, 0.99D));
        check(gatedExtra.main() == FishingLootPoolSelector.Pool.TREASURE
                        && gatedExtra.extra() == FishingLootPoolSelector.Pool.TREASURE,
                "Luck extra roll stays inside non-Angler gate");
        check(loot.roll(true, 1, FishingGame.Quality.GREEN, 5,
                        new SequenceRandom(0.999D, 0D, 0.999D)).extra() == FishingLootPoolSelector.Pool.RARE
                        && loot.roll(true, 3, FishingGame.Quality.GREEN, 5,
                        new SequenceRandom(0.999D, 0D, 0.999D)).extra() == FishingLootPoolSelector.Pool.EPIC
                        && loot.roll(true, 5, FishingGame.Quality.GREEN, 5,
                        new SequenceRandom(0.9999999D, 0D, 0.9999999D)).extra()
                        == FishingLootPoolSelector.Pool.LEGENDARY,
                "Luck extra roll uses the same P1/P3/P5 gating and weights");
        int[] sample = new int[FishingLootPoolSelector.Pool.values().length];
        Random sampleRandom = new Random(123L);
        for (int index = 0; index < 10_000; index++)
            sample[loot.choose(true, 3, FishingGame.Quality.GREEN, sampleRandom).ordinal()]++;
        check(java.util.Arrays.stream(sample).sum() == 10_000 && sample[5] == 0
                        && Math.abs(sample[0] - 8722) < 300
                        && Math.abs(sample[1] - 316) < 150,
                "P3 Green simulation follows normalized weights and excludes Legendary");
        System.out.println("P3 GREEN / 10,000: " + java.util.Arrays.toString(sample));
        testLoot.set("loot.pools.prestige-5.legendary", 0D);
        check(new FishingLootPoolSelector(testLoot).weight(FishingLootPoolSelector.Pool.LEGENDARY,
                true, 5, FishingGame.Quality.GREEN) == 0D, "zero weight stays zero");
        for (FishingLootPoolSelector.Pool category : FishingLootPoolSelector.Pool.values())
            testLoot.set("loot.pools.no-angler." + category.name().toLowerCase(java.util.Locale.ROOT), 0D);
        check(new FishingLootPoolSelector(testLoot).choose(false, -1, FishingGame.Quality.GREEN,
                fixedRandom(0D)) == null, "all-zero weights cannot select any pool");

        long started = 1_000_000_000L;
        FishingGame easyLeft = new FishingGame(angler, "easy", fixedRandom(0D), started);
        FishingGame easyRight = new FishingGame(angler, "easy", fixedRandom(1D), started);
        check(easyLeft.targetStart() == 0D && easyRight.targetStart() > 0.3D,
                "colored zone shifts across the bar");
        check(easyLeft.pointer(started) == 0D
                && easyLeft.pointer(started + 1_800_000_000L) == 1D
                && easyLeft.pointer(started + 3_600_000_000L) == 0D,
                "pointer bounces left/right");
        check(easyLeft.quality(0.01D) == FishingGame.Quality.RED
                && easyLeft.quality(0.13D) == FishingGame.Quality.ORANGE
                && easyLeft.quality(0.21D) == FishingGame.Quality.YELLOW
                && easyLeft.quality(0.31D) == FishingGame.Quality.GREEN
                && easyLeft.quality(0.9D) == FishingGame.Quality.GRAY,
                "easy colored result zones");
        check(!easyLeft.timedOut(started + 4_999_999_999L)
                && easyLeft.timedOut(started + 5_000_000_000L), "five-second timeout");
        check(new FishingGame(angler, "hard", fixedRandom(0D), started).quality(0.5D)
                == FishingGame.Quality.GRAY, "hard game has narrower target");

        FishingComboTracker combos = new FishingComboTracker(angler);
        UUID playerId = UUID.randomUUID();
        long now = System.currentTimeMillis();
        for (int index = 1; index <= 4; index++) {
            combos.attempt(playerId, now);
            check(combos.finish(playerId, FishingGame.Quality.GREEN).combo() == index,
                    "successful catch raises combo");
        }
        combos.attempt(playerId, now);
        check(combos.finish(playerId, FishingGame.Quality.GREEN).multiplier() == 1.05D,
                "fifth catch receives tier bonus immediately");
        combos.attempt(playerId, now);
        check(combos.finish(playerId, FishingGame.Quality.RED).combo() == 5,
                "red catch holds combo");
        combos.attempt(playerId, now);
        check(combos.finish(playerId, FishingGame.Quality.GRAY).combo() == 0,
                "gray resets combo");
        combos.attempt(playerId, now);
        combos.finish(playerId, FishingGame.Quality.ORANGE);
        combos.attempt(playerId, now + 60_000L);
        check(combos.combo(playerId, now + 60_000L) == 0, "idle timeout resets combo");
        check(Math.round(25D * 1.25D) == 31L && Math.round(25D * 2D * 1.2D) == 60L,
                "integer Angler XP rounding and combo bonus");

        FishingAttemptState attempt = new FishingAttemptState();
        check(!attempt.canStop(100) && !attempt.beginResolve(), "cast cannot resolve");
        attempt.bite();
        attempt.confirmingInteract(100);
        check(attempt.startGame(100, 0) && !attempt.startGame(100, 0), "one bite starts one game");
        check(attempt.canStop(100), "second click can stop even in confirming tick");
        check(attempt.beginResolve() && !attempt.beginResolve() && !attempt.canStop(102),
                "spam click and timeout share exactly-once resolve gate");
        FishingAttemptState reverseOrder = new FishingAttemptState();
        reverseOrder.bite();
        reverseOrder.startGame(100, 0);
        check(!reverseOrder.canStop(100) && reverseOrder.canStop(100),
                "catch-before-interact order consumes only confirming click");
        FishingAttemptState ignoredBite = new FishingAttemptState();
        ignoredBite.bite();
        check(ignoredBite.expireBite() && !ignoredBite.expireBite()
                && ignoredBite.phase() == FishingAttemptState.Phase.CAST,
                "ignored bite returns to waiting state once");

        NamespacedKey key = new NamespacedKey("novosmp", "fish_id");
        check(FishRegistry.resolve(fish, "river_perch", Material.COD, Set.of(key), key) != null,
                "registered PDC fish recognized");
        check(FishRegistry.resolve(fish, null, Material.COD, Set.of(), key) == null,
                "ordinary vanilla COD rejected");
        check(FishRegistry.resolve(fish, "river_perch", Material.SALMON, Set.of(key), key) == null,
                "wrong material rejected");
        check(FishRegistry.resolve(fish, "river_perch", Material.COD,
                Set.of(key, new NamespacedKey("novosmp", "custom_item_id")), key) == null,
                "other custom PDC item rejected");
        check(FishRegistry.resolve(fish, "unknown", Material.COD, Set.of(key), key) == null,
                "unregistered ID rejected");
        check(AnglerFeature.isTopPickupAction(InventoryAction.PICKUP_ALL)
                && AnglerFeature.isTopPickupAction(InventoryAction.PICKUP_HALF)
                && !AnglerFeature.isTopPickupAction(InventoryAction.PLACE_ALL)
                && !AnglerFeature.isTopPickupAction(InventoryAction.HOTBAR_SWAP),
                "catch storage only permits top-slot pickup actions");
        check(AnglerFeature.blocksBottomAction(InventoryAction.MOVE_TO_OTHER_INVENTORY)
                && AnglerFeature.blocksBottomAction(InventoryAction.UNKNOWN)
                && !AnglerFeature.blocksBottomAction(InventoryAction.PLACE_ALL)
                && !AnglerFeature.blocksBottomAction(InventoryAction.HOTBAR_SWAP)
                && !AnglerFeature.blocksBottomAction(InventoryAction.PICKUP_ALL),
                "player inventory remains usable while bottom-to-top shift is blocked");
        check(AnglerFeature.dragTouchesTop(Set.of(27, 5), 27)
                && !AnglerFeature.dragTouchesTop(Set.of(27, 28), 27),
                "only drags touching catch slots are blocked");

        YamlConfiguration professions = YamlConfiguration.loadConfiguration(
                new File("novo-smp/src/main/resources/professions.yml"));
        check(professions.getLong("requirements.angler.0.25.fish.river_perch") == 32L,
                "angler fish requirement parsed");
        int[][] comboTargets = {
                {5, 10, 20, 30}, {8, 15, 25, 35}, {10, 18, 28, 40},
                {12, 20, 30, 45}, {15, 25, 35, 50}, {18, 28, 40, 55}
        };
        int[][] greenTargets = {
                {0, 20, 50, 100}, {0, 40, 80, 140}, {0, 55, 100, 170},
                {0, 70, 125, 200}, {0, 90, 150, 230}, {0, 110, 180, 275}
        };
        int[] milestones = {25, 50, 75, 100};
        for (int prestige = 0; prestige <= 5; prestige++) {
            for (int index = 0; index < milestones.length; index++) {
                String path = "requirements.angler." + prestige + "." + milestones[index] + ".skills.";
                check(professions.getInt(path + "max_combo") == comboTargets[prestige][index]
                        && professions.getInt(path + "green_hits") == greenTargets[prestige][index],
                        "angler P" + prestige + " level " + milestones[index] + " skills");
            }
        }
        check(professions.getBoolean("professions.angler.max-prestige-requirements-enabled"),
                "Angler P5 requirements enabled");
        check(!angler.getString("messages.storage-locked", "").isBlank(),
                "fanglager locked message configured");
        YamlConfiguration itemConfig = YamlConfiguration.loadConfiguration(
                new File("novo-smp/src/main/resources/items.yml"));
        List<String> anglerBookIds = List.of(
                "ausdauer_1", "ausdauer_2", "ausdauer_3", "ausdauer_4", "ausdauer_5",
                "ruhige_hand_1", "ruhige_hand_2", "ruhige_hand_3",
                "nachfassen_1", "nachfassen_2",
                "konzentration_1", "konzentration_2", "konzentration_3",
                "meistergriff_1", "meistergriff_2",
                "totembindung_1", "spawnergriff_1", "seelenbindung_1");
        check(anglerBookIds.size() == 18, "18 Angler custom enchant books listed");
        for (String suffix : anglerBookIds) {
            String path = "items.angler_buch_" + suffix;
            check(itemConfig.getString(path + ".material", "").equals("ENCHANTED_BOOK")
                            && !itemConfig.getString(path + ".display-name", "").isBlank()
                            && !itemConfig.getStringList(path + ".lore").isEmpty()
                            && itemConfig.getInt(path + ".max-stack-size") == 1,
                    "configured custom book " + suffix);
        }
        check(itemConfig.getInt("items.magnet_buch.custom-enchantments.magnet") == 1
                        && itemConfig.getString("items.reparaturkern.material").equals("ECHO_SHARD")
                        && itemConfig.getString("items.bibliothekarsiegel.material").equals("PAPER"),
                "existing Magnet I reused and special-item definitions present");
        Map<String, AnglerLootFoundation.OverlevelBook> overlevel =
                AnglerLootFoundation.parseOverlevelBooks(angler);
        check(overlevel.size() == 9
                        && overlevel.get("lure_4").minPrestige() == 1
                        && overlevel.get("fortune_5").minPrestige() == 4
                        && overlevel.get("efficiency_7").level() == 7
                        && overlevel.get("efficiency_7").minPrestige() == 5,
                "nine vanilla overlevel-book definitions and prestige gates");
        for (String color : List.of("red", "orange", "yellow", "green")) {
            String anglerResult = angler.getString("fishing.actionbar." + color, "");
            String nonAnglerResult = angler.getString("fishing.actionbar.non-angler." + color, "");
            check(anglerResult.contains("%xp%") && !nonAnglerResult.contains("%xp%")
                    && !nonAnglerResult.contains("%stored%") && !nonAnglerResult.contains("/fanglager"),
                    color + " result templates separate Angler and non-Angler feedback");
        }
        YamlConfiguration existingServer = new YamlConfiguration();
        existingServer.set("requirements.holzfaeller.0.25.materials.holz", 1688);
        existingServer.setDefaults(professions);
        check(!existingServer.getConfigurationSection("requirements").getKeys(false).contains("angler")
                && existingServer.getDefaults().getConfigurationSection("requirements")
                        .getKeys(false).contains("angler")
                && existingServer.getConfigurationSection("requirements.angler") != null
                && existingServer.getConfigurationSection("requirements.angler.0.25.fish") != null
                && existingServer.getLong("requirements.angler.0.25.fish.river_perch") == 32L,
                "existing server YAML exposes Angler through bundled defaults without overwrite");
        MilestoneRequirement requirement = new MilestoneRequirement(25, 0L, Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of("river_perch", 32L));
        check(!requirement.empty() && requirement.fish().get("river_perch") == 32L,
                "fish requirement participates in milestone model");

        AnglerStorageCodec codec = new AnglerStorageCodec();
        ItemStack[] contents = new ItemStack[54];
        byte[] encoded = codec.serialize(contents);
        ItemStack[] restored = codec.deserialize(encoded);
        check(restored.length == 54 && restored[0] == null,
                "catch storage framing round-trip");

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            new CreateAnglerCatchStorageMigration().apply(connection, StorageDialect.SQLITE, "");
            new CreateProfessionsMigration().apply(connection, StorageDialect.SQLITE, "");
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO angler_catch_storage(player_uuid,contents,updated_at) VALUES (?,?,0)")) {
                statement.setString(1, "00000000-0000-0000-0000-000000000001");
                statement.setBytes(2, encoded);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT contents FROM angler_catch_storage WHERE player_uuid=?")) {
                statement.setString(1, "00000000-0000-0000-0000-000000000001");
                try (ResultSet rows = statement.executeQuery()) {
                    check(rows.next() && codec.deserialize(rows.getBytes(1)).length == 54,
                            "SQLite catch storage persisted");
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO profession_contributions(player_uuid,profession_id,prestige,milestone,requirement_id,amount,updated_at) VALUES (?,?,?,?,?,?,0)")) {
                for (int prestige = 0; prestige < 2; prestige++) {
                    statement.setString(1, playerId.toString());
                    statement.setString(2, "angler");
                    statement.setInt(3, prestige);
                    statement.setInt(4, 50);
                    statement.setString(5, "green_hits");
                    statement.setInt(6, prestige == 0 ? 20 : 1);
                    statement.executeUpdate();
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT amount FROM profession_contributions WHERE player_uuid=? AND profession_id='angler' AND prestige=1 AND milestone=50 AND requirement_id='green_hits'")) {
                statement.setString(1, playerId.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    check(rows.next() && rows.getInt(1) == 1,
                            "SQLite contribution key separates prestige cycles");
                }
            }
        }
        System.out.println("Angler Block 1+2 and loot-pool checks passed");
    }

    private static Random fixedRandom(double value) {
        return new Random() { @Override public double nextDouble() { return value; } };
    }

    private static final class CountingRandom extends Random {
        private final double value;
        private int calls;
        private CountingRandom(double value) { this.value = value; }
        @Override public double nextDouble() { calls++; return value; }
    }

    private static final class SequenceRandom extends Random {
        private final double[] values;
        private int index;
        private SequenceRandom(double... values) { this.values = values; }
        @Override public double nextDouble() { return values[index++ % values.length]; }
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
