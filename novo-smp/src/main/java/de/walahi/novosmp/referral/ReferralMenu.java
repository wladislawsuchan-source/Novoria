package de.walahi.novosmp.referral;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.novosmp.stats.LeaderboardProfileCache;
import de.walahi.smpcore.gui.Gui;
import de.walahi.smpcore.gui.GuiButton;
import de.walahi.smpcore.gui.MiniMessageItems;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** /ref-Menüs nach dem gewünschten Refer-a-Friend-Vorbild. */
public final class ReferralMenu {
    private static final int LEADERBOARD_PAGE_SIZE = 45;
    private final ReferralManager manager;
    private final LeaderboardProfileCache profiles;
    private final MiniMessageItems items = new MiniMessageItems();

    public ReferralMenu(NovoSMPPlugin plugin, ReferralManager manager) {
        this.manager = manager;
        this.profiles = plugin.leaderboardProfiles();
    }

    public void open(Player player) {
        manager.deliverPendingKeys(player);
        ReferralRepository.Account account = manager.account(player);
        if (account == null) {
            player.sendRichMessage("<red>Das Refer-a-Friend-System konnte nicht geladen werden.</red>");
            return;
        }

        // Vorlage: exakt drei Reihen; die vier Hauptpunkte sitzen in der mittleren Reihe.
        Gui gui = new Gui(3, items.component(manager.config("referral.menu.title", "<dark_gray>Refer a Friend</dark_gray>")));
        gui.button(10, GuiButton.of(codeItem(player, account), event -> {
            if (account.codeUnlocked()) return;
            ReferralRepository.Account unlocked = manager.unlockCode(player);
            if (unlocked == null || !unlocked.codeUnlocked()) {
                player.sendRichMessage("<red>Dein Referral-Code konnte nicht freigeschaltet werden.</red>");
                return;
            }
            player.sendRichMessage("<green>Dein Referral-Code wurde freigeschaltet: <aqua>" + unlocked.code() + "</aqua></green>");
            open(player);
        }));
        gui.button(12, GuiButton.of(item(Material.TURTLE_HELMET, "<green>Bestenliste</green>", List.of(
                "<gray>Alle Spieler nach erfolgreichen</gray>",
                "<gray>Empfehlungen sortiert.</gray>"
        )), event -> openLeaderboard(player, 0)));
        gui.button(14, GuiButton.of(item(Material.GOLD_NUGGET, "<gold>Belohnungsstufen</gold>", List.of()), event -> openTiers(player)));
        gui.button(16, GuiButton.of(item(Material.CHEST, "<yellow>Punkte-Shop</yellow>", List.of(
                "<gray>Gib Punkte aus, die du durch</gray>",
                "<gray>erfolgreiche Referrals verdient hast.</gray>",
                "",
                "<aqua>1 erfolgreicher Ref = 1 Punkt</aqua>",
                "",
                "<gray>Deine Punkte: <white>" + account.points() + "</white></gray>"
        )), event -> openShop(player)));
        gui.open(player);
    }

    private ItemStack codeItem(Player player, ReferralRepository.Account account) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Lade neue Spieler auf den</gray>");
        lore.add("<gray>Server ein & erhalte</gray>");
        lore.add("<gray>gemeinsam Belohnungen.</gray>");
        lore.add("");
        if (account.codeUnlocked()) {
            lore.add("<white>Dein Code: <aqua>" + account.code() + "</aqua></white>");
            lore.add("<gray>Verwendung für neue Spieler:</gray> <yellow>/ref " + account.code() + "</yellow>");
        } else {
            lore.add("<yellow>Klicke, um deinen Code freizuschalten.</yellow>");
            lore.add("<dark_gray>Vorher kann der Code nicht verwendet werden.</dark_gray>");
        }
        if (manager.isPending(player.getUniqueId())) {
            ReferralManager.VerificationProgress progress = manager.progress(player);
            lore.add("");
            lore.add("<yellow>Deine vorgemerkte Empfehlung:</yellow>");
            lore.add(progressLine("Blöcke abgebaut", progress.blocks(), progress.requiredBlocks()));
            lore.add(progressLine("Mobs getötet", progress.mobKills(), progress.requiredMobKills()));
            lore.add(progressLine("Advancements", progress.advancements(), progress.requiredAdvancements()));
            lore.add("");
            lore.add("<gray>Nach Abschluss erhalten beide</gray>");
            lore.add("<dark_purple>je 1 Novo-Key</dark_purple><gray>.</gray>");
        }
        return item(Material.NAME_TAG, "<gold>Dein Code</gold>", lore);
    }

    private String progressLine(String label, long current, long required) {
        boolean done = current >= required;
        String color = done ? "<green>" : "<gray>";
        return color + label + ": <white>" + Math.min(current, required) + "/" + required + "</white>";
    }

    public void openLeaderboard(Player player) {
        openLeaderboard(player, 0);
    }

    public void openLeaderboard(Player player, int page) {
        List<ReferralRepository.LeaderboardEntry> entries = manager.leaderboard();
        int maxPage = entries.isEmpty() ? 0 : (entries.size() - 1) / LEADERBOARD_PAGE_SIZE;
        int safePage = Math.max(0, Math.min(page, maxPage));
        int start = safePage * LEADERBOARD_PAGE_SIZE;
        int end = Math.min(entries.size(), start + LEADERBOARD_PAGE_SIZE);

        Gui gui = new Gui(6, items.component("<dark_gray>Bestenliste • Seite " + (safePage + 1) + "</dark_gray>"));
        if (entries.isEmpty()) {
            gui.item(22, item(Material.BARRIER, "<gray>Noch keine Referrals</gray>", List.of(
                    "<gray>Sobald ein Referral erfolgreich</gray>",
                    "<gray>bestätigt wurde, erscheint er hier.</gray>"
            )));
        } else {
            for (int i = start; i < end; i++) {
                ReferralRepository.LeaderboardEntry entry = entries.get(i);
                gui.item(i - start, playerHead(entry, i + 1));
            }
        }
        if (safePage > 0) {
            gui.button(45, GuiButton.of(item(Material.ARROW, "<yellow>Vorherige Seite</yellow>", List.of()),
                    event -> openLeaderboard(player, safePage - 1)));
        }
        gui.button(49, GuiButton.of(item(Material.BARRIER, "<red>Zurück</red>", List.of()), event -> open(player)));
        if (safePage < maxPage) {
            gui.button(53, GuiButton.of(item(Material.ARROW, "<green>Nächste Seite</green>", List.of()),
                    event -> openLeaderboard(player, safePage + 1)));
        }

        Inventory inventory = gui.createInventory();
        player.openInventory(inventory);
        for (int i = start; i < end; i++) {
            ReferralRepository.LeaderboardEntry entry = entries.get(i);
            if (profiles.cached(entry.playerId()) != null) continue;
            int slot = i - start;
            int rank = i + 1;
            profiles.resolve(entry.playerId(), entry.playerName(), profile -> {
                if (!player.isOnline()) return;
                if (player.getOpenInventory().getTopInventory() != inventory) return;
                inventory.setItem(slot, playerHead(entry, rank));
            });
        }
    }

    public void openTiers(Player player) {
        int invites = manager.invites(player.getUniqueId());
        Set<Integer> claimed = manager.claimed(player.getUniqueId());
        List<ReferralManager.RewardTier> tiers = manager.tiers();
        Gui gui = new Gui(3, items.component("<dark_gray>Belohnungsstufen</dark_gray>"));
        int[] slots = {11, 12, 13, 14, 15};
        for (int i = 0; i < Math.min(slots.length, tiers.size()); i++) {
            ReferralManager.RewardTier tier = tiers.get(i);
            boolean isClaimed = claimed.contains(tier.id());
            boolean ready = invites >= tier.requiredInvites();
            Material material = isClaimed ? Material.GRAY_DYE : ready ? Material.LIME_DYE : Material.CLOCK;
            String color = isClaimed ? "<gray>" : ready ? "<green>" : "<white>";
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add("<white>Anforderungen:</white>");
            lore.add("<aqua>" + tier.requiredInvites() + " Einladungen</aqua> <gray>(erfolgreich)</gray>");
            lore.add("");
            lore.add("<white>Belohnungen:</white>");
            lore.add("<yellow>" + tier.novoKeys() + "x Novo-Key</yellow>");
            lore.add("");
            if (isClaimed) lore.add("<green>Abgeholt</green>");
            else if (ready) lore.add("<green>Klicken zum Abholen</green>");
            else lore.add("<red>Noch " + Math.max(0, tier.requiredInvites() - invites) + " Einladungen benötigt</red>");
            gui.button(slots[i], GuiButton.of(item(material, color + "Stufe " + tier.id(), lore), event -> {
                ReferralManager.ClaimOutcome outcome = manager.claim(player, tier);
                switch (outcome) {
                    case SUCCESS -> player.sendRichMessage("<green>Du hast <yellow>" + tier.novoKeys() + "x Novo-Key</yellow> abgeholt.</green>");
                    case NOT_READY -> player.sendRichMessage("<red>Diese Belohnungsstufe ist noch nicht freigeschaltet.</red>");
                    case ALREADY_CLAIMED -> player.sendRichMessage("<gray>Diese Belohnungsstufe wurde bereits abgeholt.</gray>");
                    case KEY_UNAVAILABLE -> player.sendRichMessage("<red>Der Novo-Key ist momentan nicht verfügbar.</red>");
                    default -> player.sendRichMessage("<red>Die Belohnung konnte nicht abgeholt werden.</red>");
                }
                openTiers(player);
            }));
        }
        gui.button(18, GuiButton.of(item(Material.ARROW, "<gray>Zurück</gray>", List.of()), event -> open(player)));
        gui.open(player);
    }

    public void openShop(Player player) {
        ReferralRepository.Account account = manager.account(player);
        if (account == null) return;
        int price = manager.pointPrice();
        Gui gui = new Gui(3, items.component("<dark_gray>Punkte-Shop</dark_gray>"));
        gui.button(13, GuiButton.of(item(manager.novoKeyMaterial(), "<dark_purple>Novo-Key</dark_purple>", List.of(
                "<gray>Preis: <aqua>" + price + " Punkt" + (price == 1 ? "" : "e") + "</aqua></gray>",
                "<gray>Deine Punkte: <white>" + account.points() + "</white></gray>",
                "",
                "<green>Klicken zum Kaufen</green>"
        )), event -> {
            ReferralManager.BuyOutcome outcome = manager.buyNovoKey(player);
            switch (outcome) {
                case SUCCESS -> player.sendRichMessage("<green>Du hast einen <dark_purple>Novo-Key</dark_purple> für <aqua>" + price + " Punkt" + (price == 1 ? "" : "e") + "</aqua> gekauft.</green>");
                case NOT_ENOUGH_POINTS -> player.sendRichMessage("<red>Du hast nicht genug Referral-Punkte.</red>");
                case KEY_UNAVAILABLE -> player.sendRichMessage("<red>Der Novo-Key ist momentan nicht verfügbar.</red>");
                default -> player.sendRichMessage("<red>Der Kauf konnte nicht abgeschlossen werden.</red>");
            }
            openShop(player);
        }));
        gui.button(18, GuiButton.of(item(Material.ARROW, "<gray>Zurück</gray>", List.of()), event -> open(player)));
        gui.open(player);
    }

    private ItemStack playerHead(ReferralRepository.LeaderboardEntry entry, int rank) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta raw = stack.getItemMeta();
        if (raw instanceof SkullMeta meta) {
            org.bukkit.profile.PlayerProfile profile = profiles.cached(entry.playerId());
            if (profile != null) meta.setOwnerProfile(profile);
            meta.displayName(items.component("<gold>#" + rank + "</gold> <white>" + entry.playerName() + "</white>")
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    items.component("<gray>Einladungen: <aqua>" + entry.invites() + "</aqua></gray>").decoration(TextDecoration.ITALIC, false)
            ));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        return items.item(material, 1, name, lore);
    }
}
