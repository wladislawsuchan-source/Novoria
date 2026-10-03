package de.walahi.novosmp.clan;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.gui.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;

/** Clan menus use shared holder GUIs. Confirmations are always left-cancel/right-confirm. */
final class ClanMenu {
    private static final int BACK_SLOT = 45;
    private final NovoSMPPlugin plugin;
    private final ClanManager manager;
    private final ClanConfig config;
    private final MiniMessageItems items = new MiniMessageItems();

    ClanMenu(NovoSMPPlugin plugin, ClanManager manager, ClanConfig config) {
        this.plugin = plugin;
        this.manager = manager;
        this.config = config;
    }

    void main(Player player, Clan clan) {
        Gui gui = new Gui(6, items.component("<dark_gray>Clan • " + clan.name() + "</dark_gray>")).filler(deco());
        gui.button(10, GuiButton.of(item(Material.NAME_TAG, "<gold>" + clan.name() + "</gold>", List.of(
                "<gray>Tag:</gray> <" + clan.tagColor() + ">[" + clan.tag() + "]</" + clan.tagColor() + ">",
                "<gray>Level:</gray> <yellow>" + clan.level() + "</yellow>",
                "<gray>Mitglieder:</gray> <white>" + clan.members().size() + " / " + config.memberLimit(clan.level()) + "</white>")), null));
        gui.button(12, GuiButton.of(item(Material.PLAYER_HEAD, "<yellow>Mitglieder</yellow>",
                List.of("<gray>Mitglieder und Rollen ansehen.</gray>", "<yellow>Klicke zum Öffnen.</yellow>")), e -> members(player, clan)));
        gui.button(14, GuiButton.of(item(Material.GOLD_INGOT, "<gold>Clan-Fortschritt</gold>",
                List.of("<gray>Level, Voraussetzungen und Freischaltungen.</gray>", "<yellow>Klicke zum Öffnen.</yellow>")), e -> progress(player, clan)));
        gui.button(16, GuiButton.of(item(Material.RED_BED, "<aqua>Clan-Homes</aqua>",
                List.of("<gray>Gesetzte Homes ansehen und besuchen.</gray>")), e -> homes(player, clan)));
        gui.button(28, GuiButton.of(item(Material.ENDER_CHEST, "<light_purple>Clan-EC</light_purple>",
                List.of("<white>" + config.chestRows(clan.level()) + " Reihen</white>", "<yellow>Klicke zum Öffnen.</yellow>")), e -> manager.openChest(player)));
        gui.button(30, GuiButton.of(item(Material.TOTEM_OF_UNDYING, "<green>Clan-Party</green>",
                List.of("<gray>Party-Status und Befehle ansehen.</gray>")), e -> party(player, clan)));
        gui.button(32, GuiButton.of(item(Material.WRITABLE_BOOK, "<red>Snitch-Liste</red>",
                List.of("<gray>Interne Liste ehemaliger Mitglieder.</gray>")), e -> snitches(player, clan)));
        gui.button(34, GuiButton.of(item(Material.COMPASS, "<yellow>Clan-Liste</yellow>",
                List.of("<gray>Öffentliche Clans ansehen.</gray>")), e -> list(player)));
        gui.button(40, GuiButton.of(configuredItem("clans.menu.main.deposit", Material.SUNFLOWER, Map.of()), null));
        gui.button(49, GuiButton.of(item(Material.REPEATER, "<gray>Einstellungen</gray>",
                List.of("<gray>Verwalte die Einstellungen deines Clans.</gray>", "<yellow>Klicke zum Öffnen.</yellow>")), e -> settings(player, clan)));
        gui.button(53, GuiButton.of(item(Material.BARRIER, "<red>Clan verlassen / auflösen</red>",
                List.of("<gray>Member/Officer: <white>/clan leave</white></gray>", "<gray>Leader: <white>/clan disband</white></gray>")), e -> player.performCommand("clan leave")));
        gui.open(player);
    }

    void list(Player player) {
        List<Clan> clans = new ArrayList<>(manager.clans());
        clans.sort(Comparator.<Clan>comparingInt(Clan::level).reversed()
                .thenComparing(Comparator.comparingInt((Clan clan) -> clan.members().size()).reversed())
                .thenComparing(Clan::name, String.CASE_INSENSITIVE_ORDER));
        PaginatedGui gui = pages("<dark_gray>Öffentliche Clan-Liste</dark_gray>");
        Clan own = manager.clan(player.getUniqueId());
        if (own != null) gui.fixedButton(BACK_SLOT, back(e -> main(player, own)));
        for (Clan clan : clans) {
            Clan.Member leader = clan.members().get(clan.leader());
            gui.add(GuiButton.of(item(Material.SHIELD, "<gold>" + clan.name() + "</gold>", List.of(
                    "<gray>Tag:</gray> <" + clan.tagColor() + ">[" + clan.tag() + "]</" + clan.tagColor() + ">",
                    "<gray>Level:</gray> <white>" + clan.level() + "</white>",
                    "<gray>Mitglieder:</gray> <white>" + clan.members().size() + " / " + config.memberLimit(clan.level()) + "</white>",
                    "<gray>Leader:</gray> <white>" + (leader == null ? "Unbekannt" : leader.name()) + "</white>")), e -> publicMembers(player, clan)));
        }
        gui.open(player, 0);
    }

    void members(Player player, Clan clan) { membersGui(player, clan, true); }
    private void publicMembers(Player player, Clan clan) { membersGui(player, clan, false); }

    private void membersGui(Player player, Clan clan, boolean internal) {
        PaginatedGui gui = pages("<dark_gray>Mitglieder • " + clan.name() + "</dark_gray>")
                .fixedButton(BACK_SLOT, back(e -> { if (internal) main(player, clan); else list(player); }))
                .fixedButton(49, GuiButton.of(item(Material.PAPER,
                        "<yellow>Mitglieder: " + clan.members().size() + " / " + config.memberLimit(clan.level()) + "</yellow>",
                        List.of("<gray>Clan-Level: <white>" + clan.level() + "</white></gray>")), null));
        for (Clan.Member member : clan.members().values()) {
            gui.add(GuiButton.of(head(member), internal ? e -> memberActions(player, clan, member) : null));
        }
        gui.open(player, 0);
    }

    private void memberActions(Player player, Clan clan, Clan.Member target) {
        Clan.Member self = clan.members().get(player.getUniqueId());
        Gui gui = new Gui(3, items.component("<dark_gray>Aktionen • " + target.name() + "</dark_gray>")).filler(deco());
        if (!target.id().equals(player.getUniqueId())) gui.button(10, GuiButton.of(item(Material.TOTEM_OF_UNDYING,
                "<green>Party-Einladung</green>", List.of()), e -> player.performCommand("cp invite " + target.name())));
        if (!target.id().equals(player.getUniqueId())) {
            boolean friends = manager.plugin().friendManager() != null
                    && manager.plugin().friendManager().areFriends(player.getUniqueId(), target.id());
            boolean enabled = manager.clanGlow(player, target.id());
            gui.button(13, GuiButton.of(item(Material.GLOWSTONE_DUST,
                    friends ? "<gray>Glow über Freunde verwaltet</gray>" : "<yellow>Clan-Glow: " + (enabled ? "AN" : "AUS") + "</yellow>",
                    friends ? List.of("<gray>Die Freundes-Einstellung hat Vorrang.</gray>") : List.of("<gray>Nur für dich sichtbar. Standard: AUS.</gray>")),
                    friends ? null : e -> { manager.toggleClanGlow(player, target.id()); memberActions(player, clan, target); }));
        }
        if (self != null && self.role().staff() && target.role() != ClanRole.LEADER
                && (self.role() == ClanRole.LEADER || target.role() == ClanRole.MEMBER)) {
            gui.button(12, GuiButton.of(item(Material.BARRIER, "<red>Kicken</red>", List.of()), e -> player.performCommand("clan kick " + target.name())));
        }
        if (self != null && self.role() == ClanRole.LEADER && target.role() == ClanRole.MEMBER) {
            gui.button(14, GuiButton.of(item(Material.EMERALD, "<green>Zum Officer befördern</green>", List.of()), e -> player.performCommand("clan promote " + target.name())));
        }
        if (self != null && self.role() == ClanRole.LEADER && target.role() == ClanRole.OFFICER) {
            gui.button(14, GuiButton.of(item(Material.REDSTONE, "<yellow>Zum Member zurückstufen</yellow>", List.of()), e -> player.performCommand("clan demote " + target.name())));
        }
        if (self != null && self.role() == ClanRole.LEADER && !target.id().equals(player.getUniqueId())) {
            gui.button(16, GuiButton.of(item(Material.NETHER_STAR, "<gold>Leadership übertragen</gold>", List.of()), e -> player.performCommand("clan transfer " + target.name())));
        }
        gui.button(18, back(e -> members(player, clan)));
        gui.open(player);
    }

    void homes(Player player, Clan clan) {
        Gui gui = new Gui(3, items.component("<dark_gray>Clan-Homes</dark_gray>")).filler(deco());
        List<ClanRepository.Home> homes = manager.homes(clan);
        int baseSlots = config.homes(clan.level(), false);
        int slots = config.homes(clan.level(), clan.bonusHome());
        for (int index = 0; index < 3; index++) {
            int slot = 11 + index * 2;
            if (index < homes.size()) {
                ClanRepository.Home home = homes.get(index);
                gui.button(slot, GuiButton.of(item(Material.RED_BED, "<aqua>" + home.display() + "</aqua>",
                        List.of("<yellow>Klicke zum Teleportieren.</yellow>")), e -> manager.reply(player, manager.teleportHome(player, home.name()))));
            } else if (index < slots) {
                String path = index >= baseSlots
                        ? "clans.menu.homes.free-premium-slot"
                        : "clans.menu.homes.free-slot";
                gui.button(slot, GuiButton.of(configuredItem(path, Material.LIME_STAINED_GLASS_PANE, Map.of()), null));
            } else {
                int unlock = nextHomeUnlock(clan.level(), index + 1);
                String line = unlock > 0 ? "<gray>Wird auf Clan-Level <yellow>" + unlock + "</yellow> freigeschaltet.</gray>"
                        : "<gray>Zusätzlicher PremiumPlus Clan-Home-Slot.</gray>";
                gui.button(slot, GuiButton.of(item(Material.BARRIER, "<red>Gesperrter Home-Slot</red>", List.of(line)), null));
            }
        }
        gui.button(18, back(e -> main(player, clan)));
        gui.open(player);
    }

    private void progress(Player player, Clan clan) {
        ClanManager.Progress progress = manager.progress(clan);
        Gui gui = new Gui(4, items.component("<dark_gray>Clan-Fortschritt</dark_gray>")).filler(deco());
        if (clan.level() >= 10) {
            gui.button(13, GuiButton.of(item(Material.NETHER_STAR, "<gold>Clan-Level 10</gold>", List.of("<green>Maximallevel erreicht.</green>")), null));
        } else {
            gui.button(13, GuiButton.of(item(Material.EXPERIENCE_BOTTLE, "<gold>Level " + clan.level() + " → " + (clan.level() + 1) + "</gold>", List.of(
                    "<gray>Clan-Kasse:</gray> <gold>" + MenuFormat.integer(clan.bank()) + " / " + MenuFormat.integer(progress.cost()) + " Coins</gold>",
                    "<gray>Prestige:</gray> <white>" + MenuFormat.integer(progress.prestige()) + " / " + MenuFormat.integer(progress.requiredPrestige()) + "</white>",
                    "<gray>Spielzeit:</gray> <white>" + MenuFormat.integer(progress.playtimeSeconds() / 3600) + " / " + MenuFormat.integer(progress.requiredPlaytimeSeconds() / 3600) + " Stunden</white>",
                    "", "<gray>Nächste Freischaltungen:</gray> <white>" + nextUnlocks(clan.level()) + "</white>")), null));
            if (progress.ready()) {
                if (manager.canUpgrade(player.getUniqueId())) gui.button(22, GuiButton.of(item(Material.LIME_CONCRETE, "<green>Upgrade bereit</green>",
                        List.of("<yellow>Klicke zum Bestätigen.</yellow>")), e -> confirm(player, "Clan-Level kaufen",
                        "Level " + clan.level() + " → " + (clan.level() + 1) + " | " + MenuFormat.integer(progress.cost()) + " Coins",
                        () -> manager.reply(player, manager.upgrade(player)))));
                else gui.button(22, GuiButton.of(item(Material.GRAY_CONCRETE, "<gray>Upgrade bereit</gray>",
                        List.of("<gray>Leader oder Officer müssen das Upgrade bestätigen.</gray>")), null));
            }
        }
        gui.button(27, back(e -> main(player, clan)));
        gui.open(player);
    }

    private void party(Player player, Clan clan) {
        Gui gui = new Gui(3, items.component("<dark_gray>Clan-Party</dark_gray>")).filler(deco());
        gui.button(13, GuiButton.of(configuredItem("clans.menu.party.status", Material.TOTEM_OF_UNDYING,
                Map.of("%status%", items.miniMessage().escapeTags(manager.parties().info(player.getUniqueId())))), null));
        gui.button(18, back(e -> main(player, clan)));
        gui.open(player);
    }

    void snitches(Player player, Clan clan) {
        PaginatedGui gui = pages("<dark_gray>Interne Snitch-Liste</dark_gray>")
                .fixedButton(BACK_SLOT, back(e -> main(player, clan)));
        DateTimeFormatter format = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());
        for (ClanRepository.Snitch snitch : manager.snitches(clan)) {
            gui.add(GuiButton.of(item(Material.PLAYER_HEAD, "<red>" + snitch.name() + "</red>", List.of(
                    "<gray>Markiert von:</gray> <white>" + snitch.marker() + "</white>",
                    "<gray>Zeit:</gray> <white>" + format.format(Instant.ofEpochMilli(snitch.markedAt())) + "</white>")), null));
        }
        gui.open(player, 0);
    }

    private void settings(Player player, Clan clan) {
        Gui gui = new Gui(4, items.component("<dark_gray>Clan-Einstellungen</dark_gray>")).filler(deco());
        gui.button(10, GuiButton.of(item(Material.IRON_SWORD, "<yellow>Friendly Fire: " + (clan.friendlyFire() ? "AN" : "AUS") + "</yellow>",
                List.of("<gray>Klicken zum Umschalten.</gray>", "<gray>Freischaltung: Clan-Level <yellow>" + config.ffUnlock() + "</yellow>.</gray>")),
                e -> { manager.reply(player, manager.friendlyFire(player, !clan.friendlyFire())); settings(player, clan); }));
        String[] colors = {"#EF4444", "#F59E0B", "#22C55E", "#3B82F6", "#A855F7"};
        for (int index = 0; index < colors.length; index++) {
            String color = colors[index];
            gui.button(12 + index, GuiButton.of(item(Material.PURPLE_DYE, "<" + color + ">Tag-Farbe " + color + "</" + color + ">",
                    List.of("<gray>Benötigt einen PremiumPlus-Leader.</gray>")), e -> { manager.reply(player, manager.tagColor(player, color)); settings(player, clan); }));
        }
        gui.button(28, GuiButton.of(configuredItem("clans.menu.settings.identity", Material.NAME_TAG, Map.of()), null));
        gui.button(31, GuiButton.of(item(Material.TNT, "<red>Clan auflösen</red>",
                List.of("<gray>Clan-EC muss leer sein; Kasse verfällt.</gray>")), e -> player.performCommand("clan disband")));
        gui.button(27, back(e -> main(player, clan)));
        gui.open(player);
    }

    void confirm(Player player, String title, String summary, Runnable yes) {
        ConfirmGui.open(player, items.component("<dark_gray>" + title + "</dark_gray>"),
                item(Material.LIME_CONCRETE, "<green>Bestätigen</green>", List.of()),
                item(Material.RED_CONCRETE, "<red>Abbrechen</red>", List.of()),
                item(Material.PAPER, "<yellow>Zusammenfassung</yellow>", List.of("<gray>" + summary + "</gray>")), yes, () -> { });
    }

    private PaginatedGui pages(String title) {
        return new PaginatedGui(6, items.component(title)).range(9, 44).filler(deco())
                .navigation(48, GuiButton.of(item(Material.ARROW, "<yellow>Vorherige Seite</yellow>", List.of()), null),
                        53, GuiButton.of(item(Material.ARROW, "<yellow>Nächste Seite</yellow>", List.of()), null));
    }

    private GuiButton back(Consumer<org.bukkit.event.inventory.InventoryClickEvent> action) {
        return GuiButton.of(item(Material.ARROW, "<yellow>Zurück</yellow>", List.of("<gray>Klicke, um zurückzugehen.</gray>")), action);
    }

    private int nextHomeUnlock(int currentLevel, int requiredSlots) {
        for (int level = currentLevel + 1; level <= 10; level++) if (config.homes(level, false) >= requiredSlots) return level;
        return -1;
    }

    private String nextUnlocks(int level) {
        int next = Math.min(10, level + 1);
        List<String> unlocks = new ArrayList<>();
        if (config.memberLimit(next) > config.memberLimit(level)) unlocks.add(config.memberLimit(next) + " Mitglieder");
        if (config.officers(next) > config.officers(level)) unlocks.add(config.officers(next) + " Officer");
        if (config.homes(next, false) > config.homes(level, false)) unlocks.add(config.homes(next, false) + " Clan-Home(s)");
        if (config.chestRows(next) > config.chestRows(level)) unlocks.add(config.chestRows(next) + " EC-Reihen");
        if (next == config.ffUnlock()) unlocks.add("Friendly Fire");
        if (next == config.partyUnlock()) unlocks.add("Clan-Party");
        return unlocks.isEmpty() ? "Weitere Clan-Vorteile" : String.join(", ", unlocks);
    }

    private ItemStack head(Clan.Member member) {
        ItemStack head = item(Material.PLAYER_HEAD, "<yellow>" + member.name() + "</yellow>", List.of(
                "<gray>Rolle:</gray> <white>" + roleName(member.role()) + "</white>",
                Bukkit.getPlayer(member.id()) == null ? "<gray>Offline</gray>" : "<green>Online</green>"));
        if (head.getItemMeta() instanceof SkullMeta meta) {
            Player online = Bukkit.getPlayer(member.id());
            if (online != null) meta.setOwnerProfile(online.getPlayerProfile());
            head.setItemMeta(meta);
        }
        return head;
    }

    private String roleName(ClanRole role) {
        return switch (role) { case LEADER -> "Leader"; case OFFICER -> "Officer"; case MEMBER -> "Member"; };
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        return items.builder(material, name, lore).flags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP).build();
    }

    private ItemStack configuredItem(String path, Material fallbackMaterial, Map<String, String> placeholders) {
        Material material = MaterialResolver.resolve(
                plugin.configs().menus().getString(path + ".material"), fallbackMaterial);
        String name = plugin.configs().menus().getString(path + ".name", "");
        List<String> lore = plugin.configs().menus().getStringList(path + ".lore");
        return items.builder(material, 1, name, lore, placeholders)
                .flags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP)
                .build();
    }

    private ItemStack deco() {
        return ItemBuilder.of(Material.BLACK_STAINED_GLASS_PANE).name(items.component(" "))
                .flags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP).build();
    }
}
