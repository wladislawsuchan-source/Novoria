package de.walahi.novosmp.auction;

import de.walahi.smpcore.gui.ItemBuilder;
import de.walahi.smpcore.gui.MenuFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Creates every auction menu icon in one place. */
final class AuctionMenuItems {
    private final AuctionMenuConfig config;

    AuctionMenuItems(AuctionMenuConfig config) {
        this.config = config;
    }

    ItemStack listing(AuctionListing listing, Player viewer) {
        boolean own = listing.sellerId().equals(viewer.getUniqueId());
        Map<String, String> placeholders = Map.of(
                "%price%", MenuFormat.integer(listing.price()),
                "%seller%", listing.sellerName(),
                "%expires%", MenuFormat.duration(Duration.between(Instant.now(), listing.expiresAt())),
                "%action%", config.string(own ? "icons.listing-action-own" : "icons.listing-action-buy",
                        own ? "<yellow>Klicke für deine Angebote</yellow>" : "<green>Klicke zum Kaufen</green>")
        );
        return appendLore(listing.item(), config.lore("icons.listing-lore", List.of(
                "", "<gray>Preis: <gold>%price% Coins</gold></gray>",
                "<gray>Verkäufer: <yellow>%seller%</yellow></gray>",
                "<dark_gray>Läuft ab in: %expires%</dark_gray>", "", "%action%"
        ), placeholders));
    }

    ItemStack ownedListing(AuctionListing listing) {
        return appendLore(listing.item(), config.lore("icons.own-listing-lore", List.of(
                "", "<gray>Preis: <gold>%price% Coins</gold></gray>", "",
                "<red>Klicke, um das Angebot zurückzunehmen.</red>"
        ), Map.of("%price%", MenuFormat.integer(listing.price()))));
    }

    ItemStack collect(AuctionCollectEntry entry) {
        return appendLore(entry.item(), config.lore("icons.collect-lore", List.of(
                "", "<yellow>%reason%</yellow>", "<green>Klicke zum Einsammeln.</green>"
        ), Map.of("%reason%", config.collectReason(entry.reason()))));
    }

    ItemStack history(AuctionHistoryEntry entry) {
        String counterpart = entry.counterpartName() == null || entry.counterpartName().isBlank()
                ? "Unbekannt" : entry.counterpartName();
        String path = "icons.history." + entry.eventType();
        List<String> fallback = switch (entry.eventType()) {
            case "SOLD" -> List.of("", "<green>%counterpart% hat deinen Gegenstand gekauft.</green>",
                    "<gray>Verkauft für: <gold>%price% Coins</gold></gray>", "<dark_gray>%time%</dark_gray>");
            case "BOUGHT" -> List.of("", "<aqua>Von %counterpart% gekauft.</aqua>",
                    "<gray>Bezahlt: <gold>%price% Coins</gold></gray>", "<dark_gray>%time%</dark_gray>");
            case "EXPIRED" -> List.of("", "<yellow>Dein Angebot ist abgelaufen.</yellow>",
                    "<gray>Preis: <gold>%price% Coins</gold></gray>", "<dark_gray>%time%</dark_gray>");
            case "CANCELLED" -> List.of("", "<red>Du hast das Angebot zurückgenommen.</red>",
                    "<gray>Preis: <gold>%price% Coins</gold></gray>", "<dark_gray>%time%</dark_gray>");
            default -> {
                path = "icons.history.default";
                yield List.of("", "<gray>%event%</gray>", "<dark_gray>%time%</dark_gray>");
            }
        };
        Map<String, String> placeholders = Map.of(
                "%counterpart%", counterpart,
                "%price%", MenuFormat.integer(entry.price()),
                "%time%", relativeTime(entry.createdAt()),
                "%event%", entry.eventType()
        );
        return appendLore(entry.item(), config.lore(path, fallback, placeholders));
    }

    ItemStack sellPreview(ItemStack source, long price) {
        return appendLore(source, config.lore("icons.sell-preview-lore", List.of(
                "", "<gray>Verkaufspreis: <gold>%price% Coins</gold></gray>",
                "<gray>Anzahl: %amount%</gray>"
        ), Map.of(
                "%price%", MenuFormat.integer(price),
                "%amount%", Integer.toString(source.getAmount())
        )));
    }

    ItemStack empty(String path, Material material, String name, List<String> lore) {
        return config.item(path, material, name, lore);
    }

    ItemStack button(String path, Material material, String name, List<String> lore) {
        return config.item("buttons." + path, material, name, lore);
    }

    ItemStack button(String path, Material material, String name, List<String> lore,
                     Map<String, String> placeholders) {
        return config.item("buttons." + path, material, name, lore, placeholders);
    }

    ItemStack stats(String path, Material material, String name, String value) {
        return config.item("stats-items." + path, material, name,
                List.of("<white>%value%</white>"), Map.of("%value%", value));
    }

    String displayLimit(int value) {
        return value == Integer.MAX_VALUE ? "∞" : Integer.toString(value);
    }

    private ItemStack appendLore(ItemStack source, List<Component> additions) {
        ItemStack item = source.clone();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        List<Component> lore = meta.hasLore() && meta.lore() != null
                ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.addAll(additions);
        meta.lore(lore.stream().map(ItemBuilder::plainStyle).toList());
        item.setItemMeta(meta);
        return item;
    }

    private String relativeTime(Instant createdAt) {
        long seconds = Math.max(0L, Duration.between(createdAt, Instant.now()).getSeconds());
        if (seconds < 60L) return "Gerade eben";
        long minutes = seconds / 60L;
        if (minutes < 60L) return "Vor " + minutes + " Min.";
        long hours = minutes / 60L;
        if (hours < 24L) return "Vor " + hours + " Std.";
        long days = hours / 24L;
        if (days < 30L) return "Vor " + days + " T.";
        long months = days / 30L;
        if (months < 12L) return "Vor " + months + " Mon.";
        return "Vor " + (months / 12L) + " J.";
    }
}
