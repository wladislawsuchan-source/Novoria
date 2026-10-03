package de.walahi.novosmp.orders;

import de.walahi.smpcore.gui.ItemBuilder;
import de.walahi.smpcore.gui.MenuFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Shared item rendering and formatting for all order menus. */
final class OrderMenuItems {
    private final OrderMenuConfig config;

    OrderMenuItems(OrderMenuConfig config) {
        this.config = config;
    }

    ItemStack configured(String path, Material fallbackMaterial, String fallbackName,
                         List<String> fallbackLore) {
        return config.item(path, fallbackMaterial, fallbackName, fallbackLore);
    }

    ItemStack configured(String path, Material fallbackMaterial, String fallbackName,
                         List<String> fallbackLore, Map<String, String> placeholders) {
        return config.item(path, fallbackMaterial, fallbackName, fallbackLore, placeholders);
    }

    ItemStack publicOrder(OrderListing listing, java.util.UUID viewerId) {
        ItemStack stack = listedStack(listing);
        Map<String, String> placeholders = listingPlaceholders(listing);
        List<Component> lore = new ArrayList<>(config.lore("icons.public-order.lore", List.of(
                "<gray>Auftraggeber: <white>%owner%</white></gray>",
                "<gray>Noch benötigt: <yellow>%remaining%/%requested%</yellow></gray>",
                "<gray>Stückpreis: <gold>%price% Coins</gold></gray>",
                "<dark_gray>Läuft ab in: %expires%</dark_gray>",
                ""
        ), placeholders));
        String extraPath = listing.ownerId().equals(viewerId)
                ? "icons.public-order.own-extra"
                : "icons.public-order.deliver-extra";
        List<String> fallbackExtra = listing.ownerId().equals(viewerId)
                ? List.of("<red>Dein eigener Auftrag</red>",
                "<dark_gray>Du kannst ihn dir nicht selbst liefern.</dark_gray>")
                : List.of("<green>Klicke, um Gegenstände zu liefern.</green>");
        lore.addAll(config.lore(extraPath, fallbackExtra, placeholders));
        return ItemBuilder.from(stack).lore(lore).build();
    }

    ItemStack ownedOrder(OrderListing listing) {
        ItemStack stack = listedStack(listing);
        return ItemBuilder.from(stack)
                .lore(config.lore("icons.owned-order.lore", List.of(
                        "<gray>Geliefert: <yellow>%delivered%/%requested%</yellow></gray>",
                        "<gray>Stückpreis: <gold>%price% Coins</gold></gray>",
                        "<gray>Reserviert: <gold>%escrow% Coins</gold></gray>",
                        "<dark_gray>Läuft ab in: %expires%</dark_gray>"
                ), listingPlaceholders(listing)))
                .build();
    }

    ItemStack orderDetails(OrderListing listing) {
        ItemStack stack = listedStack(listing);
        return ItemBuilder.from(stack)
                .name(Component.text(readableName(listing.item().getType()), NamedTextColor.WHITE))
                .lore(config.lore("icons.details.lore", List.of(
                        "<gray>Noch benötigt: <yellow>%remaining%/%requested%</yellow></gray>",
                        "<gray>Noch offen: <yellow>%remaining%</yellow></gray>",
                        "<gray>Stückpreis: <gold>%price% Coins</gold></gray>",
                        "<gray>Rückzahlung bei Abbruch: <gold>%escrow% Coins</gold></gray>",
                        "<dark_gray>Läuft ab in: %expires%</dark_gray>"
                ), listingPlaceholders(listing)))
                .build();
    }

    ItemStack collect(OrderCollectEntry entry) {
        ItemStack stack = entry.item().clone();
        Map<String, String> placeholders = Map.of(
                "%amount%", format(stack.getAmount()),
                "%age%", age(entry.createdAt())
        );
        return ItemBuilder.from(stack)
                .lore(config.lore("icons.collect.lore", List.of(
                        "<gray>Menge: <yellow>%amount%</yellow></gray>",
                        "<gray>Seit: <dark_gray>%age%</dark_gray></gray>",
                        "",
                        "<green>Klicke zum Abholen.</green>"
                ), placeholders))
                .build();
    }

    ItemStack selection(OrderSelectionCatalog.Entry entry) {
        ItemStack icon = entry.stack().clone();
        icon.setAmount(1);
        ItemBuilder builder = ItemBuilder.from(icon);
        if (icon.getType() != Material.ENCHANTED_BOOK && icon.getType() != Material.OMINOUS_BOTTLE) {
            builder.name(Component.text(entry.displayName(), NamedTextColor.WHITE));
        }
        String path;
        List<String> fallback;
        if (icon.getType() == Material.ENCHANTED_BOOK) {
            path = "menus.selection.item-lore.enchanted-book";
            fallback = List.of("<gray>Drücke im nächsten Menü auf das Buch,</gray>",
                    "<gray>um die Stufe zu ändern.</gray>");
        } else if (icon.getType() == Material.OMINOUS_BOTTLE) {
            path = "menus.selection.item-lore.ominous-bottle";
            fallback = List.of("<gray>Drücke im nächsten Menü auf die Flasche,</gray>",
                    "<gray>um die Stufe zu ändern.</gray>");
        } else {
            path = "menus.selection.item-lore.normal";
            fallback = List.of("<gray>Klicke, um dieses Item zu bestellen.</gray>");
        }
        List<Component> lore = new ArrayList<>();
        if (icon.getItemMeta().lore() != null) lore.addAll(icon.getItemMeta().lore());
        lore.addAll(config.lore(path, fallback));
        return builder.lore(lore).build();
    }

    ItemStack selected(OrderDraft draft, boolean supportsLevelSelection) {
        ItemStack shown = draft.item().clone();
        List<Component> lore = new ArrayList<>(config.lore("icons.selected.lore",
                List.of("<gray>Ausgewählter Gegenstand</gray>")));
        if (supportsLevelSelection) {
            lore.add(config.component("icons.selected.level-line",
                    "<aqua>Klicke, um die Stufe zu ändern.</aqua>"));
        }
        if (draft.amount() > 0) {
            lore.add(config.component("icons.selected.amount-line",
                    "<yellow>Menge: %amount%</yellow>",
                    Map.of("%amount%", format(draft.amount()))));
        }
        if (draft.pricePerItem() > 0) {
            lore.add(config.component("icons.selected.price-line",
                    "<gold>Stückpreis: %price% Coins</gold>",
                    Map.of("%price%", format(draft.pricePerItem()))));
        }
        ItemBuilder builder = ItemBuilder.from(shown).lore(lore);
        if (shown.getType() != Material.ENCHANTED_BOOK && shown.getType() != Material.OMINOUS_BOTTLE) {
            builder.name(Component.text(readableName(shown.getType()), NamedTextColor.WHITE));
        }
        return builder.build();
    }

    String displayLimit(int limit) {
        return limit == Integer.MAX_VALUE ? "∞" : Integer.toString(limit);
    }

    String format(long value) {
        return MenuFormat.integer(value);
    }

    String remaining(Instant expiresAt) {
        long totalMinutes = Math.max(0, Duration.between(Instant.now(), expiresAt).toMinutes());
        long days = totalMinutes / 1440L;
        long hours = (totalMinutes % 1440L) / 60L;
        long minutes = totalMinutes % 60L;
        return days + "d " + hours + "h " + minutes + "m";
    }

    String age(Instant createdAt) {
        long minutes = Math.max(0, Duration.between(createdAt, Instant.now()).toMinutes());
        if (minutes < 1) return "gerade eben";
        if (minutes < 60) return "vor " + minutes + " Min.";
        long hours = minutes / 60;
        if (hours < 24) return "vor " + hours + " Std.";
        return "vor " + (hours / 24) + " Tagen";
    }

    static String readableName(Material material) {
        String[] parts = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return result.toString();
    }

    private ItemStack listedStack(OrderListing listing) {
        ItemStack stack = listing.item().clone();
        stack.setAmount(Math.min(Math.max(1, listing.remainingAmount()), stack.getMaxStackSize()));
        return stack;
    }

    private Map<String, String> listingPlaceholders(OrderListing listing) {
        int delivered = listing.requestedAmount() - listing.remainingAmount();
        return Map.of(
                "%owner%", listing.ownerName(),
                "%remaining%", format(listing.remainingAmount()),
                "%requested%", format(listing.requestedAmount()),
                "%delivered%", format(delivered),
                "%price%", format(listing.pricePerItem()),
                "%escrow%", format(listing.escrowRemaining()),
                "%expires%", remaining(listing.expiresAt())
        );
    }
}
