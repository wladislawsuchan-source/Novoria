package de.walahi.novosmp.auction;

import de.walahi.smpcore.gui.Gui;
import de.walahi.smpcore.gui.GuiButton;
import de.walahi.smpcore.gui.MenuFormat;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Buy and sell confirmation views. */
final class AuctionConfirmationMenu {
    private final AuctionMenu owner;
    private final AuctionManager auctions;
    private final AuctionMenuConfig config;
    private final AuctionMenuItems items;

    AuctionConfirmationMenu(AuctionMenu owner, AuctionManager auctions,
                            AuctionMenuConfig config, AuctionMenuItems items) {
        this.owner = owner;
        this.auctions = auctions;
        this.config = config;
        this.items = items;
    }

    void renderSell(Player player, long price, ItemStack snapshot) {
        if (snapshot == null || snapshot.getType().isAir()) {
            owner.sendMessage(player, "hold-item", "<red>Halte zuerst einen Gegenstand in deiner Hand.</red>");
            owner.back(player);
            return;
        }
        if (!owner.itemAllowed(snapshot)) {
            owner.sendMessage(player, "item-blocked",
                    "<red>Dieses Item darf nicht im Auktionshaus angeboten werden.</red>");
            owner.back(player);
            return;
        }

        int rows = config.rows("menus.sell-confirm.rows", 3);
        int size = rows * 9;
        Gui gui = new Gui(rows, config.component("menus.sell-confirm.title",
                "<dark_gray>Angebot bestätigen</dark_gray>"));
        owner.decorateBottomRow(gui);
        gui.item(config.slot("menus.sell-confirm.preview-slot", 13, size), items.sellPreview(snapshot, price));
        gui.button(config.slot("menus.sell-confirm.cancel-slot", 11, size), GuiButton.of(
                items.button("sell-cancel", Material.RED_CONCRETE, "<red>Abbrechen</red>",
                        List.of("<gray>Das Item bleibt in deiner Hand.</gray>")), event -> owner.back(player)));
        gui.button(config.slot("menus.sell-confirm.confirm-slot", 15, size), GuiButton.of(
                items.button("sell-confirm", Material.LIME_CONCRETE,
                        "<green>Angebot einstellen</green>", List.of("<gold>%price% Coins</gold>"),
                        Map.of("%price%", MenuFormat.integer(price))),
                event -> owner.createListing(player, price, snapshot)));
        gui.open(player);
        owner.play(player, "open");
    }

    void renderBuy(Player player, UUID listingId, int sourcePage) {
        try {
            AuctionListing listing = auctions.repository().findActive(listingId).orElse(null);
            if (listing == null) {
                owner.sendMessage(player, "listing-unavailable",
                        "<red>Dieses Angebot ist nicht mehr verfügbar.</red>");
                owner.replaceCurrent(player, owner.mainRoute(sourcePage));
                return;
            }
            if (listing.sellerId().equals(player.getUniqueId())) {
                owner.replaceCurrent(player, owner.listingsRoute(0));
                return;
            }

            int rows = config.rows("menus.buy-confirm.rows", 3);
            int size = rows * 9;
            Gui gui = new Gui(rows, config.component("menus.buy-confirm.title",
                    "<dark_gray>Kauf bestätigen</dark_gray>"));
            owner.decorateBottomRow(gui);
            gui.item(config.slot("menus.buy-confirm.preview-slot", 13, size), items.listing(listing, player));
            int configuredConfirm = config.slot("menus.buy-confirm.confirm-slot", 15, size);
            int configuredCancel = config.slot("menus.buy-confirm.cancel-slot", 11, size);
            int cancelSlot = Math.min(configuredConfirm, configuredCancel);
            int confirmSlot = Math.max(configuredConfirm, configuredCancel);
            gui.button(cancelSlot, GuiButton.of(
                    items.button("buy-cancel", Material.RED_CONCRETE, "<red>Abbrechen</red>", List.of()),
                    event -> owner.back(player)));
            gui.button(confirmSlot, GuiButton.of(
                    items.button("buy-confirm", Material.LIME_CONCRETE, "<green>Kaufen</green>",
                            List.of("<gold>%price% Coins</gold>"),
                            Map.of("%price%", MenuFormat.integer(listing.price()))),
                    event -> owner.buy(player, listing.id(), sourcePage)));
            gui.open(player);
            owner.play(player, "open");
        } catch (Exception exception) {
            owner.fail(player, "AH-Kaufbestätigung", exception);
        }
    }
}
