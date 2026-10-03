package de.walahi.novosmp.orders;

import de.walahi.smpcore.gui.Gui;
import de.walahi.smpcore.gui.GuiButton;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Main, owner and detail views of the order system. */
final class OrderOverviewMenu {
    private static final List<Integer> DEFAULT_OWNER_SLOTS = List.of(
            1, 2, 3, 4, 5, 6, 7,
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22
    );

    private final OrderMenu owner;
    private final OrderManager orders;
    private final OrderMenuConfig config;
    private final OrderMenuItems items;

    OrderOverviewMenu(OrderMenu owner, OrderManager orders, OrderMenuConfig config, OrderMenuItems items) {
        this.owner = owner;
        this.orders = orders;
        this.config = config;
        this.items = items;
    }

    void renderMain(Player player) {
        orders.processExpiredSafely();
        try {
            int rows = config.rows("menus.main.rows", 6);
            int size = rows * 9;
            Gui gui = new Gui(rows, config.component("menus.main.title", "<dark_gray>Orders</dark_gray>"));
            ItemStack filler = config.item("filler", Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
            int contentSize = Math.min(size, Math.min(45, Math.max(1, config.integer("menus.main.content-size", 45))));
            for (int slot = contentSize; slot < size; slot++) gui.item(slot, filler.clone());

            UUID viewerId = player.getUniqueId();
            List<OrderListing> activeListings = orders.repository().activeOrders(contentSize, 0).stream()
                    .filter(listing -> orders.itemPolicy().isOrderItemAllowed(listing.item()))
                    .toList();

            if (activeListings.isEmpty()) {
                int emptySlot = config.slot("menus.main.empty-slot", 22, size);
                gui.item(emptySlot, config.item("menus.main.empty", Material.PAPER,
                        "<gray>Keine aktiven Aufträge</gray>",
                        List.of("<dark_gray>Aktuell gibt es keine offenen Aufträge.</dark_gray>")));
            } else {
                for (int slot = 0; slot < activeListings.size() && slot < contentSize; slot++) {
                    OrderListing listing = activeListings.get(slot);
                    gui.button(slot, GuiButton.of(items.publicOrder(listing, viewerId), event -> {
                        if (listing.ownerId().equals(viewerId)) {
                            owner.sendMessage(player, "own-order-delivery",
                                    "<red>Du kannst deinen eigenen Auftrag nicht selbst erfüllen.</red>");
                            return;
                        }
                        owner.openDelivery(player, listing.id());
                    }));
                }
            }

            int ownSlot = config.slot("menus.main.own-orders-slot", 49, size);
            Map<String, String> placeholders = Map.of(
                    "%active%", Integer.toString(orders.active(viewerId)),
                    "%limit%", items.displayLimit(orders.limit(player))
            );
            gui.button(ownSlot, GuiButton.of(config.item("menus.main.own-orders", Material.CHEST,
                    "<gold>Meine Aufträge</gold>", List.of(
                            "<gray>Aktiv: <yellow>%active%</yellow>/<yellow>%limit%</yellow></gray>",
                            "<dark_gray>Klicke, um Aufträge zu erstellen und zu verwalten.</dark_gray>"
                    ), placeholders), event -> owner.openChild(player, owner.ownRoute())));
            gui.open(player);
        } catch (Exception exception) {
            owner.fail(player, "Order-Hauptmenü", exception);
        }
    }

    void renderOwn(Player player) {
        orders.processExpiredSafely();
        try {
            int rows = config.rows("menus.own.rows", 3);
            int size = rows * 9;
            Gui gui = new Gui(rows, config.component("menus.own.title", "<dark_gray>Meine Aufträge</dark_gray>"));
            gui.filler(config.item("filler", Material.BLACK_STAINED_GLASS_PANE, " ", List.of()));

            List<Integer> slots = config.slots("menus.own.listing-slots", DEFAULT_OWNER_SLOTS, size);
            List<OrderListing> listings = orders.repository().ownerOrders(player.getUniqueId(), slots.size(), 0);
            for (int index = 0; index < listings.size() && index < slots.size(); index++) {
                OrderListing listing = listings.get(index);
                gui.button(slots.get(index), GuiButton.of(items.ownedOrder(listing),
                        event -> owner.openChild(player, owner.detailRoute(listing.id()))));
            }

            int createSlot = config.slot("menus.own.create-slot", 0, size);
            gui.button(createSlot, GuiButton.of(config.item("menus.own.create", Material.PAPER,
                    "<green>Auftrag erstellen</green>", List.of(
                            "<gray>Wähle den gewünschten Gegenstand aus.</gray>",
                            "<dark_gray>Menge und Stückpreis stellst du danach ein.</dark_gray>"
                    )), event -> owner.openChild(player, owner.selectionRoute(0))));

            int collectSlot = config.slot("menus.own.collect-slot", 18, size);
            gui.button(collectSlot, GuiButton.of(config.item("menus.own.collect", Material.CHEST,
                    "<aqua>Order Collect</aqua>", List.of(
                            "<gray>Wartende Lieferungen: <yellow>%count%</yellow></gray>",
                            "<dark_gray>Klicke, um bestellte Items abzuholen.</dark_gray>"
                    ), Map.of("%count%", Integer.toString(orders.collect(player.getUniqueId())))),
                    event -> owner.openChild(player, owner.collectRoute())));
            gui.open(player);
        } catch (Exception exception) {
            owner.fail(player, "Meine Aufträge", exception);
        }
    }

    void renderDetails(Player player, UUID orderId) {
        try {
            OrderListing listing = orders.repository().findActiveOwned(orderId, player.getUniqueId());
            if (listing == null) {
                owner.sendMessage(player, "order-inactive", "<red>Dieser Auftrag ist nicht mehr aktiv.</red>");
                owner.back(player);
                return;
            }

            int rows = config.rows("menus.details.rows", 3);
            int size = rows * 9;
            Gui gui = new Gui(rows, config.component("menus.details.title", "<dark_gray>Auftrag verwalten</dark_gray>"));
            gui.item(config.slot("menus.details.item-slot", 13, size), items.orderDetails(listing));
            gui.button(config.slot("menus.details.cancel-slot", 11, size), GuiButton.of(
                    config.item("menus.details.cancel", Material.RED_STAINED_GLASS_PANE,
                            "<red>Auftrag abbrechen</red>", List.of(
                                    "<gray>Offene Coins werden zurückgezahlt.</gray>",
                                    "<dark_gray>Bereits erfüllte Teile bleiben abgeschlossen.</dark_gray>",
                                    "", "<red>Klicke zum Abbrechen.</red>"
                            )), event -> cancel(player, listing.id())));
            gui.open(player);
        } catch (Exception exception) {
            owner.fail(player, "Auftrag verwalten", exception);
        }
    }

    private void cancel(Player player, UUID orderId) {
        OrderManager.CancelResult result = orders.cancel(player, orderId);
        switch (result) {
            case SUCCESS -> owner.sendMessage(player, "cancel-success",
                    "<green>Dein Auftrag wurde abgebrochen und die offenen Coins wurden zurückgezahlt.</green>");
            case REFUND_IN_COLLECT -> owner.sendMessage(player, "cancel-collect",
                    "<yellow>Der Auftrag wurde abgebrochen. Die Rückzahlung liegt sicher in Collect.</yellow>");
            case NOT_ACTIVE -> owner.sendMessage(player, "order-inactive",
                    "<red>Dieser Auftrag ist nicht mehr aktiv.</red>");
            case STORAGE_ERROR -> owner.sendMessage(player, "cancel-error",
                    "<red>Der Auftrag konnte gerade nicht abgebrochen werden.</red>");
        }
        owner.openOwnOrders(player);
    }
}
