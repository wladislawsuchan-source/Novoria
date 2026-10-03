package de.walahi.novosmp.auction;

import de.walahi.novosmp.trade.TradeItemPolicy;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.gui.Gui;
import de.walahi.smpcore.gui.GuiNavigator;
import de.walahi.smpcore.gui.MenuFormat;
import de.walahi.smpcore.messages.MessageChannel;
import de.walahi.smpcore.services.EconomyService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Thin auction UI facade. Rendering and business operations live in dedicated classes. */
public final class AuctionMenu implements Listener {
    private final SMPCorePlugin plugin;
    private final AuctionManager auctions;
    private final AuctionMenuConfig config;
    private final AuctionSettings settings;
    private final AuctionSoundService sounds;
    private final GuiNavigator navigator;
    private final AuctionTransactionService transactions;
    private final AuctionSignInput signInput;
    private final AuctionOverviewMenu overviewMenu;
    private final AuctionCollectMenu collectMenu;
    private final AuctionHistoryMenu historyMenu;
    private final AuctionStatsMenu statsMenu;
    private final AuctionConfirmationMenu confirmationMenu;

    public AuctionMenu(SMPCorePlugin plugin, AuctionManager auctions, EconomyService economy,
                       TradeItemPolicy itemPolicy) {
        this.plugin = plugin;
        this.auctions = auctions;
        this.config = new AuctionMenuConfig(plugin);
        this.settings = auctions.settings();
        this.sounds = new AuctionSoundService(plugin, settings);
        this.navigator = new GuiNavigator(plugin);
        this.transactions = new AuctionTransactionService(plugin, auctions, economy, itemPolicy, settings);

        AuctionMenuItems items = new AuctionMenuItems(config);
        this.overviewMenu = new AuctionOverviewMenu(this, auctions, config, items);
        this.collectMenu = new AuctionCollectMenu(this, auctions, config, items);
        this.historyMenu = new AuctionHistoryMenu(this, auctions, config, items);
        this.statsMenu = new AuctionStatsMenu(this, auctions, config, items);
        this.confirmationMenu = new AuctionConfirmationMenu(this, auctions, config, items);
        this.signInput = new AuctionSignInput(plugin, this, navigator, config);

        Bukkit.getPluginManager().registerEvents(navigator, plugin);
        Bukkit.getPluginManager().registerEvents(signInput, plugin);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void open(Player player, int requestedPage) {
        navigator.resetAndOpen(player, mainRoute(requestedPage));
    }

    public void openListings(Player player, int requestedPage) {
        navigator.seedAndOpen(player, List.of(mainRoute(0), listingsRoute(requestedPage)));
    }

    public void openCollect(Player player, int requestedPage) {
        navigator.seedAndOpen(player, List.of(mainRoute(0), collectRoute(requestedPage)));
    }

    public void openHistory(Player player, int requestedPage) {
        navigator.seedAndOpen(player, List.of(
                mainRoute(0), listingsRoute(0), historyRoute(requestedPage)));
    }

    public void openStats(Player player) {
        navigator.seedAndOpen(player, List.of(
                mainRoute(0), listingsRoute(0), historyRoute(0), statsRoute()));
    }

    public void openSellConfirmation(Player player, long price, ItemStack snapshot) {
        if (!itemAllowed(snapshot)) {
            sendMessage(player, "item-blocked",
                    "<red>Dieses Item darf nicht im Auktionshaus angeboten werden.</red>");
            return;
        }
        navigator.openChild(player, sellRoute(price, snapshot.clone()));
    }

    public void createListingDirect(Player player, long price, ItemStack snapshot) {
        createListing(player, price, snapshot);
    }

    public long minimumPrice() {
        return settings.minimumPrice();
    }

    public long maximumPrice() {
        return settings.maximumPrice();
    }

    GuiNavigator.Destination mainRoute(int page) {
        return new GuiNavigator.Destination(player -> overviewMenu.renderMain(player, page));
    }

    GuiNavigator.Destination listingsRoute(int page) {
        return new GuiNavigator.Destination(player -> overviewMenu.renderListings(player, page));
    }

    GuiNavigator.Destination collectRoute(int page) {
        return new GuiNavigator.Destination(player -> collectMenu.render(player, page));
    }

    GuiNavigator.Destination historyRoute(int page) {
        return new GuiNavigator.Destination(player -> historyMenu.render(player, page));
    }

    GuiNavigator.Destination statsRoute() {
        return new GuiNavigator.Destination(statsMenu::render);
    }

    GuiNavigator.Destination buyRoute(UUID listingId, int sourcePage) {
        return new GuiNavigator.Destination(
                player -> confirmationMenu.renderBuy(player, listingId, sourcePage));
    }

    GuiNavigator.Destination sellRoute(long price, ItemStack snapshot) {
        ItemStack safeSnapshot = snapshot.clone();
        return new GuiNavigator.Destination(
                player -> confirmationMenu.renderSell(player, price, safeSnapshot));
    }

    void openChild(Player player, GuiNavigator.Destination destination) {
        navigator.openChild(player, destination);
    }

    void replaceCurrent(Player player, GuiNavigator.Destination destination) {
        navigator.replaceCurrent(player, destination);
    }

    void back(Player player) {
        navigator.back(player);
    }

    /** Fills only the lowest GUI row. Menu buttons placed afterwards overwrite the glass panes. */
    void decorateBottomRow(Gui gui) {
        if (gui == null || gui.size() < 9) return;
        ItemStack filler = config.item(
                "menus.bottom-filler",
                Material.BLACK_STAINED_GLASS_PANE,
                " ",
                List.of()
        );
        for (int slot = gui.size() - 9; slot < gui.size(); slot++) {
            gui.item(slot, filler.clone());
        }
    }

    void sellHint(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            sendMessage(player, "hold-item", "<red>Halte zuerst einen Gegenstand in deiner Hand.</red>");
            return;
        }
        if (!itemAllowed(hand)) {
            sendMessage(player, "item-blocked",
                    "<red>Dieses Item darf nicht im Auktionshaus angeboten werden.</red>");
            return;
        }
        try {
            if (auctions.activeListings(player.getUniqueId()) >= auctions.listingLimit(player)) {
                sendMessage(player, "limit-reached", "<red>Du hast dein Angebotslimit erreicht.</red>");
                return;
            }
            signInput.open(player, hand.clone());
        } catch (Exception exception) {
            fail(player, "AH-Verkauf", exception);
        }
    }

    void createListing(Player player, long price, ItemStack snapshot) {
        AuctionTransactionService.CreateResult result = transactions.create(player, price, snapshot);
        switch (result) {
            case SUCCESS -> {
                sendMessage(player, "create-success",
                        "<green>Du hast den Gegenstand für <gold>%price% Coins</gold> eingestellt.</green>",
                        Map.of("%price%", MenuFormat.integer(price)));
                play(player, "sell");
                navigator.replaceCurrent(player, listingsRoute(0));
            }
            case HOLD_ITEM -> sendMessage(player, "hold-item",
                    "<red>Halte zuerst einen Gegenstand in deiner Hand.</red>");
            case ITEM_CHANGED -> sendMessage(player, "item-changed",
                    "<red>Das Item in deiner Hand wurde verändert. Starte den Verkauf erneut.</red>");
            case ITEM_BLOCKED -> sendMessage(player, "item-blocked",
                    "<red>Dieses Item darf nicht im Auktionshaus angeboten werden.</red>");
            case LIMIT_REACHED -> sendMessage(player, "limit-reached",
                    "<red>Du hast dein Angebotslimit erreicht.</red>");
            case INVALID_PRICE -> sendMessage(player, "invalid-price",
                    "<red>Dieser Preis ist nicht gültig.</red>");
            case OPERATION_RUNNING -> sendMessage(player, "operation-running",
                    "<yellow>Bitte warte, bis die laufende AH-Aktion abgeschlossen ist.</yellow>");
            case STORAGE_ERROR -> sendMessage(player, "database-error",
                    "<red>Das Auktionshaus ist momentan nicht verfügbar.</red>");
        }
    }

    void cancel(Player player, UUID listingId, int page) {
        AuctionTransactionService.CancelResult result = transactions.cancel(player, listingId);
        switch (result) {
            case RETURNED_TO_INVENTORY -> sendMessage(player, "cancel-inventory",
                    "<green>Das Angebot wurde zurückgenommen und direkt in dein Inventar gelegt.</green>");
            case MOVED_TO_COLLECT -> sendMessage(player, "cancel-collect",
                    "<yellow>Dein Inventar ist voll. Das Item liegt sicher in Collect.</yellow>");
            case NOT_ACTIVE -> sendMessage(player, "listing-unavailable",
                    "<red>Dieses Angebot ist nicht mehr verfügbar.</red>");
            case OPERATION_RUNNING -> sendMessage(player, "operation-running",
                    "<yellow>Bitte warte, bis die laufende AH-Aktion abgeschlossen ist.</yellow>");
            case STORAGE_ERROR -> sendMessage(player, "cancel-failed",
                    "<red>Das Angebot konnte nicht zurückgenommen werden.</red>");
        }
        navigator.replaceCurrent(player, listingsRoute(page));
    }

    void collect(Player player, AuctionCollectEntry entry, int page) {
        AuctionTransactionService.CollectResult result = transactions.collect(player, entry);
        switch (result) {
            case SUCCESS -> {
                sendMessage(player, "collect-success", "<green>Du hast den Gegenstand eingesammelt.</green>");
                play(player, "collect");
            }
            case INVENTORY_FULL -> sendMessage(player, "inventory-full",
                    "<red>Du hast nicht genug Platz im Inventar.</red>");
            case ALREADY_COLLECTED -> sendMessage(player, "collect-already",
                    "<red>Dieses Item wurde bereits eingesammelt.</red>");
            case OPERATION_RUNNING -> sendMessage(player, "operation-running",
                    "<yellow>Bitte warte, bis die laufende AH-Aktion abgeschlossen ist.</yellow>");
            case STORAGE_ERROR -> sendMessage(player, "database-error",
                    "<red>Das Auktionshaus ist momentan nicht verfügbar.</red>");
        }
        navigator.replaceCurrent(player, collectRoute(page));
    }

    void collectAll(Player player, int batchSize) {
        AuctionTransactionService.CollectAllResult result = transactions.collectAll(player, batchSize);
        if (result.operationRunning()) {
            sendMessage(player, "operation-running",
                    "<yellow>Bitte warte, bis die laufende AH-Aktion abgeschlossen ist.</yellow>");
        } else if (result.storageError()) {
            sendMessage(player, "database-error",
                    "<red>Das Auktionshaus ist momentan nicht verfügbar.</red>");
        } else if (result.collected() <= 0) {
            sendMessage(player, "collect-all-empty",
                    "<yellow>Dein Inventar ist voll oder es gibt nichts abzuholen.</yellow>");
        } else {
            sendMessage(player, "collect-all-success",
                    "<green>Du hast <yellow>%count%</yellow> Collect-Einträge eingesammelt.</green>",
                    Map.of("%count%", Integer.toString(result.collected())));
            play(player, "collect");
        }
        navigator.replaceCurrent(player, collectRoute(0));
    }

    void buy(Player player, UUID listingId, int sourcePage) {
        AuctionTransactionService.PurchaseOutcome outcome = transactions.purchase(player, listingId);
        long price = outcome.price();
        switch (outcome.result()) {
            case SUCCESS -> {
                sendMessage(player, "purchase-success",
                        "<green>Du hast den Gegenstand für <gold>%price% Coins</gold> gekauft.</green>",
                        Map.of("%price%", MenuFormat.integer(price)));
                play(player, "buy");
            }
            case SUCCESS_COLLECT -> {
                sendMessage(player, "purchase-collect",
                        "<yellow>Der Kauf war erfolgreich. Das Item liegt sicher in Collect.</yellow>");
                play(player, "buy");
            }
            case NOT_AVAILABLE -> sendMessage(player, "listing-unavailable",
                    "<red>Dieses Angebot ist nicht mehr verfügbar.</red>");
            case OWN_LISTING -> sendMessage(player, "buy-self",
                    "<red>Du kannst dein eigenes Angebot nicht kaufen.</red>");
            case INVENTORY_FULL -> sendMessage(player, "inventory-full",
                    "<red>Du hast nicht genug Platz im Inventar.</red>");
            case INSUFFICIENT_FUNDS -> sendMessage(player, "not-enough-coins",
                    "<red>Du hast nicht genug Coins.</red>");
            case PAYMENT_FAILED, STORAGE_ERROR -> sendMessage(player, "purchase-failed",
                    "<red>Der Kauf konnte nicht abgeschlossen werden.</red>");
            case OPERATION_RUNNING -> sendMessage(player, "operation-running",
                    "<yellow>Bitte warte, bis die laufende AH-Aktion abgeschlossen ist.</yellow>");
        }
        navigator.replaceCurrent(player, mainRoute(sourcePage));
    }

    boolean itemAllowed(ItemStack item) {
        return item != null && transactions.itemAllowed(item);
    }

    void play(Player player, String key) {
        sounds.play(player, key);
    }

    void sendMessage(Player player, String key, String fallback) {
        sendMessage(player, key, fallback, Map.of());
    }

    void sendMessage(Player player, String key, String fallback, Map<String, String> placeholders) {
        String[] replacements = new String[placeholders.size() * 2];
        int index = 0;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            replacements[index++] = entry.getKey();
            replacements[index++] = entry.getValue() == null ? "" : entry.getValue();
        }
        plugin.messages().sendConfigured(player, config.messagesConfiguration(),
                "auction-ui.messages." + key, MessageChannel.AUCTION_HOUSE, fallback, replacements);
    }

    /** Compatibility entry point for external input handlers. */
    void sendDynamic(Player player, String miniMessage) {
        plugin.messages().sendConfigured(player, config.messagesConfiguration(),
                "auction-ui.messages.dynamic", MessageChannel.AUCTION_HOUSE,
                "%message%", "%message%", miniMessage);
    }

    void fail(Player player, String area, Exception exception) {
        plugin.getLogger().warning(area + ": " + exception.getMessage());
        sendMessage(player, "database-error",
                "<red>Das Auktionshaus ist momentan nicht verfügbar.</red>");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        transactions.clear(event.getPlayer().getUniqueId());
        navigator.clear(event.getPlayer());
    }
}
