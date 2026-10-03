package de.walahi.novosmp.auction;

import de.walahi.smpcore.gui.Gui;
import de.walahi.smpcore.gui.GuiButton;
import de.walahi.smpcore.gui.MenuFormat;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Compact personal auction statistics view. */
final class AuctionStatsMenu {
    private final AuctionMenu owner;
    private final AuctionManager auctions;
    private final AuctionMenuConfig config;
    private final AuctionMenuItems items;

    AuctionStatsMenu(AuctionMenu owner, AuctionManager auctions,
                     AuctionMenuConfig config, AuctionMenuItems items) {
        this.owner = owner;
        this.auctions = auctions;
        this.config = config;
        this.items = items;
    }

    void render(Player player) {
        try {
            AuctionStats stats = auctions.repository().stats(player.getUniqueId());
            int rows = config.rows("menus.stats.rows", 3);
            int size = rows * 9;
            Gui gui = new Gui(rows, config.component("menus.stats.title",
                    "<dark_gray>Deine AH-Statistiken</dark_gray>"));
            owner.decorateBottomRow(gui);

            gui.item(config.slot("menus.stats.listings-created-slot", 4, size),
                    items.stats("listings-created", Material.WRITABLE_BOOK,
                            "<gold>Erstellte Angebote</gold>", MenuFormat.integer(stats.listingsCreated())));
            gui.item(config.slot("menus.stats.earned-slot", 10, size),
                    items.stats("earned", Material.GOLD_INGOT,
                            "<green>Verdient</green>", MenuFormat.integer(stats.coinsEarned())));
            gui.item(config.slot("menus.stats.spent-slot", 12, size),
                    items.stats("spent", Material.REDSTONE,
                            "<red>Ausgegeben</red>", MenuFormat.integer(stats.coinsSpent())));
            gui.item(config.slot("menus.stats.active-slot", 13, size),
                    items.stats("active", Material.PAPER,
                            "<green>Aktive Angebote</green>",
                            auctions.activeListings(player.getUniqueId()) + "/"
                                    + items.displayLimit(auctions.listingLimit(player))));
            gui.item(config.slot("menus.stats.sold-slot", 14, size),
                    items.stats("sold", Material.CHEST,
                            "<yellow>Verkaufte Items</yellow>", MenuFormat.integer(stats.itemsSold())));
            gui.item(config.slot("menus.stats.bought-slot", 16, size),
                    items.stats("bought", Material.HOPPER,
                            "<aqua>Gekaufte Items</aqua>", MenuFormat.integer(stats.itemsBought())));
            gui.button(config.slot("menus.stats.back-slot", 22, size), GuiButton.of(
                    items.button("back", Material.ARROW, "<yellow>Zurück</yellow>",
                            List.of("<gray>Zum vorherigen Menü</gray>")), event -> owner.back(player)));
            gui.open(player);
            owner.play(player, "open");
        } catch (Exception exception) {
            owner.fail(player, "AH-Statistiken", exception);
        }
    }
}
