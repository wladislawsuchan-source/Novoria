package de.walahi.novosmp.lumi;

import de.walahi.novosmp.crates.CrateDefinition;
import de.walahi.novosmp.crates.CrateManager;
import de.walahi.novosmp.items.CustomItemManager;
import de.walahi.smpcore.SMPCorePlugin;
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
import java.util.Map;

/**
 * Existing Lumi-Key shop plus optional Custom-Item offers.
 *
 * <p>The Lumi-Key still reads the existing {@code lumi.shop} section from the main
 * configuration. Only additional Custom-Item offers are read from {@code lumi-shop.yml},
 * so the integration does not move or rewrite the established shop settings.</p>
 */
public final class LumiShopMenu {
    private final SMPCorePlugin plugin;
    private final LumiRepository lumis;
    private final CrateManager crates;
    private final CustomItemManager customItems;
    private final MiniMessageItems items = new MiniMessageItems();

    public LumiShopMenu(SMPCorePlugin plugin, LumiRepository lumis, CrateManager crates,
                        CustomItemManager customItems) {
        this.plugin = plugin;
        this.lumis = lumis;
        this.crates = crates;
        this.customItems = customItems;
    }

    public void open(Player player) {
        ConfigurationSection root = plugin.configs().main().getConfigurationSection("lumi.shop");
        int rows = Math.max(3, Math.min(6, root == null ? 3 : root.getInt("rows", 3)));
        int inventorySize = rows * 9;
        Gui gui = new Gui(rows, items.component(value(root, "title", "<dark_gray>Lumi-Shop</dark_gray>")));
        Material filler = MaterialResolver.resolve(value(root, "filler", null), Material.GRAY_STAINED_GLASS_PANE);
        gui.filler(items.item(filler, value(root, "filler-name", " "), List.of()));

        ConfigurationSection key = root == null ? null : root.getConfigurationSection("key");
        int keySlot = SlotLayout.valid(key == null ? 13 : key.getInt("slot", 13), inventorySize, 13);
        gui.button(keySlot, GuiButton.of(keyIcon(player, key), event -> buyKey(player)));

        addCustomItemOffers(gui, player, inventorySize);
        addCrateKeyOffers(gui, player, inventorySize);
        gui.open(player);
    }

    private void addCrateKeyOffers(Gui gui, Player player, int inventorySize) {
        ConfigurationSection section = plugin.configs().lumiShop().getConfigurationSection("crate-keys");
        if (section == null) return;
        for (String id : section.getKeys(false)) {
            ConfigurationSection configured = section.getConfigurationSection(id);
            if (configured == null || !configured.getBoolean("enabled", true)) continue;
            CrateKeyOffer offer = new CrateKeyOffer(
                    id, configured.getString("crate", id), configured.getInt("slot", 15),
                    Math.max(0L, configured.getLong("price", 0L)), Math.max(1, configured.getInt("amount", 1)),
                    configured.getString("display", id + "-Key"), configured.getString("display-name", ""),
                    configured.getStringList("lore"), configured.getString("permission", ""));
            if (!offer.permission().isBlank() && !player.hasPermission(offer.permission())) continue;
            int slot = SlotLayout.valid(offer.slot(), inventorySize, 15);
            gui.button(slot, GuiButton.of(crateKeyIcon(player, offer), event -> buyCrateKey(player, offer)));
        }
    }

    private ItemStack crateKeyIcon(Player player, CrateKeyOffer offer) {
        CrateDefinition crate = crates.find(offer.crateId()).orElse(null);
        ItemStack stack = crate == null ? new ItemStack(Material.BARRIER) : crates.keys().create(crate, offer.amount());
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        if (!offer.displayName().isBlank()) {
            meta.displayName(items.component(offer.displayName()).decoration(TextDecoration.ITALIC, false));
        }
        List<Component> lore = new ArrayList<>();
        List<Component> existing = meta.lore();
        if (existing != null) lore.addAll(existing);
        List<String> configuredLore = offer.lore().isEmpty() ? List.of(
                "<gray>Preis: <yellow>%price% Lumis</yellow></gray>",
                "<gray>Deine Lumis: <white>%balance%</white></gray>", "", "<green>Klicke zum Kaufen</green>") : offer.lore();
        for (String line : configuredLore) {
            lore.add(items.component(line
                    .replace("%price%", MenuFormat.integer(offer.price()))
                    .replace("%amount%", MenuFormat.integer(offer.amount()))
                    .replace("%balance%", MenuFormat.integer(lumis.balance(player.getUniqueId()))))
                    .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private void buyCrateKey(Player player, CrateKeyOffer offer) {
        CrateDefinition crate = crates.find(offer.crateId()).orElse(null);
        if (crate == null || !crate.enabled()) {
            sendCustomMessage(player, "messages.unavailable", "<red>Dieser Key ist momentan nicht verfügbar.</red>");
            return;
        }
        if (player.getInventory().firstEmpty() == -1) {
            sendCustomMessage(player, "messages.inventory-full", "<red>Dein Inventar ist voll.</red>");
            return;
        }
        if (!lumis.withdraw(player.getUniqueId(), offer.price())) {
            sendCustomMessage(player, "messages.not-enough", "<red>Du hast nicht genug Lumis.</red>");
            return;
        }
        try {
            crates.keys().add(player, crate, offer.amount());
            sendCustomMessage(player, "messages.success",
                    "<green>Du hast <yellow>%amount%x %item%</yellow> für <gold>%price% Lumis</gold> gekauft.</green>",
                    "%amount%", Integer.toString(offer.amount()), "%item%", offer.display(), "%price%", Long.toString(offer.price()));
        } catch (RuntimeException exception) {
            lumis.add(player.getUniqueId(), offer.price());
            sendCustomMessage(player, "messages.failed", "<red>Der Kauf konnte nicht abgeschlossen werden.</red>");
            plugin.getLogger().warning("Lumi-Shop-Crate-Key '" + offer.id() + "' für " + player.getName()
                    + " ist fehlgeschlagen: " + exception.getMessage());
        }
        open(player);
    }

    private void addCustomItemOffers(Gui gui, Player player, int inventorySize) {
        ConfigurationSection section = plugin.configs().lumiShop().getConfigurationSection("custom-items");
        if (section == null) return;

        for (String id : section.getKeys(false)) {
            ConfigurationSection configured = section.getConfigurationSection(id);
            if (configured == null || !configured.getBoolean("enabled", true)) continue;
            CustomItemOffer offer = readOffer(id, configured);
            if (offer == null) continue;
            if (!offer.permission().isBlank() && !player.hasPermission(offer.permission())) continue;

            int slot = SlotLayout.valid(offer.slot(), inventorySize, 11);
            gui.button(slot, GuiButton.of(customItemIcon(player, offer), event -> buyCustomItem(player, offer)));
        }
    }

    private CustomItemOffer readOffer(String id, ConfigurationSection section) {
        String itemId = section.getString("item", id);
        if (itemId == null || itemId.isBlank()) {
            plugin.getLogger().warning("Lumi-Shop-Custom-Item '" + id + "' hat keine Item-Kennung.");
            return null;
        }
        return new CustomItemOffer(
                id,
                itemId,
                section.getInt("slot", 11),
                Math.max(0L, section.getLong("price", 0L)),
                Math.max(1, section.getInt("amount", 1)),
                section.getString("display", itemId),
                section.getString("display-name", ""),
                section.getStringList("lore"),
                section.getString("permission", "")
        );
    }

    private ItemStack keyIcon(Player player, ConfigurationSection key) {
        long price = Math.max(0L, key == null ? 1_000L : key.getLong("price", 1_000L));
        long balance = lumis.balance(player.getUniqueId());
        CrateDefinition crate = crates.find("lumi").orElse(null);
        ItemStack stack = crate == null ? new ItemStack(Material.BARRIER) : crates.keys().create(crate, 1);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        String name = value(key, "name", "<gold>Lumi-Key</gold>");
        List<String> lore = key == null ? List.of() : key.getStringList("lore");
        if (lore.isEmpty()) {
            lore = List.of(
                    "<gray>Preis: <yellow>%price% Lumis</yellow></gray>",
                    "<gray>Deine Lumis: <white>%balance%</white></gray>",
                    "",
                    "<green>Klicke zum Kaufen</green>"
            );
        }
        Map<String, String> placeholders = Map.of(
                "%price%", MenuFormat.integer(price),
                "%balance%", MenuFormat.integer(balance)
        );
        meta.displayName(items.component(name, placeholders).decoration(TextDecoration.ITALIC, false));
        meta.lore(items.lore(lore, placeholders));
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack customItemIcon(Player player, CustomItemOffer offer) {
        ItemStack stack = customItems.create(offer.itemId(), 1);
        if (stack == null) stack = new ItemStack(Material.BARRIER);
        stack.setAmount(Math.max(1, Math.min(stack.getMaxStackSize(), offer.amount())));

        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        if (!offer.displayName().isBlank()) {
            meta.displayName(items.component(offer.displayName()).decoration(TextDecoration.ITALIC, false));
        }

        List<Component> lore = new ArrayList<>();
        List<Component> existing = meta.lore();
        if (existing != null) lore.addAll(existing);
        for (String line : offer.lore()) lore.add(render(line, player, offer));
        for (String line : plugin.configs().lumiShop().getStringList("lore-suffix")) {
            lore.add(render(line, player, offer));
        }
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private Component render(String line, Player player, CustomItemOffer offer) {
        return items.component(line
                        .replace("%price%", MenuFormat.integer(offer.price()))
                        .replace("%amount%", MenuFormat.integer(offer.amount()))
                        .replace("%balance%", MenuFormat.integer(lumis.balance(player.getUniqueId()))))
                .decoration(TextDecoration.ITALIC, false);
    }

    private void buyKey(Player player) {
        ConfigurationSection key = plugin.configs().main().getConfigurationSection("lumi.shop.key");
        long price = Math.max(0L, key == null ? 1_000L : key.getLong("price", 1_000L));
        CrateDefinition crate = crates.find("lumi").orElse(null);
        if (crate == null || !crate.enabled()) {
            plugin.messages().sendConfiguredAuto(player, "lumi.shop.messages.key-unavailable",
                    "<red>Der Lumi-Key ist momentan nicht verfügbar.</red>");
            return;
        }
        if (player.getInventory().firstEmpty() == -1) {
            plugin.messages().sendConfiguredAuto(player, "lumi.shop.messages.inventory-full",
                    "<red>Dein Inventar ist voll.</red>");
            return;
        }
        if (!lumis.withdraw(player.getUniqueId(), price)) {
            plugin.messages().sendConfiguredAuto(player, "lumi.shop.messages.not-enough",
                    "<red>Du hast nicht genug Lumis.</red>");
            return;
        }
        try {
            crates.keys().add(player, crate, 1);
            plugin.messages().sendConfiguredAuto(player, "lumi.shop.messages.success",
                    "<green>Du hast einen Lumi-Key gekauft.</green>");
        } catch (RuntimeException exception) {
            lumis.add(player.getUniqueId(), price);
            plugin.messages().sendConfiguredAuto(player, "lumi.shop.messages.failed",
                    "<red>Der Kauf konnte nicht abgeschlossen werden.</red>");
            plugin.getLogger().warning("Lumi-Key-Kauf für " + player.getName()
                    + " ist fehlgeschlagen: " + exception.getMessage());
        }
        open(player);
    }

    private void buyCustomItem(Player player, CustomItemOffer offer) {
        List<ItemStack> rewards = customItemStacks(offer);
        if (rewards.isEmpty()) {
            sendCustomMessage(player, "messages.unavailable",
                    "<red>Dieses Angebot ist momentan nicht verfügbar.</red>");
            return;
        }
        if (!fits(player, rewards)) {
            sendCustomMessage(player, "messages.inventory-full", "<red>Dein Inventar ist voll.</red>");
            return;
        }

        ItemStack[] inventoryBefore = cloneContents(player.getInventory().getStorageContents());
        if (!lumis.withdraw(player.getUniqueId(), offer.price())) {
            sendCustomMessage(player, "messages.not-enough", "<red>Du hast nicht genug Lumis.</red>");
            return;
        }

        try {
            if (!deliver(player, rewards)) throw new IllegalStateException("Ware konnte nicht übergeben werden");
            sendCustomMessage(player, "messages.success",
                    "<green>Du hast <yellow>%amount%x %item%</yellow> für <gold>%price% Lumis</gold> gekauft.</green>",
                    "%amount%", Integer.toString(offer.amount()),
                    "%item%", offer.display(),
                    "%price%", Long.toString(offer.price()));
        } catch (RuntimeException exception) {
            player.getInventory().setStorageContents(inventoryBefore);
            lumis.add(player.getUniqueId(), offer.price());
            sendCustomMessage(player, "messages.failed", "<red>Der Kauf konnte nicht abgeschlossen werden.</red>");
            plugin.getLogger().warning("Lumi-Shop-Custom-Item '" + offer.id() + "' für " + player.getName()
                    + " ist fehlgeschlagen: " + exception.getMessage());
        }
        open(player);
    }

    private List<ItemStack> customItemStacks(CustomItemOffer offer) {
        ItemStack sample = customItems.create(offer.itemId(), 1);
        if (sample == null) return List.of();

        List<ItemStack> result = new ArrayList<>();
        int remaining = offer.amount();
        int maxStack = Math.max(1, sample.getMaxStackSize());
        while (remaining > 0) {
            int chunk = Math.min(maxStack, remaining);
            ItemStack stack = customItems.create(offer.itemId(), chunk);
            if (stack == null) return List.of();
            result.add(stack);
            remaining -= chunk;
        }
        return result;
    }

    private boolean fits(Player player, List<ItemStack> rewards) {
        ItemStack[] simulated = cloneContents(player.getInventory().getStorageContents());
        for (ItemStack incoming : rewards) {
            int remaining = incoming.getAmount();
            for (int index = 0; index < simulated.length && remaining > 0; index++) {
                ItemStack current = simulated[index];
                if (current == null || current.getType().isAir() || !current.isSimilar(incoming)) continue;
                int moved = Math.min(Math.max(0, current.getMaxStackSize() - current.getAmount()), remaining);
                if (moved <= 0) continue;
                current.setAmount(current.getAmount() + moved);
                remaining -= moved;
            }
            for (int index = 0; index < simulated.length && remaining > 0; index++) {
                ItemStack current = simulated[index];
                if (current != null && !current.getType().isAir()) continue;
                int moved = Math.min(incoming.getMaxStackSize(), remaining);
                ItemStack placed = incoming.clone();
                placed.setAmount(moved);
                simulated[index] = placed;
                remaining -= moved;
            }
            if (remaining > 0) return false;
        }
        return true;
    }

    private boolean deliver(Player player, List<ItemStack> rewards) {
        for (ItemStack reward : rewards) {
            if (!player.getInventory().addItem(reward.clone()).isEmpty()) return false;
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

    private void sendCustomMessage(Player player, String path, String fallback, String... replacements) {
        plugin.messages().sendConfigured(player, plugin.configs().lumiShop(), path,
                MessageChannel.NOVORIA, fallback, replacements);
    }

    private String value(ConfigurationSection section, String path, String fallback) {
        return section == null ? fallback : section.getString(path, fallback);
    }

    private record CrateKeyOffer(
            String id, String crateId, int slot, long price, int amount, String display,
            String displayName, List<String> lore, String permission
    ) { }

    private record CustomItemOffer(
            String id,
            String itemId,
            int slot,
            long price,
            int amount,
            String display,
            String displayName,
            List<String> lore,
            String permission
    ) { }
}
