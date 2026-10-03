package de.walahi.novosmp.auction;

import de.walahi.smpcore.gui.Gui;
import de.walahi.smpcore.gui.GuiButton;
import de.walahi.smpcore.gui.PageWindow;
import de.walahi.smpcore.gui.SlotLayout;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/** Public listings and the seller's own active listings. */
final class AuctionOverviewMenu {
    private static final List<Integer> DEFAULT_CONTENT_SLOTS = SlotLayout.range(0, 44);

    private final AuctionMenu owner;
    private final AuctionManager auctions;
    private final AuctionMenuConfig config;
    private final AuctionMenuItems items;

    AuctionOverviewMenu(AuctionMenu owner, AuctionManager auctions,
                        AuctionMenuConfig config, AuctionMenuItems items) {
        this.owner = owner;
        this.auctions = auctions;
        this.config = config;
        this.items = items;
    }

    void renderMain(Player player, int requestedPage) {
        try {
            int rows = config.rows("menus.main.rows", 6);
            int size = rows * 9;
            List<Integer> contentSlots = config.slots("menus.main.content-slots",
                    DEFAULT_CONTENT_SLOTS, size);
            int total = auctions.repository().countAllActive();
            PageWindow page = PageWindow.of(requestedPage, total, contentSlots.size());
            Gui gui = new Gui(rows, config.component("menus.main.title",
                    "<dark_gray>Auktionshaus • Seite %page%/%pages%</dark_gray>", page.placeholders()));

            List<AuctionListing> listings = auctions.repository()
                    .activeListings(contentSlots.size(), page.offset());
            if (listings.isEmpty()) {
                gui.item(config.slot("menus.main.empty-slot", 22, size),
                        items.empty("menus.main.empty", Material.BARRIER,
                                "<yellow>Keine Angebote</yellow>",
                                List.of("<gray>Aktuell sind keine Gegenstände im Auktionshaus.</gray>")));
            }
            for (int index = 0; index < listings.size() && index < contentSlots.size(); index++) {
                AuctionListing listing = listings.get(index);
                gui.button(contentSlots.get(index), GuiButton.of(items.listing(listing, player), event -> {
                    owner.play(player, "click");
                    if (listing.sellerId().equals(player.getUniqueId())) {
                        owner.openChild(player, owner.listingsRoute(0));
                    } else {
                        owner.openChild(player, owner.buyRoute(listing.id(), page.page()));
                    }
                }));
            }

            owner.decorateBottomRow(gui);

            Map<String, String> ownPlaceholders = Map.of(
                    "%active%", Integer.toString(auctions.activeListings(player.getUniqueId())),
                    "%limit%", items.displayLimit(auctions.listingLimit(player))
            );
            gui.button(config.slot("menus.main.own-slot", 45, size), GuiButton.of(
                    config.item("menus.main.own", Material.CHEST, "<gold>Meine Angebote</gold>",
                            List.of("<gray>Aktiv: <yellow>%active%</yellow>/<yellow>%limit%</yellow></gray>"),
                            ownPlaceholders),
                    event -> owner.openChild(player, owner.listingsRoute(0))));

            gui.button(config.slot("menus.main.collect-slot", 46, size), GuiButton.of(
                    config.item("menus.main.collect", Material.HOPPER, "<yellow>Collect</yellow>",
                            List.of("<gray>Abzuholen: <yellow>%count%</yellow></gray>"),
                            Map.of("%count%", Integer.toString(auctions.pendingCollect(player.getUniqueId())))),
                    event -> owner.openChild(player, owner.collectRoute(0))));

            addPageButtons(gui, player, page, "menus.main", owner.mainRoute(page.page()));
            int sellSlot = config.slot("menus.main.sell-slot", 53, size);
            gui.button(sellSlot, GuiButton.of(items.button("sell", Material.MAP,
                    "<green>Gegenstand verkaufen</green>", List.of(
                            "<gray>Item in der Hand auswählen</gray>",
                            "<dark_gray>Preis anschließend auf dem Schild eingeben</dark_gray>"
                    )), event -> owner.sellHint(player)));
            gui.open(player);
            owner.play(player, "open");
        } catch (Exception exception) {
            owner.fail(player, "AH-Hauptmenü", exception);
        }
    }

    void renderListings(Player player, int requestedPage) {
        try {
            int rows = config.rows("menus.listings.rows", 6);
            int size = rows * 9;
            List<Integer> contentSlots = config.slots("menus.listings.content-slots",
                    DEFAULT_CONTENT_SLOTS, size);
            int total = auctions.activeListings(player.getUniqueId());
            PageWindow page = PageWindow.of(requestedPage, total, contentSlots.size());
            Gui gui = new Gui(rows, config.component("menus.listings.title",
                    "<dark_gray>Meine Angebote • Seite %page%/%pages%</dark_gray>", page.placeholders()));

            List<AuctionListing> listings = auctions.repository().sellerListings(
                    player.getUniqueId(), contentSlots.size(), page.offset());
            if (listings.isEmpty()) {
                gui.item(config.slot("menus.listings.empty-slot", 22, size),
                        items.empty("menus.listings.empty", Material.CHEST,
                                "<yellow>Keine aktiven Angebote</yellow>",
                                List.of("<gray>Stelle ein Item über den Verkaufsknopf ein.</gray>")));
            }
            for (int index = 0; index < listings.size() && index < contentSlots.size(); index++) {
                AuctionListing listing = listings.get(index);
                gui.button(contentSlots.get(index), GuiButton.of(items.ownedListing(listing),
                        event -> owner.cancel(player, listing.id(), page.page())));
            }

            owner.decorateBottomRow(gui);

            gui.button(config.slot("menus.listings.back-slot", 45, size), GuiButton.of(
                    items.button("back", Material.ARROW, "<yellow>Zurück</yellow>",
                            List.of("<gray>Zum vorherigen Menü</gray>")), event -> owner.back(player)));
            gui.button(config.slot("menus.listings.history-slot", 47, size), GuiButton.of(
                    items.button("history", Material.WRITABLE_BOOK, "<gold>Verlauf</gold>",
                            List.of("<gray>Käufe, Verkäufe und abgelaufene Angebote</gray>")),
                    event -> owner.openChild(player, owner.historyRoute(0))));

            addPageButtons(gui, player, page, "menus.listings", owner.listingsRoute(page.page()));
            gui.button(config.slot("menus.listings.sell-slot", 52, size), GuiButton.of(
                    items.button("sell", Material.MAP, "<green>Gegenstand verkaufen</green>",
                            List.of("<gray>Item in der Hand auswählen</gray>",
                                    "<dark_gray>Preis anschließend auf dem Schild eingeben</dark_gray>")),
                    event -> owner.sellHint(player)));
            gui.button(config.slot("menus.listings.stats-slot", 53, size), GuiButton.of(
                    items.button("stats", Material.BOOK, "<aqua>AH-Statistiken</aqua>",
                            List.of("<gray>Deine Marktübersicht</gray>")),
                    event -> owner.openChild(player, owner.statsRoute())));
            gui.open(player);
            owner.play(player, "open");
        } catch (Exception exception) {
            owner.fail(player, "Eigene AH-Angebote", exception);
        }
    }

    private void addPageButtons(Gui gui, Player player, PageWindow page, String menuPath,
                                de.walahi.smpcore.gui.GuiNavigator.Destination currentRoute) {
        int size = gui.size();
        if (page.hasPrevious()) {
            gui.button(config.slot(menuPath + ".previous-slot", 48, size), GuiButton.of(
                    items.button("previous", Material.ARROW, "<yellow>Vorherige Seite</yellow>", List.of()),
                    event -> owner.replaceCurrent(player,
                            menuPath.endsWith("main") ? owner.mainRoute(page.page() - 1)
                                    : owner.listingsRoute(page.page() - 1))));
        }
        String status = menuPath.endsWith("main")
                ? "Angebote: " + page.totalEntries()
                : "Angebote: " + page.totalEntries() + "/"
                + items.displayLimit(auctions.listingLimit(player));
        gui.button(config.slot(menuPath + ".refresh-slot", 49, size), GuiButton.of(
                items.button("refresh", Material.BLAZE_POWDER, "<green>Aktualisieren</green>",
                        List.of("<gray>%status%</gray>"), Map.of("%status%", status)),
                event -> owner.replaceCurrent(player, currentRoute)));
        if (page.hasNext()) {
            gui.button(config.slot(menuPath + ".next-slot", 50, size), GuiButton.of(
                    items.button("next", Material.ARROW, "<yellow>Nächste Seite</yellow>", List.of()),
                    event -> owner.replaceCurrent(player,
                            menuPath.endsWith("main") ? owner.mainRoute(page.page() + 1)
                                    : owner.listingsRoute(page.page() + 1))));
        }
    }

}
