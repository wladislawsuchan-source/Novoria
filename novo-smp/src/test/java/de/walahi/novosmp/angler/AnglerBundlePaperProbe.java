package de.walahi.novosmp.angler;

import de.walahi.novosmp.enchants.CustomEnchantment;
import de.walahi.novosmp.enchants.CustomEnchantmentService;
import de.walahi.novosmp.enchants.AnglerEnchantmentDefinitions;
import de.walahi.novosmp.enchants.CustomEnchantmentAnvilListener;
import de.walahi.novosmp.enchants.TotemBindingListener;
import de.walahi.novosmp.enchants.SpawnergriffListener;
import de.walahi.novosmp.enchants.SoulboundDeathListener;
import de.walahi.novosmp.items.CustomItemManager;
import de.walahi.novosmp.items.LibrarianSealListener;
import de.walahi.novosmp.lumi.LumiRepository;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.config.ConfigurationLoader;
import de.walahi.smpcore.config.PluginConfigurations;
import de.walahi.smpcore.network.ServerType;
import de.walahi.smpcore.storage.StorageManager;
import de.walahi.smpcore.messages.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Villager;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataType;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.HashSet;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Local Paper integration probe; deliberately not part of the production plugin lifecycle. */
public final class AnglerBundlePaperProbe extends SMPCorePlugin {
    private PluginConfigurations probeConfigurations;
    private StorageManager probeStorage;
    private AnglerLootFoundation probeFoundation;
    private CustomItemManager probeItems;
    private LumiRepository probeLumis;
    private CustomEnchantmentService probeEnchants;
    private MessageService probeMessages;

    @Override protected ServerType forcedServerType() { return ServerType.SMP; }
    @Override public PluginConfigurations configs() { return probeConfigurations; }
    @Override public MessageService messages() {
        if (probeMessages == null) probeMessages = new MessageService(this);
        return probeMessages;
    }
    @Override public void onDisable() { if (probeStorage != null) probeStorage.close(); }

    @Override public void onEnable() {
        try {
            probeConfigurations = new PluginConfigurations(this, new ConfigurationLoader(this),
                    getDataFolder(), getDataFolder(), "novo-smp.yml", ServerType.SMP);
            probeItems = new CustomItemManager(this);
            probeEnchants = new CustomEnchantmentService(this, () -> probeConfigurations.angler());
            probeEnchants.register(new CustomEnchantment("magnet", "Magnet", "<aqua>", 1,
                    CustomEnchantment.Applicability.TOOL_OR_WEAPON));
            AnglerEnchantmentDefinitions.register(probeEnchants);
            probeItems.enchantApplier(probeEnchants);
            probeStorage = new StorageManager(this);
            probeStorage.initialize();
            try (Connection connection = probeStorage.connection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS lumi_accounts ("
                        + "player_uuid VARCHAR(36) NOT NULL PRIMARY KEY, balance BIGINT NOT NULL DEFAULT 0,"
                        + " updated_at BIGINT NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS angler_catch_storage ("
                        + "player_uuid VARCHAR(36) NOT NULL PRIMARY KEY, contents BLOB NULL,"
                        + " updated_at BIGINT NOT NULL)");
            }
            probeLumis = new LumiRepository(probeStorage);
            probeFoundation = new AnglerLootFoundation(probeItems, probeLumis,
                    probeConfigurations.angler(), getLogger());
        } catch (Exception exception) {
            getLogger().severe("EPIC_DELIVERY_SETUP=FAIL " + exception);
        }
        ItemStack normal = new ItemStack(Material.STONE, 3);
        AnglerLootFoundation.NormalItemReward normalReward = new AnglerLootFoundation.NormalItemReward(normal);
        normal.setAmount(1);
        boolean rewardModel = normalReward.item().getAmount() == 3
                && normalReward.type() == AnglerLootFoundation.RewardType.NORMAL_ITEM
                && new AnglerLootFoundation.CustomItemReward("magnet_buch", 1).type()
                    == AnglerLootFoundation.RewardType.CUSTOM_ITEM
                && new AnglerLootFoundation.OverlevelBookReward("lure_4", 1).type()
                    == AnglerLootFoundation.RewardType.OVERLEVEL_BOOK
                && new AnglerLootFoundation.BundleReward(List.of(normalReward)).type()
                    == AnglerLootFoundation.RewardType.BUNDLE
                && new AnglerLootFoundation.CurrencyReward(AnglerLootFoundation.RewardType.LUMI, 25).type()
                    == AnglerLootFoundation.RewardType.LUMI;
        getLogger().info("REWARD_MODEL_PROBE=" + (rewardModel ? "PASS" : "FAIL"));
        Map<String, List<ItemStack>> cases = new LinkedHashMap<>();
        cases.put("normal", List.of(new ItemStack(Material.STONE, 16), new ItemStack(Material.DIRT, 8)));
        cases.put("over64", List.of(new ItemStack(Material.STONE, 64), new ItemStack(Material.DIRT, 64)));
        cases.put("tools", List.of(new ItemStack(Material.DIAMOND_PICKAXE),
                new ItemStack(Material.DIAMOND_AXE), new ItemStack(Material.DIAMOND_SHOVEL)));
        cases.put("armor", List.of(new ItemStack(Material.DIAMOND_HELMET),
                new ItemStack(Material.DIAMOND_CHESTPLATE), new ItemStack(Material.DIAMOND_LEGGINGS),
                new ItemStack(Material.DIAMOND_BOOTS)));
        cases.put("armor_set", List.of(enchanted(Material.NETHERITE_HELMET, "protection", 4),
                enchanted(Material.NETHERITE_CHESTPLATE, "protection", 4),
                enchanted(Material.NETHERITE_LEGGINGS, "protection", 4),
                enchanted(Material.NETHERITE_BOOTS, "protection", 4),
                enchanted(Material.NETHERITE_PICKAXE, "efficiency", 5),
                enchanted(Material.NETHERITE_SWORD, "sharpness", 5)));
        for (Map.Entry<String, List<ItemStack>> test : cases.entrySet()) {
            try {
                ItemStack bundle = AnglerLootFoundation.createBundle(test.getValue());
                List<ItemStack> readback = AnglerLootFoundation.bundleContents(bundle);
                ItemStack restored = ItemStack.deserializeBytes(bundle.serializeAsBytes());
                List<ItemStack> persisted = AnglerLootFoundation.bundleContents(restored);
                boolean valid = same(test.getValue(), readback) && same(test.getValue(), persisted);
                getLogger().info("BUNDLE_PROBE " + test.getKey() + "=" + (valid ? "PASS" : "FAIL")
                        + " expected_stacks=" + test.getValue().size()
                        + " readback_stacks=" + readback.size() + " persisted_stacks=" + persisted.size()
                        + " expected_items=" + count(test.getValue())
                        + " readback_items=" + count(readback) + " persisted_items=" + count(persisted));
            } catch (RuntimeException exception) {
                getLogger().severe("BUNDLE_PROBE " + test.getKey() + "=ERROR " + exception);
            }
        }
        try (InputStream resource = getResource("angler.yml")) {
            if (resource == null) throw new IllegalStateException("angler.yml fehlt");
            YamlConfiguration config = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(resource, StandardCharsets.UTF_8));
            for (AnglerLootFoundation.OverlevelBook definition :
                    AnglerLootFoundation.parseOverlevelBooks(config).values()) {
                ItemStack book = AnglerLootFoundation.createOverlevelBook(definition);
                boolean valid = book != null && book.getItemMeta() instanceof EnchantmentStorageMeta meta
                        && meta.getStoredEnchants().values().contains(definition.level());
                getLogger().info("OVERLEVEL_PROBE " + definition.id() + "=" + (valid ? "PASS" : "FAIL"));
            }
            runTreasureProbe(config);
            runJunkProbe(config);
            runRareProbe(config);
            runEpicProbe(config);
            runLegendaryProbe(config);
            runAnglerAnvilProbe();
            runRepairCoreProbe();
            runLibrarianSealProbe();
            runNachfassenProbe(config);
            runRuhigeHandProbe(config);
            runKonzentrationProbe(config);
            runMeistergriffProbe(config);
            runAfkIntervalProbe(config);
            runAfkLifecycleProbe();
            runTotemBindingProbe();
            runSoulboundProbe();
            getServer().getWorlds().getFirst().addPluginChunkTicket(4, 0, this);
            Bukkit.getScheduler().runTaskLater(this, this::runSpawnergriffProbe, 40L);
        } catch (Exception exception) {
            getLogger().severe("OVERLEVEL_PROBE=ERROR " + exception);
        }
        ItemStack damaged = new ItemStack(Material.DIAMOND_PICKAXE);
        Damageable damagedMeta = (Damageable) damaged.getItemMeta();
        NamespacedKey chargeKey = new NamespacedKey(this, "probe_custom_charges");
        damagedMeta.setDamage(100);
        damagedMeta.getPersistentDataContainer().set(chargeKey, PersistentDataType.INTEGER, 7);
        damaged.setItemMeta(damagedMeta);
        ItemStack repaired = AnglerLootFoundation.repairedDurabilityCopy(damaged);
        boolean repairedOnlyDurability = repaired != null
                && ((Damageable) repaired.getItemMeta()).getDamage() == 0
                && ((Damageable) damaged.getItemMeta()).getDamage() == 100
                && Integer.valueOf(7).equals(repaired.getItemMeta()
                .getPersistentDataContainer().get(chargeKey, PersistentDataType.INTEGER));
        getLogger().info("REPAIR_PRIMITIVE=" + (repairedOnlyDurability ? "PASS" : "FAIL"));
    }

    private void runAfkIntervalProbe(YamlConfiguration bundled) {
        int[] expected = {30, 27, 24, 21, 18, 15};
        YamlConfiguration configured = new YamlConfiguration();
        boolean defaults = true;
        for (int level = 0; level < expected.length; level++)
            defaults &= AnglerFishingService.afkIntervalSeconds(configured, bundled, level) == expected[level];
        configured.set("enchants.ausdauer.afk-interval-seconds.2", 19);
        boolean override = AnglerFishingService.afkIntervalSeconds(configured, bundled, 2) == 19;
        configured.set("enchants.ausdauer.afk-interval-seconds.2", -2);
        boolean invalid = AnglerFishingService.afkIntervalSeconds(configured, bundled, 2) == 24;
        ItemStack rod = new ItemStack(Material.FISHING_ROD);
        Enchantment lure = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("lure"));
        if (lure != null) rod.addUnsafeEnchantment(lure, 5);
        boolean lureIndependent = AnglerFishingService.afkIntervalSeconds(configured, bundled,
                probeEnchants.level(rod, "ausdauer")) == 30;
        getLogger().info("AFK_INTERVAL_DEFAULTS=" + (defaults ? "PASS" : "FAIL"));
        getLogger().info("AFK_INTERVAL_OVERRIDE=" + (override ? "PASS" : "FAIL"));
        getLogger().info("AFK_INTERVAL_INVALID_FALLBACK=" + (invalid ? "PASS" : "FAIL"));
        getLogger().info("AFK_LURE_INDEPENDENT=" + (lureIndependent ? "PASS" : "FAIL"));
    }

    private void runAfkLifecycleProbe() {
        int[] calls = new int[3]; // valid, reset count, actual approach ticks
        calls[0] = 1;
        FishHook hook = (FishHook) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {FishHook.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isValid" -> calls[0] == 1;
                    case "resetFishingState" -> { calls[1]++; yield null; }
                    case "setWaitTime", "setLureTime", "setApplyLure", "setRainInfluenced", "setSkyInfluenced" -> null;
                    case "setTimeUntilBite" -> { calls[2] = (Integer) args[0]; yield null; }
                    case "getTimeUntilBite" -> calls[2];
                    case "getWaitTime" -> 0;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        AnglerFishingService.prepareAfkHook(hook, 30);
        boolean enter = calls[1] == 1 && calls[2] == 600;
        AnglerFishingService.prepareAfkHook(hook, 15);
        boolean refresh = calls[1] == 2 && calls[2] == 300
                && AnglerFishingService.afkSecondsRemaining(hook) == 15;
        calls[0] = 0;
        AnglerFishingService.prepareAfkHook(hook, 15);
        boolean invalid = calls[1] == 2 && calls[2] == 300;
        boolean automaticEvents = !AnglerFishingService.playerFishingAction(PlayerFishEvent.State.LURED,
                EquipmentSlot.HAND)
                && !AnglerFishingService.playerFishingAction(PlayerFishEvent.State.BITE, EquipmentSlot.HAND)
                && !AnglerFishingService.playerFishingAction(PlayerFishEvent.State.FAILED_ATTEMPT,
                EquipmentSlot.HAND);
        boolean manualEvents = AnglerFishingService.playerFishingAction(PlayerFishEvent.State.FISHING,
                EquipmentSlot.HAND)
                && AnglerFishingService.playerFishingAction(PlayerFishEvent.State.REEL_IN, EquipmentSlot.HAND)
                && !AnglerFishingService.playerFishingAction(PlayerFishEvent.State.CAUGHT_FISH, null);
        getLogger().info("AFK_HOOK_CYCLE=" + (enter && refresh && invalid ? "PASS" : "FAIL"));
        getLogger().info("AFK_EVENT_ACTIVITY=" + (automaticEvents && manualEvents ? "PASS" : "FAIL"));
    }

    private boolean same(List<ItemStack> expected, List<ItemStack> actual) {
        if (actual.size() != expected.size()) return false;
        for (int i = 0; i < expected.size(); i++) {
            if (expected.get(i).getAmount() != actual.get(i).getAmount()
                    || !expected.get(i).isSimilar(actual.get(i))) return false;
        }
        return true;
    }

    private int count(List<ItemStack> items) { return items.stream().mapToInt(ItemStack::getAmount).sum(); }

    private ItemStack enchanted(Material material, String id, int level) {
        Enchantment enchantment = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(id));
        if (enchantment == null) throw new IllegalStateException("Test-Enchantment fehlt: " + id);
        ItemStack item = new ItemStack(material);
        item.addUnsafeEnchantment(enchantment, level);
        return item;
    }

    private void runTreasureProbe(YamlConfiguration config) {
        FishingTreasurePool pool = new FishingTreasurePool(config, getLogger());
        getLogger().info("TREASURE_READY=" + (pool.ready() ? "PASS" : "FAIL")
                + " entries=" + pool.weights().size()
                + " weight_sum=" + pool.weights().values().stream().mapToDouble(Double::doubleValue).sum());
        if (!pool.ready()) return;
        Map<String, Integer> counts = new LinkedHashMap<>();
        Set<String> checked = new HashSet<>();
        Random random = new Random(123L);
        boolean allValid = true;
        boolean duplicateTrim = false;
        for (int index = 0; index < 10_000; index++) {
            FishingTreasurePool.Result result = pool.roll(random);
            counts.merge(result.id(), 1, Integer::sum);
            if (result.id().equals("small_trim_bundle")
                    && result.reward() instanceof AnglerLootFoundation.BundleReward trim
                    && trim.contents().size() == 2
                    && trim.contents().get(0) instanceof AnglerLootFoundation.NormalItemReward first
                    && trim.contents().get(1) instanceof AnglerLootFoundation.NormalItemReward second
                    && first.item().getType() == second.item().getType()) duplicateTrim = true;
            if (checked.add(result.id()) && !validTreasure(result, config)) {
                getLogger().severe("TREASURE_ITEM_PROBE " + result.id() + "=FAIL");
                allValid = false;
            }
        }
        getLogger().info("TREASURE_ITEMS=" + (allValid && checked.size() == 8 ? "PASS" : "FAIL")
                + " checked=" + checked.size() + " trim_duplicates=" + duplicateTrim);
        for (Map.Entry<String, Integer> entry : counts.entrySet())
            getLogger().info("TREASURE_DISTRIBUTION " + entry.getKey() + "=" + entry.getValue());
    }

    private boolean validTreasure(FishingTreasurePool.Result result, YamlConfiguration config) {
        if (result.reward() instanceof AnglerLootFoundation.NormalItemReward normal) {
            ItemStack item = normal.item();
            if (result.id().equals("enchanted_book")) {
                if (!(item.getItemMeta() instanceof EnchantmentStorageMeta meta)
                        || meta.getStoredEnchants().size() != 1) return false;
                return meta.getStoredEnchants().entrySet().stream()
                        .allMatch(entry -> entry.getValue() > 0
                                && entry.getValue() <= entry.getKey().getMaxLevel());
            }
            String path = "loot.treasure.entries." + result.id();
            return item.getType().name().equals(config.getString(path + ".material"))
                    && item.getAmount() == config.getInt(path + ".amount");
        }
        if (result.reward() instanceof AnglerLootFoundation.BundleReward bundle) {
            List<ItemStack> stacks = new ArrayList<>();
            for (AnglerLootFoundation.LootReward part : bundle.contents()) {
                if (!(part instanceof AnglerLootFoundation.NormalItemReward normal)) return false;
                stacks.add(normal.item());
            }
            ItemStack vanilla = AnglerLootFoundation.createBundle(stacks);
            return same(stacks, AnglerLootFoundation.bundleContents(vanilla))
                    && same(stacks, AnglerLootFoundation.bundleContents(
                    ItemStack.deserializeBytes(vanilla.serializeAsBytes())));
        }
        return false;
    }

    private void runJunkProbe(YamlConfiguration config) {
        FishingJunkPool pool = new FishingJunkPool(config, getLogger());
        YamlConfiguration existingServerConfig = new YamlConfiguration();
        existingServerConfig.set("fishing.active.enabled", true);
        existingServerConfig.set("loot.pools.prestige-0.fish", 90D);
        existingServerConfig.setDefaults(config);
        FishingJunkPool inherited = new FishingJunkPool(existingServerConfig, getLogger());
        getLogger().info("JUNK_EXISTING_YAML_DEFAULTS="
                + (inherited.ready() && inherited.weights().size() == 11 ? "PASS" : "FAIL"));
        getLogger().info("JUNK_READY=" + (pool.ready() ? "PASS" : "FAIL")
                + " entries=" + pool.weights().size()
                + " weight_sum=" + pool.weights().values().stream().mapToDouble(Double::doubleValue).sum());
        if (!pool.ready()) return;
        Map<String, Integer> counts = new LinkedHashMap<>();
        Random random = new Random(541L);
        boolean allValid = true;
        for (int index = 0; index < 10_000; index++) {
            FishingJunkPool.Result result = pool.roll(random);
            counts.merge(result.id(), 1, Integer::sum);
            if (!validJunk(result, config)) {
                getLogger().severe("JUNK_ITEM_PROBE " + result.id() + "=FAIL");
                allValid = false;
                break;
            }
        }
        boolean distribution = counts.size() == 11;
        for (Map.Entry<String, Double> weight : pool.weights().entrySet()) {
            int expected = (int) (weight.getValue() * 100);
            distribution &= Math.abs(counts.getOrDefault(weight.getKey(), 0) - expected)
                    <= Math.max(50, expected * 0.15);
            getLogger().info("JUNK_DISTRIBUTION " + weight.getKey() + "="
                    + counts.getOrDefault(weight.getKey(), 0) + "/" + expected);
        }
        getLogger().info("JUNK_ITEMS=" + (allValid ? "PASS" : "FAIL"));
        getLogger().info("JUNK_DISTRIBUTION=" + (distribution ? "PASS" : "FAIL"));
    }

    private boolean validJunk(FishingJunkPool.Result result, YamlConfiguration config) {
        if (!(result.reward() instanceof AnglerLootFoundation.NormalItemReward normal)) return false;
        ItemStack item = normal.item();
        String path = "loot.junk.entries." + result.id();
        if (!item.getType().name().equals(config.getString(path + ".material"))
                || !item.getEnchantments().isEmpty()
                || item.getAmount() < config.getInt(path + ".min-amount", config.getInt(path + ".amount"))
                || item.getAmount() > config.getInt(path + ".max-amount", config.getInt(path + ".amount")))
            return false;
        if (config.contains(path + ".min-durability-percent")) {
            if (!(item.getItemMeta() instanceof Damageable damaged)) return false;
            int maximum = item.getType().getMaxDurability();
            double remainingPercent = 100D * (maximum - damaged.getDamage()) / maximum;
            return remainingPercent >= config.getInt(path + ".min-durability-percent") - 1D
                    && remainingPercent <= config.getInt(path + ".max-durability-percent") + 1D;
        }
        return !(item.getItemMeta() instanceof Damageable damaged) || damaged.getDamage() == 0;
    }

    private void runRareProbe(YamlConfiguration config) {
        Map<String, AnglerLootFoundation.OverlevelBook> books = AnglerLootFoundation.parseOverlevelBooks(config);
        FishingRarePool pool = new FishingRarePool(config,
                new FishingTreasurePool(config, getLogger()), books, getLogger());
        getLogger().info("RARE_READY=" + (pool.ready() ? "PASS" : "FAIL")
                + " entries=" + pool.weights().size()
                + " weight_sum=" + pool.weights().values().stream().mapToDouble(Double::doubleValue).sum());
        YamlConfiguration existingServer = new YamlConfiguration();
        existingServer.set("loot.pools.prestige-1.fish", 89.4D);
        existingServer.setDefaults(config);
        FishingRarePool inherited = new FishingRarePool(existingServer,
                new FishingTreasurePool(existingServer, getLogger()), books, getLogger());
        getLogger().info("RARE_EXISTING_YAML_DEFAULTS="
                + (inherited.ready() && inherited.entryIds().size() == 17 ? "PASS" : "FAIL"));
        if (!pool.ready()) return;
        boolean allValid = true;
        Random random = new Random(903L);
        for (String id : pool.entryIds()) {
            try {
                FishingRarePool.Result result = pool.rollSpecific(id, random, 5);
                if (!validRare(result, config, books)) allValid = false;
                getLogger().info("RARE_ENTRY " + id + "=" + (validRare(result, config, books) ? "PASS" : "FAIL"));
            } catch (RuntimeException exception) {
                allValid = false;
                getLogger().severe("RARE_ENTRY " + id + "=ERROR " + exception);
            }
        }
        getLogger().info("RARE_ITEMS=" + (allValid ? "PASS" : "FAIL"));
        boolean prestigeGates = !pool.hasEligibleOverlevel(0);
        for (int prestige = 1; prestige <= 5; prestige++) {
            prestigeGates &= pool.hasEligibleOverlevel(prestige);
            for (int draw = 0; draw < 100; draw++) {
                FishingRarePool.Result result = pool.rollSpecific("overlevel_book", random, prestige);
                if (!(result.reward() instanceof AnglerLootFoundation.OverlevelBookReward reward)
                        || books.get(reward.id()).minPrestige() > prestige) prestigeGates = false;
            }
        }
        for (int draw = 0; draw < 1_000; draw++)
            prestigeGates &= !pool.rollEntryId(random, 0).equals("overlevel_book");
        getLogger().info("RARE_PRESTIGE_GATES=" + (prestigeGates ? "PASS" : "FAIL"));
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int draw = 0; draw < 10_000; draw++)
            counts.merge(pool.rollEntryId(random, 5), 1, Integer::sum);
        boolean distribution = counts.size() == 17;
        for (Map.Entry<String, Double> weight : pool.weights().entrySet()) {
            int expected = (int) (weight.getValue() * 100);
            distribution &= Math.abs(counts.getOrDefault(weight.getKey(), 0) - expected)
                    <= Math.max(60, expected * 0.15);
            getLogger().info("RARE_DISTRIBUTION " + weight.getKey() + "="
                    + counts.getOrDefault(weight.getKey(), 0) + "/" + expected);
        }
        getLogger().info("RARE_DISTRIBUTION=" + (distribution ? "PASS" : "FAIL"));
    }

    private boolean validRare(FishingRarePool.Result result, YamlConfiguration config,
                              Map<String, AnglerLootFoundation.OverlevelBook> books) {
        if (result.reward() instanceof AnglerLootFoundation.CustomItemReward custom)
            return result.id().equals("repair_core") && custom.id().equals("reparaturkern")
                    && custom.amount() == 1;
        if (result.reward() instanceof AnglerLootFoundation.OverlevelBookReward overlevel) {
            AnglerLootFoundation.OverlevelBook definition = books.get(overlevel.id());
            return definition != null && definition.minPrestige() <= overlevel.anglerPrestige()
                    && AnglerLootFoundation.createOverlevelBook(definition) != null;
        }
        if (!(result.reward() instanceof AnglerLootFoundation.BundleReward bundle)) return false;
        List<ItemStack> stacks = new ArrayList<>();
        for (AnglerLootFoundation.LootReward reward : bundle.contents()) {
            if (!(reward instanceof AnglerLootFoundation.NormalItemReward normal)) return false;
            stacks.add(normal.item());
        }
        ItemStack vanilla = AnglerLootFoundation.createBundle(stacks);
        boolean valid = same(stacks, AnglerLootFoundation.bundleContents(vanilla))
                && same(stacks, AnglerLootFoundation.bundleContents(
                ItemStack.deserializeBytes(vanilla.serializeAsBytes())));
        String path = "loot.rare.entries." + result.id();
        String type = config.getString(path + ".type", "");
        if (type.equals("BUNDLE")) {
            List<Map<?, ?>> configured = config.getMapList(path + ".contents");
            valid &= configured.size() == stacks.size();
            for (int i = 0; i < Math.min(configured.size(), stacks.size()); i++)
                valid &= stacks.get(i).getType().name().equals(configured.get(i).get("material"))
                        && stacks.get(i).getAmount() == ((Number) configured.get(i).get("amount")).intValue();
        } else if (type.equals("VANILLA_BOOK_BUNDLE")) {
            valid &= stacks.size() == config.getInt(path + ".count");
            for (ItemStack book : stacks) {
                valid &= book.getType() == Material.ENCHANTED_BOOK
                        && book.getItemMeta() instanceof EnchantmentStorageMeta meta
                        && meta.getStoredEnchants().size() == 1
                        && meta.getStoredEnchants().entrySet().stream()
                        .allMatch(enchant -> enchant.getValue() <= enchant.getKey().getMaxLevel());
            }
        } else if (type.equals("UNIQUE_TRIM_BUNDLE")) {
            Set<Material> unique = new HashSet<>();
            valid &= stacks.size() >= config.getInt(path + ".min-types")
                    && stacks.size() <= config.getInt(path + ".max-types");
            for (ItemStack item : stacks)
                valid &= unique.add(item.getType())
                        && config.contains(path + ".templates." + item.getType().name())
                        && item.getAmount() >= config.getInt(path + ".min-amount-per-type")
                        && item.getAmount() <= config.getInt(path + ".max-amount-per-type");
        } else if (type.equals("EQUIPMENT_BUNDLE")) {
            valid &= stacks.size() == config.getMapList(path + ".items").size();
            for (ItemStack item : stacks)
                valid &= item.getItemMeta() instanceof Damageable damageable
                        && damageable.getDamage() == 0
                        && item.getEnchantments().size() == 3
                        && item.getEnchantments().entrySet().stream()
                        .allMatch(enchant -> enchant.getValue() <= enchant.getKey().getMaxLevel()
                                && !enchant.getKey().getKey().getKey().equals("mending"));
        }
        return valid;
    }

    private void runEpicProbe(YamlConfiguration config) {
        Map<String, AnglerLootFoundation.OverlevelBook> books = AnglerLootFoundation.parseOverlevelBooks(config);
        FishingRarePool rare = new FishingRarePool(config,
                new FishingTreasurePool(config, getLogger()), books, getLogger());
        FishingEpicPool pool = new FishingEpicPool(config, rare, getLogger());
        getLogger().info("EPIC_READY=" + (pool.ready() && pool.entryIds().size() == 19
                && pool.weights().values().stream().mapToDouble(Double::doubleValue).sum() == 100D
                ? "PASS" : "FAIL"));
        YamlConfiguration inheritedConfig = new YamlConfiguration();
        inheritedConfig.set("loot.pools.prestige-3.epic", 0.6D);
        inheritedConfig.setDefaults(config);
        FishingRarePool inheritedRare = new FishingRarePool(inheritedConfig,
                new FishingTreasurePool(inheritedConfig, getLogger()), books, getLogger());
        FishingEpicPool inherited = new FishingEpicPool(inheritedConfig, inheritedRare, getLogger());
        getLogger().info("EPIC_EXISTING_YAML_DEFAULTS=" + (inherited.ready() ? "PASS" : "FAIL"));
        if (!pool.ready()) return;
        boolean entriesValid = true;
        Random random = new Random(1147L);
        for (String id : pool.entryIds()) {
            try {
                FishingEpicPool.Result result = pool.rollSpecific(id, random, 5);
                boolean valid = validEpic(result, config, books);
                entriesValid &= valid;
                getLogger().info("EPIC_ENTRY " + id + "=" + (valid ? "PASS" : "FAIL"));
            } catch (RuntimeException exception) {
                entriesValid = false;
                getLogger().severe("EPIC_ENTRY " + id + "=ERROR " + exception);
            }
        }
        getLogger().info("EPIC_ITEMS=" + (entriesValid ? "PASS" : "FAIL"));
        boolean gates = !pool.hasEligibleOverlevel(0);
        int vanillaBooks = 0;
        int customBooks = 0;
        Set<String> seenCustom = new HashSet<>();
        for (int prestige = 0; prestige <= 5; prestige++) {
            for (int draw = 0; draw < 1_000; draw++) {
                FishingEpicPool.Result enchantment = pool.rollSpecific("enchantment_plus", random, prestige);
                if (!(enchantment.reward() instanceof AnglerLootFoundation.BundleReward bundle)) {
                    gates = false; continue;
                }
                for (AnglerLootFoundation.LootReward part : bundle.contents()) {
                    if (part instanceof AnglerLootFoundation.CustomItemReward custom) {
                        if (prestige == 5) { customBooks++; seenCustom.add(custom.id()); }
                        int unlock = config.getInt("loot.epic.entries.enchantment_plus.custom-books."
                                + custom.id().replace("angler_buch_", "") + ".min-prestige", -1);
                        if (unlock < 1 || unlock > prestige) gates = false;
                    } else if (part instanceof AnglerLootFoundation.NormalItemReward normal) {
                        if (prestige == 5) vanillaBooks++;
                        if (!(normal.item().getItemMeta() instanceof EnchantmentStorageMeta meta)
                                || meta.getStoredEnchants().size() != 1
                                || meta.getStoredEnchants().entrySet().stream().anyMatch(entry ->
                                entry.getValue() > entry.getKey().getMaxLevel())) gates = false;
                    } else gates = false;
                }
            }
            if (prestige > 0) for (int draw = 0; draw < 100; draw++) {
                FishingEpicPool.Result overlevel = pool.rollSpecific("overlevel_book", random, prestige);
                if (!(overlevel.reward() instanceof AnglerLootFoundation.OverlevelBookReward book)
                        || books.get(book.id()).minPrestige() > prestige) gates = false;
            }
        }
        for (int draw = 0; draw < 1_000; draw++) gates &= !pool.rollEntryId(random, 0).equals("overlevel_book");
        getLogger().info("EPIC_PRESTIGE_GATES=" + (gates ? "PASS" : "FAIL"));
        getLogger().info("EPIC_BOOK_MIX=" + (customBooks > 0 && customBooks < vanillaBooks
                && seenCustom.size() == 15 ? "PASS" : "FAIL")
                + " vanilla=" + vanillaBooks + " custom=" + customBooks
                + " unique_custom=" + seenCustom.size());
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int draw = 0; draw < 10_000; draw++) counts.merge(pool.rollEntryId(random, 5), 1, Integer::sum);
        boolean distribution = counts.size() == 19;
        for (Map.Entry<String, Double> weight : pool.weights().entrySet()) {
            int expected = (int) (weight.getValue() * 100);
            int observed = counts.getOrDefault(weight.getKey(), 0);
            distribution &= Math.abs(observed - expected) <= Math.max(60, expected * 0.2);
            getLogger().info("EPIC_DISTRIBUTION " + weight.getKey() + "=" + observed + "/" + expected);
        }
        getLogger().info("EPIC_DISTRIBUTION=" + (distribution ? "PASS" : "FAIL"));
        if (probeFoundation != null) runEpicDeliveryProbe(pool);
    }

    private void runEpicDeliveryProbe(FishingEpicPool pool) {
        Random random = new Random(809L);
        boolean allPhysical = true;
        for (String id : pool.entryIds()) {
            FishingEpicPool.Result result = pool.rollSpecific(id, random, 5);
            if (result.reward() instanceof AnglerLootFoundation.CurrencyReward) continue;
            try {
                ItemStack item = probeFoundation.createPhysicalReward(result.reward());
                boolean valid = item != null;
                if (result.reward() instanceof AnglerLootFoundation.BundleReward bundle) {
                    List<ItemStack> expected = new ArrayList<>();
                    for (AnglerLootFoundation.LootReward content : bundle.contents())
                        expected.add(probeFoundation.createPhysicalReward(content));
                    valid &= item.getType() == Material.BUNDLE
                            && same(expected, AnglerLootFoundation.bundleContents(item))
                            && same(expected, AnglerLootFoundation.bundleContents(
                            ItemStack.deserializeBytes(item.serializeAsBytes())));
                }
                if (id.equals("magnet_1")) valid &= "magnet_buch".equals(probeItems.identify(item));
                if (id.equals("librarian_seal")) valid &= "bibliothekarsiegel".equals(probeItems.identify(item));
                allPhysical &= valid;
                getLogger().info("EPIC_PHYSICAL " + id + "=" + (valid ? "PASS" : "FAIL"));
            } catch (RuntimeException exception) {
                allPhysical = false;
                getLogger().severe("EPIC_PHYSICAL " + id + "=ERROR " + exception);
            }
        }
        getLogger().info("EPIC_PHYSICAL_ITEMS=" + (allPhysical ? "PASS" : "FAIL"));
        UUID accountId = UUID.fromString("7ed14345-0311-4612-b418-a25e6bc49979");
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> accountId;
                    case "getName" -> "EpicProbe";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        long before = probeLumis.balance(accountId);
        var first = probeFoundation.deliver(player, pool.rollSpecific("lumi_1", random, 5).reward(), null);
        long middle = probeLumis.balance(accountId);
        var second = probeFoundation.deliver(player, pool.rollSpecific("lumi_2", random, 5).reward(), null);
        long after = probeLumis.balance(accountId);
        boolean credited = first.success() && second.success() && first.lumisCredited() == 10L
                && second.lumisCredited() == 25L && first.storedItems() == 0 && second.storedItems() == 0
                && middle - before == 10L && after - middle == 25L;
        getLogger().info("EPIC_LUMI_DELIVERY=" + (credited ? "PASS" : "FAIL"));
    }

    private boolean validEpic(FishingEpicPool.Result result, YamlConfiguration config,
                              Map<String, AnglerLootFoundation.OverlevelBook> books) {
        String path = "loot.epic.entries." + result.id();
        String type = config.getString(path + ".type", "");
        if (result.reward() instanceof AnglerLootFoundation.CurrencyReward currency)
            return type.equals("LUMI") && currency.type() == AnglerLootFoundation.RewardType.LUMI
                    && currency.amount() == config.getLong(path + ".amount");
        if (result.reward() instanceof AnglerLootFoundation.CustomItemReward custom)
            return type.equals("CUSTOM_ITEM") && custom.id().equals(config.getString(path + ".item-id"))
                    && custom.amount() == 1;
        if (result.reward() instanceof AnglerLootFoundation.OverlevelBookReward book)
            return type.equals("OVERLEVEL_BOOK") && books.containsKey(book.id())
                    && AnglerLootFoundation.createOverlevelBook(books.get(book.id())) != null;
        if (result.reward() instanceof AnglerLootFoundation.NormalItemReward normal)
            return type.equals("RANDOM_TOOL") && normal.item().getItemMeta() instanceof Damageable damageable
                    && damageable.getDamage() == 0 && normal.item().getEnchantments().size() == 4;
        if (!(result.reward() instanceof AnglerLootFoundation.BundleReward bundle)) return false;
        List<ItemStack> physical = new ArrayList<>();
        for (AnglerLootFoundation.LootReward part : bundle.contents()) {
            if (part instanceof AnglerLootFoundation.NormalItemReward normal) physical.add(normal.item());
            else if (part instanceof AnglerLootFoundation.CustomItemReward custom) {
                // The live CustomItemManager creates these; ensure their exact YAML IDs exist.
                try (InputStream itemsStream = getResource("items.yml")) {
                    if (itemsStream == null) return false;
                    YamlConfiguration itemsConfig = YamlConfiguration.loadConfiguration(
                            new InputStreamReader(itemsStream, StandardCharsets.UTF_8));
                    if (!itemsConfig.contains("items." + custom.id())) return false;
                } catch (Exception exception) { return false; }
                physical.add(new ItemStack(Material.ENCHANTED_BOOK));
            } else return false;
        }
        ItemStack item = AnglerLootFoundation.createBundle(physical);
        boolean valid = same(physical, AnglerLootFoundation.bundleContents(item))
                && same(physical, AnglerLootFoundation.bundleContents(
                ItemStack.deserializeBytes(item.serializeAsBytes())));
        if (type.equals("BOOK_BUNDLE")) valid &= physical.size() == 3;
        if (type.equals("RANDOM_TRIM_BUNDLE")) valid &= physical.size() >= config.getInt(path + ".min-count")
                && physical.size() <= config.getInt(path + ".max-count");
        if (type.equals("EQUIPMENT_BUNDLE")) valid &= physical.size() == 3 && physical.stream()
                .allMatch(stack -> stack.getEnchantments().size() == 4
                        && stack.getItemMeta() instanceof Damageable damageable && damageable.getDamage() == 0);
        if (type.equals("BUNDLE")) valid &= physical.size() == config.getMapList(path + ".contents").size();
        return valid;
    }

    private void runLegendaryProbe(YamlConfiguration config) {
        Map<String, AnglerLootFoundation.OverlevelBook> books = AnglerLootFoundation.parseOverlevelBooks(config);
        FishingRarePool rare = new FishingRarePool(config,
                new FishingTreasurePool(config, getLogger()), books, getLogger());
        FishingLegendaryPool pool = new FishingLegendaryPool(config, rare, getLogger());
        boolean outerUnchanged = config.getDouble("loot.pools.prestige-5.legendary") == 0.01D;
        for (int prestige = 0; prestige < 5; prestige++)
            outerUnchanged &= config.getDouble("loot.pools.prestige-" + prestige + ".legendary") == 0D;
        getLogger().info("LEGENDARY_OUTER_CHANCE=" + (outerUnchanged ? "PASS" : "FAIL"));
        getLogger().info("LEGENDARY_READY=" + (pool.ready() && pool.entryIds().size() == 10
                && pool.weights().values().stream().mapToDouble(Double::doubleValue).sum() == 100D
                && pool.requiredPrestige() == 5 ? "PASS" : "FAIL"));
        YamlConfiguration olderServer = new YamlConfiguration();
        olderServer.set("loot.pools.prestige-5.legendary", 0.01D);
        olderServer.setDefaults(config);
        FishingRarePool inheritedRare = new FishingRarePool(olderServer,
                new FishingTreasurePool(olderServer, getLogger()), books, getLogger());
        FishingLegendaryPool inherited = new FishingLegendaryPool(olderServer, inheritedRare, getLogger());
        getLogger().info("LEGENDARY_EXISTING_YAML_DEFAULTS=" + (inherited.ready()
                && inherited.entryIds().size() == 10 ? "PASS" : "FAIL"));
        if (!pool.ready()) return;
        Random random = new Random(3951L);
        boolean prestigeGate = true;
        for (int prestige = 0; prestige < 5; prestige++) {
            try { pool.rollEntryId(random, prestige); prestigeGate = false; }
            catch (IllegalStateException expected) { }
            try { pool.rollSpecific("totem_binding", random, prestige); prestigeGate = false; }
            catch (IllegalStateException expected) { }
        }
        getLogger().info("LEGENDARY_PRESTIGE_GATE=" + (prestigeGate ? "PASS" : "FAIL"));
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int draw = 0; draw < 10_000; draw++) counts.merge(pool.rollEntryId(random, 5), 1, Integer::sum);
        boolean distribution = counts.size() == 10;
        for (Map.Entry<String, Double> weight : pool.weights().entrySet()) {
            int expected = (int) (weight.getValue() * 100);
            int observed = counts.getOrDefault(weight.getKey(), 0);
            distribution &= Math.abs(observed - expected) <= Math.max(80, expected * 0.15);
            getLogger().info("LEGENDARY_DISTRIBUTION " + weight.getKey() + "=" + observed + "/" + expected);
        }
        getLogger().info("LEGENDARY_DISTRIBUTION=" + (distribution ? "PASS" : "FAIL"));
        boolean allValid = true;
        for (String id : pool.entryIds()) {
            try {
                FishingLegendaryPool.Result result = pool.rollSpecific(id, random, 5);
                boolean valid = validLegendary(result, config, books);
                allValid &= valid;
                getLogger().info("LEGENDARY_ENTRY " + id + "=" + (valid ? "PASS" : "FAIL"));
            } catch (Exception exception) {
                allValid = false;
                getLogger().severe("LEGENDARY_ENTRY " + id + "=ERROR " + exception);
            }
        }
        getLogger().info("LEGENDARY_ITEMS=" + (allValid ? "PASS" : "FAIL"));
        Set<String> seenOverlevel = new HashSet<>();
        boolean duplicateOverlevel = false;
        boolean fiveBooksEveryTime = true;
        for (int draw = 0; draw < 1_000; draw++) {
            FishingLegendaryPool.Result result = pool.rollSpecific("legendary_enchantment_bundle", random, 5);
            if (!(result.reward() instanceof AnglerLootFoundation.BundleReward bundle)
                    || bundle.contents().size() != 5) {
                fiveBooksEveryTime = false;
                continue;
            }
            Set<String> inBundle = new HashSet<>();
            for (AnglerLootFoundation.LootReward part : bundle.contents()) {
                if (part instanceof AnglerLootFoundation.OverlevelBookReward book) {
                    seenOverlevel.add(book.id());
                    if (!inBundle.add(book.id())) duplicateOverlevel = true;
                }
            }
        }
        long eligibleBooks = books.values().stream().filter(book -> book.minPrestige() <= 5).count();
        getLogger().info("LEGENDARY_BOOK_ROLLS=" + (fiveBooksEveryTime && duplicateOverlevel
                && seenOverlevel.size() == eligibleBooks ? "PASS" : "FAIL")
                + " eligible=" + eligibleBooks + " seen=" + seenOverlevel.size()
                + " duplicate=" + duplicateOverlevel);
        if (probeFoundation != null && probeStorage != null) runLegendaryDeliveryProbe(pool);
    }

    private boolean validLegendary(FishingLegendaryPool.Result result, YamlConfiguration config,
                                   Map<String, AnglerLootFoundation.OverlevelBook> books) {
        String path = "loot.legendary.entries." + result.id();
        String type = config.getString(path + ".type", "");
        if (result.reward() instanceof AnglerLootFoundation.CurrencyReward currency)
            return type.equals("LUMI") && currency.type() == AnglerLootFoundation.RewardType.LUMI
                    && currency.amount() == config.getLong(path + ".amount");
        if (result.reward() instanceof AnglerLootFoundation.CustomItemReward custom)
            return type.equals("CUSTOM_ITEM") && custom.id().equals(config.getString(path + ".item-id"))
                    && custom.amount() == 1;
        if (!(result.reward() instanceof AnglerLootFoundation.BundleReward bundle)) return false;
        if (type.equals("BOOK_BUNDLE")) {
            int overlevels = 0;
            int vanilla = 0;
            for (AnglerLootFoundation.LootReward part : bundle.contents()) {
                if (part instanceof AnglerLootFoundation.OverlevelBookReward book) {
                    AnglerLootFoundation.OverlevelBook definition = books.get(book.id());
                    if (definition == null || definition.minPrestige() > 5
                            || AnglerLootFoundation.createOverlevelBook(definition) == null) return false;
                    overlevels++;
                } else if (part instanceof AnglerLootFoundation.NormalItemReward normal) {
                    if (normal.item().getType() != Material.ENCHANTED_BOOK
                            || !(normal.item().getItemMeta() instanceof EnchantmentStorageMeta meta)
                            || meta.getStoredEnchants().size() != 1
                            || meta.getStoredEnchants().entrySet().stream().anyMatch(entry ->
                            entry.getValue() > entry.getKey().getMaxLevel())) return false;
                    vanilla++;
                } else return false;
            }
            return overlevels == config.getInt(path + ".overlevel-count")
                    && vanilla == config.getInt(path + ".vanilla-count") && overlevels + vanilla == 5;
        }
        if (type.equals("EQUIPMENT_BUNDLE")) {
            List<Map<?, ?>> configured = config.getMapList(path + ".items");
            if (configured.size() != bundle.contents().size()) return false;
            for (int index = 0; index < configured.size(); index++) {
                if (!(bundle.contents().get(index) instanceof AnglerLootFoundation.NormalItemReward normal)) return false;
                ItemStack item = normal.item();
                if (!item.getType().name().equals(configured.get(index).get("material"))
                        || !(item.getItemMeta() instanceof Damageable damageable) || damageable.getDamage() != 0
                        || !(configured.get(index).get("enchantments") instanceof Map<?, ?> enchantments)
                        || item.getEnchantments().size() != enchantments.size()) return false;
                for (Map.Entry<?, ?> enchant : enchantments.entrySet()) {
                    Enchantment resolved = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(
                            String.valueOf(enchant.getKey())));
                    if (resolved == null || item.getEnchantmentLevel(resolved)
                            != ((Number) enchant.getValue()).intValue()) return false;
                }
            }
            return true;
        }
        if (type.equals("BUNDLE")) {
            List<Map<?, ?>> configured = config.getMapList(path + ".contents");
            if (configured.size() != bundle.contents().size()) return false;
            for (int index = 0; index < configured.size(); index++) {
                Object custom = configured.get(index).get("item-id");
                AnglerLootFoundation.LootReward part = bundle.contents().get(index);
                if (custom != null) {
                    if (!(part instanceof AnglerLootFoundation.CustomItemReward reward)
                            || !reward.id().equals(custom) || reward.amount() != 1) return false;
                } else if (!(part instanceof AnglerLootFoundation.NormalItemReward normal)
                        || !normal.item().getType().name().equals(configured.get(index).get("material"))
                        || normal.item().getAmount() != ((Number) configured.get(index).get("amount")).intValue())
                    return false;
            }
            return true;
        }
        return false;
    }

    private void runLegendaryDeliveryProbe(FishingLegendaryPool pool) {
        Random random = new Random(2147L);
        AnglerStorageRepository catchStorage = new AnglerStorageRepository(probeStorage);
        UUID catchOwner = UUID.fromString("4e2c5174-073a-4760-86ca-6e560825469a");
        boolean physical = true;
        for (String id : pool.entryIds()) {
            FishingLegendaryPool.Result result = pool.rollSpecific(id, random, 5);
            if (result.reward() instanceof AnglerLootFoundation.CurrencyReward) continue;
            try {
                ItemStack item = probeFoundation.createPhysicalReward(result.reward());
                boolean valid = item != null;
                if (result.reward() instanceof AnglerLootFoundation.BundleReward bundle) {
                    List<ItemStack> expected = new ArrayList<>();
                    for (AnglerLootFoundation.LootReward part : bundle.contents())
                        expected.add(probeFoundation.createPhysicalReward(part));
                    valid &= item.getType() == Material.BUNDLE
                            && same(expected, AnglerLootFoundation.bundleContents(item))
                            && same(expected, AnglerLootFoundation.bundleContents(
                            ItemStack.deserializeBytes(item.serializeAsBytes())));
                } else if (result.reward() instanceof AnglerLootFoundation.CustomItemReward custom)
                    valid &= custom.id().equals(probeItems.identify(item));
                ItemStack[] contents = new ItemStack[54];
                contents[0] = item;
                catchStorage.save(catchOwner, contents);
                ItemStack restored = catchStorage.load(catchOwner)[0];
                valid &= restored != null && item.isSimilar(restored)
                        && item.getAmount() == restored.getAmount();
                if (result.reward() instanceof AnglerLootFoundation.CustomItemReward custom)
                    valid &= custom.id().equals(probeItems.identify(restored));
                if (item.getType() == Material.BUNDLE)
                    valid &= same(AnglerLootFoundation.bundleContents(item),
                            AnglerLootFoundation.bundleContents(restored));
                physical &= valid;
                getLogger().info("LEGENDARY_PHYSICAL " + id + "=" + (valid ? "PASS" : "FAIL"));
            } catch (Exception exception) {
                physical = false;
                getLogger().severe("LEGENDARY_PHYSICAL " + id + "=ERROR " + exception);
            }
        }
        getLogger().info("LEGENDARY_PHYSICAL_PERSISTENCE=" + (physical ? "PASS" : "FAIL"));
        UUID accountId = UUID.fromString("69700f6d-edce-4335-80e8-119ac941ac97");
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> accountId;
                    case "getName" -> "LegendaryProbe";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        long before = probeLumis.balance(accountId);
        var delivery = probeFoundation.deliver(player,
                pool.rollSpecific("legendary_lumi", random, 5).reward(), null);
        long after = probeLumis.balance(accountId);
        boolean credited = delivery.success() && delivery.lumisCredited() == 50L
                && delivery.storedItems() == 0 && delivery.droppedItems() == 0 && after - before == 50L;
        try {
            probeStorage.close();
            probeStorage.initialize();
            credited &= new LumiRepository(probeStorage).balance(accountId) == after;
        } catch (Exception exception) {
            credited = false;
            getLogger().severe("LEGENDARY_LUMI_PERSISTENCE=ERROR " + exception);
        }
        getLogger().info("LEGENDARY_LUMI_PERSISTENCE=" + (credited ? "PASS" : "FAIL"));
    }

    private void runAnglerAnvilProbe() {
        if (probeItems == null || probeEnchants == null || probeStorage == null) {
            getLogger().info("ANGLER_ANVIL_SETUP=FAIL");
            return;
        }
        CustomEnchantmentAnvilListener anvil = new CustomEnchantmentAnvilListener(probeEnchants, probeItems);
        boolean books = true;
        int bookCount = 0;
        YamlConfiguration defaults;
        try (InputStream stream = getResource("items.yml")) {
            if (stream == null) throw new IllegalStateException("items.yml fehlt");
            defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            getLogger().severe("ANGLER_ANVIL_BOOKS=ERROR " + exception);
            return;
        }
        for (String id : defaults.getConfigurationSection("items").getKeys(false)) {
            AnglerEnchantmentDefinitions.BookLevel definition = AnglerEnchantmentDefinitions.bookLevel(id);
            if (definition == null) continue;
            bookCount++;
            ItemStack book = probeItems.create(id, 1);
            boolean valid = book != null && id.equals(probeItems.identify(book))
                    && probeEnchants.level(book, definition.enchantmentId()) == definition.level()
                    && vanillaBookName(book);
            books &= valid;
            if (!valid) getLogger().warning("ANGLER_ANVIL_BOOK_FAILURE " + id + " actual="
                    + level(book, definition.enchantmentId()) + " expected=" + definition.level()
                    + " definition=" + probeItems.find(id).map(value -> value.customEnchantments()).orElse(Map.of())
                    + " configured=" + configs().items().getConfigurationSection("items." + id)
                    .isSet("custom-enchantments")
                    + " default=" + (configs().items().getDefaults() == null ? "none" :
                    configs().items().getDefaults().getConfigurationSection("items." + id)
                            .getConfigurationSection("custom-enchantments")));
        }
        getLogger().info("ANGLER_ANVIL_BOOKS=" + (books && bookCount == 18 ? "PASS" : "FAIL")
                + " count=" + bookCount);
        YamlConfiguration olderServer = new YamlConfiguration();
        olderServer.set("items.angler_buch_ausdauer_1.material", "ENCHANTED_BOOK");
        olderServer.setDefaults(defaults);
        boolean oldYamlDefaults = olderServer.getConfigurationSection("items.angler_buch_ausdauer_1")
                .getInt("custom-enchantments.ausdauer") == 1;
        getLogger().info("ANGLER_ANVIL_OLD_YAML_DEFAULTS=" + (oldYamlDefaults ? "PASS" : "FAIL"));

        ItemStack aus1 = probeItems.create("angler_buch_ausdauer_1", 1);
        ItemStack aus2 = probeItems.create("angler_buch_ausdauer_2", 1);
        ItemStack aus3 = probeItems.create("angler_buch_ausdauer_3", 1);
        ItemStack aus5 = probeItems.create("angler_buch_ausdauer_5", 1);
        ItemStack ruhig1 = probeItems.create("angler_buch_ruhige_hand_1", 1);
        ItemStack totem1 = probeItems.create("angler_buch_totembindung_1", 1);
        ItemStack spawner1 = probeItems.create("angler_buch_spawnergriff_1", 1);
        ItemStack soul1 = probeItems.create("angler_buch_seelenbindung_1", 1);
        boolean bookItem = level(mergeResult(anvil, new ItemStack(Material.FISHING_ROD), aus1), "ausdauer") == 1
                && level(mergeResult(anvil, new ItemStack(Material.SHIELD), totem1), "totembindung") == 1
                && level(mergeResult(anvil, new ItemStack(Material.DIAMOND_PICKAXE), spawner1), "spawnergriff") == 1
                && level(mergeResult(anvil, new ItemStack(Material.NETHERITE_SWORD), soul1), "seelenbindung") == 1;
        getLogger().info("ANGLER_ANVIL_BOOK_ITEM=" + (bookItem ? "PASS" : "FAIL"));
        boolean incompatible = invalidMerge(anvil, new ItemStack(Material.NETHERITE_SWORD), aus1)
                && invalidMerge(anvil, new ItemStack(Material.DIAMOND_PICKAXE), totem1)
                && invalidMerge(anvil, new ItemStack(Material.DIAMOND_AXE), spawner1);
        getLogger().info("ANGLER_ANVIL_INCOMPATIBLE=" + (incompatible ? "PASS" : "FAIL"));

        ItemStack combined11 = mergeResult(anvil, aus1, aus1);
        ItemStack combined22 = mergeResult(anvil, aus2, aus2);
        ItemStack combined32 = mergeResult(anvil, aus3, aus2);
        ItemStack combined23 = mergeResult(anvil, aus2, aus3);
        ItemStack combinedDifferent = mergeResult(anvil, aus1, ruhig1);
        ItemStack oldNamedBook = aus1.clone();
        var oldNamedMeta = oldNamedBook.getItemMeta();
        oldNamedMeta.customName(Component.text("Ausdauer I"));
        oldNamedBook.setItemMeta(oldNamedMeta);
        ItemStack combinedOldNamed = mergeResult(anvil, oldNamedBook, aus1);
        CustomEnchantmentAnvilListener.MergeOutcome capped = anvil.merge(aus5, aus5, null);
        boolean bookBook = level(combined11, "ausdauer") == 2
                && level(combined22, "ausdauer") == 3
                && (capped == null || capped.result() != null && level(capped.result(), "ausdauer") == 5)
                && level(combined32, "ausdauer") == 3
                && level(combined23, "ausdauer") == 3
                && level(combinedDifferent, "ausdauer") == 1
                && level(combinedDifferent, "ruhige_hand") == 1
                && visible(combined11, "Ausdauer II") && visible(combinedDifferent, "Ruhige Hand I")
                && vanillaBookName(combined11) && vanillaBookName(combinedDifferent)
                && vanillaBookName(combinedOldNamed) && level(combinedOldNamed, "ausdauer") == 2;
        if (!bookBook) getLogger().warning("ANGLER_ANVIL_BOOK_BOOK_DETAIL levels="
                + level(combined11, "ausdauer") + "," + level(combined22, "ausdauer")
                + "," + level(combined32, "ausdauer") + "," + level(combined23, "ausdauer")
                + " different=" + level(combinedDifferent, "ausdauer") + "/"
                + level(combinedDifferent, "ruhige_hand") + " visible="
                + visible(combined11, "Ausdauer II") + "/" + visible(combinedDifferent, "Ruhige Hand I"));
        getLogger().info("ANGLER_ANVIL_BOOK_BOOK=" + (bookBook ? "PASS" : "FAIL"));

        ItemStack rodA = new ItemStack(Material.FISHING_ROD);
        ItemStack rodB = new ItemStack(Material.FISHING_ROD);
        probeEnchants.applyDetailed(rodA, "ausdauer", 2);
        probeEnchants.applyDetailed(rodA, "ruhige_hand", 1);
        probeEnchants.applyDetailed(rodB, "ausdauer", 2);
        probeEnchants.applyDetailed(rodB, "nachfassen", 1);
        CustomEnchantmentAnvilListener.MergeOutcome rodMerge = anvil.merge(rodA, rodB, null);
        boolean itemItem = rodMerge != null && !rodMerge.invalid() && rodMerge.fallback()
                && rodMerge.anglerChanged() && level(rodMerge.result(), "ausdauer") == 3
                && level(rodMerge.result(), "ruhige_hand") == 1
                && level(rodMerge.result(), "nachfassen") == 1;
        getLogger().info("ANGLER_ANVIL_ITEM_ITEM=" + (itemItem ? "PASS" : "FAIL"));

        ItemStack multi = new ItemStack(Material.FISHING_ROD);
        boolean multiApplied = true;
        for (Map.Entry<String, Integer> enchant : Map.of("ausdauer", 5, "ruhige_hand", 3,
                "nachfassen", 2, "konzentration", 3, "meistergriff", 2).entrySet()) {
            multiApplied &= probeEnchants.applyDetailed(multi, enchant.getKey(), enchant.getValue())
                    == CustomEnchantmentService.ApplyResult.SUCCESS;
            multiApplied &= level(multi, enchant.getKey()) == enchant.getValue();
        }
        getLogger().info("ANGLER_ANVIL_MULTI=" + (multiApplied && probeEnchants.levels(multi).size() == 5
                ? "PASS" : "FAIL"));

        ItemStack vanillaLeft = rodA.clone();
        vanillaLeft.addEnchantment(Registry.ENCHANTMENT.get(NamespacedKey.minecraft("lure")), 3);
        ItemStack vanillaRight = rodB.clone();
        vanillaRight.addEnchantment(Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking")), 3);
        ItemStack vanillaResult = new ItemStack(Material.FISHING_ROD);
        vanillaResult.addEnchantment(Registry.ENCHANTMENT.get(NamespacedKey.minecraft("lure")), 3);
        vanillaResult.addEnchantment(Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking")), 3);
        ItemStack mergedVanilla = mergeResult(anvil, vanillaLeft, vanillaRight, vanillaResult);
        boolean vanillaPreserved = mergedVanilla != null && level(mergedVanilla, "ausdauer") == 3
                && mergedVanilla.getEnchantmentLevel(Registry.ENCHANTMENT.get(NamespacedKey.minecraft("lure"))) == 3
                && mergedVanilla.getEnchantmentLevel(Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking"))) == 3;
        getLogger().info("ANGLER_ANVIL_VANILLA_PRESERVED=" + (vanillaPreserved ? "PASS" : "FAIL"));

        ItemStack oldBook = aus1.clone();
        probeEnchants.remove(oldBook, "ausdauer");
        ItemStack legacyResult = mergeResult(anvil, new ItemStack(Material.FISHING_ROD), oldBook);
        boolean legacy = level(oldBook, "ausdauer") == 0 && level(legacyResult, "ausdauer") == 1;
        getLogger().info("ANGLER_ANVIL_LEGACY_BOOK=" + (legacy ? "PASS" : "FAIL"));

        ItemStack magnetBook = probeItems.create("magnet_buch", 1);
        CustomEnchantmentAnvilListener.MergeOutcome magnet = anvil.merge(
                new ItemStack(Material.DIAMOND_PICKAXE), magnetBook, null);
        boolean magnetUnchanged = magnet != null && !magnet.invalid() && magnet.fallback()
                && level(magnet.result(), "magnet") == 1
                && magnetBook.getItemMeta().customName() != null;
        getLogger().info("ANGLER_ANVIL_MAGNET_REGRESSION=" + (magnetUnchanged ? "PASS" : "FAIL"));

        boolean applicability = true;
        for (String raw : List.of("WOODEN_PICKAXE", "STONE_PICKAXE", "IRON_PICKAXE",
                "GOLDEN_PICKAXE", "DIAMOND_PICKAXE", "NETHERITE_PICKAXE")) {
            ItemStack pickaxe = new ItemStack(Material.matchMaterial(raw));
            applicability &= probeEnchants.applyDetailed(pickaxe, "spawnergriff", 1)
                    == CustomEnchantmentService.ApplyResult.SUCCESS;
        }
        for (Material material : Material.values()) {
            String name = material.name();
            if (name.endsWith("_PICKAXE") || name.endsWith("_AXE") || name.endsWith("_SHOVEL")
                    || name.endsWith("_HOE") || name.endsWith("_SWORD")
                    || Set.of(Material.FISHING_ROD, Material.SHEARS, Material.FLINT_AND_STEEL,
                    Material.BOW, Material.CROSSBOW, Material.TRIDENT, Material.MACE).contains(material)) {
                applicability &= probeEnchants.applyDetailed(new ItemStack(material), "seelenbindung", 1)
                        == CustomEnchantmentService.ApplyResult.SUCCESS;
            }
        }
        applicability &= probeEnchants.applyDetailed(new ItemStack(Material.SHIELD), "seelenbindung", 1)
                == CustomEnchantmentService.ApplyResult.NOT_APPLICABLE;
        applicability &= probeEnchants.applyDetailed(new ItemStack(Material.NETHERITE_HELMET), "seelenbindung", 1)
                == CustomEnchantmentService.ApplyResult.NOT_APPLICABLE;
        getLogger().info("ANGLER_ANVIL_APPLICABILITY=" + (applicability ? "PASS" : "FAIL"));

        try {
            UUID owner = UUID.fromString("3af72d55-355d-46d2-a4f9-c18327cab9c4");
            ItemStack[] stored = new ItemStack[54];
            stored[0] = multi;
            stored[1] = combinedDifferent;
            AnglerStorageRepository repository = new AnglerStorageRepository(probeStorage);
            ItemStack[] beforeRestartWrite = repository.load(owner);
            ItemStack previousBook = beforeRestartWrite[1];
            ItemStack previousRod = beforeRestartWrite[0];
            getLogger().info("ANGLER_ANVIL_RESTART_NAME="
                    + (vanillaBookName(previousBook) && level(previousBook, "ausdauer") == 1
                    && level(previousBook, "ruhige_hand") == 1 ? "PASS" : "FAIL"));
            if (previousRod != null) getLogger().info("RUHIGE_HAND_RESTART_PDC="
                    + (level(previousRod, "ruhige_hand") == 3 ? "PASS" : "FAIL"));
            if (previousRod != null) getLogger().info("KONZENTRATION_RESTART_PDC="
                    + (level(previousRod, "konzentration") == 3 ? "PASS" : "FAIL"));
            if (previousRod != null) getLogger().info("MEISTERGRIFF_RESTART_PDC="
                    + (level(previousRod, "meistergriff") == 2 ? "PASS" : "FAIL"));
            repository.save(owner, stored);
            ItemStack[] loaded = repository.load(owner);
            ItemStack restored = loaded[0];
            ItemStack restoredBook = loaded[1];
            boolean persistence = restored != null && restored.isSimilar(multi)
                    && probeEnchants.levels(restored).equals(probeEnchants.levels(multi))
                    && restored.getItemMeta().lore().equals(multi.getItemMeta().lore())
                    && vanillaBookName(restoredBook)
                    && level(restoredBook, "ausdauer") == 1
                    && level(restoredBook, "ruhige_hand") == 1;
            getLogger().info("ANGLER_ANVIL_PERSISTENCE=" + (persistence ? "PASS" : "FAIL"));
        } catch (Exception exception) {
            getLogger().severe("ANGLER_ANVIL_PERSISTENCE=ERROR " + exception);
        }
    }

    private void runRepairCoreProbe() {
        CustomEnchantmentAnvilListener listener = new CustomEnchantmentAnvilListener(probeEnchants, probeItems);
        ItemStack cores = probeItems.create("reparaturkern", 5);
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        Damageable swordMeta = (Damageable) sword.getItemMeta();
        swordMeta.setDamage(120);
        swordMeta.customName(Component.text("Testschwert"));
        NamespacedKey marker = new NamespacedKey(this, "repair_core_probe");
        swordMeta.getPersistentDataContainer().set(marker, PersistentDataType.INTEGER, 7);
        sword.setItemMeta(swordMeta);
        sword.addEnchantment(Registry.ENCHANTMENT.get(NamespacedKey.minecraft("sharpness")), 3);
        ItemStack result = listener.repairWithCore(sword, cores);
        boolean ordinary = cores != null && cores.getAmount() == 5 && result != null
                && ((Damageable) result.getItemMeta()).getDamage() == 0
                && ((Damageable) sword.getItemMeta()).getDamage() == 120
                && result.getItemMeta().customName().equals(sword.getItemMeta().customName())
                && result.getEnchantmentLevel(Registry.ENCHANTMENT.get(NamespacedKey.minecraft("sharpness"))) == 3
                && Integer.valueOf(7).equals(result.getItemMeta().getPersistentDataContainer()
                    .get(marker, PersistentDataType.INTEGER));
        getLogger().info("REPAIR_CORE_DURABILITY_AND_META=" + (ordinary ? "PASS" : "FAIL"));

        ItemStack full = sword.clone();
        Damageable fullMeta = (Damageable) full.getItemMeta();
        fullMeta.setDamage(0);
        full.setItemMeta(fullMeta);
        ItemStack fake = new ItemStack(Material.ECHO_SHARD);
        ItemMeta fakeMeta = fake.getItemMeta();
        fakeMeta.customName(cores.getItemMeta().customName());
        fake.setItemMeta(fakeMeta);
        boolean invalid = listener.repairWithCore(full, cores) == null
                && listener.repairWithCore(new ItemStack(Material.DIAMOND), cores) == null
                && listener.repairWithCore(sword, fake) == null;
        getLogger().info("REPAIR_CORE_INVALID_INPUTS=" + (invalid ? "PASS" : "FAIL"));

        ItemStack pickaxe = new ItemStack(Material.DIAMOND_PICKAXE);
        probeEnchants.applyDetailed(pickaxe, "spawnergriff", 1);
        probeEnchants.setSpawnerUses(pickaxe, 1);
        damage(pickaxe, 25);
        ItemStack repairedPickaxe = listener.repairWithCore(pickaxe, cores);
        boolean spawner = repairedPickaxe != null && probeEnchants.spawnerUses(repairedPickaxe) == 1
                && level(repairedPickaxe, "spawnergriff") == 1
                && repairedPickaxe.getItemMeta().lore().equals(pickaxe.getItemMeta().lore());
        getLogger().info("REPAIR_CORE_SPAWNER_USES=" + (spawner ? "PASS" : "FAIL"));

        boolean totem = true;
        for (int charge : List.of(0, 1)) {
            ItemStack shield = new ItemStack(Material.SHIELD);
            probeEnchants.applyDetailed(shield, "totembindung", 1);
            probeEnchants.setTotemCharge(shield, charge);
            damage(shield, 20);
            ItemStack repairedShield = listener.repairWithCore(shield, cores);
            totem &= repairedShield != null && level(repairedShield, "totembindung") == 1
                    && probeEnchants.totemCharge(repairedShield) == charge
                    && repairedShield.getItemMeta().lore().equals(shield.getItemMeta().lore());
        }
        getLogger().info("REPAIR_CORE_TOTEM_CHARGE=" + (totem ? "PASS" : "FAIL"));

        ItemStack rod = new ItemStack(Material.FISHING_ROD);
        for (Map.Entry<String, Integer> enchant : Map.of("nachfassen", 2, "ruhige_hand", 3,
                "konzentration", 3, "meistergriff", 2).entrySet()) {
            probeEnchants.applyDetailed(rod, enchant.getKey(), enchant.getValue());
        }
        damage(rod, 20);
        ItemStack repairedRod = listener.repairWithCore(rod, cores);
        boolean custom = repairedRod != null && probeEnchants.levels(repairedRod).equals(probeEnchants.levels(rod))
                && repairedRod.getItemMeta().lore().equals(rod.getItemMeta().lore());
        ItemStack soul = new ItemStack(Material.NETHERITE_SWORD);
        probeEnchants.applyDetailed(soul, "seelenbindung", 1);
        damage(soul, 20);
        ItemStack repairedSoul = listener.repairWithCore(soul, cores);
        custom &= repairedSoul != null && level(repairedSoul, "seelenbindung") == 1;
        getLogger().info("REPAIR_CORE_CUSTOM_ENCHANTS=" + (custom ? "PASS" : "FAIL"));

        ItemStack[] slots = {sword, cores, null};
        int[] costs = {17, 0};
        AnvilInventory inventory = (AnvilInventory) Proxy.newProxyInstance(getClassLoader(),
                new Class<?>[]{AnvilInventory.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getItem" -> slots[(int) args[0]];
                    case "setItem" -> { slots[(int) args[0]] = (ItemStack) args[1]; yield null; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        AnvilView view = (AnvilView) Proxy.newProxyInstance(getClassLoader(),
                new Class<?>[]{AnvilView.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getTopInventory" -> inventory;
                    case "getRepairCost" -> costs[0];
                    case "getRepairItemCountCost" -> costs[1];
                    case "setRepairCost" -> { costs[0] = (int) args[0]; yield null; }
                    case "setRepairItemCountCost" -> { costs[1] = (int) args[0]; yield null; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        PrepareAnvilEvent event = new PrepareAnvilEvent(view, null);
        listener.onPrepareRepairCore(event);
        boolean anvil = costs[0] == 1 && costs[1] == 1 && event.getResult() != null
                && cores.getAmount() == 5 && ((Damageable) sword.getItemMeta()).getDamage() == 120;
        getLogger().info("REPAIR_CORE_ANVIL_PREVIEW=" + (anvil ? "PASS" : "FAIL"));
    }

    private void damage(ItemStack item, int amount) {
        Damageable meta = (Damageable) item.getItemMeta();
        meta.setDamage(amount);
        item.setItemMeta(meta);
    }

    private void runLibrarianSealProbe() {
        ItemStack seal = probeItems.create("bibliothekarsiegel", 1);
        ItemStack fake = new ItemStack(Material.PAPER);
        ItemMeta fakeMeta = fake.getItemMeta();
        fakeMeta.customName(seal.getItemMeta().customName());
        fakeMeta.lore(seal.getItemMeta().lore());
        fake.setItemMeta(fakeMeta);
        getLogger().info("LIBRARIAN_SEAL_PDC=" + (probeItems.is(seal, "bibliothekarsiegel")
                && !probeItems.is(fake, "bibliothekarsiegel") ? "PASS" : "FAIL"));

        Villager villager = getServer().getWorlds().getFirst().spawn(
                getServer().getWorlds().getFirst().getSpawnLocation(), Villager.class);
        try {
            villager.setProfession(Villager.Profession.LIBRARIAN);
            villager.customName(Component.text("SealProbe"));
            villager.setVillagerExperience(0);
            villager.setVillagerLevel(1);
            UUID uuid = villager.getUniqueId();
            var location = villager.getLocation();
            var type = villager.getVillagerType();
            NamespacedKey marker = new NamespacedKey(this, "seal_probe_marker");
            villager.getPersistentDataContainer().set(marker, PersistentDataType.INTEGER, 42);
            villager.resetOffers();
            int firstCount = villager.getRecipeCount();
            villager.resetOffers();
            boolean vanilla = firstCount == 2 && villager.getRecipeCount() == 2
                    && villager.getProfession() == Villager.Profession.LIBRARIAN
                    && villager.getUniqueId().equals(uuid) && villager.getLocation().equals(location)
                    && villager.getVillagerType() == type && villager.customName().equals(Component.text("SealProbe"))
                    && Integer.valueOf(42).equals(villager.getPersistentDataContainer()
                        .get(marker, PersistentDataType.INTEGER))
                    && !LibrarianSealListener.hasTraded(villager);
            getLogger().info("LIBRARIAN_SEAL_VANILLA_RESET=" + (vanilla ? "PASS" : "FAIL")
                    + " offers=" + firstCount + "/" + villager.getRecipeCount());

            MerchantRecipe used = new MerchantRecipe(villager.getRecipe(0));
            used.setUses(1);
            villager.setRecipe(0, used);
            boolean usesLock = LibrarianSealListener.hasTraded(villager);
            villager.resetOffers(); // Restock-like state: uses disappear, earned XP must still lock.
            villager.setVillagerExperience(5);
            boolean experienceLock = LibrarianSealListener.hasTraded(villager);
            getLogger().info("LIBRARIAN_SEAL_TRADE_LOCK="
                    + (usesLock && experienceLock ? "PASS" : "FAIL"));
        } catch (Exception exception) {
            getLogger().severe("LIBRARIAN_SEAL_VANILLA_RESET=ERROR " + exception);
        } finally {
            villager.remove();
        }

        int[] resets = {0};
        int[] openedMenus = {0};
        int[] closedMenus = {0};
        int[] messages = {0};
        boolean[] openedAfterReset = {true};
        Villager.Profession[] profession = {Villager.Profession.LIBRARIAN};
        int[] villagerXp = {0};
        List<MerchantRecipe> generatedOffers = List.of(
                new MerchantRecipe(new ItemStack(Material.EMERALD), 10),
                new MerchantRecipe(new ItemStack(Material.BOOKSHELF), 10));
        Villager mockVillager = (Villager) Proxy.newProxyInstance(getClassLoader(),
                new Class<?>[]{Villager.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getProfession" -> profession[0];
                    case "getVillagerExperience" -> villagerXp[0];
                    case "getVillagerLevel" -> 1;
                    case "getRecipes" -> resets[0] == 0 ? List.of() : generatedOffers;
                    case "resetOffers" -> { resets[0]++; yield null; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        ItemStack[] hands = {seal, seal};
        PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(getClassLoader(),
                new Class<?>[]{PlayerInventory.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getItem" -> hands[args[0] == EquipmentSlot.HAND ? 0 : 1];
                    case "getItemInOffHand" -> hands[1];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Player player = (Player) Proxy.newProxyInstance(getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getInventory" -> inventory;
                    case "getUniqueId" -> UUID.fromString("ea772c9e-51d8-4432-adb3-8ecf78e866c0");
                    case "openMerchant" -> {
                        openedMenus[0]++;
                        openedAfterReset[0] &= args[0] == mockVillager
                                && resets[0] == openedMenus[0]
                                && mockVillager.getRecipes().size() == 2;
                        yield null;
                    }
                    case "closeInventory" -> { closedMenus[0]++; yield null; }
                    case "sendMessage" -> { messages[0]++; yield null; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        LibrarianSealListener listener = new LibrarianSealListener(this, probeItems);
        PlayerInteractEntityEvent main = new PlayerInteractEntityEvent(player, mockVillager, EquipmentSlot.HAND);
        PlayerInteractEntityEvent off = new PlayerInteractEntityEvent(player, mockVillager, EquipmentSlot.OFF_HAND);
        listener.onInteract(main);
        listener.onInteract(off);
        boolean once = main.isCancelled() && off.isCancelled() && resets[0] == 1
                && openedMenus[0] == 1 && openedAfterReset[0]
                && hands[0].getAmount() == 1 && hands[1].getAmount() == 1;
        getLogger().info("LIBRARIAN_SEAL_ONE_REROLL_PER_CLICK=" + (once ? "PASS" : "FAIL"));

        hands[0] = fake;
        hands[1] = seal;
        resets[0] = 0;
        openedMenus[0] = 0;
        openedAfterReset[0] = true;
        listener = new LibrarianSealListener(this, probeItems);
        main = new PlayerInteractEntityEvent(player, mockVillager, EquipmentSlot.HAND);
        off = new PlayerInteractEntityEvent(player, mockVillager, EquipmentSlot.OFF_HAND);
        listener.onInteract(main);
        listener.onInteract(off);
        boolean offhand = main.isCancelled() && off.isCancelled() && resets[0] == 1
                && openedMenus[0] == 1 && openedAfterReset[0];
        getLogger().info("LIBRARIAN_SEAL_OFFHAND=" + (offhand ? "PASS" : "FAIL"));

        LibrarianSealListener repeatListener = listener;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            player.closeInventory();
            PlayerInteractEntityEvent repeated = new PlayerInteractEntityEvent(
                    player, mockVillager, EquipmentSlot.OFF_HAND);
            repeatListener.onInteract(repeated);
            boolean repeat = repeated.isCancelled() && closedMenus[0] == 1
                    && resets[0] == 2 && openedMenus[0] == 2 && openedAfterReset[0]
                    && hands[1].getAmount() == 1;
            getLogger().info("LIBRARIAN_SEAL_REPEAT_OPEN=" + (repeat ? "PASS" : "FAIL"));
        }, 20L);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            profession[0] = Villager.Profession.FARMER;
            int priorMessages = messages[0];
            PlayerInteractEntityEvent wrongProfession = new PlayerInteractEntityEvent(
                    player, mockVillager, EquipmentSlot.OFF_HAND);
            repeatListener.onInteract(wrongProfession);
            boolean blocked = wrongProfession.isCancelled() && messages[0] == priorMessages + 1
                    && resets[0] == 2 && openedMenus[0] == 2;
            getLogger().info("LIBRARIAN_SEAL_WRONG_PROFESSION_NO_MENU=" + (blocked ? "PASS" : "FAIL"));
        }, 22L);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            profession[0] = Villager.Profession.LIBRARIAN;
            villagerXp[0] = 5;
            int priorMessages = messages[0];
            PlayerInteractEntityEvent traded = new PlayerInteractEntityEvent(
                    player, mockVillager, EquipmentSlot.OFF_HAND);
            repeatListener.onInteract(traded);
            boolean blocked = traded.isCancelled() && messages[0] == priorMessages + 1
                    && resets[0] == 2 && openedMenus[0] == 2;
            getLogger().info("LIBRARIAN_SEAL_TRADED_NO_MENU=" + (blocked ? "PASS" : "FAIL"));
        }, 24L);
    }

    private ItemStack mergeResult(CustomEnchantmentAnvilListener anvil, ItemStack left, ItemStack right) {
        return mergeResult(anvil, left, right, null);
    }

    private ItemStack mergeResult(CustomEnchantmentAnvilListener anvil, ItemStack left, ItemStack right,
                                  ItemStack vanilla) {
        CustomEnchantmentAnvilListener.MergeOutcome result = anvil.merge(left, right, vanilla);
        return result == null || result.invalid() ? null : result.result();
    }

    private boolean invalidMerge(CustomEnchantmentAnvilListener anvil, ItemStack left, ItemStack right) {
        CustomEnchantmentAnvilListener.MergeOutcome result = anvil.merge(left, right, null);
        return result != null && result.invalid();
    }

    private int level(ItemStack item, String id) {
        return item == null ? 0 : probeEnchants.level(item, id);
    }

    private boolean visible(ItemStack item, String text) {
        return item != null && item.getItemMeta() != null && item.getItemMeta().lore() != null
                && item.getItemMeta().lore().stream().anyMatch(line ->
                PlainTextComponentSerializer.plainText().serialize(line).contains(text));
    }

    private boolean vanillaBookName(ItemStack item) {
        return item != null && item.getType() == Material.ENCHANTED_BOOK
                && item.getItemMeta() != null && item.getItemMeta().customName() == null;
    }

    private void runNachfassenProbe(YamlConfiguration config) {
        FishingAttemptState without = playingAttempt(0);
        getLogger().info("NACHFASSEN_NONE=" + (!without.consumeGrayRetry(FishingGame.Quality.GRAY, false)
                && without.beginResolve() ? "PASS" : "FAIL"));

        FishingAttemptState first = playingAttempt(1);
        boolean firstGreen = first.consumeGrayRetry(FishingGame.Quality.GRAY, false)
                && first.phase() == FishingAttemptState.Phase.PLAYING
                && !first.consumeGrayRetry(FishingGame.Quality.GREEN, false)
                && first.beginResolve();
        FishingAttemptState firstGray = playingAttempt(1);
        boolean firstFailed = firstGray.consumeGrayRetry(FishingGame.Quality.GRAY, false)
                && !firstGray.consumeGrayRetry(FishingGame.Quality.GRAY, false)
                && firstGray.beginResolve();
        getLogger().info("NACHFASSEN_I=" + (firstGreen && firstFailed ? "PASS" : "FAIL"));

        FishingAttemptState second = playingAttempt(2);
        boolean secondYellow = second.consumeGrayRetry(FishingGame.Quality.GRAY, false)
                && second.consumeGrayRetry(FishingGame.Quality.GRAY, false)
                && !second.consumeGrayRetry(FishingGame.Quality.YELLOW, false)
                && second.beginResolve();
        FishingAttemptState secondGray = playingAttempt(2);
        boolean secondFailed = secondGray.consumeGrayRetry(FishingGame.Quality.GRAY, false)
                && secondGray.consumeGrayRetry(FishingGame.Quality.GRAY, false)
                && !secondGray.consumeGrayRetry(FishingGame.Quality.GRAY, false)
                && secondGray.beginResolve();
        getLogger().info("NACHFASSEN_II=" + (secondYellow && secondFailed ? "PASS" : "FAIL"));

        FishingAttemptState orange = playingAttempt(2);
        getLogger().info("NACHFASSEN_NON_GRAY="
                + (!orange.consumeGrayRetry(FishingGame.Quality.ORANGE, false)
                && orange.beginResolve() ? "PASS" : "FAIL"));

        long startedNanos = 1_000_000_000L;
        FishingGame game = new FishingGame(config, "easy", new Random(42), startedNanos);
        FishingAttemptState timeout = playingAttempt(2);
        double target = game.targetStart();
        boolean sameSession = timeout.consumeGrayRetry(FishingGame.Quality.GRAY, false)
                && timeout.phase() == FishingAttemptState.Phase.PLAYING
                && game.targetStart() == target
                && game.pointer(startedNanos + 1_000_000_000L)
                != game.pointer(startedNanos + 1_200_000_000L);
        boolean expired = game.timedOut(startedNanos + 5_000_000_000L)
                && !timeout.consumeGrayRetry(FishingGame.Quality.GRAY, true)
                && timeout.beginResolve();
        getLogger().info("NACHFASSEN_SAME_GAME_TIMEOUT="
                + (sameSession && expired ? "PASS" : "FAIL"));

        try {
            UUID owner = UUID.fromString("3af72d55-355d-46d2-a4f9-c18327cab9c4");
            ItemStack rod = new AnglerStorageRepository(probeStorage).load(owner)[0];
            FishingAttemptState restored = playingAttempt(level(rod, "nachfassen"));
            boolean persisted = level(rod, "nachfassen") == 2
                    && restored.consumeGrayRetry(FishingGame.Quality.GRAY, false)
                    && restored.consumeGrayRetry(FishingGame.Quality.GRAY, false)
                    && !restored.consumeGrayRetry(FishingGame.Quality.GRAY, false);
            getLogger().info("NACHFASSEN_PERSISTENCE=" + (persisted ? "PASS" : "FAIL"));
        } catch (Exception exception) {
            getLogger().severe("NACHFASSEN_PERSISTENCE=ERROR " + exception);
        }
    }

    private FishingAttemptState playingAttempt(int grayRetries) {
        FishingAttemptState state = new FishingAttemptState();
        state.bite();
        state.startGame(100, grayRetries);
        return state;
    }

    private void runRuhigeHandProbe(YamlConfiguration config) {
        long startedNanos = 1_000_000_000L;
        boolean levels = true;
        double[][] expectedMultipliers = {{1.10D, 1.05D}, {1.20D, 1.10D}, {1.30D, 1.15D}};
        for (String difficulty : List.of("easy", "medium", "hard")) {
            FishingGame normal = gameAtLeft(config, difficulty, startedNanos, 0);
            boolean normalZones = zoneBoundaries(config, difficulty, normal, 1D, 1D);
            for (int level = 1; level <= 3; level++) {
                double greenMultiplier = config.getDouble(
                        "enchants.ruhige-hand.levels." + level + ".green-multiplier");
                double yellowMultiplier = config.getDouble(
                        "enchants.ruhige-hand.levels." + level + ".yellow-multiplier");
                FishingGame enlarged = gameAtLeft(config, difficulty, startedNanos, level);
                boolean valid = Math.abs(greenMultiplier - expectedMultipliers[level - 1][0]) < 1e-9D
                        && Math.abs(yellowMultiplier - expectedMultipliers[level - 1][1]) < 1e-9D
                        && zoneBoundaries(config, difficulty, enlarged,
                        greenMultiplier, yellowMultiplier)
                        && enlarged.pointer(startedNanos + 1_000_000_000L)
                        == normal.pointer(startedNanos + 1_000_000_000L)
                        && enlarged.timedOut(startedNanos + 5_000_000_000L)
                        && !enlarged.timedOut(startedNanos + 4_999_999_999L);
                levels &= valid;
                getLogger().info("RUHIGE_HAND_" + difficulty.toUpperCase() + "_" + level
                        + "=" + (valid ? "PASS" : "FAIL"));
            }
            levels &= normalZones;
            getLogger().info("RUHIGE_HAND_" + difficulty.toUpperCase() + "_NONE="
                    + (normalZones ? "PASS" : "FAIL"));
        }
        FishingGame inherited = gameAtLeft(configs().angler(), "hard", startedNanos, 3);
        boolean oldYaml = zoneBoundaries(config, "hard", inherited,
                config.getDouble("enchants.ruhige-hand.levels.3.green-multiplier"),
                config.getDouble("enchants.ruhige-hand.levels.3.yellow-multiplier"));
        getLogger().info("RUHIGE_HAND_OLD_YAML_DEFAULTS=" + (oldYaml ? "PASS" : "FAIL"));
        YamlConfiguration override = new YamlConfiguration();
        override.set("enchants.ruhige-hand.levels.1.green-multiplier", 1.5D);
        override.setDefaults(config);
        FishingGame overridden = gameAtLeft(override, "hard", startedNanos, 1);
        getLogger().info("RUHIGE_HAND_CONFIG_OVERRIDE="
                + (zoneBoundaries(config, "hard", overridden, 1.5D, 1.05D)
                ? "PASS" : "FAIL"));

        try {
            UUID owner = UUID.fromString("3af72d55-355d-46d2-a4f9-c18327cab9c4");
            ItemStack rod = new AnglerStorageRepository(probeStorage).load(owner)[0];
            FishingGame restored = gameAtLeft(config, "hard", startedNanos,
                    level(rod, "ruhige_hand"));
            FishingAttemptState nachfassen = playingAttempt(level(rod, "nachfassen"));
            boolean combination = level(rod, "ruhige_hand") == 3
                    && zoneBoundaries(config, "hard", restored,
                    config.getDouble("enchants.ruhige-hand.levels.3.green-multiplier"),
                    config.getDouble("enchants.ruhige-hand.levels.3.yellow-multiplier"))
                    && nachfassen.consumeGrayRetry(FishingGame.Quality.GRAY, false)
                    && nachfassen.consumeGrayRetry(FishingGame.Quality.GRAY, false)
                    && !nachfassen.consumeGrayRetry(FishingGame.Quality.GRAY, false)
                    && restored.timedOut(startedNanos + 5_000_000_000L);
            getLogger().info("RUHIGE_HAND_NACHFASSEN_PERSISTENCE="
                    + (combination && levels ? "PASS" : "FAIL"));
        } catch (Exception exception) {
            getLogger().severe("RUHIGE_HAND_NACHFASSEN_PERSISTENCE=ERROR " + exception);
        }
    }

    private FishingGame gameAtLeft(org.bukkit.configuration.file.FileConfiguration config,
                                   String difficulty, long startedNanos, int level) {
        return new FishingGame(config, difficulty, new Random() {
            @Override public double nextDouble() { return 0D; }
        }, startedNanos, level);
    }

    private boolean zoneBoundaries(YamlConfiguration config, String difficulty, FishingGame game,
                                   double greenMultiplier, double yellowMultiplier) {
        String path = "fishing.minigame." + difficulty + ".";
        double red = config.getDouble(path + "red-each") / 100D;
        double orange = config.getDouble(path + "orange-each") / 100D;
        double yellow = config.getDouble(path + "yellow-each") * yellowMultiplier / 100D;
        double green = config.getDouble(path + "green") * greenMultiplier / 100D;
        double epsilon = 1e-8D;
        double redEnd = red;
        double orangeEnd = redEnd + orange;
        double yellowEnd = orangeEnd + yellow;
        double greenEnd = yellowEnd + green;
        double rightYellowEnd = greenEnd + yellow;
        double rightOrangeEnd = rightYellowEnd + orange;
        double rightRedEnd = rightOrangeEnd + red;
        return game.targetStart() == 0D
                && game.quality(redEnd - epsilon) == FishingGame.Quality.RED
                && game.quality(redEnd + epsilon) == FishingGame.Quality.ORANGE
                && game.quality(orangeEnd - epsilon) == FishingGame.Quality.ORANGE
                && game.quality(orangeEnd + epsilon) == FishingGame.Quality.YELLOW
                && game.quality(yellowEnd - epsilon) == FishingGame.Quality.YELLOW
                && game.quality(yellowEnd + epsilon) == FishingGame.Quality.GREEN
                && game.quality(greenEnd - epsilon) == FishingGame.Quality.GREEN
                && game.quality(greenEnd + epsilon) == FishingGame.Quality.YELLOW
                && game.quality(rightYellowEnd + epsilon) == FishingGame.Quality.ORANGE
                && game.quality(rightOrangeEnd + epsilon) == FishingGame.Quality.RED
                && game.quality(rightRedEnd + epsilon) == FishingGame.Quality.GRAY
                && rightRedEnd <= 1D;
    }

    private void runKonzentrationProbe(YamlConfiguration config) {
        boolean normal = comboCases(config, 0, new int[] {4, 5, 9, 10, 20, 30},
                new double[] {1D, 1.05D, 1.05D, 1.10D, 1.15D, 1.20D});
        boolean first = config.getIntegerList("enchants.konzentration.levels.1")
                .equals(List.of(5, 9, 18, 27))
                && comboCases(config, 1, new int[] {4, 5, 8, 9, 18, 27, 30},
                new double[] {1D, 1.05D, 1.05D, 1.10D, 1.15D, 1.20D, 1.20D});
        boolean second = config.getIntegerList("enchants.konzentration.levels.2")
                .equals(List.of(4, 8, 16, 24))
                && comboCases(config, 2, new int[] {3, 4, 8, 16, 24, 30},
                new double[] {1D, 1.05D, 1.10D, 1.15D, 1.20D, 1.20D});
        boolean third = config.getIntegerList("enchants.konzentration.levels.3")
                .equals(List.of(4, 7, 14, 21))
                && comboCases(config, 3, new int[] {3, 4, 7, 14, 21, 30},
                new double[] {1D, 1.05D, 1.10D, 1.15D, 1.20D, 1.20D});
        getLogger().info("KONZENTRATION_NONE=" + (normal ? "PASS" : "FAIL"));
        getLogger().info("KONZENTRATION_I=" + (first ? "PASS" : "FAIL"));
        getLogger().info("KONZENTRATION_II=" + (second ? "PASS" : "FAIL"));
        getLogger().info("KONZENTRATION_III=" + (third ? "PASS" : "FAIL"));

        boolean oldYaml = comboCases(configs().angler(), 2, new int[] {4, 8, 16, 24},
                new double[] {1.05D, 1.10D, 1.15D, 1.20D});
        getLogger().info("KONZENTRATION_OLD_YAML_DEFAULTS=" + (oldYaml ? "PASS" : "FAIL"));
        YamlConfiguration override = new YamlConfiguration();
        override.set("enchants.konzentration.levels.2", List.of(4, 6, 12, 18));
        override.setDefaults(config);
        boolean configurable = comboCases(override, 2, new int[] {4, 6, 12, 18},
                new double[] {1.05D, 1.10D, 1.15D, 1.20D});
        if (!configurable) getLogger().warning("KONZENTRATION_CONFIG_OVERRIDE_DETAIL list="
                + override.getIntegerList("enchants.konzentration.levels.2")
                + " values=" + comboAt(override, 4, 2) + "," + comboAt(override, 6, 2)
                + "," + comboAt(override, 12, 2) + "," + comboAt(override, 18, 2));
        getLogger().info("KONZENTRATION_CONFIG_OVERRIDE=" + (configurable ? "PASS" : "FAIL"));

        FishingComboTracker tracker = new FishingComboTracker(config);
        UUID id = UUID.randomUUID();
        long now = System.currentTimeMillis();
        for (int index = 0; index < 8; index++) {
            tracker.attempt(id, now);
            tracker.finish(id, FishingGame.Quality.GREEN, 2);
        }
        tracker.attempt(id, now);
        FishingComboTracker.Result failed = tracker.finish(id, FishingGame.Quality.GRAY, 2);
        tracker.attempt(id, now + 60_000L);
        FishingComboTracker.Result afterTimeout = tracker.finish(id, FishingGame.Quality.GREEN, 2);
        boolean reset = failed.combo() == 0 && failed.multiplier() == 1D
                && afterTimeout.combo() == 1 && afterTimeout.multiplier() == 1D;
        getLogger().info("KONZENTRATION_RESET_TIMEOUT=" + (reset ? "PASS" : "FAIL"));

        try {
            UUID owner = UUID.fromString("3af72d55-355d-46d2-a4f9-c18327cab9c4");
            ItemStack rod = new AnglerStorageRepository(probeStorage).load(owner)[0];
            FishingComboTracker.Result persisted = comboAt(config, 21, level(rod, "konzentration"));
            boolean combined = level(rod, "konzentration") == 3
                    && level(rod, "nachfassen") == 2
                    && level(rod, "ruhige_hand") == 3
                    && persisted.combo() == 21 && persisted.multiplier() == 1.20D;
            getLogger().info("KONZENTRATION_PARALLEL_PERSISTENCE="
                    + (combined ? "PASS" : "FAIL"));
        } catch (Exception exception) {
            getLogger().severe("KONZENTRATION_PARALLEL_PERSISTENCE=ERROR " + exception);
        }
    }

    private static final class CountingRandom extends Random {
        private final double value;
        private int rolls;

        private CountingRandom(double value) { this.value = value; }
        @Override public double nextDouble() { rolls++; return value; }
    }

    private void runMeistergriffProbe(YamlConfiguration config) {
        String base = "enchants.meistergriff.levels.";
        boolean configured = config.getDouble(base + "1.double-combo-chance") == 0.15D
                && config.getDouble(base + "2.double-combo-chance") == 0.30D;
        getLogger().info("MEISTERGRIFF_CONFIG=" + (configured ? "PASS" : "FAIL"));

        CountingRandom noEnchant = new CountingRandom(0D);
        boolean noProc = !AnglerFishingService.rollMeistergriff(config, FishingGame.Quality.GREEN,
                0, noEnchant) && noEnchant.rolls == 0;
        FishingComboTracker tracker = new FishingComboTracker(config);
        UUID id = UUID.randomUUID();
        long now = System.currentTimeMillis();
        tracker.attempt(id, now);
        noProc &= tracker.finish(id, FishingGame.Quality.GREEN, 0, false).combo() == 1;
        getLogger().info("MEISTERGRIFF_NONE=" + (noProc ? "PASS" : "FAIL"));

        for (int level = 1; level <= 2; level++) {
            double chance = config.getDouble(base + level + ".double-combo-chance");
            CountingRandom proc = new CountingRandom(chance - 0.001D);
            CountingRandom miss = new CountingRandom(chance + 0.001D);
            boolean hit = AnglerFishingService.rollMeistergriff(config, FishingGame.Quality.GREEN,
                    level, proc);
            boolean missed = AnglerFishingService.rollMeistergriff(config, FishingGame.Quality.GREEN,
                    level, miss);
            tracker.attempt(id, now);
            FishingComboTracker.Result doubled = tracker.finish(id, FishingGame.Quality.GREEN, 0, hit);
            tracker.attempt(id, now);
            FishingComboTracker.Result normal = tracker.finish(id, FishingGame.Quality.GREEN, 0, missed);
            Random distribution = new Random(157L + level);
            int doubles = 0;
            for (int index = 0; index < 100_000; index++) {
                if (AnglerFishingService.rollMeistergriff(config, FishingGame.Quality.GREEN,
                        level, distribution)) doubles++;
            }
            boolean valid = hit && !missed && proc.rolls == 1 && miss.rolls == 1
                    && doubled.combo() == (level == 1 ? 3 : 6)
                    && normal.combo() == (level == 1 ? 4 : 7)
                    && Math.abs(doubles / 100_000D - chance) < 0.01D;
            getLogger().info("MEISTERGRIFF_" + (level == 1 ? "I" : "II") + "="
                    + (valid ? "PASS" : "FAIL") + " doubles=" + doubles);
        }

        CountingRandom nonGreen = new CountingRandom(0D);
        boolean onlyGreen = true;
        for (FishingGame.Quality quality : List.of(FishingGame.Quality.RED,
                FishingGame.Quality.ORANGE, FishingGame.Quality.YELLOW, FishingGame.Quality.GRAY)) {
            onlyGreen &= !AnglerFishingService.rollMeistergriff(config, quality, 2, nonGreen);
            tracker.attempt(id, now);
            FishingComboTracker.Result result = tracker.finish(id, quality, 0, true);
            onlyGreen &= result.combo() == (quality == FishingGame.Quality.GRAY ? 0
                    : quality == FishingGame.Quality.RED ? 7
                    : quality == FishingGame.Quality.ORANGE ? 8 : 9);
        }
        getLogger().info("MEISTERGRIFF_NON_GREEN="
                + (onlyGreen && nonGreen.rolls == 0 ? "PASS" : "FAIL"));

        FishingAttemptState retry = playingAttempt(2);
        CountingRandom afterRetry = new CountingRandom(0D);
        boolean retryGreen = retry.consumeGrayRetry(FishingGame.Quality.GRAY, false)
                && retry.consumeGrayRetry(FishingGame.Quality.GRAY, false)
                && afterRetry.rolls == 0
                && !retry.consumeGrayRetry(FishingGame.Quality.GREEN, false)
                && retry.beginResolve()
                && AnglerFishingService.rollMeistergriff(config, FishingGame.Quality.GREEN, 2, afterRetry)
                && afterRetry.rolls == 1;
        FishingAttemptState allGray = playingAttempt(2);
        CountingRandom neverRolled = new CountingRandom(0D);
        boolean retryGray = allGray.consumeGrayRetry(FishingGame.Quality.GRAY, false)
                && allGray.consumeGrayRetry(FishingGame.Quality.GRAY, false)
                && !allGray.consumeGrayRetry(FishingGame.Quality.GRAY, false)
                && allGray.beginResolve()
                && !AnglerFishingService.rollMeistergriff(config, FishingGame.Quality.GRAY,
                2, neverRolled) && neverRolled.rolls == 0;
        getLogger().info("MEISTERGRIFF_NACHFASSEN="
                + (retryGreen && retryGray ? "PASS" : "FAIL"));

        FishingComboTracker combined = new FishingComboTracker(config);
        UUID combinedId = UUID.randomUUID();
        for (int index = 0; index < 5; index++) {
            combined.attempt(combinedId, now);
            combined.finish(combinedId, FishingGame.Quality.GREEN, 3);
        }
        combined.attempt(combinedId, now);
        FishingComboTracker.Result seven = combined.finish(combinedId,
                FishingGame.Quality.GREEN, 3, true);
        getLogger().info("MEISTERGRIFF_KONZENTRATION="
                + (seven.combo() == 7 && seven.multiplier() == 1.10D ? "PASS" : "FAIL"));

        YamlConfiguration override = new YamlConfiguration();
        override.set(base + "2.double-combo-chance", 1D);
        override.setDefaults(config);
        boolean configurable = AnglerFishingService.rollMeistergriff(override,
                FishingGame.Quality.GREEN, 2, new CountingRandom(0.99D));
        boolean oldYaml = AnglerFishingService.rollMeistergriff(configs().angler(),
                FishingGame.Quality.GREEN, 2, new CountingRandom(0.29D));
        getLogger().info("MEISTERGRIFF_CONFIG_OVERRIDE="
                + (configurable && oldYaml ? "PASS" : "FAIL"));

        try {
            UUID owner = UUID.fromString("3af72d55-355d-46d2-a4f9-c18327cab9c4");
            ItemStack rod = new AnglerStorageRepository(probeStorage).load(owner)[0];
            boolean persisted = level(rod, "meistergriff") == 2
                    && level(rod, "ruhige_hand") == 3
                    && level(rod, "nachfassen") == 2
                    && level(rod, "konzentration") == 3
                    && AnglerFishingService.rollMeistergriff(config, FishingGame.Quality.GREEN,
                    level(rod, "meistergriff"), new CountingRandom(0D));
            getLogger().info("MEISTERGRIFF_PARALLEL_PERSISTENCE="
                    + (persisted ? "PASS" : "FAIL"));
        } catch (Exception exception) {
            getLogger().severe("MEISTERGRIFF_PARALLEL_PERSISTENCE=ERROR " + exception);
        }
    }

    private void runTotemBindingProbe() {
        TotemBindingListener binding = new TotemBindingListener(probeEnchants);
        ItemStack shield = new ItemStack(Material.SHIELD);
        boolean enchanted = probeEnchants.applyDetailed(shield, "totembindung", 1)
                == CustomEnchantmentService.ApplyResult.SUCCESS
                && probeEnchants.totemCharge(shield) == 0
                && visible(shield, "Totemladung: 0/1");
        ItemStack stack = new ItemStack(Material.TOTEM_OF_UNDYING, 5);
        boolean loaded = binding.loadOne(shield, stack)
                && stack.getAmount() == 4
                && probeEnchants.totemCharge(shield) == 1
                && visible(shield, "Totemladung: 1/1");
        getLogger().info("TOTEMBINDUNG_LOAD=" + (enchanted && loaded ? "PASS" : "FAIL"));

        ItemStack fullCursor = new ItemStack(Material.TOTEM_OF_UNDYING, 5);
        ItemStack diamond = new ItemStack(Material.DIAMOND, 3);
        boolean noExtra = !binding.loadOne(shield, fullCursor) && fullCursor.getAmount() == 5
                && probeEnchants.totemCharge(shield) == 1;
        ItemStack emptyShield = shield.clone();
        probeEnchants.setTotemCharge(emptyShield, 0);
        boolean wrongItem = !binding.loadOne(emptyShield, diamond)
                && diamond.getAmount() == 3 && probeEnchants.totemCharge(emptyShield) == 0;
        getLogger().info("TOTEMBINDUNG_FULL_WRONG_ITEM="
                + (noExtra && wrongItem ? "PASS" : "FAIL"));

        ItemStack offhandShield = emptyShield.clone();
        ItemStack[] offhandCase = {new ItemStack(Material.TOTEM_OF_UNDYING, 3), offhandShield};
        PlayerSwapHandItemsEvent offhandSwap = new PlayerSwapHandItemsEvent(
                fakePlayerWithHands(offhandCase), offhandCase[1], offhandCase[0]);
        binding.onSwapHands(offhandSwap);
        boolean offhandLoaded = offhandSwap.isCancelled()
                && offhandCase[0].getType() == Material.TOTEM_OF_UNDYING
                && offhandCase[0].getAmount() == 2
                && probeEnchants.totemCharge(offhandCase[1]) == 1;
        PlayerSwapHandItemsEvent chargedSwap = new PlayerSwapHandItemsEvent(
                fakePlayerWithHands(offhandCase), offhandCase[1], offhandCase[0]);
        binding.onSwapHands(chargedSwap);
        offhandLoaded &= !chargedSwap.isCancelled() && offhandCase[0].getAmount() == 2
                && probeEnchants.totemCharge(offhandCase[1]) == 1;
        getLogger().info("TOTEMBINDUNG_SWAP_OFFHAND=" + (offhandLoaded ? "PASS" : "FAIL"));

        ItemStack[] mainhandCase = {emptyShield.clone(), new ItemStack(Material.TOTEM_OF_UNDYING)};
        PlayerSwapHandItemsEvent mainhandSwap = new PlayerSwapHandItemsEvent(
                fakePlayerWithHands(mainhandCase), mainhandCase[1], mainhandCase[0]);
        binding.onSwapHands(mainhandSwap);
        boolean mainhandLoaded = mainhandSwap.isCancelled()
                && probeEnchants.totemCharge(mainhandCase[0]) == 1
                && mainhandCase[1].getType().isAir();
        getLogger().info("TOTEMBINDUNG_SWAP_MAINHAND=" + (mainhandLoaded ? "PASS" : "FAIL"));

        ItemStack[] ordinaryCase = {new ItemStack(Material.TOTEM_OF_UNDYING, 2),
                new ItemStack(Material.SHIELD)};
        PlayerSwapHandItemsEvent ordinarySwap = new PlayerSwapHandItemsEvent(
                fakePlayerWithHands(ordinaryCase), ordinaryCase[1], ordinaryCase[0]);
        binding.onSwapHands(ordinarySwap);
        boolean ordinaryUnchanged = !ordinarySwap.isCancelled()
                && ordinaryCase[0].getAmount() == 2
                && ordinaryCase[1].getType() == Material.SHIELD;
        getLogger().info("TOTEMBINDUNG_SWAP_NORMAL_SHIELD="
                + (ordinaryUnchanged ? "PASS" : "FAIL"));

        ItemStack serialized = ItemStack.deserializeBytes(shield.serializeAsBytes());
        boolean itemPersistent = probeEnchants.totemCharge(serialized) == 1
                && visible(serialized, "Totemladung: 1/1");
        try {
            AnglerStorageRepository repository = new AnglerStorageRepository(probeStorage);
            UUID owner = UUID.fromString("8a4f1a85-0471-4c29-8415-a23da7345ef9");
            ItemStack previous = repository.load(owner)[0];
            if (previous != null) getLogger().info("TOTEMBINDUNG_RESTART_PDC="
                    + (probeEnchants.totemCharge(previous) == 1 ? "PASS" : "FAIL"));
            ItemStack[] stored = new ItemStack[54];
            stored[0] = shield;
            repository.save(owner, stored);
            ItemStack restored = repository.load(owner)[0];
            itemPersistent &= probeEnchants.totemCharge(restored) == 1
                    && visible(restored, "Totemladung: 1/1");
        } catch (Exception exception) {
            getLogger().severe("TOTEMBINDUNG_PERSISTENCE=ERROR " + exception);
            itemPersistent = false;
        }
        getLogger().info("TOTEMBINDUNG_PERSISTENCE=" + (itemPersistent ? "PASS" : "FAIL"));

        ItemStack[] mainHands = {shield.clone(), new ItemStack(Material.AIR),
                new ItemStack(Material.STONE), new ItemStack(Material.TOTEM_OF_UNDYING),
                shield.clone(), new ItemStack(Material.STONE)};
        ItemStack[] offHands = {new ItemStack(Material.AIR), shield.clone(),
                new ItemStack(Material.AIR), shield.clone(), shield.clone(),
                new ItemStack(Material.AIR)};
        boolean[] uncancelled = new boolean[mainHands.length];
        int[] mainCharges = new int[mainHands.length];
        int[] offCharges = new int[mainHands.length];
        for (int index = 0; index < mainHands.length; index++) {
            ItemStack[] hands = {mainHands[index], offHands[index]};
            Player player = fakePlayerWithHands(hands);
            EntityResurrectEvent event = new EntityResurrectEvent(player,
                    index == 3 ? EquipmentSlot.HAND : null);
            event.setCancelled(index != 3);
            binding.onResurrect(event);
            uncancelled[index] = !event.isCancelled();
            mainCharges[index] = probeEnchants.totemCharge(hands[0]);
            offCharges[index] = probeEnchants.totemCharge(hands[1]);
            if (index == 0 || index == 1 || index == 4) {
                ItemStack used = index == 1 ? hands[1] : hands[0];
                uncancelled[index] &= level(used, "totembindung") == 1
                        && visible(used, "Totemladung: 0/1");
            }
        }
        getLogger().info("TOTEMBINDUNG_MAIN_OFF="
                + (uncancelled[0] && mainCharges[0] == 0
                && uncancelled[1] && offCharges[1] == 0 ? "PASS" : "FAIL"));
        getLogger().info("TOTEMBINDUNG_NOT_HELD="
                + (!uncancelled[2] && mainCharges[2] == 0 ? "PASS" : "FAIL"));
        getLogger().info("TOTEMBINDUNG_VANILLA_PRIORITY="
                + (uncancelled[3] && offCharges[3] == 1 ? "PASS" : "FAIL"));
        getLogger().info("TOTEMBINDUNG_TWO_SHIELDS="
                + (uncancelled[4] && mainCharges[4] == 0 && offCharges[4] == 1 ? "PASS" : "FAIL"));
        getLogger().info("TOTEMBINDUNG_NO_CHARGE="
                + (!uncancelled[5] ? "PASS" : "FAIL"));

        CustomEnchantmentAnvilListener anvil = new CustomEnchantmentAnvilListener(probeEnchants, probeItems);
        ItemStack anvilResult = mergeResult(anvil, shield, new ItemStack(Material.SHIELD),
                new ItemStack(Material.SHIELD));
        ItemStack bothResult = mergeResult(anvil, shield, shield.clone(),
                new ItemStack(Material.SHIELD));
        ItemStack rightOnly = mergeResult(anvil, new ItemStack(Material.SHIELD), shield,
                new ItemStack(Material.SHIELD));
        boolean anvilSafe = level(anvilResult, "totembindung") == 1
                && probeEnchants.totemCharge(anvilResult) == 1
                && visible(anvilResult, "Totemladung: 1/1")
                && probeEnchants.totemCharge(bothResult) == 1
                && level(rightOnly, "totembindung") == 1
                && probeEnchants.totemCharge(rightOnly) == 0
                && visible(rightOnly, "Totemladung: 0/1");
        getLogger().info("TOTEMBINDUNG_ANVIL_LEFT_CHARGE="
                + (anvilSafe ? "PASS" : "FAIL"));
    }

    private Player fakePlayerWithHands(ItemStack[] hands) {
        PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {PlayerInventory.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getItemInMainHand" -> hands[0];
                    case "getItemInOffHand" -> hands[1];
                    case "setItemInMainHand" -> { hands[0] = (ItemStack) args[0]; yield null; }
                    case "setItemInOffHand" -> { hands[1] = (ItemStack) args[0]; yield null; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (Player) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getInventory")) return inventory;
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private void runSoulboundProbe() {
        try {
            SoulboundDeathListener listener = new SoulboundDeathListener(probeEnchants);
            Player victim = (Player) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "hasMetadata" -> false;
                        case "getUniqueId" -> UUID.fromString("40b7cebc-7030-49d2-bd4c-4dcf9533f337");
                        default -> throw new UnsupportedOperationException(method.getName());
                    });

            ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
            probeEnchants.applyDetailed(sword, "seelenbindung", 1);
            probeEnchants.applyDetailed(sword, "magnet", 1);
            ItemMeta swordMeta = sword.getItemMeta();
            swordMeta.displayName(Component.text("Gebundenes Schwert"));
            List<Component> swordLore = new ArrayList<>(swordMeta.lore());
            swordLore.add(Component.text("Unveränderte Zusatzzeile"));
            swordMeta.lore(swordLore);
            swordMeta.getPersistentDataContainer().set(new NamespacedKey(this, "soulbound_test"),
                    PersistentDataType.STRING, "bleibt");
            ((Damageable) swordMeta).setDamage(120);
            sword.setItemMeta(swordMeta);
            byte[] originalSword = sword.serializeAsBytes();
            ItemStack ordinary = new ItemStack(Material.DIAMOND_PICKAXE);

            PlayerDeathEvent pve = soulboundDeath(victim, DamageType.MOB_ATTACK, false,
                    sword, ordinary);
            listener.onDeath(pve);
            boolean one = containsSame(pve.getItemsToKeep(), sword)
                    && !containsSame(pve.getDrops(), sword)
                    && containsSame(pve.getDrops(), ordinary)
                    && pve.getItemsToKeep().size() == 1
                    && Arrays.equals(originalSword, sword.serializeAsBytes())
                    && probeEnchants.level(sword, "seelenbindung") == 1
                    && probeEnchants.level(sword, "magnet") == 1;
            getLogger().info("SEELENBINDUNG_ONE_METADATA_PVE=" + (one ? "PASS" : "FAIL"));

            ItemStack bow = new ItemStack(Material.BOW);
            probeEnchants.applyDetailed(bow, "seelenbindung", 1);
            ItemStack pickaxe = new ItemStack(Material.NETHERITE_PICKAXE);
            probeEnchants.applyDetailed(pickaxe, "spawnergriff", 1);
            probeEnchants.setSpawnerUses(pickaxe, 2);
            probeEnchants.applyDetailed(pickaxe, "seelenbindung", 1);
            byte[] originalPickaxe = pickaxe.serializeAsBytes();
            PlayerDeathEvent several = soulboundDeath(victim, DamageType.PLAYER_ATTACK, false,
                    ordinary, sword, bow, pickaxe);
            listener.onDeath(several);
            boolean multiple = several.getItemsToKeep().size() == 3
                    && containsSame(several.getItemsToKeep(), sword)
                    && containsSame(several.getItemsToKeep(), bow)
                    && containsSame(several.getItemsToKeep(), pickaxe)
                    && several.getDrops().size() == 1
                    && containsSame(several.getDrops(), ordinary)
                    && Arrays.equals(originalPickaxe, pickaxe.serializeAsBytes())
                    && probeEnchants.spawnerUses(pickaxe) == 2;
            getLogger().info("SEELENBINDUNG_MULTIPLE_PVP=" + (multiple ? "PASS" : "FAIL"));

            PlayerDeathEvent repeated = soulboundDeath(victim, DamageType.LAVA, false, sword);
            listener.onDeath(repeated);
            boolean permanent = repeated.getItemsToKeep().size() == 1
                    && repeated.getDrops().isEmpty()
                    && containsSame(repeated.getItemsToKeep(), sword)
                    && probeEnchants.level(sword, "seelenbindung") == 1
                    && Arrays.equals(originalSword, sword.serializeAsBytes());
            getLogger().info("SEELENBINDUNG_PERMANENT=" + (permanent ? "PASS" : "FAIL"));

            PlayerDeathEvent keepInventory = soulboundDeath(victim, DamageType.FALL, true, sword);
            listener.onDeath(keepInventory);
            boolean keptNormally = keepInventory.getItemsToKeep().isEmpty()
                    && keepInventory.getDrops().size() == 1
                    && containsSame(keepInventory.getDrops(), sword);
            getLogger().info("SEELENBINDUNG_KEEP_INVENTORY="
                    + (keptNormally ? "PASS" : "FAIL"));

            PlayerDeathEvent plain = soulboundDeath(victim, DamageType.GENERIC, false, ordinary);
            listener.onDeath(plain);
            boolean unaffected = plain.getItemsToKeep().isEmpty()
                    && plain.getDrops().size() == 1
                    && containsSame(plain.getDrops(), ordinary);
            getLogger().info("SEELENBINDUNG_PLAIN_ITEM=" + (unaffected ? "PASS" : "FAIL"));
        } catch (Exception exception) {
            getLogger().severe("SEELENBINDUNG_PROBE=ERROR " + exception);
        }
    }

    private PlayerDeathEvent soulboundDeath(Player victim, DamageType cause,
                                            boolean keepInventory, ItemStack... drops) {
        PlayerDeathEvent event = new PlayerDeathEvent(victim, DamageSource.builder(cause).build(),
                new ArrayList<>(List.of(drops)), 0, Component.empty(), true);
        event.setKeepInventory(keepInventory);
        return event;
    }

    private boolean containsSame(List<ItemStack> items, ItemStack expected) {
        return items.stream().anyMatch(item -> item == expected);
    }

    private void runSpawnergriffProbe() {
        SpawnergriffListener grip = new SpawnergriffListener(this, probeEnchants);
        World world = getServer().getWorlds().getFirst();
        Block block = world.getBlockAt(64, world.getMaxHeight() - 10, 0);
        world.loadChunk(block.getX() >> 4, block.getZ() >> 4);
        BlockState original = block.getState();
        try {
            // This isolated probe reuses its world after restarts.
            nearbySpawnerItems(block).forEach(Item::remove);
            boolean materials = true;
            for (Material material : List.of(Material.WOODEN_PICKAXE, Material.STONE_PICKAXE,
                    Material.IRON_PICKAXE, Material.GOLDEN_PICKAXE,
                    Material.DIAMOND_PICKAXE, Material.NETHERITE_PICKAXE)) {
                ItemStack pickaxe = new ItemStack(material);
                materials &= probeEnchants.applyDetailed(pickaxe, "spawnergriff", 1)
                        == CustomEnchantmentService.ApplyResult.SUCCESS
                        && probeEnchants.spawnerUses(pickaxe) == 3;
            }
            materials &= probeEnchants.applyDetailed(new ItemStack(Material.SHIELD), "spawnergriff", 1)
                    == CustomEnchantmentService.ApplyResult.NOT_APPLICABLE;
            getLogger().info("SPAWNERGRIFF_PICKAXES=" + (materials ? "PASS" : "FAIL"));

            CustomEnchantmentAnvilListener anvil = new CustomEnchantmentAnvilListener(
                    probeEnchants, probeItems);
            ItemStack newFromBook = mergeResult(anvil, new ItemStack(Material.NETHERITE_PICKAXE),
                    probeItems.create("angler_buch_spawnergriff_1", 1),
                    new ItemStack(Material.NETHERITE_PICKAXE));
            boolean appliedBook = newFromBook != null
                    && probeEnchants.level(newFromBook, "spawnergriff") == 1
                    && Integer.valueOf(3).equals(rawSpawnerUses(newFromBook))
                    && spawnerUsesLore(newFromBook, 3);
            getLogger().info("SPAWNERGRIFF_ANVIL_NEW_3=" + (appliedBook ? "PASS" : "FAIL"));

            ItemStack legacy = new ItemStack(Material.NETHERITE_PICKAXE);
            probeEnchants.applyDetailed(legacy, "spawnergriff", 1);
            var legacyMeta = legacy.getItemMeta();
            legacyMeta.getPersistentDataContainer().remove(new NamespacedKey(this, "spawnergriff_uses"));
            legacyMeta.lore(legacyMeta.lore().stream().filter(line ->
                    !PlainTextComponentSerializer.plainText().serialize(line).trim()
                            .startsWith("Spawner-Nutzungen:")).toList());
            legacy.setItemMeta(legacyMeta);
            boolean migratedMissing = rawSpawnerUses(legacy) == null
                    && probeEnchants.spawnerUses(legacy) == 3
                    && Integer.valueOf(3).equals(rawSpawnerUses(legacy)) && spawnerUsesLore(legacy, 3);
            probeEnchants.setSpawnerUses(legacy, 0);
            boolean migratedZero = probeEnchants.spawnerUses(legacy) == 3
                    && Integer.valueOf(3).equals(rawSpawnerUses(legacy)) && spawnerUsesLore(legacy, 3);
            getLogger().info("SPAWNERGRIFF_LEGACY_MIGRATION="
                    + (migratedMissing && migratedZero ? "PASS" : "FAIL"));

            String usesPath = "enchants.spawnergriff.max-uses";
            var anglerConfig = probeConfigurations.angler();
            boolean defaultUses = probeEnchants.maxSpawnerUses() == 3;
            anglerConfig.set(usesPath, "invalid");
            defaultUses &= probeEnchants.maxSpawnerUses() == 3;
            anglerConfig.set(usesPath, 0);
            defaultUses &= probeEnchants.maxSpawnerUses() == 3;
            anglerConfig.set(usesPath, null);
            getLogger().info("SPAWNERGRIFF_CONFIG_DEFAULT=" + (defaultUses ? "PASS" : "FAIL"));

            anglerConfig.set(usesPath, 5);
            try {
                ItemStack five = new ItemStack(Material.NETHERITE_PICKAXE);
                probeEnchants.applyDetailed(five, "spawnergriff", 1);
                boolean configuredFive = Integer.valueOf(5).equals(rawSpawnerUses(five))
                        && spawnerUsesLore(five, 5);
                ItemStack[] fiveHand = {five};
                Block fiveBlock = world.getBlockAt(68, block.getY(), block.getZ());
                BlockState fiveOriginal = fiveBlock.getState();
                try {
                    simulateSpawnerBreak(grip, fiveBlock, fakeMinerWithTool(fiveHand),
                            EntityType.ZOMBIE, false);
                    configuredFive &= Integer.valueOf(4).equals(rawSpawnerUses(fiveHand[0]))
                            && spawnerUsesLore(fiveHand[0], 4);
                } finally {
                    fiveOriginal.update(true, false);
                    Bukkit.getScheduler().runTaskLater(this,
                            () -> nearbySpawnerItems(fiveBlock).forEach(Item::remove), 3L);
                }
                getLogger().info("SPAWNERGRIFF_CONFIG_FIVE="
                        + (configuredFive ? "PASS" : "FAIL"));

                probeEnchants.setSpawnerUses(fiveHand[0], 1);
                anglerConfig.set(usesPath, 3);
                boolean unchanged = probeEnchants.spawnerUses(fiveHand[0]) == 1
                        && Integer.valueOf(1).equals(rawSpawnerUses(fiveHand[0]))
                        && spawnerUsesLore(fiveHand[0], 1);
                anglerConfig.set(usesPath, 5);
                unchanged &= probeEnchants.spawnerUses(fiveHand[0]) == 1
                        && Integer.valueOf(1).equals(rawSpawnerUses(fiveHand[0]))
                        && spawnerUsesLore(fiveHand[0], 1);
                getLogger().info("SPAWNERGRIFF_CONFIG_CHANGE_NO_REFILL="
                        + (unchanged ? "PASS" : "FAIL"));

                ItemStack legacyFive = new ItemStack(Material.NETHERITE_PICKAXE);
                probeEnchants.applyDetailed(legacyFive, "spawnergriff", 1);
                var meta = legacyFive.getItemMeta();
                meta.getPersistentDataContainer().remove(new NamespacedKey(this, "spawnergriff_uses"));
                legacyFive.setItemMeta(meta);
                boolean migrateFive = probeEnchants.spawnerUses(legacyFive) == 5
                        && Integer.valueOf(5).equals(rawSpawnerUses(legacyFive))
                        && spawnerUsesLore(legacyFive, 5);
                getLogger().info("SPAWNERGRIFF_CONFIG_MIGRATION_FIVE="
                        + (migrateFive ? "PASS" : "FAIL"));
            } finally {
                anglerConfig.set(usesPath, null);
            }

            ItemStack[] hands = {new ItemStack(Material.DIAMOND_PICKAXE)};
            Player player = fakeMinerWithTool(hands);
            BlockDropItemEvent noEnchant = simulateSpawnerBreak(grip, block, player,
                    EntityType.ZOMBIE, false);
            boolean without = noEnchant != null && nearbySpawnerItems(block).isEmpty();
            getLogger().info("SPAWNERGRIFF_NONE=" + (without ? "PASS" : "FAIL"));

            probeEnchants.applyDetailed(hands[0], "spawnergriff", 1);
            boolean firstLore = Integer.valueOf(3).equals(rawSpawnerUses(hands[0]))
                    && spawnerUsesLore(hands[0], 3);
            boolean threeUses = true;
            for (int index = 0; index < 3; index++) {
                EntityType type = index == 1 ? EntityType.BLAZE : EntityType.ZOMBIE;
                if (index == 1) hands[0].addEnchantment(
                        Registry.ENCHANTMENT.get(NamespacedKey.minecraft("silk_touch")), 1);
                BlockDropItemEvent dropped = simulateSpawnerBreak(grip, block, player, type, false);
                getLogger().info("SPAWNERGRIFF_STEP " + index
                        + " uses=" + probeEnchants.spawnerUses(hands[0])
                        + " enchant=" + level(hands[0], "spawnergriff")
                        + " eventDrops=" + (dropped == null ? -1 : dropped.getItems().size()));
                threeUses &= dropped != null && dropped.getItems().isEmpty();
                int remaining = index == 2 ? 0 : 2 - index;
                threeUses &= probeEnchants.spawnerUses(hands[0]) == remaining
                        && probeEnchants.level(hands[0], "spawnergriff") == (index == 2 ? 0 : 1)
                        && hands[0].getType() == Material.DIAMOND_PICKAXE;
                threeUses &= index == 2
                        ? rawSpawnerUses(hands[0]) == null && !hasSpawnerUsesLore(hands[0])
                        : Integer.valueOf(remaining).equals(rawSpawnerUses(hands[0]))
                        && spawnerUsesLore(hands[0], remaining);
            }
            getLogger().info("SPAWNERGRIFF_THREE_USES_SILK="
                    + (threeUses && firstLore && hands[0].containsEnchantment(
                    Registry.ENCHANTMENT.get(NamespacedKey.minecraft("silk_touch")))
                    ? "PASS" : "FAIL"));
            Bukkit.getScheduler().runTaskLater(this, () -> {
                List<Item> drops = nearbySpawnerItems(block);
                int zombie = 0;
                int blaze = 0;
                int total = 0;
                boolean mobLore = true;
                for (Item drop : drops) {
                    int amount = drop.getItemStack().getAmount();
                    total += amount;
                    EntityType type = grip.storedEntityType(drop.getItemStack());
                    mobLore &= type != null && mobTypeLore(drop.getItemStack(), type);
                    if (type == EntityType.ZOMBIE) zombie += amount;
                    if (type == EntityType.BLAZE) blaze += amount;
                    drop.remove();
                }
                getLogger().info("SPAWNERGRIFF_ONE_DROP_EACH="
                        + (total == 3 ? "PASS" : "FAIL") + " total=" + total);
                getLogger().info("SPAWNERGRIFF_MOB_TYPES="
                        + (zombie == 2 && blaze == 1 ? "PASS" : "FAIL")
                        + " zombie=" + zombie + " blaze=" + blaze);
                getLogger().info("SPAWNERGRIFF_MOB_LORE=" + (mobLore ? "PASS" : "FAIL"));
            }, 2L);
            ItemStack caveSpider = grip.createSpawnerItem(EntityType.CAVE_SPIDER);
            getLogger().info("SPAWNERGRIFF_CAVE_SPIDER_LORE="
                    + (grip.storedEntityType(caveSpider) == EntityType.CAVE_SPIDER
                    && mobTypeLore(caveSpider, EntityType.CAVE_SPIDER) ? "PASS" : "FAIL"));

            ItemStack protectedTool = new ItemStack(Material.NETHERITE_PICKAXE);
            probeEnchants.applyDetailed(protectedTool, "spawnergriff", 1);
            ItemStack[] protectedHand = {protectedTool};
            Player protectedMiner = fakeMinerWithTool(protectedHand);
            boolean excluded = true;
            for (Material type : List.of(Material.STONE, Material.DIAMOND_ORE,
                    Material.TRIAL_SPAWNER, Material.VAULT)) {
                block.setType(type);
                BlockBreakEvent event = new BlockBreakEvent(block, protectedMiner);
                grip.onBreak(event);
                excluded &= probeEnchants.spawnerUses(protectedHand[0]) == 3;
            }
            BlockDropItemEvent cancelled = simulateSpawnerBreak(grip, block, protectedMiner,
                    EntityType.ZOMBIE, true);
            excluded &= cancelled == null && probeEnchants.spawnerUses(protectedHand[0]) == 3;
            getLogger().info("SPAWNERGRIFF_EXCLUSIONS_CANCEL="
                    + (excluded ? "PASS" : "FAIL"));

            probeEnchants.consumeSpawnerUse(protectedHand[0]);
            boolean persistence = probeEnchants.spawnerUses(protectedHand[0]) == 2
                    && probeEnchants.spawnerUses(ItemStack.deserializeBytes(
                    protectedHand[0].serializeAsBytes())) == 2;
            AnglerStorageRepository storage = new AnglerStorageRepository(probeStorage);
            UUID owner = UUID.fromString("9e56ad8e-5fc5-4e65-9e5a-a398ea8c38b1");
            ItemStack previous = storage.load(owner)[0];
            if (previous != null) getLogger().info("SPAWNERGRIFF_RESTART_PDC="
                    + (probeEnchants.spawnerUses(previous) == 2 ? "PASS" : "FAIL"));
            ItemStack[] saved = new ItemStack[54];
            saved[0] = protectedHand[0];
            storage.save(owner, saved);
            persistence &= probeEnchants.spawnerUses(storage.load(owner)[0]) == 2;
            getLogger().info("SPAWNERGRIFF_PERSISTENCE="
                    + (persistence ? "PASS" : "FAIL"));

            probeEnchants.consumeSpawnerUse(protectedHand[0]);
            ItemStack repaired = mergeResult(anvil, protectedHand[0],
                    new ItemStack(Material.NETHERITE_PICKAXE), new ItemStack(Material.NETHERITE_PICKAXE));
            ItemStack fresh = new ItemStack(Material.NETHERITE_PICKAXE);
            probeEnchants.applyDetailed(fresh, "spawnergriff", 1);
            ItemStack combined = mergeResult(anvil, protectedHand[0], fresh,
                    new ItemStack(Material.NETHERITE_PICKAXE));
            boolean anvilUses = probeEnchants.spawnerUses(protectedHand[0]) == 1
                    && probeEnchants.spawnerUses(repaired) == 1
                    && probeEnchants.spawnerUses(combined) == 1
                    && Integer.valueOf(1).equals(rawSpawnerUses(repaired))
                    && spawnerUsesLore(repaired, 1)
                    && Integer.valueOf(1).equals(rawSpawnerUses(combined))
                    && spawnerUsesLore(combined, 1);
            probeEnchants.applyDetailed(protectedHand[0], "spawnergriff", 1);
            anvilUses &= probeEnchants.spawnerUses(protectedHand[0]) == 1;
            getLogger().info("SPAWNERGRIFF_ANVIL_NO_REFILL="
                    + (anvilUses ? "PASS" : "FAIL"));

            ItemStack placeItem = grip.createSpawnerItem(EntityType.BLAZE);
            block.setType(Material.AIR);
            BlockState replaced = block.getState();
            block.setType(Material.SPAWNER);
            BlockPlaceEvent place = new BlockPlaceEvent(block, replaced,
                    block.getRelative(org.bukkit.block.BlockFace.DOWN), placeItem,
                    protectedMiner, true, EquipmentSlot.HAND);
            int usesBeforePlace = probeEnchants.spawnerUses(protectedHand[0]);
            grip.onPlace(place);
            Bukkit.getScheduler().runTask(this, () -> {
                boolean placed = block.getState() instanceof CreatureSpawner spawner
                        && spawner.getSpawnedType() == EntityType.BLAZE
                        && probeEnchants.spawnerUses(protectedHand[0]) == usesBeforePlace;
                getLogger().info("SPAWNERGRIFF_PLACE=" + (placed ? "PASS" : "FAIL"));
                original.update(true, false);
            });
        } catch (Exception exception) {
            original.update(true, false);
            getLogger().severe("SPAWNERGRIFF_PROBE=ERROR " + exception);
        }
    }

    private BlockDropItemEvent simulateSpawnerBreak(SpawnergriffListener grip, Block block,
                                                     Player player, EntityType type, boolean cancel) {
        block.setType(Material.SPAWNER);
        CreatureSpawner spawner = (CreatureSpawner) block.getState();
        spawner.setSpawnedType(type);
        spawner.update(true, false);
        BlockBreakEvent breakEvent = new BlockBreakEvent(block, player);
        breakEvent.setCancelled(cancel);
        grip.onBreak(breakEvent);
        if (cancel) return null;
        BlockState before = block.getState();
        block.setType(Material.AIR);
        BlockDropItemEvent dropEvent = new BlockDropItemEvent(block, before, player,
                new ArrayList<>());
        grip.onDrop(dropEvent);
        return dropEvent;
    }

    private List<Item> nearbySpawnerItems(Block block) {
        return block.getWorld().getNearbyEntities(block.getLocation(), 2D, 2D, 2D).stream()
                .filter(Item.class::isInstance).map(Item.class::cast)
                .filter(item -> item.getItemStack().getType() == Material.SPAWNER).toList();
    }

    private Integer rawSpawnerUses(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(
                new NamespacedKey(this, "spawnergriff_uses"), PersistentDataType.INTEGER);
    }

    private boolean spawnerUsesLore(ItemStack item, int uses) {
        return item != null && item.hasItemMeta() && item.getItemMeta().lore() != null
                && item.getItemMeta().lore().stream().anyMatch(line ->
                PlainTextComponentSerializer.plainText().serialize(line).trim()
                        .equals("Spawner-Nutzungen: " + uses + "/" + probeEnchants.maxSpawnerUses()));
    }

    private boolean mobTypeLore(ItemStack item, EntityType type) {
        if (item == null || !item.hasItemMeta() || item.getItemMeta().lore() == null
                || item.getItemMeta().lore().size() != 1) return false;
        Component line = item.getItemMeta().lore().getFirst();
        return line.children().size() == 1
                && line.children().getFirst() instanceof TranslatableComponent translated
                && translated.key().equals(type.translationKey())
                && PlainTextComponentSerializer.plainText().serialize(line).startsWith("Mob: ");
    }

    private boolean hasSpawnerUsesLore(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().lore() != null
                && item.getItemMeta().lore().stream().anyMatch(line ->
                PlainTextComponentSerializer.plainText().serialize(line).trim()
                        .startsWith("Spawner-Nutzungen:"));
    }

    private Player fakeMinerWithTool(ItemStack[] hand) {
        PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {PlayerInventory.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getItemInMainHand" -> hand[0];
                    case "setItemInMainHand" -> { hand[0] = (ItemStack) args[0]; yield null; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        UUID id = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getInventory" -> inventory;
                    case "getUniqueId" -> id;
                    case "getGameMode" -> GameMode.SURVIVAL;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private boolean comboCases(org.bukkit.configuration.file.FileConfiguration config,
                               int concentrationLevel, int[] counts, double[] multipliers) {
        if (counts.length != multipliers.length) return false;
        for (int index = 0; index < counts.length; index++) {
            FishingComboTracker.Result result = comboAt(config, counts[index], concentrationLevel);
            if (result.combo() != counts[index]
                    || Math.abs(result.multiplier() - multipliers[index]) > 1e-9D) return false;
        }
        return true;
    }

    private FishingComboTracker.Result comboAt(org.bukkit.configuration.file.FileConfiguration config,
                                               int count, int concentrationLevel) {
        FishingComboTracker tracker = new FishingComboTracker(config);
        UUID id = UUID.randomUUID();
        long now = System.currentTimeMillis();
        FishingComboTracker.Result result = null;
        for (int index = 0; index < count; index++) {
            tracker.attempt(id, now);
            result = tracker.finish(id, FishingGame.Quality.GREEN, concentrationLevel);
        }
        return result;
    }
}
