package de.walahi.novosmp.orders;

import de.walahi.smpcore.gui.Gui;
import de.walahi.smpcore.gui.GuiButton;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/** Rendering and actions for Order Collect. */
final class OrderCollectMenu {
    private final OrderMenu owner;
    private final OrderManager orders;
    private final OrderMenuConfig config;
    private final OrderMenuItems items;

    OrderCollectMenu(OrderMenu owner, OrderManager orders, OrderMenuConfig config, OrderMenuItems items) {
        this.owner = owner;
        this.orders = orders;
        this.config = config;
        this.items = items;
    }

    void render(Player player) {
        try {
            int rows = config.rows("menus.collect.rows", 6);
            int size = rows * 9;
            int pageSize = Math.min(size, Math.max(1, config.integer("menus.collect.page-size", 45)));
            Gui gui = new Gui(rows, config.component("menus.collect.title", "<dark_gray>Order Collect</dark_gray>"));
            List<OrderCollectEntry> entries = orders.collectEntries(player.getUniqueId(), pageSize);
            if (entries.isEmpty()) {
                gui.item(config.slot("menus.collect.empty-slot", 22, size),
                        config.item("menus.collect.empty", Material.CHEST,
                                "<gray>Keine Lieferungen</gray>",
                                List.of("<dark_gray>Aktuell warten keine bestellten Items.</dark_gray>")));
            } else {
                for (int slot = 0; slot < entries.size() && slot < pageSize; slot++) {
                    OrderCollectEntry entry = entries.get(slot);
                    gui.button(slot, GuiButton.of(items.collect(entry), event -> collectOne(player, entry.id())));
                }
            }
            int collectAllSlot = config.slot("menus.collect.collect-all-slot", 49, size);
            gui.button(collectAllSlot, GuiButton.of(config.item("menus.collect.collect-all", Material.HOPPER,
                    "<green>Alles abholen</green>", List.of(
                            "<gray>Nimmt so viele Items wie möglich.</gray>",
                            "<dark_gray>Nicht abgeholte Items bleiben sicher gespeichert.</dark_gray>"
                    )), event -> collectAll(player)));
            gui.open(player);
        } catch (Exception exception) {
            owner.fail(player, "Order Collect", exception);
        }
    }

    private void collectOne(Player player, long entryId) {
        OrderManager.CollectResult result = orders.collectOne(player, entryId);
        Map<String, String> placeholders = Map.of("%amount%", items.format(result.collectedAmount()));
        switch (result.status()) {
            case SUCCESS -> owner.sendMessage(player, "collect-success",
                    "<green>Du hast <yellow>%amount% Items</yellow> abgeholt.</green>", placeholders);
            case PARTIAL -> owner.sendMessage(player, "collect-partial",
                    "<yellow>%amount% Items abgeholt. Der Rest bleibt sicher in Order Collect.</yellow>", placeholders);
            case NOT_FOUND -> owner.sendMessage(player, "collect-not-found",
                    "<red>Diese Lieferung wurde bereits abgeholt.</red>");
            case STORAGE_ERROR -> owner.sendMessage(player, "collect-error",
                    "<red>Die Lieferung konnte nicht abgeholt werden.</red>");
        }
        owner.replaceCurrent(player, owner.collectRoute());
    }

    private void collectAll(Player player) {
        OrderManager.CollectAllResult result = orders.collectAll(player);
        if (result.status() == OrderManager.CollectStatus.STORAGE_ERROR) {
            owner.sendMessage(player, "collect-all-error",
                    "<red>Order Collect konnte nicht vollständig verarbeitet werden.</red>");
        } else if (result.collectedAmount() <= 0) {
            owner.sendMessage(player, "collect-all-empty",
                    "<yellow>Dein Inventar ist voll oder es warten keine Items.</yellow>");
        } else if (result.remainingAmount() > 0) {
            owner.sendMessage(player, "collect-all-partial",
                    "<yellow>%amount% Items abgeholt. Der Rest bleibt sicher gespeichert.</yellow>",
                    Map.of("%amount%", items.format(result.collectedAmount())));
        } else {
            owner.sendMessage(player, "collect-all-success",
                    "<green>Du hast alle wartenden Items abgeholt.</green>");
        }
        owner.replaceCurrent(player, owner.collectRoute());
    }
}
