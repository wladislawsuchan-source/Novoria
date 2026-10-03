package de.walahi.novosmp.shop;

import de.walahi.novosmp.items.CustomItemManager;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.api.event.ActionContext;
import de.walahi.smpcore.api.event.ActionSource;
import de.walahi.smpcore.economy.EconomyOperationResult;
import de.walahi.smpcore.gui.Gui;
import de.walahi.smpcore.gui.GuiButton;
import de.walahi.smpcore.gui.MaterialResolver;
import de.walahi.smpcore.gui.MenuFormat;
import de.walahi.smpcore.gui.MiniMessageItems;
import de.walahi.smpcore.gui.SlotLayout;
import de.walahi.smpcore.messages.MessageChannel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ShopMenu {
    private static final int MIN_AMOUNT = 1;
    private static final int MAX_AMOUNT = 64;

    private final SMPCorePlugin plugin;
    private final CustomItemManager customItems;
    private final ShopMendingPolicy mendingPolicy;
    private final MiniMessageItems items = new MiniMessageItems();

    public ShopMenu(SMPCorePlugin plugin, CustomItemManager customItems, ShopMendingPolicy mendingPolicy) {
        this.plugin = plugin;
        this.customItems = customItems;
        this.mendingPolicy = mendingPolicy;
    }

    public void open(Player player) {
        ConfigurationSection root = plugin.configs().main().getConfigurationSection("shop");
        int rows = clampedRows(root == null ? 3 : root.getInt("rows", 3));
        Gui gui = new Gui(rows, items.component(value(root, "title", "<dark_gray>Shop</dark_gray>")));
        gui.filler(MaterialResolver.resolve(value(root, "filler", null), Material.GRAY_STAINED_GLASS_PANE));

        addCategories(gui, player, root == null ? null : root.getConfigurationSection("categories"));
        addCategories(gui, player, plugin.configs().customItemShop().getConfigurationSection("categories"));
        gui.open(player);
    }

    private void addCategories(Gui gui, Player player, ConfigurationSection categories) {
        if (categories == null) return;
        for (String id : categories.getKeys(false)) {
            ConfigurationSection category = categories.getConfigurationSection(id);
            if (category == null || !category.getBoolean("enabled", true)) continue;
            int slot = SlotLayout.valid(category.getInt("slot", 13), gui.rows() * 9, 13);
            gui.button(slot, GuiButton.of(categoryItem(category, id),
                    event -> openCategory(player, id, category)));
        }
    }

    private void openCategory(Player player, String categoryId, ConfigurationSection category) {
        int rows = clampedRows(category.getInt("rows", 3));
        int inventorySize = rows * 9;
        Gui gui = new Gui(rows, items.component(category.getString(
                "menu-title", "<dark_gray>" + categoryId + "</dark_gray>")));
        gui.filler(MaterialResolver.resolve(category.getString("filler"), Material.GRAY_STAINED_GLASS_PANE));

        ConfigurationSection products = category.getConfigurationSection("products");
        if (products != null) {
            for (String productId : products.getKeys(false)) {
                ConfigurationSection product = products.getConfigurationSection(productId);
                if (product == null || !product.getBoolean("enabled", true)) continue;
                int slot = SlotLayout.valid(product.getInt("slot", 13), inventorySize, 13);
                gui.button(slot, GuiButton.of(productItem(category, product),
                        event -> openBuySelector(player, categoryId, category, productId, product, 1)));
            }
        }

        ConfigurationSection back = category.getConfigurationSection("back");
        int backSlot = SlotLayout.valid(back == null ? inventorySize - 9 : back.getInt("slot", inventorySize - 9),
                inventorySize, inventorySize - 9);
        Material backMaterial = MaterialResolver.resolve(back == null ? null : back.getString("material"), Material.ARROW);
        String backName = value(back, "name", "<yellow>Zurück</yellow>");
        List<String> backLore = back == null || back.getStringList("lore").isEmpty()
                ? List.of("<gray>Zur Kategorieübersicht</gray>")
                : back.getStringList("lore");
        gui.button(backSlot, GuiButton.of(items.item(backMaterial, backName, backLore), event -> open(player)));
        gui.open(player);
    }

    private void openBuySelector(Player player, String categoryId, ConfigurationSection category,
                                 String productId, ConfigurationSection product, int requestedAmount) {
        int maxAmount = productMaxAmount(product);
        int amount = Math.max(MIN_AMOUNT, Math.min(maxAmount, requestedAmount));
        long unitPrice = Math.max(0L, product.getLong("price", 0L));
        long totalPrice = safeMultiply(unitPrice, amount);
        String currencyLabel = currencyLabel(category, product);

        ConfigurationSection menu = plugin.configs().main().getConfigurationSection("shop.buy-menu");
        int rows = clampedRows(menu == null ? 3 : menu.getInt("rows", 3));
        int inventorySize = rows * 9;
        Gui gui = new Gui(rows, items.component(value(menu, "title", "<dark_gray>Kaufen</dark_gray>")));
        gui.filler(MaterialResolver.resolve(value(menu, "filler", null), Material.GRAY_STAINED_GLASS_PANE));

        Map<String, String> placeholders = Map.of(
                "%amount%", Integer.toString(amount),
                "%unit_price%", MenuFormat.integer(unitPrice),
                "%total_price%", MenuFormat.integer(totalPrice),
                "%currency%", currencyLabel,
                "%category%", category.getString("name", categoryId)
        );
        List<String> centerLore = currency(category, product) == ShopCurrency.COINS
                ? listOrDefault(menu, "product.lore", List.of(
                    "<gray>Menge: <white>%amount%</white></gray>",
                    "<gray>Einzelpreis: <gold>%unit_price% Coins</gold></gray>",
                    "<gray>Gesamt: <gold>%total_price% Coins</gold></gray>"))
                : List.of(
                    "<gray>Menge: <white>%amount%</white></gray>",
                    "<gray>Einzelpreis: <gold>%unit_price% %currency%</gold></gray>",
                    "<gray>Gesamt: <gold>%total_price% %currency%</gold></gray>");
        int productSlot = SlotLayout.valid(menu == null ? 13 : menu.getInt("product.slot", 13), inventorySize, 13);
        gui.item(productSlot, selectorItem(product, amount, centerLore, placeholders));

        addAmountButton(gui, menu, "minus-64", 9, Material.RED_STAINED_GLASS_PANE, "<red>-64</red>",
                player, categoryId, category, productId, product, amount - 64);
        addAmountButton(gui, menu, "minus-10", 10, Material.RED_STAINED_GLASS_PANE, "<red>-10</red>",
                player, categoryId, category, productId, product, amount - 10);
        addAmountButton(gui, menu, "minus-1", 11, Material.RED_STAINED_GLASS_PANE, "<red>-1</red>",
                player, categoryId, category, productId, product, amount - 1);
        addAmountButton(gui, menu, "plus-1", 15, Material.LIME_STAINED_GLASS_PANE, "<green>+1</green>",
                player, categoryId, category, productId, product, amount + 1);
        addAmountButton(gui, menu, "plus-10", 16, Material.LIME_STAINED_GLASS_PANE, "<green>+10</green>",
                player, categoryId, category, productId, product, amount + 10);
        addAmountButton(gui, menu, "plus-64", 17, Material.LIME_STAINED_GLASS_PANE, "<green>+64</green>",
                player, categoryId, category, productId, product, amount + 64);

        ConfigurationSection cancel = menu == null ? null : menu.getConfigurationSection("cancel");
        int cancelSlot = SlotLayout.valid(cancel == null ? 21 : cancel.getInt("slot", 21), inventorySize, 21);
        Material cancelMaterial = MaterialResolver.resolve(cancel == null ? null : cancel.getString("material"), Material.RED_DYE);
        List<String> cancelLore = listOrDefault(cancel, "lore", List.of("<gray>Zurück zu %category%</gray>"));
        gui.button(cancelSlot, GuiButton.of(items.item(cancelMaterial, 1,
                        value(cancel, "name", "<red>Abbrechen</red>"), cancelLore, placeholders),
                event -> openCategory(player, categoryId, category)));

        ConfigurationSection confirm = menu == null ? null : menu.getConfigurationSection("confirm");
        int confirmSlot = SlotLayout.valid(confirm == null ? 23 : confirm.getInt("slot", 23), inventorySize, 23);
        Material confirmMaterial = MaterialResolver.resolve(confirm == null ? null : confirm.getString("material"), Material.LIME_DYE);
        List<String> confirmLore = currency(category, product) == ShopCurrency.COINS
                ? listOrDefault(confirm, "lore", List.of(
                    "<gray>%amount%x für <gold>%total_price% Coins</gold></gray>",
                    "<yellow>Klicke zum Bestätigen</yellow>"))
                : List.of(
                    "<gray>%amount%x für <gold>%total_price% %currency%</gold></gray>",
                    "<yellow>Klicke zum Bestätigen</yellow>");
        gui.button(confirmSlot, GuiButton.of(items.item(confirmMaterial, 1,
                        value(confirm, "name", "<green>Kaufen</green>"), confirmLore, placeholders),
                event -> {
                    if (buy(player, productId, category, product, amount)) {
                        openBuySelector(player, categoryId, category, productId, product, amount);
                    }
                }));

        gui.open(player);
    }

    private void addAmountButton(Gui gui, ConfigurationSection menu, String key,
                                 int fallbackSlot, Material fallbackMaterial, String fallbackName,
                                 Player player, String categoryId, ConfigurationSection category,
                                 String productId, ConfigurationSection product, int newAmount) {
        ConfigurationSection button = menu == null ? null : menu.getConfigurationSection("amount-buttons." + key);
        int slot = SlotLayout.valid(button == null ? fallbackSlot : button.getInt("slot", fallbackSlot),
                gui.rows() * 9, fallbackSlot);
        Material material = MaterialResolver.resolve(button == null ? null : button.getString("material"), fallbackMaterial);
        gui.button(slot, GuiButton.of(items.item(material,
                        value(button, "name", fallbackName),
                        listOrDefault(button, "lore", List.of("<gray>Menge anpassen</gray>"))),
                event -> openBuySelector(player, categoryId, category, productId, product, newAmount)));
    }

    private boolean buy(Player player, String productId, ConfigurationSection category,
                        ConfigurationSection product, int amount) {
        long unitPrice = product.getLong("price", 0L);
        if (unitPrice <= 0 || amount < MIN_AMOUNT || amount > productMaxAmount(product)) return false;

        long total = safeMultiply(unitPrice, amount);
        if (total == Long.MAX_VALUE) return false;
        List<ItemStack> rewards = rewardStacks(product, amount);
        if (rewards.isEmpty()) {
            sendCustomShopMessage(player, "messages.invalid-product", "<red>Dieses Angebot ist ungültig.</red>");
            return false;
        }
        if (!fits(player, rewards)) {
            plugin.messages().sendConfiguredAuto(player, "shop.messages.inventory-full",
                    "<red>Du hast nicht genug Platz im Inventar.</red>");
            return false;
        }

        ShopCurrency currency = currency(category, product);
        String currencyItem = currencyItem(category, product);
        if (currency == ShopCurrency.CUSTOM_ITEM && total > Integer.MAX_VALUE) {
            sendCustomShopMessage(player, "messages.invalid-product", "<red>Dieses Angebot ist ungültig.</red>");
            return false;
        }

        ItemStack[] inventoryBefore = cloneContents(player.getInventory().getContents());
        boolean paid;
        if (currency == ShopCurrency.CUSTOM_ITEM) {
            paid = !currencyItem.isBlank() && customItems.take(player, currencyItem, (int) total);
            if (!paid) {
                sendCustomShopMessage(player, "messages.not-enough-currency",
                        "<red>Du hast nicht genug <white>%currency%</white>.</red>",
                        "%currency%", currencyLabel(category, product));
                return false;
            }
        } else {
            EconomyOperationResult result = plugin.services().economy().withdraw(player.getUniqueId(), total,
                    "SHOP-" + productId + "-x" + amount,
                    ActionContext.player(ActionSource.GUI, player.getUniqueId()));
            if (result == EconomyOperationResult.INSUFFICIENT_FUNDS) {
                plugin.messages().sendConfiguredAuto(player, "shop.messages.not-enough-coins",
                        "<red>Du hast nicht genug Coins.</red>");
                return false;
            }
            if (result != EconomyOperationResult.SUCCESS) {
                plugin.messages().sendConfiguredAuto(player, "shop.messages.failed",
                        "<red>Der Kauf konnte nicht abgeschlossen werden.</red>");
                return false;
            }
            paid = true;
        }

        if (!deliver(player, rewards)) {
            player.getInventory().setContents(inventoryBefore);
            rollbackCoinPayment(player, currency, total, productId);
            plugin.messages().sendConfiguredAuto(player, "shop.messages.failed",
                    "<red>Der Kauf konnte nicht abgeschlossen werden.</red>");
            return false;
        }

        if (currency == ShopCurrency.CUSTOM_ITEM) {
            sendCustomShopMessage(player, "messages.success",
                    "<green>Du hast <yellow>%amount%x %item%</yellow> für <gold>%price% %currency%</gold> gekauft.</green>",
                    "%amount%", Integer.toString(amount),
                    "%item%", productDisplay(product),
                    "%price%", Long.toString(total),
                    "%currency%", currencyLabel(category, product));
        } else {
            plugin.messages().sendConfiguredAuto(player, "shop.messages.success",
                    "<green>Du hast <yellow>%amount%x %item%</yellow> für <gold>%price% Coins</gold> gekauft.</green>",
                    "%amount%", Integer.toString(amount),
                    "%item%", productDisplay(product),
                    "%price%", Long.toString(total));
        }
        return true;
    }

    private void rollbackCoinPayment(Player player, ShopCurrency currency, long total, String productId) {
        if (currency != ShopCurrency.COINS) return;
        plugin.services().economy().deposit(player.getUniqueId(), total,
                "SHOP-ROLLBACK-" + productId, ActionContext.system(player.getUniqueId()));
    }

    private boolean deliver(Player player, List<ItemStack> rewards) {
        for (ItemStack reward : rewards) {
            if (!player.getInventory().addItem(reward.clone()).isEmpty()) return false;
        }
        return true;
    }

    private List<ItemStack> rewardStacks(ConfigurationSection product, int amount) {
        if (isCustomItemProduct(product)) {
            String id = product.getString("item", "");
            ItemStack sample = customItems.create(id, 1);
            if (sample == null) return List.of();
            applyMendingPolicy(product, sample);
            int maxStack = Math.max(1, sample.getMaxStackSize());
            List<ItemStack> result = new ArrayList<>();
            int remaining = amount;
            while (remaining > 0) {
                int chunk = Math.min(maxStack, remaining);
                ItemStack stack = customItems.create(id, chunk);
                if (stack == null) return List.of();
                applyMendingPolicy(product, stack);
                result.add(stack);
                remaining -= chunk;
            }
            return result;
        }

        Material type = MaterialResolver.resolve(product.getString("material"), null);
        if (type == null) return List.of();
        List<ItemStack> result = new ArrayList<>();
        int remaining = amount;
        int maxStack = Math.max(1, type.getMaxStackSize());
        while (remaining > 0) {
            int chunk = Math.min(maxStack, remaining);
            result.add(new ItemStack(type, chunk));
            remaining -= chunk;
        }
        return result;
    }

    private ItemStack selectorItem(ConfigurationSection product, int amount,
                                   List<String> lore, Map<String, String> placeholders) {
        ItemStack base;
        if (isCustomItemProduct(product)) {
            base = customItems.create(product.getString("item", ""), 1);
            if (base == null) {
                base = new ItemStack(Material.BARRIER);
            } else {
                applyMendingPolicy(product, base);
                base.setAmount(Math.max(1, Math.min(base.getMaxStackSize(), amount)));
            }
        } else {
            Material material = MaterialResolver.resolve(product.getString("material"), Material.BARRIER);
            base = new ItemStack(material, Math.min(amount, material.getMaxStackSize()));
        }
        return decorate(base, product.getString("name", ""), lore, placeholders);
    }

    private ItemStack productItem(ConfigurationSection category, ConfigurationSection product) {
        ItemStack base;
        if (isCustomItemProduct(product)) {
            base = customItems.create(product.getString("item", ""), 1);
            if (base == null) base = new ItemStack(Material.BARRIER);
            else applyMendingPolicy(product, base);
        } else {
            Material type = MaterialResolver.resolve(product.getString("material"), Material.BARRIER);
            base = new ItemStack(type);
        }

        // Custom-Items bringen ihre eigentliche Beschreibung bereits aus items.yml mit.
        // Shop-Lore wird deshalb nur auf ausdrücklichen Wunsch ergänzt. So tauchen
        // Fähigkeitstexte und Kombinationshinweise nicht doppelt im Tooltip auf.
        List<String> lore = isCustomItemProduct(product)
                && !product.getBoolean("append-shop-lore", false)
                ? new ArrayList<>()
                : new ArrayList<>(product.getStringList("lore"));
        lore.add("");
        lore.add("<gray>Preis: <gold>" + MenuFormat.integer(product.getLong("price", 0L)) + " "
                + currencyLabel(category, product) + "</gold></gray>");
        lore.add("<yellow>Klicke zum Auswählen</yellow>");
        return decorate(base, product.getString("name", ""), lore, Map.of());
    }

    private ItemStack categoryItem(ConfigurationSection section, String id) {
        Material material = MaterialResolver.resolve(section.getString("material"), Material.CHEST);
        return items.item(material,
                section.getString("name", "<yellow>" + id + "</yellow>"),
                section.getStringList("lore"));
    }

    private ItemStack decorate(ItemStack base, String name, List<String> lore, Map<String, String> placeholders) {
        ItemStack result = base.clone();
        ItemMeta meta = result.getItemMeta();
        if (meta == null) return result;
        if (name != null && !name.isBlank()) {
            meta.displayName(items.component(replace(name, placeholders)).decoration(TextDecoration.ITALIC, false));
        }
        List<Component> merged = new ArrayList<>();
        List<Component> existing = meta.lore();
        if (existing != null) merged.addAll(existing);
        for (String line : lore) {
            merged.add(items.component(replace(line, placeholders)).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(merged);
        result.setItemMeta(meta);
        return result;
    }

    private ItemStack applyMendingPolicy(ConfigurationSection product, ItemStack item) {
        if (item == null || !isCustomItemProduct(product)) return item;
        return mendingPolicy.apply(item, product.getBoolean("allow-mending", false));
    }

    private boolean fits(Player player, List<ItemStack> stacks) {
        ItemStack[] simulated = player.getInventory().getStorageContents();
        simulated = simulated.clone();
        for (ItemStack incoming : stacks) {
            int remaining = incoming.getAmount();
            for (int i = 0; i < simulated.length && remaining > 0; i++) {
                ItemStack current = simulated[i];
                if (current == null || current.getType().isAir()) continue;
                if (!current.isSimilar(incoming)) continue;
                int space = current.getMaxStackSize() - current.getAmount();
                int moved = Math.min(space, remaining);
                if (moved > 0) {
                    current = current.clone();
                    current.setAmount(current.getAmount() + moved);
                    simulated[i] = current;
                    remaining -= moved;
                }
            }
            for (int i = 0; i < simulated.length && remaining > 0; i++) {
                ItemStack current = simulated[i];
                if (current != null && !current.getType().isAir()) continue;
                int moved = Math.min(incoming.getMaxStackSize(), remaining);
                ItemStack placed = incoming.clone();
                placed.setAmount(moved);
                simulated[i] = placed;
                remaining -= moved;
            }
            if (remaining > 0) return false;
        }
        return true;
    }


    private ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            copy[index] = contents[index] == null ? null : contents[index].clone();
        }
        return copy;
    }

    private ShopCurrency currency(ConfigurationSection category, ConfigurationSection product) {
        String configured = product.getString("currency", category.getString("currency", "COINS"));
        try {
            return ShopCurrency.valueOf(configured.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return ShopCurrency.COINS;
        }
    }

    private String currencyItem(ConfigurationSection category, ConfigurationSection product) {
        return product.getString("currency-item", category.getString("currency-item", customItems.currencyId()));
    }

    private String currencyLabel(ConfigurationSection category, ConfigurationSection product) {
        return product.getString("currency-label", category.getString("currency-label",
                currency(category, product) == ShopCurrency.COINS ? "Coins" : currencyItem(category, product)));
    }

    private boolean isCustomItemProduct(ConfigurationSection product) {
        return "CUSTOM_ITEM".equalsIgnoreCase(product.getString("type", "VANILLA_ITEM"));
    }

    private int productMaxAmount(ConfigurationSection product) {
        return Math.max(MIN_AMOUNT, Math.min(MAX_AMOUNT, product.getInt("max-amount", MAX_AMOUNT)));
    }

    private String productDisplay(ConfigurationSection product) {
        if (product.contains("display")) return product.getString("display", "Item");
        if (isCustomItemProduct(product)) return product.getString("item", "Custom-Item");
        return product.getString("material", "Item");
    }

    private void sendCustomShopMessage(Player player, String path, String fallback, String... replacements) {
        plugin.messages().sendConfigured(player, plugin.configs().customItemShop(), path,
                MessageChannel.SHOP, fallback, replacements);
    }

    private long safeMultiply(long first, int second) {
        try {
            return Math.multiplyExact(first, second);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private int clampedRows(int rows) {
        return Math.max(3, Math.min(6, rows));
    }

    private String value(ConfigurationSection section, String path, String fallback) {
        return section == null ? fallback : section.getString(path, fallback);
    }

    private List<String> listOrDefault(ConfigurationSection section, String path, List<String> fallback) {
        if (section == null) return fallback;
        List<String> configured = section.getStringList(path);
        return configured.isEmpty() ? fallback : configured;
    }

    private String replace(String input, Map<String, String> placeholders) {
        String result = input == null ? "" : input;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }
}
