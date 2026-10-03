package de.walahi.novosmp.auction;

import de.walahi.smpcore.gui.Gui;
import de.walahi.smpcore.gui.GuiButton;
import de.walahi.smpcore.gui.PageWindow;
import de.walahi.smpcore.gui.SlotLayout;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/** Player-facing purchase, sale, expiry and cancellation history. */
final class AuctionHistoryMenu {
    private static final List<Integer> DEFAULT_CONTENT_SLOTS = SlotLayout.range(0, 44);

    private final AuctionMenu owner;
    private final AuctionManager auctions;
    private final AuctionMenuConfig config;
    private final AuctionMenuItems items;

    AuctionHistoryMenu(AuctionMenu owner, AuctionManager auctions,
                       AuctionMenuConfig config, AuctionMenuItems items) {
        this.owner = owner;
        this.auctions = auctions;
        this.config = config;
        this.items = items;
    }

    void render(Player player, int requestedPage) {
        try {
            int rows = config.rows("menus.history.rows", 6);
            int size = rows * 9;
            List<Integer> slots = config.slots("menus.history.content-slots",
                    DEFAULT_CONTENT_SLOTS, size);
            int total = auctions.repository().countHistory(player.getUniqueId());
            PageWindow page = PageWindow.of(requestedPage, total, slots.size());
            Gui gui = new Gui(rows, config.component("menus.history.title",
                    "<dark_gray>AH-Verlauf • Seite %page%/%pages%</dark_gray>", page.placeholders()));

            List<AuctionHistoryEntry> entries = auctions.repository()
                    .historyEntries(player.getUniqueId(), slots.size(), page.offset());
            if (entries.isEmpty()) {
                gui.item(config.slot("menus.history.empty-slot", 22, size),
                        items.empty("menus.history.empty", Material.WRITABLE_BOOK,
                                "<yellow>Noch kein Verlauf</yellow>",
                                List.of("<gray>Käufe und Verkäufe erscheinen später hier.</gray>")));
            }
            for (int index = 0; index < entries.size() && index < slots.size(); index++) {
                gui.item(slots.get(index), items.history(entries.get(index)));
            }

            owner.decorateBottomRow(gui);

            gui.button(config.slot("menus.history.back-slot", 45, size), GuiButton.of(
                    items.button("back", Material.ARROW, "<yellow>Zurück</yellow>",
                            List.of("<gray>Zum vorherigen Menü</gray>")), event -> owner.back(player)));
            if (page.hasPrevious()) {
                gui.button(config.slot("menus.history.previous-slot", 48, size), GuiButton.of(
                        items.button("previous", Material.ARROW, "<yellow>Vorherige Seite</yellow>", List.of()),
                        event -> owner.replaceCurrent(player, owner.historyRoute(page.page() - 1))));
            }
            gui.button(config.slot("menus.history.refresh-slot", 49, size), GuiButton.of(
                    items.button("refresh", Material.BLAZE_POWDER, "<green>Aktualisieren</green>",
                            List.of("<gray>%status%</gray>"), Map.of("%status%", "Einträge: " + total)),
                    event -> owner.replaceCurrent(player, owner.historyRoute(page.page()))));
            if (page.hasNext()) {
                gui.button(config.slot("menus.history.next-slot", 50, size), GuiButton.of(
                        items.button("next", Material.ARROW, "<yellow>Nächste Seite</yellow>", List.of()),
                        event -> owner.replaceCurrent(player, owner.historyRoute(page.page() + 1))));
            }
            gui.button(config.slot("menus.history.stats-slot", 53, size), GuiButton.of(
                    items.button("stats", Material.BOOK, "<aqua>AH-Statistiken</aqua>",
                            List.of("<gray>Deine Marktübersicht</gray>")),
                    event -> owner.openChild(player, owner.statsRoute())));
            gui.open(player);
            owner.play(player, "open");
        } catch (Exception exception) {
            owner.fail(player, "AH-Verlauf", exception);
        }
    }

}
