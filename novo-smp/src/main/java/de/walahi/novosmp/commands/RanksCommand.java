package de.walahi.novosmp.commands;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.api.event.ActionContext;
import de.walahi.smpcore.api.event.ActionSource;
import de.walahi.smpcore.commands.framework.BaseCommand;
import de.walahi.smpcore.economy.EconomyOperationResult;
import de.walahi.smpcore.gui.Gui;
import de.walahi.smpcore.gui.GuiButton;
import de.walahi.smpcore.gui.MaterialResolver;
import de.walahi.smpcore.gui.MenuFormat;
import de.walahi.smpcore.gui.MiniMessageItems;
import de.walahi.smpcore.gui.SlotLayout;
import de.walahi.smpcore.services.EconomyService;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class RanksCommand extends BaseCommand {
    private final EconomyService economy;
    private final MiniMessageItems items = new MiniMessageItems();

    public RanksCommand(SMPCorePlugin plugin, EconomyService economy) {
        super(plugin);
        this.economy = economy;
    }

    @Override
    protected String permission() {
        return "smpcore.ranks.use";
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendRichMessage("<dark_gray>[<green>SMP</green>]</dark_gray> <red>Dieser Befehl ist nur für Spieler.</red>");
            return true;
        }
        if (args.length != 0) {
            player.sendRichMessage("<dark_gray>[<green>SMP</green>]</dark_gray> <gray>Benutzung: <yellow>/ranks</yellow></gray>");
            return true;
        }
        open(player);
        return true;
    }

    private void open(Player player) {
        ConfigurationSection menu = plugin.configs().menus().getConfigurationSection("ranks.menu");
        int rows = Math.max(3, Math.min(6, menu == null ? 3 : menu.getInt("rows", 3)));
        int inventorySize = rows * 9;
        Gui gui = new Gui(rows, items.component(value(menu, "title", "<dark_gray>Ränge kaufen</dark_gray>")));
        Material filler = MaterialResolver.resolve(value(menu, "filler.material", null),
                Material.GRAY_STAINED_GLASS_PANE);
        gui.filler(items.item(filler, value(menu, "filler.name", " "),
                menu == null ? List.of() : menu.getStringList("filler.lore")));

        RankOffer premium = offer("premium", "Premium", 1_000_000L, 30,
                Material.EMERALD, 11, "<green><bold>Premium</bold></green>");
        RankOffer premiumPlus = offer("premium-plus", "Premium+", 2_000_000L, 30,
                Material.EMERALD_BLOCK, 15, "<dark_green><bold>Premium+</bold></dark_green>");

        gui.button(SlotLayout.valid(premium.slot(), inventorySize, 11),
                GuiButton.of(offerItem(premium), event -> purchase(player, premium)));
        gui.button(SlotLayout.valid(premiumPlus.slot(), inventorySize, 15),
                GuiButton.of(offerItem(premiumPlus), event -> purchase(player, premiumPlus)));
        gui.open(player);
    }

    private RankOffer offer(String id, String display, long fallbackPrice, int fallbackDuration,
                            Material fallbackMaterial, int fallbackSlot, String fallbackName) {
        String settingsId = id.equals("premium-plus") ? "premium-plus" : "premium";
        String group = id.equals("premium-plus") ? "premiumplus" : "premium";
        long price = Math.max(0L, plugin.configs().main().getLong(
                "ranks." + settingsId + ".price", fallbackPrice));
        int durationDays = Math.max(1, plugin.configs().main().getInt(
                "ranks." + settingsId + ".duration-days", fallbackDuration));

        ConfigurationSection section = plugin.configs().menus().getConfigurationSection("ranks.menu.items." + id);
        int slot = section == null ? fallbackSlot : section.getInt("slot", fallbackSlot);
        Material material = MaterialResolver.resolve(section == null ? null : section.getString("material"),
                fallbackMaterial);
        String name = value(section, "name", fallbackName);
        List<String> lore = section == null ? List.of() : section.getStringList("lore");
        if (lore.isEmpty()) {
            lore = List.of(
                    "<gray>Preis: <green>%price% Coins</green></gray>",
                    "<gray>Laufzeit: <yellow>%duration% Tage</yellow></gray>",
                    "",
                    "<yellow>Klicken zum Kaufen</yellow>"
            );
        }
        return new RankOffer(group, display, price, durationDays, slot, material, name, lore);
    }

    private ItemStack offerItem(RankOffer offer) {
        return items.item(offer.material(), 1, offer.name(), offer.lore(), Map.of(
                "%price%", MenuFormat.integer(offer.price()),
                "%duration%", Integer.toString(offer.durationDays()),
                "%rank%", offer.display()
        ));
    }

    private void purchase(Player player, RankOffer offer) {
        if (!economy.available()) {
            player.sendRichMessage("<dark_gray>[<green>SMP</green>]</dark_gray> <red>Die Economy ist momentan nicht verfügbar.</red>");
            return;
        }

        LuckPerms luckPerms;
        try {
            luckPerms = LuckPermsProvider.get();
        } catch (IllegalStateException exception) {
            player.sendRichMessage("<dark_gray>[<green>SMP</green>]</dark_gray> <red>LuckPerms ist nicht verfügbar.</red>");
            return;
        }
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            player.sendRichMessage("<dark_gray>[<green>SMP</green>]</dark_gray> <red>Deine Rangdaten konnten nicht geladen werden.</red>");
            return;
        }

        boolean ownsPremium = hasGroup(user, "premium");
        boolean ownsPremiumPlus = hasGroup(user, "premiumplus");
        if ((offer.group().equals("premium") && (ownsPremium || ownsPremiumPlus))
                || (offer.group().equals("premiumplus") && ownsPremiumPlus)) {
            player.sendRichMessage("<dark_gray>[<green>SMP</green>]</dark_gray> <red>Diesen Rang besitzt du bereits.</red>");
            return;
        }
        if (plugin.getRankManager().resolve(player).priority() < 9) {
            player.sendRichMessage("<dark_gray>[<green>SMP</green>]</dark_gray> <red>Team- und Partnerränge können hier keinen Spielerrang kaufen.</red>");
            return;
        }

        EconomyOperationResult result = economy.withdraw(player.getUniqueId(), offer.price(),
                "Rangkauf: " + offer.display(),
                ActionContext.player(ActionSource.GUI, player.getUniqueId()));
        if (result == EconomyOperationResult.INSUFFICIENT_FUNDS) {
            player.sendRichMessage("<dark_gray>[<green>SMP</green>]</dark_gray> <red>Dir fehlen Coins. Preis: <yellow>"
                    + MenuFormat.integer(offer.price()) + "</yellow>.</red>");
            return;
        }
        if (result != EconomyOperationResult.SUCCESS) {
            player.sendRichMessage("<dark_gray>[<green>SMP</green>]</dark_gray> <red>Der Kauf konnte nicht abgeschlossen werden.</red>");
            return;
        }

        // Premium+ keeps an existing Premium node. Benefits are cumulative and no paid
        // membership is silently removed during an upgrade.
        InheritanceNode grantedNode = InheritanceNode.builder(offer.group())
                .expiry(Duration.ofDays(offer.durationDays())).build();
        user.data().add(grantedNode);
        luckPerms.getUserManager().saveUser(user).whenComplete((unused, error) -> {
            if (error == null) {
                Bukkit.getScheduler().runTask(plugin, () -> completePurchase(player, offer));
                return;
            }

            plugin.getLogger().severe("Rangkauf für " + player.getName()
                    + " konnte nicht gespeichert werden: " + error.getMessage());
            user.data().remove(grantedNode);
            luckPerms.getUserManager().saveUser(user).whenComplete((rollbackUnused, rollbackError) ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (rollbackError != null) {
                            plugin.getLogger().severe("Rangkauf-Rollback für " + player.getName()
                                    + " ist fehlgeschlagen. Keine automatische Erstattung, damit kein Rang+Coins-Dupe entsteht: "
                                    + rollbackError.getMessage());
                            player.sendRichMessage("<dark_gray>[<green>SMP</green>]</dark_gray> "
                                    + "<red>Der Rangkauf muss von einem Admin geprüft werden. Es wurde nichts automatisch doppelt ausgezahlt.</red>");
                            return;
                        }

                        EconomyOperationResult refund = economy.deposit(player.getUniqueId(), offer.price(),
                                "Rangkauf-Rückerstattung: " + offer.display(),
                                ActionContext.player(ActionSource.SYSTEM, player.getUniqueId()));
                        if (refund == EconomyOperationResult.SUCCESS) {
                            player.sendRichMessage("<dark_gray>[<green>SMP</green>]</dark_gray> "
                                    + "<red>Der Rang konnte nicht gespeichert werden. Deine Coins wurden erstattet.</red>");
                        } else {
                            plugin.getLogger().severe("Rangkauf-Rückerstattung für " + player.getName()
                                    + " ist fehlgeschlagen: " + refund);
                            player.sendRichMessage("<dark_gray>[<green>SMP</green>]</dark_gray> "
                                    + "<red>Die Erstattung muss von einem Admin geprüft werden.</red>");
                        }
                    }));
        });
    }

    private void completePurchase(Player player, RankOffer offer) {
        player.closeInventory();
        plugin.getRankManager().apply(player);
        player.sendRichMessage("<dark_gray>[<green>SMP</green>]</dark_gray> <gray>Du hast <green><bold>"
                + offer.display() + "</bold></green> für <yellow>"
                + MenuFormat.integer(offer.price()) + " Coins</yellow> gekauft. <gray>Laufzeit: <yellow>"
                + offer.durationDays() + " Tage</yellow>.</gray>");
    }

    private boolean hasGroup(User user, String group) {
        return user.getNodes().stream().anyMatch(node ->
                node instanceof InheritanceNode inheritance
                        && inheritance.getValue()
                        && inheritance.getGroupName().equalsIgnoreCase(group));
    }

    private String value(ConfigurationSection section, String path, String fallback) {
        return section == null ? fallback : section.getString(path, fallback);
    }

    private record RankOffer(String group, String display, long price, int durationDays,
                             int slot, Material material, String name, List<String> lore) {
    }
}
