package de.walahi.novosmp.commands;

import de.walahi.novosmp.auction.AuctionManager;
import de.walahi.novosmp.auction.AuctionMenu;
import de.walahi.novosmp.auction.AuctionPermissions;
import de.walahi.novosmp.auction.AuctionPriceParser;
import de.walahi.novosmp.trade.TradeItemPolicy;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import de.walahi.smpcore.gui.MenuFormat;
import de.walahi.smpcore.services.EconomyService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;

/** Auction command facade; all sale validation lives in the auction transaction service. */
public final class AuctionHouseCommand extends BaseCommand {
    private final AuctionMenu menu;

    public AuctionHouseCommand(SMPCorePlugin plugin, AuctionManager auctions,
                               EconomyService economy, TradeItemPolicy itemPolicy) {
        super(plugin);
        this.menu = new AuctionMenu(plugin, auctions, economy, itemPolicy);
    }

    @Override
    protected String permission() {
        return AuctionPermissions.USE;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            return messageOrDefault(sender, "auction-house.messages.player-only",
                    "<dark_gray>[<gold>AH</gold>]</dark_gray> <red>Nur für Spieler.</red>");
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("open")) {
            menu.open(player, 0);
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "sell" -> sell(player, args);
            case "listings" -> { menu.openListings(player, 0); yield true; }
            case "collect", "expired" -> { menu.openCollect(player, 0); yield true; }
            case "history" -> { menu.openHistory(player, 0); yield true; }
            case "stats" -> { menu.openStats(player); yield true; }
            default -> messageOrDefault(player, "auction-house.messages.usage",
                    "<dark_gray>[<gold>AH</gold>]</dark_gray> <gray>Benutzung: "
                            + "<yellow>/ah [sell|listings|collect|history|stats]</yellow></gray>");
        };
    }

    private boolean sell(Player player, String[] args) {
        if (!player.hasPermission(AuctionPermissions.SELL)) {
            return plugin.messages().sendConfiguredAuto(
                    player, plugin.configs().messages(), "messages.no-permission",
                    "<red>Keine Berechtigung.</red>");
        }
        if (args.length != 2) {
            return messageOrDefault(player, "auction-house.messages.sell-usage",
                    "<dark_gray>[<gold>AH</gold>]</dark_gray> <gray>Benutzung: "
                            + "<yellow>/ah sell <Preis></yellow></gray>");
        }

        Long price = AuctionPriceParser.parse(args[1]);
        if (price == null) {
            return messageOrDefault(player, "auction-house.messages.invalid-price",
                    "<dark_gray>[<gold>AH</gold>]</dark_gray> "
                            + "<red>Gib einen gültigen Preis ein.</red>");
        }
        if (price < menu.minimumPrice() || price > menu.maximumPrice()) {
            return messageOrDefault(player, "auction-house.messages.price-range",
                    "<dark_gray>[<gold>AH</gold>]</dark_gray> <red>Der Preis muss zwischen "
                            + "%min% und %max% Coins liegen.</red>",
                    "%min%", MenuFormat.integer(menu.minimumPrice()),
                    "%max%", MenuFormat.integer(menu.maximumPrice()));
        }

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            return messageOrDefault(player, "auction-house.messages.hold-item",
                    "<dark_gray>[<gold>AH</gold>]</dark_gray> "
                            + "<red>Halte zuerst einen Gegenstand in deiner Hand.</red>");
        }
        menu.createListingDirect(player, price, hand.clone());
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (args.length != 1) return List.of();
        String query = args[0].toLowerCase(Locale.ROOT);
        return List.of("sell", "listings", "collect", "history", "stats").stream()
                .filter(value -> value.startsWith(query))
                .toList();
    }
}
