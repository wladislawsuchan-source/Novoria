package de.walahi.novosmp.orders;

import de.walahi.smpcore.gui.Gui;
import de.walahi.smpcore.gui.GuiButton;
import de.walahi.smpcore.gui.PageSlice;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.OminousBottleMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Item selection and creation editor for new orders. */
final class OrderCreationMenu {
    private final OrderMenu owner;
    private final OrderManager orders;
    private final OrderMenuState state;
    private final OrderSelectionCatalog catalog;
    private final OrderMenuConfig config;
    private final OrderMenuItems items;
    private final OrderSignInput signInput;

    OrderCreationMenu(OrderMenu owner, OrderManager orders, OrderMenuState state,
                      OrderSelectionCatalog catalog, OrderMenuConfig config,
                      OrderMenuItems items, OrderSignInput signInput) {
        this.owner = owner;
        this.orders = orders;
        this.state = state;
        this.catalog = catalog;
        this.config = config;
        this.items = items;
        this.signInput = signInput;
    }

    void renderSelection(Player player, int requestedPage) {
        UUID viewerId = player.getUniqueId();
        OrderSelectionCatalog.Filter filter = state.filter(viewerId);
        OrderSelectionCatalog.Sort sort = state.sort(viewerId);
        String search = state.search(viewerId);
        int rows = config.rows("menus.selection.rows", 6);
        int size = rows * 9;
        int pageSize = Math.min(size, Math.min(45,
                Math.max(1, config.integer("menus.selection.page-size", 45))));
        PageSlice<OrderSelectionCatalog.Entry> page = PageSlice.of(
                catalog.visible(filter, sort, search), requestedPage, pageSize);
        state.page(viewerId, page.page());
        Map<String, String> titlePlaceholders = Map.of(
                "%page%", Integer.toString(page.page() + 1),
                "%pages%", Integer.toString(page.pageCount())
        );
        Gui gui = new Gui(rows, config.component("menus.selection.title",
                "<dark_gray>Order erstellen • Items %page%/%pages%</dark_gray>", titlePlaceholders));
        gui.filler(config.item("filler", Material.BLACK_STAINED_GLASS_PANE, " ", List.of()));

        if (page.entries().isEmpty()) {
            gui.item(config.slot("menus.selection.empty-slot", 22, size),
                    config.item("menus.selection.empty", Material.BARRIER,
                            "<red>Keine passenden Items</red>",
                            List.of("<gray>Ändere Filter oder Suche.</gray>")));
        } else {
            for (int slot = 0; slot < page.entries().size() && slot < pageSize; slot++) {
                OrderSelectionCatalog.Entry entry = page.entries().get(slot);
                gui.button(slot, GuiButton.of(items.selection(entry),
                        event -> selectItem(player, entry.stack())));
            }
        }

        int filterSlot = config.slot("menus.selection.filter-slot", 48, size);
        gui.button(filterSlot, GuiButton.of(filterItem(filter), event -> {
            state.filter(viewerId, filter.next());
            state.page(viewerId, 0);
            owner.replaceCurrent(player, owner.selectionRoute(0));
        }));

        if (page.hasPrevious()) {
            int previousSlot = config.slot("menus.selection.previous-slot", 45, size);
            gui.button(previousSlot, GuiButton.of(config.item("menus.selection.previous", Material.ARROW,
                    "<yellow>Vorherige Seite</yellow>", List.of()),
                    event -> owner.replaceCurrent(player, owner.selectionRoute(page.page() - 1))));
        }

        int sortSlot = config.slot("menus.selection.sort-slot", 49, size);
        gui.button(sortSlot, GuiButton.of(sortItem(sort), event -> {
            state.sort(viewerId, sort.next());
            state.page(viewerId, 0);
            owner.replaceCurrent(player, owner.selectionRoute(0));
        }));

        if (page.hasNext()) {
            int nextSlot = config.slot("menus.selection.next-slot", 53, size);
            gui.button(nextSlot, GuiButton.of(config.item("menus.selection.next", Material.ARROW,
                    "<yellow>Nächste Seite</yellow>", List.of()),
                    event -> owner.replaceCurrent(player, owner.selectionRoute(page.page() + 1))));
        }

        String searchLabel = search.isBlank()
                ? config.string("labels.no-search", "Keine Suche aktiv")
                : config.string("labels.search-prefix", "Suche: %search%").replace("%search%", search);
        int searchSlot = config.slot("menus.selection.search-slot", 50, size);
        gui.button(searchSlot, GuiButton.of(config.item("menus.selection.search", Material.OAK_SIGN,
                "<yellow>Suche</yellow>", List.of(
                        "<yellow>%search%</yellow>",
                        "<gray>Klicke und gib den Itemnamen ein.</gray>",
                        "<dark_gray>Leere Eingabe setzt die Suche zurück.</dark_gray>"
                ), Map.of("%search%", searchLabel)), event -> signInput.openSearch(player)));
        gui.open(player);
    }

    void renderEditor(Player player) {
        OrderDraft draft = state.draft(player.getUniqueId());
        if (draft == null) {
            owner.openOwnOrders(player);
            return;
        }

        int rows = config.rows("menus.editor.rows", 3);
        int size = rows * 9;
        Gui gui = new Gui(rows, config.component("menus.editor.title",
                "<dark_gray>Neuen Auftrag erstellen</dark_gray>"));
        gui.filler(config.item("filler", Material.BLACK_STAINED_GLASS_PANE, " ", List.of()));

        gui.button(config.slot("menus.editor.cancel-slot", 10, size), GuiButton.of(
                config.item("menus.editor.cancel", Material.RED_STAINED_GLASS_PANE,
                        "<red>Abbrechen</red>", List.of("<gray>Auftrag verwerfen</gray>")),
                event -> cancelCreation(player)));

        int itemSlot = config.slot("menus.editor.item-slot", 12, size);
        if (supportsLevelSelection(draft.item())) {
            gui.button(itemSlot, GuiButton.of(items.selected(draft, true),
                    event -> cycleSelectedLevel(player, draft)));
        } else {
            gui.item(itemSlot, items.selected(draft, false));
        }

        String amountStatus = draft.amount() > 0
                ? "<gray>Menge: <yellow>" + items.format(draft.amount()) + "</yellow></gray>"
                : config.string("labels.not-set", "<red>Noch nicht festgelegt</red>");
        List<Component> amountLore = config.lore("menus.editor.amount.lore", List.of(
                "%status%",
                "<dark_gray>Klicke, um die Menge einzugeben.</dark_gray>"
        ), Map.of("%status%", amountStatus));
        gui.button(config.slot("menus.editor.amount-slot", 13, size), GuiButton.of(
                config.items().builder(config.material("menus.editor.amount.material", Material.CHEST),
                        config.string("menus.editor.amount.name", "<gold>Menge</gold>"), List.of())
                        .lore(amountLore).build(), event -> signInput.openAmount(player)));

        long minimumPrice = owner.minimumPricePerItem();
        String priceStatus = draft.pricePerItem() > 0
                ? "<gray>Stückpreis: <gold>" + items.format(draft.pricePerItem()) + " Coins</gold></gray>"
                : config.string("labels.not-set", "<red>Noch nicht festgelegt</red>");
        List<Component> priceLore = config.lore("menus.editor.price.lore", List.of(
                "%status%",
                "<dark_gray>Mindestpreis: %minimum% Coins pro Stück</dark_gray>",
                "<dark_gray>Klicke, um den Stückpreis einzugeben.</dark_gray>"
        ), Map.of(
                "%status%", priceStatus,
                "%minimum%", items.format(minimumPrice)
        ));
        gui.button(config.slot("menus.editor.price-slot", 14, size), GuiButton.of(
                config.items().builder(config.material("menus.editor.price.material", Material.EMERALD),
                        config.string("menus.editor.price.name", "<green>Preis pro Stück</green>"), List.of())
                        .lore(priceLore).build(), event -> signInput.openPrice(player)));

        List<Component> confirmLore = draft.complete(minimumPrice)
                ? config.lore("menus.editor.confirm.complete-lore", List.of(
                        "<gray>Gesamtpreis: <gold>%total% Coins</gold></gray>",
                        "<green>Klicke, um den Auftrag zu erstellen.</green>"
                ), Map.of("%total%", items.format(safeTotal(draft))))
                : config.lore("menus.editor.confirm.incomplete-lore",
                        List.of("<red>Lege zuerst Menge und Stückpreis fest.</red>"));
        gui.button(config.slot("menus.editor.confirm-slot", 16, size), GuiButton.of(
                config.items().builder(config.material("menus.editor.confirm.material", Material.LIME_STAINED_GLASS_PANE),
                        config.string("menus.editor.confirm.name", "<green>Bestätigen</green>"), List.of())
                        .lore(confirmLore).build(), event -> confirmCreation(player)));
        gui.open(player);
    }

    private ItemStack filterItem(OrderSelectionCatalog.Filter active) {
        List<Component> lore = new ArrayList<>();
        for (OrderSelectionCatalog.Filter filter : OrderSelectionCatalog.Filter.values()) {
            boolean selected = filter == active;
            String displayName = config.string("filters." + filter.name().toLowerCase(java.util.Locale.ROOT),
                    filter.displayName());
            lore.add(Component.text((selected ? "▣ " : "□ ") + displayName,
                    selected ? NamedTextColor.AQUA : NamedTextColor.GRAY));
        }
        return config.items().builder(config.material("menus.selection.filter.material", Material.HOPPER),
                config.string("menus.selection.filter.name", "<aqua>Filter</aqua>"), List.of())
                .lore(lore).build();
    }

    private ItemStack sortItem(OrderSelectionCatalog.Sort active) {
        List<Component> lore = new ArrayList<>();
        for (OrderSelectionCatalog.Sort sort : OrderSelectionCatalog.Sort.values()) {
            boolean selected = sort == active;
            String displayName = config.string("sorts." + sort.name().toLowerCase(java.util.Locale.ROOT),
                    sort.displayName());
            lore.add(Component.text((selected ? "▣ " : "□ ") + displayName,
                    selected ? NamedTextColor.AQUA : NamedTextColor.GRAY));
        }
        return config.items().builder(config.material("menus.selection.sort.material", Material.HOPPER_MINECART),
                config.string("menus.selection.sort.name", "<green>Sortieren</green>"), List.of())
                .lore(lore).build();
    }

    private void selectItem(Player player, ItemStack selected) {
        state.createDraft(player.getUniqueId(), selected);
        owner.openChild(player, owner.editorRoute());
    }

    private boolean supportsLevelSelection(ItemStack stack) {
        if (stack == null) return false;
        if (stack.getType() == Material.ENCHANTED_BOOK
                && stack.getItemMeta() instanceof EnchantmentStorageMeta meta) {
            return meta.getStoredEnchants().size() == 1;
        }
        return stack.getType() == Material.OMINOUS_BOTTLE;
    }

    private void cycleSelectedLevel(Player player, OrderDraft draft) {
        if (draft.item().getType() == Material.ENCHANTED_BOOK
                && draft.item().getItemMeta() instanceof EnchantmentStorageMeta meta
                && meta.getStoredEnchants().size() == 1) {
            Map.Entry<Enchantment, Integer> stored = meta.getStoredEnchants().entrySet().iterator().next();
            Enchantment enchantment = stored.getKey();
            int maxLevel = Math.max(1, enchantment.getMaxLevel());
            int nextLevel = stored.getValue() >= maxLevel ? 1 : stored.getValue() + 1;
            meta.removeStoredEnchant(enchantment);
            meta.addStoredEnchant(enchantment, nextLevel, true);
            draft.item().setItemMeta(meta);
        } else if (draft.item().getType() == Material.OMINOUS_BOTTLE) {
            cycleOminousBottleLevel(draft.item());
        }
        owner.replaceCurrent(player, owner.editorRoute());
    }

    private void cycleOminousBottleLevel(ItemStack bottle) {
        if (!(bottle.getItemMeta() instanceof OminousBottleMeta meta)) {
            owner.warn("Ominous Bottle besitzt keine OminousBottleMeta.");
            return;
        }
        int amplifier = meta.hasAmplifier() ? meta.getAmplifier() : 0;
        meta.setAmplifier(amplifier >= 4 ? 0 : amplifier + 1);
        bottle.setItemMeta(meta);
    }

    private void cancelCreation(Player player) {
        state.removeDraft(player.getUniqueId());
        owner.openOwnOrders(player);
    }

    private void confirmCreation(Player player) {
        OrderDraft draft = state.draft(player.getUniqueId());
        if (draft == null) {
            owner.sendMessage(player, "draft-missing",
                    "<red>Der Erstellungsentwurf ist nicht mehr vorhanden.</red>");
            owner.openOwnOrders(player);
            return;
        }
        long minimumPrice = owner.minimumPricePerItem();
        if (!draft.complete(minimumPrice)) {
            owner.sendMessage(player, "draft-incomplete",
                    "<red>Lege eine gültige Menge und mindestens %minimum% Coins pro Stück fest.</red>",
                    Map.of("%minimum%", items.format(minimumPrice)));
            owner.reopenCreationEditor(player);
            return;
        }

        OrderManager.CreateResult result = orders.create(player, draft.item(), draft.amount(), draft.pricePerItem());
        switch (result) {
            case SUCCESS -> owner.sendMessage(player, "create-success",
                    "<green>Dein Auftrag für <yellow>%amount%x %item%</yellow> wurde erstellt.</green>",
                    Map.of(
                            "%amount%", items.format(draft.amount()),
                            "%item%", OrderMenuItems.readableName(draft.item().getType())
                    ));
            case LIMIT_REACHED -> owner.sendMessage(player, "create-limit",
                    "<red>Du hast dein Order-Limit erreicht.</red>");
            case INSUFFICIENT_FUNDS -> owner.sendMessage(player, "create-funds",
                    "<red>Du hast nicht genug Coins für diesen Auftrag.</red>");
            case INVALID -> {
                owner.sendMessage(player, "create-invalid",
                        "<red>Die Menge ist ungültig oder der Stückpreis liegt unter %minimum% Coins.</red>",
                        Map.of("%minimum%", items.format(minimumPrice)));
                owner.reopenCreationEditor(player);
                return;
            }
            case ITEM_BLOCKED -> {
                owner.sendConfigured(player, "orders.messages.item-blocked",
                        "<red>Dieses Item ist für Aufträge gesperrt.</red>");
                owner.reopenCreationEditor(player);
                return;
            }
            case STORAGE_ERROR -> owner.sendMessage(player, "create-storage",
                    "<red>Der Auftrag konnte nicht gespeichert werden.</red>");
        }
        if (result == OrderManager.CreateResult.SUCCESS) state.removeDraft(player.getUniqueId());
        owner.openOwnOrders(player);
    }

    private long safeTotal(OrderDraft draft) {
        try {
            return Math.multiplyExact((long) draft.amount(), draft.pricePerItem());
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }
}
