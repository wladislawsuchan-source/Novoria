package de.walahi.novosmp.auction;

import de.walahi.smpcore.gui.Gui;
import de.walahi.smpcore.gui.GuiButton;
import de.walahi.smpcore.gui.PageWindow;
import de.walahi.smpcore.gui.SlotLayout;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/** Durable delivery view for expired, cancelled and fallback auction items. */
final class AuctionCollectMenu {
    private static final List<Integer> DEFAULT_CONTENT_SLOTS = SlotLayout.range(0, 44);

    private final AuctionMenu owner;
    private final AuctionManager auctions;
    private final AuctionMenuConfig config;
    private final AuctionMenuItems items;

    AuctionCollectMenu(AuctionMenu owner, AuctionManager auctions,
                       AuctionMenuConfig config, AuctionMenuItems items) {
        this.owner = owner;
        this.auctions = auctions;
        this.config = config;
        this.items = items;
    }

    void render(Player player, int requestedPage) {
        try {
            int rows = config.rows("menus.collect.rows", 6);
            int size = rows * 9;
            List<Integer> slots = config.slots("menus.collect.content-slots",
                    DEFAULT_CONTENT_SLOTS, size);
            int total = auctions.pendingCollect(player.getUniqueId());
            PageWindow page = PageWindow.of(requestedPage, total, slots.size());
            Gui gui = new Gui(rows, config.component("menus.collect.title",
                    "<dark_gray>Collect • Seite %page%/%pages%</dark_gray>", page.placeholders()));

            List<AuctionCollectEntry> entries = auctions.repository()
                    .collectEntries(player.getUniqueId(), slots.size(), page.offset());
            if (entries.isEmpty()) {
                gui.item(config.slot("menus.collect.empty-slot", 22, size),
                        items.empty("menus.collect.empty", Material.HOPPER,
                                "<yellow>Collect ist leer</yellow>",
                                List.of("<gray>Hier landen abgelaufene oder nicht zustellbare Items.</gray>")));
            }
            for (int index = 0; index < entries.size() && index < slots.size(); index++) {
                AuctionCollectEntry entry = entries.get(index);
                gui.button(slots.get(index), GuiButton.of(items.collect(entry),
                        event -> owner.collect(player, entry, page.page())));
            }

            owner.decorateBottomRow(gui);

            gui.button(config.slot("menus.collect.back-slot", 45, size), GuiButton.of(
                    items.button("back", Material.ARROW, "<yellow>Zurück</yellow>",
                            List.of("<gray>Zum vorherigen Menü</gray>")), event -> owner.back(player)));
            if (page.hasPrevious()) {
                gui.button(config.slot("menus.collect.previous-slot", 48, size), GuiButton.of(
                        items.button("previous", Material.ARROW, "<yellow>Vorherige Seite</yellow>", List.of()),
                        event -> owner.replaceCurrent(player, owner.collectRoute(page.page() - 1))));
            }
            gui.button(config.slot("menus.collect.refresh-slot", 49, size), GuiButton.of(
                    items.button("refresh", Material.BLAZE_POWDER, "<green>Aktualisieren</green>",
                            List.of("<gray>%status%</gray>"), Map.of("%status%", "Abzuholen: " + total)),
                    event -> owner.replaceCurrent(player, owner.collectRoute(page.page()))));
            if (page.hasNext()) {
                gui.button(config.slot("menus.collect.next-slot", 50, size), GuiButton.of(
                        items.button("next", Material.ARROW, "<yellow>Nächste Seite</yellow>", List.of()),
                        event -> owner.replaceCurrent(player, owner.collectRoute(page.page() + 1))));
            }
            if (total > 0) {
                gui.button(config.slot("menus.collect.collect-all-slot", 53, size), GuiButton.of(
                        items.button("collect-all", Material.CHEST_MINECART,
                                "<green>Alles einsammeln</green>",
                                List.of("<gray>Sammelt so viele Items wie möglich ein.</gray>")),
                        event -> owner.collectAll(player, Math.max(1, slots.size()))));
            }
            gui.open(player);
            owner.play(player, "open");
        } catch (Exception exception) {
            owner.fail(player, "AH-Collect", exception);
        }
    }

}
