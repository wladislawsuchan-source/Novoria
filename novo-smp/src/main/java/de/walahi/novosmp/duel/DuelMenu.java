package de.walahi.novosmp.duel;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.gui.Gui;
import de.walahi.smpcore.gui.GuiButton;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Config-driven duel menus. Functional icons may still fall back to map/kit icons. */
public final class DuelMenu {
    private static final List<Integer> DEFAULT_CONTENT_SLOTS = List.of(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    );

    private final DuelManager manager;
    private final DuelWagerSignInput wagerInput;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public DuelMenu(NovoSMPPlugin plugin, DuelManager manager, DuelWagerSignInput wagerInput) {
        this.manager = manager;
        this.wagerInput = wagerInput;
    }

    public void openDraft(Player player) {
        DuelDraft draft = manager.draft(player.getUniqueId());
        if (draft == null) {
            player.closeInventory();
            return;
        }
        Player target = Bukkit.getPlayer(draft.target());
        DuelMap map = config().map(draft.mapId());
        DuelKit kit = config().kit(draft.kitId());
        boolean specialized = manager.isSpecializedDraft(player.getUniqueId());

        String menu = "draft";
        int rows = rows(menu, 3);
        Gui gui = gui(menu, rows, "<aqua><bold>Duell erstellen</bold></aqua>");
        gui.button(slot(menu, "cancel", 10, rows), GuiButton.of(item(menu, "cancel", Material.RED_CONCRETE,
                "<red><bold>Abbrechen</bold></red>", List.of("<gray>Duellanfrage verwerfen</gray>")),
                event -> manager.cancelDraft(player)));
        gui.button(slot(menu, "map", 12, rows), GuiButton.of(item(menu, "map",
                map == null ? Material.BARRIER : map.icon(),
                "<yellow><bold>Map: <white>%map%</white></bold></yellow>",
                map == null ? List.of("<red>Keine Arena verfügbar.</red>", "<gray>Klicken, um die Map zu wechseln.</gray>")
                        : List.of("<gray>Freie Arenen: <white>%available%/%total%</white></gray>",
                        "<gray>Klicken, um die Map zu wechseln.</gray>"),
                "%map%", map == null ? missing() : esc(map.displayName()),
                "%available%", map == null ? "0" : Integer.toString(manager.availableArenaCount(map.id())),
                "%total%", map == null ? "0" : Integer.toString(config().arenaCount(map.id()))),
                event -> manager.cycleMap(player)));
        gui.button(slot(menu, "duration", 13, rows), GuiButton.of(item(menu, "duration", Material.CLOCK,
                "<yellow><bold>Dauer: <white>%duration%</white></bold></yellow>",
                List.of("<gray>Klicken, um die Kampfdauer zu wechseln.</gray>"),
                "%duration%", duration(draft.durationSeconds())), event -> manager.cycleDuration(player)));
        gui.button(slot(menu, "wager", 14, rows), GuiButton.of(item(menu, "wager", Material.OAK_SIGN,
                "<gold><bold>Einsatz: <white>%wager% Coins</white></bold></gold>",
                List.of("<gray>Klicken und Betrag am Schild eingeben.</gray>",
                        "<dark_gray>Beide Spieler zahlen denselben Betrag.</dark_gray>"),
                "%wager%", coins(draft.wager())), event -> {
            manager.sounds().play(player, "menu-click");
            wagerInput.open(player);
        }));
        gui.button(slot(menu, "kit", 15, rows), GuiButton.of(item(menu, "kit",
                kit == null ? (specialized ? Material.BARRIER : Material.CHEST) : kit.icon(),
                "<aqua><bold>Kit: <white>%kit%</white></bold></aqua>",
                kit == null
                        ? List.of("<gray>Verbrauchte Items bleiben nach dem Duell verbraucht.</gray>",
                        "<yellow>Klicken, um das Kit auszuwählen.</yellow>")
                        : List.of("<gray>Das Kit ersetzt dein Inventar nur während des Duells.</gray>",
                        "<yellow>Klicken, um das Kit auszuwählen.</yellow>"),
                "%kit%", kit == null ? (specialized ? missing() : ownInventory()) : esc(kit.displayName())), event -> {
            manager.sounds().play(player, "menu-click");
            openKitSelection(player, 0);
        }));
        gui.button(slot(menu, "rules", 16, rows), GuiButton.of(rulesItem(menu, "rules", draft.rules(), true), event -> {
            manager.sounds().play(player, "menu-click");
            openRules(player);
        }));
        gui.button(slot(menu, "confirm", 22, rows), GuiButton.of(item(menu, "confirm", Material.LIME_CONCRETE,
                "<green><bold>Duellanfrage senden</bold></green>",
                List.of("<gray>Gegner: <white>%target%</white></gray>", "<yellow>Klicken zum Bestätigen.</yellow>"),
                "%target%", target == null ? offline() : esc(target.getName())), event -> manager.confirmDraft(player)));
        gui.open(player);
    }

    public void openKitSelection(Player player) {
        openKitSelection(player, 0);
    }

    private void openKitSelection(Player player, int requestedPage) {
        DuelDraft draft = manager.draft(player.getUniqueId());
        if (draft == null) return;
        String menu = "kit-selection";
        int rows = rows(menu, 6);
        List<Integer> contentSlots = validSlots(config().integers("menus." + menu + ".content-slots", DEFAULT_CONTENT_SLOTS), rows);
        List<DuelKit> kits = new ArrayList<>(config().kits().values());
        int perPage = Math.max(1, contentSlots.size());
        int pages = Math.max(1, (kits.size() + perPage - 1) / perPage);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        Gui gui = gui(menu, rows, "<aqua>Kit auswählen <gray>(%page%/%pages%)</gray></aqua>",
                "%page%", Integer.toString(page + 1), "%pages%", Integer.toString(pages));

        if (!manager.isSpecializedDraft(player.getUniqueId())) {
            gui.button(slot(menu, "own-inventory", 4, rows), GuiButton.of(item(menu, "own-inventory", Material.CHEST,
                    "<yellow><bold>Eigenes Inventar</bold></yellow>",
                    List.of("<gray>Du kämpfst mit deinen aktuellen Items.</gray>", "<red>Verbrauch bleibt dauerhaft.</red>")),
                    event -> manager.selectKit(player, null)));
        }

        int from = page * perPage;
        int to = Math.min(kits.size(), from + perPage);
        for (int index = from; index < to; index++) {
            DuelKit kit = kits.get(index);
            int slot = contentSlots.get(index - from);
            gui.button(slot, GuiButton.of(item(menu, "kit", kit.icon(),
                    "<aqua><bold>%kit%</bold></aqua>",
                    List.of("<gray>Deine persönliche Sortierung wird verwendet.</gray>",
                            "<yellow>Klicken zum Auswählen.</yellow>"),
                    "%kit%", esc(kit.displayName())), event -> manager.selectKit(player, kit.id())));
        }
        addPagination(gui, menu, rows, page, pages,
                () -> openKitSelection(player, page - 1), () -> openKitSelection(player, page + 1));
        gui.button(slot(menu, "back", rows * 9 - 9, rows), GuiButton.of(item(menu, "back", Material.ARROW,
                "<yellow>Zurück</yellow>", List.of()), event -> {
            manager.sounds().play(player, "menu-back");
            openDraft(player);
        }));
        gui.open(player);
    }

    public void openRules(Player player) {
        DuelDraft draft = manager.draft(player.getUniqueId());
        if (draft == null) return;
        DuelRules rules = draft.rules();
        String menu = "rules";
        int rows = rows(menu, 3);
        Gui gui = gui(menu, rows, "<red>Duell-Regeln</red>");
        gui.button(slot(menu, "crystals", 10, rows), GuiButton.of(toggleItem(menu, "crystals", Material.END_CRYSTAL,
                "Endkristalle", rules.crystalsAllowed()), event -> manager.setRules(player, rules.toggleCrystals())));
        gui.button(slot(menu, "crystal-damage", 12, rows), GuiButton.of(toggleItem(menu, "crystal-damage", Material.DIAMOND_SWORD,
                "Kristallschaden", rules.crystalDamage()), event -> manager.setRules(player, rules.toggleCrystalDamage())));
        gui.button(slot(menu, "tnt", 14, rows), GuiButton.of(toggleItem(menu, "tnt", Material.TNT,
                "TNT", rules.tntAllowed()), event -> manager.setRules(player, rules.toggleTnt())));
        gui.button(slot(menu, "tnt-damage", 16, rows), GuiButton.of(toggleItem(menu, "tnt-damage", Material.IRON_SWORD,
                "TNT-Schaden", rules.tntDamage()), event -> manager.setRules(player, rules.toggleTntDamage())));
        gui.button(slot(menu, "back", 22, rows), GuiButton.of(item(menu, "back", Material.ARROW,
                "<yellow><bold>Zurück</bold></yellow>", List.of()), event -> {
            manager.sounds().play(player, "menu-back");
            openDraft(player);
        }));
        gui.open(player);
    }

    public void openRequest(Player target, DuelRequest request) {
        if (request == null || request.expired()) return;
        Player challenger = Bukkit.getPlayer(request.challenger());
        DuelMap map = config().map(request.mapId());
        DuelKit kit = config().kit(request.kitId());
        boolean specialized = manager.isSpecializedRequest(request);
        String menu = "request";
        int rows = rows(menu, 4);
        Gui gui = gui(menu, rows, "<yellow><bold>Duellanfrage</bold></yellow>");
        gui.item(slot(menu, "challenger", 4, rows), item(menu, "challenger", Material.PLAYER_HEAD,
                "<gold><bold>Herausforderer: <white>%challenger%</white></bold></gold>", List.of(),
                "%challenger%", challenger == null ? offline() : esc(challenger.getName())));
        gui.item(slot(menu, "map", 11, rows), item(menu, "map", map == null ? Material.BARRIER : map.icon(),
                "<yellow><bold>Map: <white>%map%</white></bold></yellow>",
                map == null ? List.of("<red>Keine Arena verfügbar.</red>")
                        : List.of("<gray>Freie Arenen: <white>%available%/%total%</white></gray>"),
                "%map%", map == null ? missing() : esc(map.displayName()),
                "%available%", map == null ? "0" : Integer.toString(manager.availableArenaCount(map.id())),
                "%total%", map == null ? "0" : Integer.toString(config().arenaCount(map.id()))));
        gui.item(slot(menu, "duration", 12, rows), item(menu, "duration", Material.CLOCK,
                "<yellow><bold>Dauer: <white>%duration%</white></bold></yellow>", List.of(),
                "%duration%", duration(request.durationSeconds())));
        List<String> specializedLore = manager.requestLore(request);
        gui.item(slot(menu, "wager", 13, rows), item(menu, "wager", specializedLore.isEmpty() ? Material.OAK_SIGN : Material.DRAGON_EGG,
                "<gold><bold>Einsatz: <white>%wager% Coins</white></bold></gold>", List.of(),
                "%wager%", coins(request.wager())));
        if (!specializedLore.isEmpty()) gui.item(slot(menu, "wager", 13, rows), item(menu, "wager", Material.DRAGON_EGG,
                "<gold><bold>King-Duell</bold></gold>", specializedLore));
        gui.item(slot(menu, "kit", 14, rows), item(menu, "kit", kit == null ? (specialized ? Material.BARRIER : Material.CHEST) : kit.icon(),
                "<aqua><bold>Kit: <white>%kit%</white></bold></aqua>", List.of(),
                "%kit%", kit == null ? (specialized ? missing() : ownInventory()) : esc(kit.displayName())));
        gui.item(slot(menu, "rules", 15, rows), rulesItem(menu, "rules", request.rules(), false));
        gui.button(slot(menu, "deny", 29, rows), GuiButton.of(item(menu, "deny", Material.RED_CONCRETE,
                "<red><bold>Abbrechen</bold></red>", List.of()), event -> manager.deny(target, request)));
        gui.button(slot(menu, "accept", 33, rows), GuiButton.of(item(menu, "accept", Material.LIME_CONCRETE,
                "<green><bold>Akzeptieren</bold></green>",
                List.of("<gray>Coins werden beim Start erneut geprüft.</gray>")), event -> manager.accept(target, request)));
        gui.open(target);
    }

    public void openKitEditorList(Player player) {
        openKitEditorList(player, 0);
    }

    private void openKitEditorList(Player player, int requestedPage) {
        if (config().kits().isEmpty()) {
            DuelMessages.send(config(), player, config().message("kits.none", "<yellow>Es wurden noch keine Duell-Kits eingerichtet.</yellow>"));
            return;
        }
        String menu = "kit-editor";
        int rows = rows(menu, 6);
        List<Integer> contentSlots = validSlots(config().integers("menus." + menu + ".content-slots", DEFAULT_CONTENT_SLOTS), rows);
        List<DuelKit> kits = new ArrayList<>(config().kits().values());
        int perPage = Math.max(1, contentSlots.size());
        int pages = Math.max(1, (kits.size() + perPage - 1) / perPage);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        Gui gui = gui(menu, rows, "<aqua><bold>Kit-Sortierung</bold></aqua> <gray>(%page%/%pages%)</gray>",
                "%page%", Integer.toString(page + 1), "%pages%", Integer.toString(pages));
        int from = page * perPage;
        int to = Math.min(kits.size(), from + perPage);
        for (int index = from; index < to; index++) {
            DuelKit kit = kits.get(index);
            gui.button(contentSlots.get(index - from), GuiButton.of(item(menu, "kit", kit.icon(),
                    "<aqua><bold>%kit%</bold></aqua>",
                    List.of("<gray>Hotbar, Inventar, Rüstung, Offhand</gray>",
                            "<gray>und Stapel frei sortieren.</gray>", "<yellow>Klicken zum Bearbeiten.</yellow>"),
                    "%kit%", esc(kit.displayName())), event -> {
                manager.sounds().play(player, "menu-click");
                manager.editor().openPlayerLayout(player, kit.id());
            }));
        }
        addPagination(gui, menu, rows, page, pages,
                () -> openKitEditorList(player, page - 1), () -> openKitEditorList(player, page + 1));
        gui.open(player);
        manager.sounds().play(player, "menu-open");
    }

    private void addPagination(Gui gui, String menu, int rows, int page, int pages,
                               Runnable previous, Runnable next) {
        if (page > 0) {
            gui.button(slot(menu, "previous", rows * 9 - 6, rows), GuiButton.of(item(menu, "previous", Material.ARROW,
                    "<yellow>Vorherige Seite</yellow>", List.of()), event -> {
                manager.sounds().play((Player) event.getWhoClicked(), "menu-click");
                previous.run();
            }));
        }
        if (page + 1 < pages) {
            gui.button(slot(menu, "next", rows * 9 - 4, rows), GuiButton.of(item(menu, "next", Material.ARROW,
                    "<yellow>Nächste Seite</yellow>", List.of()), event -> {
                manager.sounds().play((Player) event.getWhoClicked(), "menu-click");
                next.run();
            }));
        }
    }

    private ItemStack rulesItem(String menu, String key, DuelRules rules, boolean clickable) {
        List<String> lore = new ArrayList<>();
        lore.add(line(ruleName("crystals", "Endkristalle"), rules.crystalsAllowed()));
        lore.add(line(ruleName("crystal-damage", "Kristallschaden"), rules.crystalDamage()));
        lore.add(line(ruleName("tnt", "TNT"), rules.tntAllowed()));
        lore.add(line(ruleName("tnt-damage", "TNT-Schaden"), rules.tntDamage()));
        lore.addAll(config().strings("menus.common.rules-fixed-lore", List.of(
                "<gray>Wasser, Lava, Feuer sowie Blockabbau</gray>",
                "<gray>und Blockplatzierung sind immer erlaubt.</gray>")));
        if (clickable) lore.add(config().text("menus.common.click-to-edit", "<yellow>Klicken zum Anpassen.</yellow>"));
        return item(menu, key, Material.COMPARATOR, "<red><bold>Arena-Regeln</bold></red>", lore);
    }

    private ItemStack toggleItem(String menu, String key, Material material, String fallbackName, boolean enabled) {
        String state = enabled
                ? config().text("menus.common.state-enabled", "AN")
                : config().text("menus.common.state-disabled", "AUS");
        String name = config().text("menus.common.toggle-name",
                "%color%<bold>%rule%: %state%</bold>",
                "%color%", enabled ? "<green>" : "<red>",
                "%rule%", ruleName(key, fallbackName), "%state%", state);
        return item(menu, key, material, name,
                List.of(config().text("menus.common.toggle-lore", "<gray>Klicken zum Umschalten.</gray>")));
    }

    private String line(String name, boolean enabled) {
        return config().text(enabled ? "menus.common.rule-enabled-line" : "menus.common.rule-disabled-line",
                enabled ? "<green>✔ %rule%</green>" : "<red>✖ %rule%</red>", "%rule%", name);
    }

    private String ruleName(String key, String fallback) {
        return config().text("menus.common.rule-names." + key, fallback);
    }

    private Gui gui(String menu, int rows, String fallbackTitle, String... replacements) {
        String title = config().text("menus." + menu + ".title", fallbackTitle, replacements);
        Gui gui = new Gui(rows, component(title));
        Material filler = config().configuredMaterial("menus." + menu + ".filler", Material.BLACK_STAINED_GLASS_PANE);
        if (filler == null || filler.isAir() || !filler.isItem()) filler = Material.BLACK_STAINED_GLASS_PANE;
        gui.filler(filler);
        return gui;
    }

    private int rows(String menu, int fallback) {
        return config().integer("menus." + menu + ".rows", fallback, 1, 6);
    }

    private int slot(String menu, String key, int fallback, int rows) {
        return config().integer("menus." + menu + ".items." + key + ".slot", fallback, 0, rows * 9 - 1);
    }

    private ItemStack item(String menu, String key, Material fallbackMaterial,
                           String fallbackName, List<String> fallbackLore, String... replacements) {
        String path = "menus." + menu + ".items." + key;
        Material material = config().configuredMaterial(path + ".material", fallbackMaterial);
        Material resolved = material == null || material.isAir() || !material.isItem() ? Material.BARRIER : material;
        ItemStack item = new ItemStack(resolved);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(component(config().text(path + ".name", fallbackName, replacements)));
        List<String> loreLines = config().strings(path + ".lore", fallbackLore);
        if (!loreLines.isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String line : loreLines) lore.add(component(replace(line, replacements)));
            meta.lore(lore);
        }
        Integer customModelData = config().customModelData(path + ".custom-model-data");
        if (customModelData != null) meta.setCustomModelData(customModelData);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private List<Integer> validSlots(List<Integer> configured, int rows) {
        List<Integer> result = new ArrayList<>();
        for (Integer slot : configured) {
            if (slot != null && slot >= 0 && slot < rows * 9 && !result.contains(slot)) result.add(slot);
        }
        if (result.isEmpty()) result.add(Math.min(10, rows * 9 - 1));
        return result;
    }

    private String duration(int seconds) {
        if (seconds % 60 == 0) {
            int minutes = seconds / 60;
            return config().text(minutes == 1 ? "formats.duration-minute" : "formats.duration-minutes",
                    minutes == 1 ? "%minutes% Minute" : "%minutes% Minuten", "%minutes%", Integer.toString(minutes));
        }
        return config().text("formats.duration-clock", "%minutes%:%seconds% Minuten",
                "%minutes%", Integer.toString(seconds / 60), "%seconds%", String.format(Locale.GERMANY, "%02d", seconds % 60));
    }

    private Component component(String value) {
        String raw = value == null ? "" : value;
        try {
            return mm.deserialize(raw);
        } catch (RuntimeException ignored) {
            return Component.text(raw);
        }
    }

    private String replace(String value, String... replacements) {
        String result = value == null ? "" : value;
        for (int index = 0; index + 1 < replacements.length; index += 2) {
            result = result.replace(replacements[index], replacements[index + 1]);
        }
        return result;
    }

    private String coins(long amount) {
        return String.format(Locale.GERMANY, "%,d", amount).replace(',', '.');
    }

    private DuelConfig config() { return manager.config(); }
    private String ownInventory() { return config().text("formats.own-inventory", "Eigenes Inventar"); }
    private String missing() { return config().text("formats.missing", "Fehlt"); }
    private String offline() { return config().text("formats.offline", "Offline"); }
    private String esc(String value) { return value == null ? "" : value.replace("<", "\\<").replace(">", "\\>"); }
}
