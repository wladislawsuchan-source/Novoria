package de.walahi.novosmp.economy.sell;

import de.walahi.novosmp.angler.FishDefinition;
import de.walahi.novosmp.angler.FishRegistry;
import com.comphenix.protocol.ProtocolLibrary;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.stats.StatsAccess;
import de.walahi.novosmp.trade.TradeItemPolicy;
import de.walahi.novosmp.heads.HeadCollectionManager;
import de.walahi.smpcore.StatType;
import de.walahi.smpcore.api.event.ActionContext;
import de.walahi.smpcore.api.event.ActionSource;
import de.walahi.smpcore.economy.EconomyOperationResult;
import de.walahi.smpcore.services.EconomyService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import org.bukkit.persistence.PersistentDataType;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class SellManager implements Listener {
    private static final int SIZE = 45;
    private static final int INPUT_SIZE = 36;
    private static final int SELL_BUTTON_SLOT = 40;
    private static final NumberFormat FORMAT = NumberFormat.getIntegerInstance(Locale.GERMANY);
    private static final String PRICE_LINE_MARKER = "smpcore:sell-price";

    private final SMPCorePlugin plugin;
    private final EconomyService economy;
    private final StatsAccess statsManager;
    private final TradeItemPolicy tradePolicy;
    private final SellBonusProvider bonusProvider;
    private final HeadCollectionManager heads;
    private FishRegistry fishRegistry;
    private final Map<Material, Double> explicitPrices = new EnumMap<>(Material.class);
    private final Map<PotionType, Double> potionPrices = new EnumMap<>(PotionType.class);

    // Wird nur verwendet, um durch 11.7.0 bereits veränderte Items einmalig zu reparieren.
    private final NamespacedKey legacyRenderedPriceKey;
    private final NamespacedKey guiControlKey;

    private double defaultPrice;
    private long enchantmentLevelBonus;
    private double splashPotionAddition;
    private double lingeringPotionAddition;
    private double mobHeadPrice;

    public SellManager(SMPCorePlugin plugin, EconomyService economy, StatsAccess statsManager,
                       TradeItemPolicy tradePolicy) {
        this(plugin, economy, statsManager, tradePolicy, SellBonusProvider.none(), null);
    }

    public SellManager(SMPCorePlugin plugin, EconomyService economy, StatsAccess statsManager,
                       TradeItemPolicy tradePolicy, SellBonusProvider bonusProvider) {
        this(plugin, economy, statsManager, tradePolicy, bonusProvider, null);
    }

    public SellManager(SMPCorePlugin plugin, EconomyService economy, StatsAccess statsManager,
                       TradeItemPolicy tradePolicy, SellBonusProvider bonusProvider, HeadCollectionManager heads) {
        this.plugin = Objects.requireNonNull(plugin);
        this.economy = Objects.requireNonNull(economy);
        this.statsManager = Objects.requireNonNull(statsManager);
        this.tradePolicy = Objects.requireNonNull(tradePolicy);
        this.bonusProvider = Objects.requireNonNull(bonusProvider);
        this.heads = heads;
        this.legacyRenderedPriceKey = new NamespacedKey(plugin, "rendered_sell_price");
        this.guiControlKey = new NamespacedKey(plugin, "sell_gui_control");
        reloadPrices();

        ProtocolLibrary.getProtocolManager().addPacketListener(new SellPricePacketListener(this));
    }

    SMPCorePlugin plugin() {
        return plugin;
    }

    public void fishRegistry(FishRegistry registry) { this.fishRegistry = registry; }

    public void reloadPrices() {
        explicitPrices.clear();
        potionPrices.clear();
        tradePolicy.reload();

        defaultPrice = Math.max(0D, plugin.configs().prices().getDouble("economy.sell.pricing.default-price", 0D));
        enchantmentLevelBonus = Math.max(0L,
                plugin.configs().prices().getLong("economy.sell.pricing.enchantment-level-bonus", 5L));
        splashPotionAddition = Math.max(0D,
                plugin.configs().prices().getDouble("economy.sell.potions.splash-addition", 1D));
        lingeringPotionAddition = Math.max(0D,
                plugin.configs().prices().getDouble("economy.sell.potions.lingering-addition", 3D));
        mobHeadPrice = Math.max(0D,
                plugin.configs().prices().getDouble("economy.sell.mob-head-price", 5D));

        ConfigurationSection potionSection = plugin.configs().prices().getConfigurationSection("economy.sell.potions.types");
        if (potionSection != null) {
            for (String key : potionSection.getKeys(false)) {
                try {
                    PotionType type = PotionType.valueOf(key.trim().toUpperCase(Locale.ROOT));
                    double price = potionSection.getDouble(key, 0D);
                    if (price > 0D) potionPrices.put(type, price);
                } catch (IllegalArgumentException ignored) {
                    plugin.getLogger().warning("Ungültiger PotionType in prices.yml: " + key);
                }
            }
        }

        ConfigurationSection section = plugin.configs().prices().getConfigurationSection("economy.sell.prices");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                Material material = Material.matchMaterial(key);
                double price = section.getDouble(key, 0D);
                if (material != null && isRealItem(material) && price > 0D) {
                    explicitPrices.put(material, price);
                }
            }
        }

    }

    public void open(Player player) {
        cleanLegacyInventory(player.getInventory());
        SellInventoryHolder holder = new SellInventoryHolder(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                Component.text("Items verkaufen", NamedTextColor.DARK_GREEN));
        holder.inventory(inventory);
        updateControlRow(player, inventory);
        player.openInventory(inventory);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSellMenuControlClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof SellInventoryHolder holder)
                || !holder.owner().equals(player.getUniqueId())) return;

        int rawSlot = event.getRawSlot();
        if (rawSlot >= INPUT_SIZE && rawSlot < SIZE) {
            event.setCancelled(true);
            if (rawSlot == SELL_BUTTON_SLOT) sellFromButton(player, top);
            return;
        }

        scheduleControlRefresh(player, top);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSellMenuControlDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof SellInventoryHolder holder)
                || !holder.owner().equals(player.getUniqueId())) return;

        if (event.getRawSlots().stream().anyMatch(slot -> slot >= INPUT_SIZE && slot < SIZE)) {
            event.setCancelled(true);
            return;
        }
        scheduleControlRefresh(player, top);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            cleanLegacyInventory(event.getPlayer().getInventory());
            event.getPlayer().updateInventory();
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        cleanLegacyInventory(event.getView().getTopInventory());
        cleanLegacyInventory(event.getPlayer().getInventory());
    }

    /**
     * Workaround für die rein clientseitige Gesamtpreis-Lore: Zwei echte
     * serverseitige Items sind weiterhin identisch, der Vanilla-Client betrachtet
     * die unterschiedlich gerenderten Gesamtpreise jedoch als verschiedene
     * Komponenten. Normale Links-/Rechtsklick-Stapelvorgänge werden deshalb hier
     * anhand der unveränderten Server-Items ausgeführt.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRenderedStackMerge(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;

        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        if (current == null || cursor == null || current.getType().isAir() || cursor.getType().isAir()) return;
        if (!current.isSimilar(cursor)) return;

        int maximum = Math.min(current.getMaxStackSize(), event.getInventory().getMaxStackSize());
        int free = maximum - current.getAmount();
        if (free <= 0) return;

        int moved = event.getClick() == ClickType.RIGHT ? 1 : Math.min(free, cursor.getAmount());
        if (moved <= 0) return;

        event.setCancelled(true);
        ItemStack merged = current.clone();
        merged.setAmount(current.getAmount() + moved);
        event.setCurrentItem(merged);

        int remaining = cursor.getAmount() - moved;
        if (remaining <= 0) {
            player.setItemOnCursor(null);
        } else {
            ItemStack rest = cursor.clone();
            rest.setAmount(remaining);
            player.setItemOnCursor(rest);
        }
        refreshRenderedPrices(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            refreshRenderedPrices(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            refreshRenderedPrices(player);
        }
    }

    private void refreshRenderedPrices(Player player) {
        // Creative verwendet einen eigenen Client-/Server-Inventarablauf. Ein
        // erzwungenes updateInventory() direkt nach jedem Klick überschreibt dort
        // das Aufnehmen und Verschieben von Items aus dem Creative-Menü.
        if (player.getGameMode() == GameMode.CREATIVE) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.getGameMode() != GameMode.CREATIVE) {
                player.updateInventory();
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        Inventory inventory = event.getInventory();
        if (inventory.getHolder() instanceof SellInventoryHolder holder && !holder.completed()
                && event.getPlayer() instanceof Player player && holder.owner().equals(player.getUniqueId())) {
            holder.completed(true);
            sellOnClose(player, inventory);
        }
    }

    /**
     * Erstellt ausschließlich eine Anzeige-Kopie für ProtocolLib.
     */
    ItemStack createDisplayStack(Player player, ItemStack original) {
        if (original == null || original.getType().isAir()) return original;
        if (isGuiControl(original)) return original;

        ItemStack display = original.clone();
        ItemMeta meta = display.getItemMeta();
        if (meta == null || !isSellable(display, meta)) return display;

        double stackPrice = calculateStackPrice(player == null ? null : player.getUniqueId(), display, meta);
        if (stackPrice <= 0D) return display;

        // Gewünschte Anzeige: immer der Gesamtpreis des aktuell sichtbaren Stacks.
        // Das tatsächliche Zusammenlegen übernimmt onRenderedStackMerge anhand der
        // unveränderten serverseitigen ItemStacks.
        String rendered = formatCoins(stackPrice) + (Math.abs(stackPrice - 1D) < 0.000001D ? " Coin" : " Coins");
        List<Component> existing = meta.lore();
        List<Component> updated = existing == null ? new ArrayList<>() : new ArrayList<>(existing);

        // ProtocolLib kann bei einer erneuten Slot-Aktualisierung bereits die zuvor
        // gerenderte Anzeige-Kopie liefern. Deshalb alte Preiszeilen immer zuerst
        // aus der Paket-Kopie entfernen, bevor genau eine neue Zeile ergänzt wird.
        updated.removeIf(this::isRenderedPriceLine);
        updated.add(Component.text(rendered, NamedTextColor.GOLD).insertion(PRICE_LINE_MARKER));
        meta.lore(updated);
        display.setItemMeta(meta);
        return display;
    }


    /**
     * Entfernt ausschließlich die virtuelle Preiszeile aus einer vom Client
     * zurückgesendeten Item-Kopie. Das echte Item und seine sonstige Lore
     * bleiben unverändert.
     */
    ItemStack removeDisplayPrice(ItemStack clientStack) {
        if (clientStack == null || clientStack.getType().isAir()) return clientStack;

        ItemStack cleaned = clientStack.clone();
        ItemMeta meta = cleaned.getItemMeta();
        if (meta == null || !meta.hasLore() || meta.lore() == null) return cleaned;

        List<Component> lore = new ArrayList<>(meta.lore());
        boolean changed = lore.removeIf(this::isRenderedPriceLine);
        if (!changed) return cleaned;

        meta.lore(lore.isEmpty() ? null : lore);
        cleaned.setItemMeta(meta);
        return cleaned;
    }

    private boolean isRenderedPriceLine(Component component) {
        if (component == null) return false;
        if (PRICE_LINE_MARKER.equals(component.insertion())) return true;

        // Repariert außerdem Paket-Kopien aus 11.7.1, die noch keinen Marker hatten.
        String plain = PlainTextComponentSerializer.plainText().serialize(component);
        return NamedTextColor.GOLD.equals(component.color())
                && plain.matches("[0-9][0-9.,]* Coin(?:s)?(?:/Stück)?");
    }

    private boolean cleanLegacyPriceLore(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;

        String previous = meta.getPersistentDataContainer().get(legacyRenderedPriceKey, PersistentDataType.STRING);
        if (previous == null) return false;

        List<Component> lore = meta.lore();
        if (lore != null) {
            List<Component> updated = new ArrayList<>(lore);
            updated.remove(Component.text(previous, NamedTextColor.GOLD));
            meta.lore(updated.isEmpty() ? null : updated);
        }
        meta.getPersistentDataContainer().remove(legacyRenderedPriceKey);
        stack.setItemMeta(meta);
        return true;
    }

    private void cleanLegacyInventory(Inventory inventory) {
        if (inventory == null) return;
        for (ItemStack stack : inventory.getContents()) {
            cleanLegacyPriceLore(stack);
        }
    }

    private boolean isSellable(ItemStack stack, ItemMeta meta) {
        if (isSignedMobHead(stack)) return mobHeadPrice > 0D;
        return tradePolicy.isSellItemAllowed(stack) && unitPrice(stack) > 0D;
    }

    private boolean isRealItem(Material material) {
        return material != null && material.isItem() && !material.isAir();
    }

    private double calculateStackPrice(UUID playerId, ItemStack stack, ItemMeta meta) {
        FishDefinition fish = fishRegistry == null ? null : fishRegistry.identify(stack);
        if (fish != null) return (double) fish.sellPrice() * stack.getAmount();
        double base = (unitPrice(stack) + enchantmentBonus(meta)) * stack.getAmount();
        double multiplier = Math.max(1D, bonusProvider.multiplier(playerId, stack.getType()));
        return base * multiplier;
    }

    public double unitPrice(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return 0D;
        FishDefinition fish = fishRegistry == null ? null : fishRegistry.identify(stack);
        if (fish != null) return fish.sellPrice();
        if (isSignedMobHead(stack)) return mobHeadPrice;
        if (isPotionMaterial(stack.getType())) return potionUnitPrice(stack);
        return unitPrice(stack.getType());
    }

    private boolean isSignedMobHead(ItemStack stack) {
        return heads != null && heads.isSignedMobHead(stack);
    }

    private double potionUnitPrice(ItemStack stack) {
        if (!(stack.getItemMeta() instanceof PotionMeta meta) || meta.hasCustomEffects()) return 0D;
        PotionType type = meta.getBasePotionType();
        if (type == null) return 0D;
        double base = potionPrices.getOrDefault(type, 0D);
        if (base <= 0D) return 0D;
        return switch (stack.getType()) {
            case SPLASH_POTION -> base + splashPotionAddition;
            case LINGERING_POTION -> base + lingeringPotionAddition;
            case POTION -> base;
            default -> 0D;
        };
    }

    public boolean isPotionMaterial(Material material) {
        return material == Material.POTION
                || material == Material.SPLASH_POTION
                || material == Material.LINGERING_POTION;
    }

    public double unitPrice(Material material) {
        if (!tradePolicy.isMaterialAllowed(material, TradeItemPolicy.Channel.SELL)) return 0D;
        return explicitPrices.getOrDefault(material, 0D);
    }

    public boolean isWorthVisible(Material material) {
        return tradePolicy.isWorthMaterialAllowed(material)
                && (unitPrice(material) > 0D || (isPotionMaterial(material) && !potionPrices.isEmpty()));
    }

    public String formatUnitPrice(Material material) {
        return formatCoins(unitPrice(material));
    }

    private String formatCoins(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.000001D) return FORMAT.format((long) Math.rint(value));
        return String.format(Locale.GERMANY, "%.2f", value).replaceAll("0+$", "").replaceAll(",+$", "");
    }

    private double categoryPrice(Material material) {
        String category = categoryOf(material);
        return Math.max(0.01D, plugin.configs().prices().getDouble("economy.sell.pricing.categories." + category, defaultPrice));
    }

    private String categoryOf(Material material) {
        String name = material.name();
        if (name.contains("GLASS")) return "glass";
        if (name.endsWith("_LOG") || name.endsWith("_WOOD") || name.endsWith("_PLANKS")
                || name.contains("BAMBOO") || name.contains("STEM") || name.contains("HYPHAE")) return "wood";
        if (name.contains("ORE") || name.startsWith("RAW_")) return "ores";
        if (name.endsWith("_SWORD") || name.endsWith("_PICKAXE") || name.endsWith("_AXE")
                || name.endsWith("_SHOVEL") || name.endsWith("_HOE") || name.equals("BOW")
                || name.equals("CROSSBOW") || name.equals("TRIDENT") || name.equals("MACE")) return "tools";
        if (name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE") || name.endsWith("_LEGGINGS")
                || name.endsWith("_BOOTS") || name.equals("ELYTRA") || name.equals("SHIELD")) return "armor";
        if (material.isEdible()) return "food";
        if (name.contains("STONE") || name.contains("DEEPSLATE") || name.contains("TUFF")
                || name.contains("GRANITE") || name.contains("DIORITE") || name.contains("ANDESITE")) return "stone";
        return "misc";
    }

    private long enchantmentBonus(ItemMeta meta) {
        long totalLevels = meta.getEnchants().values().stream().mapToLong(Integer::longValue).sum();
        if (meta instanceof EnchantmentStorageMeta storageMeta) {
            totalLevels += storageMeta.getStoredEnchants().values().stream().mapToLong(Integer::longValue).sum();
        }
        try {
            return Math.multiplyExact(totalLevels, enchantmentLevelBonus);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private void sellFromButton(Player player, Inventory inventory) {
        Sale sale = calculate(player, inventory);

        if (sale.coins <= 0L) {
            plugin.messages().sendConfiguredAuto(player, "economy.sell.messages.nothing-to-sell",
                    "<dark_gray>[<green>SMP</green>]</dark_gray> <red>Es wurden keine verkaufbaren Items verkauft.</red>");
            updateControlRow(player, inventory);
            return;
        }

        EconomyOperationResult result = economy.deposit(player.getUniqueId(), sale.coins, "SELL_GUI",
                ActionContext.player(ActionSource.COMMAND, player.getUniqueId()));
        if (result != EconomyOperationResult.SUCCESS) {
            plugin.messages().sendConfiguredAuto(player, "economy.sell.messages.failed",
                    "<dark_gray>[<green>SMP</green>]</dark_gray> <red>Der Verkauf konnte nicht gespeichert werden. Deine Items bleiben im Verkaufsmenü.</red>");
            updateControlRow(player, inventory);
            return;
        }

        removeSellableItems(inventory);
        completeSuccessfulSale(player, sale);
        updateControlRow(player, inventory);
        player.updateInventory();
    }

    /** Schließen verkauft weiterhin und gibt unverkäufliche Items zurück. */
    private void sellOnClose(Player player, Inventory inventory) {
        Sale sale = calculate(player, inventory);

        if (sale.coins <= 0L) {
            boolean hadItems = containsInputItems(inventory);
            returnInputItems(player, inventory);
            if (hadItems) {
                plugin.messages().sendConfiguredAuto(player, "economy.sell.messages.nothing-to-sell",
                        "<dark_gray>[<green>SMP</green>]</dark_gray> <red>Es wurden keine verkaufbaren Items verkauft.</red>");
            }
            return;
        }

        EconomyOperationResult result = economy.deposit(player.getUniqueId(), sale.coins, "SELL_GUI",
                ActionContext.player(ActionSource.COMMAND, player.getUniqueId()));
        if (result != EconomyOperationResult.SUCCESS) {
            returnInputItems(player, inventory);
            plugin.messages().sendConfiguredAuto(player, "economy.sell.messages.failed",
                    "<dark_gray>[<green>SMP</green>]</dark_gray> <red>Der Verkauf konnte nicht gespeichert werden. Deine Items wurden zurückgegeben.</red>");
            return;
        }

        removeSellableItems(inventory);
        returnInputItems(player, inventory);
        completeSuccessfulSale(player, sale);
    }

    private void completeSuccessfulSale(Player player, Sale sale) {
        statsManager.addStat(player.getUniqueId(), StatType.SELL_EARNINGS, sale.coins);
        plugin.messages().sendConfiguredAuto(player, "economy.sell.messages.success",
                "<dark_gray>[<green>SMP</green>]</dark_gray> <gray>Du hast <yellow>%items%</yellow> Items für <green>%coins% Coins</green> verkauft.</gray>",
                "%items%", FORMAT.format(sale.items), "%coins%", FORMAT.format(sale.coins));
        plugin.sounds().play(player, "economy.coins-sold");
    }

    private Sale calculate(Player player, Inventory inventory) {
        double exactCoins = 0D;
        long items = 0L;
        for (int slot = 0; slot < INPUT_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType().isAir()) continue;
            cleanLegacyPriceLore(stack);
            ItemMeta meta = stack.getItemMeta();
            if (meta == null || !isSellable(stack, meta)) continue;
            double stackCoins = calculateStackPrice(player.getUniqueId(), stack, meta);
            if (!Double.isFinite(stackCoins) || stackCoins >= Long.MAX_VALUE) return new Sale(Long.MAX_VALUE, items);
            exactCoins += stackCoins;
            if (!Double.isFinite(exactCoins) || exactCoins >= Long.MAX_VALUE) return new Sale(Long.MAX_VALUE, items);
            try { items = Math.addExact(items, stack.getAmount()); } catch (ArithmeticException ignored) { items = Long.MAX_VALUE; }
        }
        return new Sale((long) Math.floor(exactCoins + 0.0000001D), items);
    }

    private void removeSellableItems(Inventory inventory) {
        for (int slot = 0; slot < INPUT_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType().isAir()) continue;
            cleanLegacyPriceLore(stack);
            ItemMeta meta = stack.getItemMeta();
            if (meta != null && isSellable(stack, meta)) inventory.setItem(slot, null);
        }
    }

    private void returnInputItems(Player player, Inventory inventory) {
        for (int slot = 0; slot < INPUT_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType().isAir()) continue;
            cleanLegacyPriceLore(stack);
            inventory.setItem(slot, null);
            Map<Integer, ItemStack> remaining = player.getInventory().addItem(stack);
            remaining.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        }
    }

    private boolean containsInputItems(Inventory inventory) {
        for (int slot = 0; slot < INPUT_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack != null && !stack.getType().isAir()) return true;
        }
        return false;
    }

    private void scheduleControlRefresh(Player player, Inventory inventory) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (player.getOpenInventory().getTopInventory() != inventory) return;
            updateControlRow(player, inventory);
            if (player.getGameMode() != GameMode.CREATIVE) player.updateInventory();
        });
    }

    private void updateControlRow(Player player, Inventory inventory) {
        ItemStack filler = guiControl(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "));
        for (int slot = INPUT_SIZE; slot < SIZE; slot++) inventory.setItem(slot, filler);

        Sale sale = calculate(player, inventory);
        ItemStack button = guiControl(Material.LIME_STAINED_GLASS_PANE,
                Component.text("Sachen verkaufen", NamedTextColor.GREEN));
        ItemMeta meta = button.getItemMeta();
        meta.lore(List.of(
                Component.text("Gesamtpreis: ", NamedTextColor.GRAY)
                        .append(Component.text(formatCoins(sale.coins) + " Coins", NamedTextColor.GOLD)),
                Component.empty(),
                Component.text("Klicken zum Verkaufen", NamedTextColor.GREEN)
        ));
        button.setItemMeta(meta);
        inventory.setItem(SELL_BUTTON_SLOT, button);
    }

    private ItemStack guiControl(Material material, Component name) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(name);
        meta.getPersistentDataContainer().set(guiControlKey, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    private boolean isGuiControl(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(guiControlKey, PersistentDataType.BYTE);
    }

    private record Sale(long coins, long items) {}
}
