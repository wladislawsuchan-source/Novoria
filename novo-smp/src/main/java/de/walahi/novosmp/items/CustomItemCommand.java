package de.walahi.novosmp.items;

import de.walahi.novosmp.angler.FishDefinition;
import de.walahi.novosmp.angler.AnglerFeature;
import de.walahi.novosmp.angler.FishItemFactory;
import de.walahi.novosmp.angler.FishRegistry;
import de.walahi.novosmp.angler.AnglerFishingService;
import de.walahi.novosmp.angler.AnglerLootFoundation;
import de.walahi.novosmp.angler.FishingLootPoolSelector;
import de.walahi.novosmp.angler.FishingGame;
import de.walahi.novosmp.enchants.CustomEnchantmentService;
import de.walahi.novosmp.enchants.HolzschlagConfig;
import de.walahi.novosmp.enchants.MiningEnchantConfig;
import de.walahi.smpcore.SMPCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import de.walahi.smpcore.commands.framework.BaseCommand;

import java.util.ArrayList;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/**
 * Verwaltungsbefehl für Custom-Items.
 *
 * <p>Wird zweimal registriert: einmal als allgemeines {@code /customitem} und einmal als
 * {@code /fragmente}, bei dem die Item-Kennung fest vorgegeben ist. Damit lassen sich Werkzeugfragmente gezielt administrieren.</p>
 */
public final class CustomItemCommand extends BaseCommand {
    private final CustomItemManager items;
    private final HolzschlagConfig holzschlag;
    private final CustomEnchantmentService enchantments;
    private final MiningEnchantConfig miningEnchants;

    /** Feste Item-Kennung, oder {@code null} für den allgemeinen Befehl. */
    private final String fixedItemId;
    private final FishRegistry fishRegistry;
    private final FishItemFactory fishFactory;
    private final AnglerFeature anglerFeature;
    private final AnglerFishingService anglerFishing;
    private final AnglerLootFoundation anglerLoot;

    public CustomItemCommand(SMPCorePlugin plugin, CustomItemManager items,
                             HolzschlagConfig holzschlag, MiningEnchantConfig miningEnchants,
                             CustomEnchantmentService enchantments, String fixedItemId) {
        this(plugin, items, holzschlag, miningEnchants, enchantments, fixedItemId, null, null, null, null);
    }

    public CustomItemCommand(SMPCorePlugin plugin, CustomItemManager items,
                             HolzschlagConfig holzschlag, MiningEnchantConfig miningEnchants,
                             CustomEnchantmentService enchantments, String fixedItemId,
                             FishRegistry fishRegistry, AnglerFeature anglerFeature,
                             AnglerFishingService anglerFishing, AnglerLootFoundation anglerLoot) {
        super(plugin);
        this.items = items;
        this.holzschlag = holzschlag;
        this.enchantments = enchantments;
        this.miningEnchants = miningEnchants;
        this.fixedItemId = fixedItemId;
        this.fishRegistry = fishRegistry;
        this.fishFactory = fishRegistry == null ? null : new FishItemFactory(fishRegistry);
        this.anglerFeature = anglerFeature;
        this.anglerFishing = anglerFishing;
        this.anglerLoot = anglerLoot;
    }

    @Override
    protected String permission() {
        return "novosmp.customitem.admin";
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (isAnglerAlias(label)) return handleAnglerTest(sender, args);
        if (args.length == 0) return usage(sender, label);

        String action = args[0].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "give", "take", "count" -> handleInventoryAction(sender, label, action, args);
            case "storecatch" -> handleStoreCatch(sender, label, args);
            case "fishingpool" -> handleFishingPool(sender, label, args);
            case "fishingroll" -> handleFishingRoll(sender, label, args);
            case "bundletest" -> handleBundleTest(sender, label, args);
            case "list" -> handleList(sender);
            case "reload" -> handleReload(sender);
            default -> usage(sender, label);
        };
    }

    private boolean handleAnglerTest(CommandSender sender, String[] args) {
        if (anglerLoot == null || args.length < 3 || args.length > 4) return anglerUsage(sender);
        String action = args[0].toLowerCase(Locale.ROOT);
        if (!List.of("testbook", "testcustomitem", "testlumi", "testloot").contains(action))
            return anglerUsage(sender);
        if (args.length == 4 && !action.equals("testloot")) return anglerUsage(sender);
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) return anglerMessage(sender, "messages.admin-test-player-offline",
                "<red>Spieler <white>%player%</white> ist nicht online.</red>", "%player%", args[1]);
        if (action.equals("testloot")) return handleAnglerTestLoot(sender, target, args);
        if (action.equals("testlumi")) return handleAnglerTestLumi(sender, target, args[2]);
        return handleAnglerTestItem(sender, target, args[2], action.equals("testbook"));
    }

    private boolean handleAnglerTestLoot(CommandSender sender, Player target, String[] args) {
        String category = args[2].toLowerCase(Locale.ROOT);
        if (anglerFishing == null || !List.of("treasure", "junk", "rare", "epic", "legendary").contains(category))
            return anglerMessage(sender, "messages.admin-test-loot-usage",
                    "<yellow>/angler testloot <Spieler> <treasure|junk|rare|epic|legendary> [Anzahl bis 1000|Entry-ID]</yellow>");
        int attempts = 1;
        String entryId = null;
        if (args.length == 4) {
            try { attempts = Integer.parseInt(args[3]); }
            catch (NumberFormatException exception) {
                if (category.equals("rare") || category.equals("epic") || category.equals("legendary"))
                    entryId = args[3].toLowerCase(Locale.ROOT);
                else attempts = -1;
            }
        }
        if (attempts < 1 || attempts > 1_000)
            return anglerMessage(sender, "messages.admin-test-loot-usage",
                    "<yellow>/angler testloot <Spieler> <treasure|junk|rare|epic|legendary> [Anzahl bis 1000|Entry-ID]</yellow>");
        String poolName = switch (category) {
            case "junk" -> "Junk";
            case "rare" -> "Rare";
            case "epic" -> "Epic";
            case "legendary" -> "Legendary";
            default -> "Treasure";
        };
        boolean ready = switch (category) {
            case "junk" -> anglerFishing.junkReady();
            case "rare" -> anglerFishing.rareReady();
            case "epic" -> anglerFishing.epicReady();
            case "legendary" -> anglerFishing.legendaryReady();
            default -> anglerFishing.treasureReady();
        };
        if (!ready) return anglerMessage(sender,
                "messages.admin-test-loot-unavailable",
                "<red>%pool%-Loot ist nicht vollständig konfiguriert. Server-Log prüfen.</red>",
                "%pool%", poolName);
        if (category.equals("rare") || category.equals("epic") || category.equals("legendary")) {
            if (!anglerFishing.rareAnglerActive(target))
                return anglerMessage(sender, "messages.admin-test-loot-angler-required",
                        "<red>%player% muss Angler als aktiven Beruf haben.</red>", "%player%", target.getName());
            if (category.equals("legendary") && !anglerFishing.legendaryEligible(target))
                return anglerMessage(sender, "messages.admin-test-loot-legendary-prestige-required",
                        "<red>%player% benötigt Angler-Prestige V für Legendary-Loot.</red>",
                        "%player%", target.getName());
            java.util.Set<String> validEntries = switch (category) {
                case "rare" -> anglerFishing.rareEntryIds();
                case "epic" -> anglerFishing.epicEntryIds();
                default -> anglerFishing.legendaryEntryIds();
            };
            if (entryId != null && !validEntries.contains(entryId))
                return anglerMessage(sender, "messages.admin-test-loot-entry-unknown",
                        "<red>Unbekannte Entry-ID: <white>%entry%</white>.</red>", "%entry%", entryId);
            if ("overlevel_book".equals(entryId) && !(category.equals("rare")
                    ? anglerFishing.rareOverlevelAvailable(target) : anglerFishing.epicOverlevelAvailable(target)))
                return anglerMessage(sender, "messages.admin-test-loot-prestige-locked",
                        "<red>Für %player%s aktuelles Angler-Prestige ist kein Overlevel-Buch freigeschaltet.</red>",
                        "%player%", target.getName());
        }
        if (attempts == 1 || entryId != null) {
            String displayName;
            if (category.equals("junk")) {
                var result = anglerFishing.giveJunk(target);
                displayName = result == null ? null : result.displayName();
            } else if (category.equals("rare")) {
                var result = anglerFishing.giveRare(target, entryId);
                displayName = result == null ? null : result.displayName();
            } else if (category.equals("epic")) {
                var result = anglerFishing.giveEpic(target, entryId);
                displayName = result == null ? null : result.displayName();
            } else if (category.equals("legendary")) {
                var result = anglerFishing.giveLegendary(target, entryId);
                displayName = result == null ? null : result.displayName();
            } else {
                var result = anglerFishing.giveTreasure(target);
                displayName = result == null ? null : result.displayName();
            }
            if (displayName == null) return anglerMessage(sender, "messages.admin-test-loot-failed",
                    "<red>%pool%-Test konnte nicht ausgegeben werden. Server-Log prüfen.</red>",
                    "%pool%", poolName);
            return anglerMessage(sender, "messages.admin-test-loot-given",
                    "<green>%pool%-Test an <white>%player%</white>: <yellow>%entry%</yellow>.</green>",
                    "%pool%", poolName, "%player%", target.getName(), "%entry%", displayName);
        }
        var counts = switch (category) {
            case "junk" -> anglerFishing.simulateJunk(attempts);
            case "rare" -> anglerFishing.simulateRare(target, attempts);
            case "epic" -> anglerFishing.simulateEpic(target, attempts);
            case "legendary" -> anglerFishing.simulateLegendary(target, attempts);
            default -> anglerFishing.simulateTreasure(attempts);
        };
        anglerMessage(sender, "messages.admin-test-loot-header",
                "<aqua>%pool%-Simulation für %player%: %count% interne Rolls, keine Items ausgegeben.</aqua>",
                "%pool%", poolName, "%player%", target.getName(), "%count%", Integer.toString(attempts));
        counts.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry ->
                anglerMessage(sender, "messages.admin-test-loot-line",
                        "<gray>%entry%: <yellow>%count%</yellow></gray>",
                        "%entry%", entry.getKey(), "%count%", Integer.toString(entry.getValue())));
        return true;
    }

    private boolean handleAnglerTestItem(CommandSender sender, Player target, String rawId, boolean book) {
        String id = rawId.toLowerCase(Locale.ROOT);
        AnglerLootFoundation.LootReward reward;
        if (book) {
            AnglerLootFoundation.OverlevelBook definition = anglerLoot.overlevelBooks().get(id);
            if (definition == null) {
                String path = "loot.overlevel-books." + id;
                if (plugin.configs().angler().contains(path)) return anglerMessage(sender,
                        "messages.admin-test-book-invalid",
                        "<red>Overlevel-Buch <white>%id%</white> ist in angler.yml ungültig konfiguriert (Enchant/Level).</red>",
                        "%id%", id);
                return anglerMessage(sender, "messages.admin-test-book-unknown",
                        "<red>Unbekannte Overlevel-Buch-ID: <white>%id%</white>.</red>", "%id%", id);
            }
            // Admin item test: use the configured unlock tier, not the recipient's profession state.
            reward = new AnglerLootFoundation.OverlevelBookReward(id, definition.minPrestige());
        } else {
            if (items.find(id).isEmpty()) return anglerMessage(sender, "messages.admin-test-custom-unknown",
                    "<red>Unbekannte Custom-Item-ID: <white>%id%</white>.</red>", "%id%", id);
            reward = new AnglerLootFoundation.CustomItemReward(id, 1);
        }
        try {
            ItemStack item = anglerLoot.createPhysicalReward(reward);
            if (!book && !items.is(item, id))
                throw new IllegalStateException("Custom-Item-ID fehlt am erzeugten Item: " + id);
            int dropped = 0;
            for (ItemStack overflow : target.getInventory().addItem(item).values()) {
                dropped += overflow.getAmount();
                target.getWorld().dropItemNaturally(target.getLocation(), overflow);
            }
            return anglerMessage(sender, book ? "messages.admin-test-book-given" : "messages.admin-test-custom-given",
                    "<green>Angler-Testitem <yellow>%id%</yellow> an <white>%player%</white> vergeben.%overflow%</green>",
                    "%id%", id, "%player%", target.getName(),
                    "%overflow%", dropped == 0 ? "" : " (Inventar voll: Item davor gedroppt)");
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Angler-Testitem '" + id + "' konnte nicht erzeugt werden: " + exception);
            return anglerMessage(sender, "messages.admin-test-item-failed",
                    "<red>Angler-Testitem <white>%id%</white> konnte nicht erzeugt werden. Server-Log prüfen.</red>",
                    "%id%", id);
        }
    }

    private boolean handleAnglerTestLumi(CommandSender sender, Player target, String rawAmount) {
        long amount;
        try { amount = Long.parseLong(rawAmount); }
        catch (NumberFormatException exception) { amount = -1L; }
        if (amount <= 0L) return anglerMessage(sender, "messages.admin-test-lumi-invalid",
                "<red>Die Lumi-Menge muss eine positive ganze Zahl sein.</red>");
        AnglerLootFoundation.DeliveryResult result = anglerLoot.deliver(target,
                new AnglerLootFoundation.CurrencyReward(AnglerLootFoundation.RewardType.LUMI, amount), null);
        if (!result.success()) return anglerMessage(sender, "messages.admin-test-lumi-failed",
                "<red>Lumi-Gutschrift für <white>%player%</white> fehlgeschlagen. Server-Log prüfen.</red>",
                "%player%", target.getName());
        return anglerMessage(sender, "messages.admin-test-lumi-given",
                "<green>%amount% Lumis an %player% gutgeschrieben.</green>",
                "%amount%", Long.toString(result.lumisCredited()), "%player%", target.getName());
    }

    private boolean anglerUsage(CommandSender sender) {
        return anglerMessage(sender, "messages.admin-test-usage",
                "<yellow>/angler testbook|testcustomitem|testlumi|testloot <Spieler> <ID|Menge|treasure|junk|rare|epic|legendary> [Anzahl|Entry-ID]</yellow>");
    }

    private boolean anglerMessage(CommandSender sender, String path, String fallback, String... replacements) {
        return plugin.messages().sendConfiguredAuto(sender, plugin.configs().angler(), path, fallback, replacements);
    }

    private boolean handleInventoryAction(CommandSender sender, String label, String action, String[] args) {
        // /customitem give <spieler> <item> [anzahl]   bzw.   /fragmente give <spieler> [anzahl]
        int itemIndex = fixedItemId == null ? 2 : -1;
        int amountIndex = fixedItemId == null ? 3 : 2;
        int minimumArgs = fixedItemId == null ? 3 : 2;
        if (args.length < minimumArgs) return usage(sender, label);

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            return messageOrDefault(sender, "custom-items.messages.player-not-found",
                    "<red>Spieler <white>%player%</white> ist nicht online.</red>",
                    "%player%", args[1]);
        }

        String itemId = fixedItemId != null ? fixedItemId : args[itemIndex];
        FishDefinition fish = fish(itemId);
        if (items.find(itemId).isEmpty() && fish == null) {
            return messageOrDefault(sender, "custom-items.messages.unknown-item",
                    "<red>Unbekanntes Item: <white>%item%</white></red>", "%item%", itemId);
        }

        if (action.equals("count")) {
            return messageOrDefault(sender, "custom-items.messages.count",
                    "<green><white>%player%</white> hat <yellow>%amount%×</yellow> <white>%item%</white>.</green>",
                    "%player%", target.getName(),
                    "%amount%", String.valueOf(fish == null ? items.count(target, itemId) : countFish(target, fish.id())),
                    "%item%", itemId);
        }

        int amount = parseAmount(args, amountIndex);
        if (amount <= 0) {
            return messageOrDefault(sender, "custom-items.messages.invalid-amount",
                    "<red>Ungültige Anzahl.</red>");
        }

        if (action.equals("give")) {
            int dropped = fish == null ? items.give(target, itemId, amount) : giveFish(target, fish.id(), amount);
            return messageOrDefault(sender, "custom-items.messages.given",
                    "<green><yellow>%amount%×</yellow> <white>%item%</white> an <white>%player%</white> vergeben.%dropped%</green>",
                    "%amount%", String.valueOf(amount),
                    "%item%", itemId,
                    "%player%", target.getName(),
                    "%dropped%", dropped > 0 ? " (" + dropped + " davor gedroppt)" : "");
        }

        if (!(fish == null ? items.take(target, itemId, amount) : takeFish(target, fish.id(), amount))) {
            return messageOrDefault(sender, "custom-items.messages.not-enough",
                    "<red><white>%player%</white> hat nicht genug <white>%item%</white>.</red>",
                    "%player%", target.getName(), "%item%", itemId);
        }
        return messageOrDefault(sender, "custom-items.messages.taken",
                "<green><yellow>%amount%×</yellow> <white>%item%</white> von <white>%player%</white> entfernt.</green>",
                "%amount%", String.valueOf(amount), "%item%", itemId, "%player%", target.getName());
    }

    private boolean handleStoreCatch(CommandSender sender, String label, String[] args) {
        if (fixedItemId != null || anglerFeature == null || args.length < 3) return usage(sender, label);
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) return messageOrDefault(sender, "custom-items.messages.player-not-found",
                "<red>Spieler <white>%player%</white> ist nicht online.</red>", "%player%", args[1]);
        FishDefinition fish = fish(args[2]);
        if (fish == null) return messageOrDefault(sender, "custom-items.messages.unknown-item",
                "<red>Unbekanntes Item: <white>%item%</white></red>", "%item%", args[2]);
        int amount = parseAmount(args, 3);
        if (amount <= 0) return messageOrDefault(sender, "custom-items.messages.invalid-amount",
                "<red>Ungültige Anzahl.</red>");

        int stored = 0;
        int dropped = 0;
        int remaining = amount;
        while (remaining > 0) {
            ItemStack catchItem = fishFactory.create(fish.id(), remaining);
            if (catchItem == null) break;
            remaining -= catchItem.getAmount();
            ItemStack overflow = anglerFeature.storeCatch(target, catchItem);
            int overflowAmount = overflow == null ? 0 : overflow.getAmount();
            stored += catchItem.getAmount() - overflowAmount;
            if (overflow != null) {
                dropped += overflowAmount;
                target.getWorld().dropItemNaturally(target.getLocation(), overflow);
            }
        }
        return plugin.messages().sendConfiguredAuto(sender, plugin.configs().angler(),
                "messages.admin-storecatch",
                "<green>%stored%x %fish% im Fanglager von %player% gespeichert. "
                        + "<yellow>%dropped%x Überlauf vor dem Spieler gedroppt.</yellow></green>",
                "%stored%", Integer.toString(stored), "%fish%", fish.displayName(),
                "%player%", target.getName(), "%dropped%", Integer.toString(dropped));
    }

    private boolean handleFishingPool(CommandSender sender, String label, String[] args) {
        if (fixedItemId != null || anglerFishing == null || args.length != 3) return usage(sender, label);
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) return messageOrDefault(sender, "custom-items.messages.player-not-found",
                "<red>Spieler <white>%player%</white> ist nicht online.</red>", "%player%", args[1]);
        FishingLootPoolSelector.Pool pool;
        try { pool = FishingLootPoolSelector.Pool.valueOf(args[2].toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { return usage(sender, label); }
        anglerFishing.previewPool(target, pool);
        return plugin.messages().sendConfiguredAuto(sender, plugin.configs().angler(),
                "messages.admin-pool-preview", "<green>Test-Pool %pool% für %player% angezeigt (ohne Belohnung).</green>",
                "%pool%", pool.name(), "%player%", target.getName());
    }

    private boolean handleFishingRoll(CommandSender sender, String label, String[] args) {
        if (fixedItemId != null || anglerFishing == null || args.length < 3 || args.length > 4)
            return usage(sender, label);
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) return messageOrDefault(sender, "custom-items.messages.player-not-found",
                "<red>Spieler <white>%player%</white> ist nicht online.</red>", "%player%", args[1]);
        FishingGame.Quality quality;
        try { quality = FishingGame.Quality.valueOf(args[2].toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { return usage(sender, label); }
        if (quality == FishingGame.Quality.GRAY) return usage(sender, label);
        int attempts = 1000;
        if (args.length == 4) {
            try { attempts = Integer.parseInt(args[3]); }
            catch (NumberFormatException exception) { return usage(sender, label); }
        }
        if (attempts < 1 || attempts > 10_000) return usage(sender, label);
        AnglerFishingService.RollSimulation result = anglerFishing.simulateRolls(target, quality, attempts);
        NumberFormat numbers = NumberFormat.getIntegerInstance(Locale.GERMANY);
        plugin.messages().sendConfiguredAuto(sender, plugin.configs().angler(),
                "messages.admin-roll-header",
                "<aqua>Fishing-Roll Simulation (%count% / %quality% / %tier%)</aqua>",
                "%count%", numbers.format(result.attempts()), "%quality%", quality.name(),
                "%tier%", result.angler() ? "P" + result.prestige() : "Kein Angler");
        for (FishingLootPoolSelector.Pool pool : FishingLootPoolSelector.Pool.values()) {
            String name = pool.name().substring(0, 1) + pool.name().substring(1).toLowerCase(Locale.ROOT);
            plugin.messages().sendConfiguredAuto(sender, plugin.configs().angler(),
                    "messages.admin-roll-line", "<gray>%pool%: <yellow>%count%</yellow></gray>",
                    "%pool%", name, "%count%", numbers.format(result.counts()[pool.ordinal()]));
        }
        return true;
    }

    private boolean handleBundleTest(CommandSender sender, String label, String[] args) {
        if (fixedItemId != null || anglerLoot == null || args.length != 3) return usage(sender, label);
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) return messageOrDefault(sender, "custom-items.messages.player-not-found",
                "<red>Spieler <white>%player%</white> ist nicht online.</red>", "%player%", args[1]);
        List<ItemStack> contents = bundleTestContents(args[2]);
        if (contents == null) return usage(sender, label);
        try {
            ItemStack bundle = AnglerLootFoundation.createBundle(contents);
            List<ItemStack> stored = AnglerLootFoundation.bundleContents(bundle);
            List<ItemStack> persisted = AnglerLootFoundation.bundleContents(
                    ItemStack.deserializeBytes(bundle.serializeAsBytes()));
            if (!sameBundleContents(contents, stored) || !sameBundleContents(contents, persisted))
                throw new IllegalStateException("Bundle-Inhalt weicht beim Readback oder Speichern ab");
            int expected = contents.stream().mapToInt(ItemStack::getAmount).sum();
            int actual = stored.stream().mapToInt(ItemStack::getAmount).sum();
            for (ItemStack overflow : target.getInventory().addItem(bundle).values())
                target.getWorld().dropItemNaturally(target.getLocation(), overflow);
            return plugin.messages().sendConfiguredAuto(sender, plugin.configs().angler(),
                    "messages.admin-bundle-test",
                    "<green>Vanilla-Bundle %case% an %player%: %actual-stacks%/%expected-stacks% Stacks, %actual%/%expected% Items im Data-Component-Readback. Client-Anzeige/Entnahme bitte prüfen.</green>",
                    "%case%", args[2], "%player%", target.getName(),
                    "%stacks%", Integer.toString(stored.size()),
                    "%actual-stacks%", Integer.toString(stored.size()),
                    "%expected-stacks%", Integer.toString(contents.size()),
                    "%actual%", Integer.toString(actual), "%expected%", Integer.toString(expected));
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Bundle-Test '" + args[2] + "' gescheitert: " + exception);
            return plugin.messages().sendConfiguredAuto(sender, plugin.configs().angler(),
                    "messages.admin-bundle-test-failed",
                    "<red>Bundle-Test %case% gescheitert: %reason%</red>",
                    "%case%", args[2], "%reason%", exception.getClass().getSimpleName());
        }
    }

    private List<ItemStack> bundleTestContents(String rawCase) {
        return switch (rawCase.toLowerCase(Locale.ROOT)) {
            case "normal" -> List.of(new ItemStack(Material.STONE, 16), new ItemStack(Material.DIRT, 8));
            case "over64" -> List.of(new ItemStack(Material.STONE, 64), new ItemStack(Material.DIRT, 64));
            case "tools" -> List.of(new ItemStack(Material.DIAMOND_PICKAXE),
                    new ItemStack(Material.DIAMOND_AXE), new ItemStack(Material.DIAMOND_SHOVEL));
            case "armor" -> List.of(new ItemStack(Material.DIAMOND_HELMET),
                    new ItemStack(Material.DIAMOND_CHESTPLATE), new ItemStack(Material.DIAMOND_LEGGINGS),
                    new ItemStack(Material.DIAMOND_BOOTS));
            case "armor_set" -> List.of(enchantedTestItem(Material.NETHERITE_HELMET, "protection", 4),
                    enchantedTestItem(Material.NETHERITE_CHESTPLATE, "protection", 4),
                    enchantedTestItem(Material.NETHERITE_LEGGINGS, "protection", 4),
                    enchantedTestItem(Material.NETHERITE_BOOTS, "protection", 4),
                    enchantedTestItem(Material.NETHERITE_PICKAXE, "efficiency", 5),
                    enchantedTestItem(Material.NETHERITE_SWORD, "sharpness", 5));
            default -> null;
        };
    }

    private ItemStack enchantedTestItem(Material material, String enchantId, int level) {
        Enchantment enchantment = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(enchantId));
        if (enchantment == null) throw new IllegalStateException("Test-Enchantment fehlt: " + enchantId);
        ItemStack item = new ItemStack(material);
        item.addUnsafeEnchantment(enchantment, level);
        return item;
    }

    private boolean sameBundleContents(List<ItemStack> expected, List<ItemStack> actual) {
        if (expected.size() != actual.size()) return false;
        for (int index = 0; index < expected.size(); index++) {
            ItemStack left = expected.get(index);
            ItemStack right = actual.get(index);
            if (right == null || left.getAmount() != right.getAmount() || !left.isSimilar(right)) return false;
        }
        return true;
    }

    private boolean handleList(CommandSender sender) {
        if (items.definitions().isEmpty() && (fishRegistry == null || fishRegistry.definitions().isEmpty())) {
            return messageOrDefault(sender, "custom-items.messages.list-empty",
                    "<yellow>Es sind keine Custom-Items konfiguriert.</yellow>");
        }
        StringBuilder builder = new StringBuilder();
        for (CustomItemDefinition definition : items.definitions()) {
            if (!builder.isEmpty()) builder.append("<gray>, </gray>");
            builder.append("<white>").append(definition.id()).append("</white>");
        }
        if (fishRegistry != null) for (FishDefinition definition : fishRegistry.definitions()) {
            if (!builder.isEmpty()) builder.append("<gray>, </gray>");
            builder.append("<white>fish:").append(definition.id()).append("</white>");
        }
        return messageOrDefault(sender, "custom-items.messages.list",
                "<green>Custom-Items (%count%): %items%</green>",
                "%count%", String.valueOf(items.definitions().size()
                        + (fishRegistry == null ? 0 : fishRegistry.definitions().size())),
                "%items%", builder.toString());
    }

    private boolean handleReload(CommandSender sender) {
        holzschlag.reload();
        enchantments.register(holzschlag.enchantment());
        miningEnchants.reload();
        miningEnchants.enchantments().forEach(enchantments::register);
        items.reload();
        if (fishRegistry != null) {
            plugin.configs().anglerFile().reload();
            fishRegistry.reload();
            if (anglerLoot != null) anglerLoot.reload(plugin.configs().angler());
            if (anglerFishing != null) anglerFishing.reload();
        }
        plugin.configs().customItemShopFile().reload();
        plugin.configs().lumiShopFile().reload();
        return messageOrDefault(sender, "custom-items.messages.reloaded",
                "<green>Custom-Items und Custom-Verzauberungen wurden neu geladen.</green>");
    }

    private int parseAmount(String[] args, int index) {
        if (index >= args.length) return 1;
        try {
            return Math.min(2304, Integer.parseInt(args[index]));
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private boolean usage(CommandSender sender, String label) {
        String item = fixedItemId == null ? " <item>" : "";
        return messageOrDefault(sender, "custom-items.messages.usage",
                "<yellow>/%label% give|take <spieler>%item% [anzahl]  •  /%label% count <spieler>%item%"
                        + "  •  /%label% list  •  /%label% reload"
                        + (fixedItemId == null ? "  •  /%label% storecatch <spieler> fish:<id> [anzahl]"
                        + "  •  /%label% fishingpool <spieler> <pool>"
                        + "  •  /%label% fishingroll <spieler> <farbe> [anzahl]"
                        + "  •  /%label% bundletest <spieler> <fall>" : "")
                        + "</yellow>",
                "%label%", label, "%item%", item);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(permission())) return List.of();

        if (isAnglerAlias(alias)) {
            if (args.length == 1) return filtered(
                    List.of("testbook", "testcustomitem", "testlumi", "testloot"), args[0]);
            if (args.length == 2) return filtered(Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName).toList(), args[1]);
            if (args.length == 3 && anglerLoot != null) {
                return switch (args[0].toLowerCase(Locale.ROOT)) {
                    case "testbook" -> filtered(anglerLoot.overlevelBooks().keySet().stream().sorted().toList(), args[2]);
                    case "testcustomitem" -> filtered(items.definitions().stream()
                            .map(CustomItemDefinition::id).sorted().toList(), args[2]);
                    case "testlumi" -> filtered(List.of("10", "25", "50"), args[2]);
                    case "testloot" -> filtered(List.of("treasure", "junk", "rare", "epic", "legendary"), args[2]);
                    default -> List.of();
                };
            }
            if (args.length == 4 && args[0].equalsIgnoreCase("testloot")) {
                List<String> choices = new ArrayList<>(List.of("1", "100", "1000"));
                if (args[2].equalsIgnoreCase("rare") && anglerFishing != null)
                    choices.addAll(anglerFishing.rareEntryIds().stream().sorted().toList());
                if (args[2].equalsIgnoreCase("epic") && anglerFishing != null)
                    choices.addAll(anglerFishing.epicEntryIds().stream().sorted().toList());
                if (args[2].equalsIgnoreCase("legendary") && anglerFishing != null)
                    choices.addAll(anglerFishing.legendaryEntryIds().stream().sorted().toList());
                return filtered(choices, args[3]);
            }
            return List.of();
        }

        if (args.length == 1) {
            return filtered(fixedItemId == null
                    ? List.of("give", "take", "count", "storecatch", "fishingpool", "fishingroll", "bundletest", "list", "reload")
                    : List.of("give", "take", "count", "list", "reload"), args[0]);
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (!List.of("give", "take", "count", "storecatch", "fishingpool", "fishingroll", "bundletest").contains(action)) return List.of();

        if (args.length == 2) {
            List<String> names = new ArrayList<>();
            Bukkit.getOnlinePlayers().forEach(player -> names.add(player.getName()));
            return filtered(names, args[1]);
        }
        if (args.length == 3 && fixedItemId == null) {
            if (action.equals("fishingpool")) return filtered(
                    java.util.Arrays.stream(FishingLootPoolSelector.Pool.values()).map(Enum::name).toList(), args[2]);
            if (action.equals("fishingroll")) return filtered(List.of("red", "orange", "yellow", "green"), args[2]);
            if (action.equals("bundletest")) return filtered(
                    List.of("normal", "over64", "tools", "armor", "armor_set"), args[2]);
            if (action.equals("storecatch")) {
                return filtered(fishRegistry.definitions().stream()
                        .map(definition -> "fish:" + definition.id()).toList(), args[2]);
            }
            List<String> ids = new ArrayList<>();
            items.definitions().forEach(definition -> ids.add(definition.id()));
            if (fishRegistry != null) fishRegistry.definitions().forEach(definition -> ids.add("fish:" + definition.id()));
            return filtered(ids, args[2]);
        }
        return List.of();
    }

    private FishDefinition fish(String itemId) {
        return fishRegistry != null && itemId != null && itemId.startsWith("fish:")
                ? fishRegistry.find(itemId.substring(5)) : null;
    }

    private int countFish(Player player, String fishId) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            FishDefinition found = fishRegistry.identify(stack);
            if (found != null && found.id().equals(fishId)) count += stack.getAmount();
        }
        return count;
    }

    private int giveFish(Player player, String fishId, int amount) {
        int dropped = 0;
        int remaining = amount;
        while (remaining > 0) {
            ItemStack stack = fishFactory.create(fishId, remaining);
            if (stack == null) break;
            remaining -= stack.getAmount();
            for (ItemStack leftover : player.getInventory().addItem(stack).values()) {
                dropped += leftover.getAmount();
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
        return dropped;
    }

    private boolean takeFish(Player player, String fishId, int amount) {
        if (countFish(player, fishId) < amount) return false;
        int remaining = amount;
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int slot = 0; slot < storage.length && remaining > 0; slot++) {
            ItemStack stack = storage[slot];
            FishDefinition found = fishRegistry.identify(stack);
            if (found == null || !found.id().equals(fishId)) continue;
            int used = Math.min(remaining, stack.getAmount());
            remaining -= used;
            if (used == stack.getAmount()) player.getInventory().setItem(slot, null);
            else stack.setAmount(stack.getAmount() - used);
        }
        return true;
    }

    private List<String> filtered(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) result.add(option);
        }
        return result;
    }

    private boolean isAnglerAlias(String alias) {
        String commandName = alias.substring(alias.lastIndexOf(':') + 1);
        return commandName.equalsIgnoreCase("angler");
    }
}
