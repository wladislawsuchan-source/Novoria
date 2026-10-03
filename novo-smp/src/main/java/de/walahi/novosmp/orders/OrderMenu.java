package de.walahi.novosmp.orders;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.gui.GuiNavigator;
import de.walahi.smpcore.messages.MessageChannel;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Thin facade and navigation coordinator for the order UI.
 * Rendering, catalogue creation, delivery processing and transient state live in dedicated classes.
 */
public final class OrderMenu implements Listener {
    private final SMPCorePlugin plugin;
    private final OrderManager orders;
    private final GuiNavigator navigator;
    private final OrderMenuState state;
    private final OrderMenuConfig config;
    private final OrderOverviewMenu overviewMenu;
    private final OrderCollectMenu collectMenu;
    private final OrderCreationMenu creationMenu;
    private final OrderDeliveryListener deliveryListener;

    public OrderMenu(SMPCorePlugin plugin, OrderManager orders) {
        this.plugin = plugin;
        this.orders = orders;
        this.navigator = new GuiNavigator(plugin);
        this.state = new OrderMenuState();
        this.config = new OrderMenuConfig(plugin);

        OrderMenuItems items = new OrderMenuItems(config);
        OrderSelectionCatalog catalog = new OrderSelectionCatalog(orders.itemPolicy());
        OrderSignInput signInput = new OrderSignInput(plugin, this, navigator);
        this.overviewMenu = new OrderOverviewMenu(this, orders, config, items);
        this.collectMenu = new OrderCollectMenu(this, orders, config, items);
        this.creationMenu = new OrderCreationMenu(this, orders, state, catalog, config, items, signInput);
        this.deliveryListener = new OrderDeliveryListener(this, orders, config, items);

        Bukkit.getPluginManager().registerEvents(navigator, plugin);
        Bukkit.getPluginManager().registerEvents(signInput, plugin);
        Bukkit.getPluginManager().registerEvents(deliveryListener, plugin);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void open(Player player) {
        state.removeDraft(player.getUniqueId());
        navigator.resetAndOpen(player, mainRoute());
    }

    public void openOwnOrders(Player player) {
        state.removeDraft(player.getUniqueId());
        navigator.seedAndOpen(player, List.of(mainRoute(), ownRoute()));
    }

    public void openCollect(Player player) {
        state.removeDraft(player.getUniqueId());
        navigator.seedAndOpen(player, List.of(mainRoute(), ownRoute(), collectRoute()));
    }

    void setDraftAmount(Player player, int amount) {
        OrderDraft draft = state.draft(player.getUniqueId());
        if (draft != null) draft.amount(amount);
    }

    void setDraftPrice(Player player, long pricePerItem) {
        OrderDraft draft = state.draft(player.getUniqueId());
        if (draft != null) draft.pricePerItem(pricePerItem);
    }

    void setSearch(Player player, String query) {
        state.search(player.getUniqueId(), query);
    }

    void reopenMain(Player player) {
        replaceCurrent(player, mainRoute());
    }

    void reopenItemSelection(Player player) {
        replaceCurrent(player, selectionRoute(state.page(player.getUniqueId())));
    }

    void reopenCreationEditor(Player player) {
        if (state.draft(player.getUniqueId()) == null) {
            openOwnOrders(player);
            return;
        }
        replaceCurrent(player, editorRoute());
    }

    int minimumAmount() {
        return orders.minimumAmount();
    }

    int maximumAmount() {
        return orders.maximumAmount();
    }

    long minimumPricePerItem() {
        return orders.minimumPricePerItem();
    }

    long maximumPricePerItem() {
        return orders.maximumPricePerItem();
    }

    GuiNavigator.Destination mainRoute() {
        return new GuiNavigator.Destination(overviewMenu::renderMain);
    }

    GuiNavigator.Destination ownRoute() {
        return new GuiNavigator.Destination(overviewMenu::renderOwn);
    }

    GuiNavigator.Destination selectionRoute(int page) {
        return new GuiNavigator.Destination(player -> creationMenu.renderSelection(player, page));
    }

    GuiNavigator.Destination editorRoute() {
        return new GuiNavigator.Destination(creationMenu::renderEditor);
    }

    GuiNavigator.Destination detailRoute(UUID orderId) {
        return new GuiNavigator.Destination(player -> overviewMenu.renderDetails(player, orderId));
    }

    GuiNavigator.Destination collectRoute() {
        return new GuiNavigator.Destination(collectMenu::render);
    }

    void openMain(Player player) {
        navigator.resetAndOpen(player, mainRoute());
    }

    void openDelivery(Player player, UUID orderId) {
        deliveryListener.open(player, orderId);
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

    void setExternalInput(Player player, boolean active) {
        navigator.setExternalInput(player, active);
    }

    void sendDynamic(Player player, String miniMessage) {
        plugin.messages().sendConfigured(player, plugin.configs().orders(),
                "order-ui.messages.dynamic", MessageChannel.ORDER, "%message%", "%message%", miniMessage);
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
        plugin.messages().sendConfigured(player, plugin.configs().orders(),
                "order-ui.messages." + key, MessageChannel.ORDER, fallback, replacements);
    }

    /** Compatibility entry point used by the virtual sign input. */
    void sendMessage(Player player, String miniMessage) {
        sendDynamic(player, miniMessage);
    }

    void sendConfigured(Player player, String path, String fallback) {
        plugin.messages().sendConfiguredAuto(player, plugin.configs().main(), path, fallback);
    }

    void fail(Player player, String area, Exception exception) {
        plugin.getLogger().warning(area + " konnte nicht geöffnet werden: " + exception.getMessage());
        sendMessage(player, "database-error", "<red>Das Order-System ist momentan nicht verfügbar.</red>");
    }

    void warn(String message) {
        plugin.getLogger().warning(message);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        state.clear(event.getPlayer().getUniqueId());
        navigator.clear(event.getPlayer());
    }
}
