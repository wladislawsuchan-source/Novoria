package de.walahi.novosmp.bounty;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.gui.GuiButton;
import de.walahi.smpcore.gui.ItemBuilder;
import de.walahi.smpcore.gui.MiniMessageItems;
import de.walahi.smpcore.gui.PaginatedGui;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;
import java.util.Map;

/** Read-only, highest-first bounty overview using the shared holder GUI. */
final class BountyMenu {
    private final NovoSMPPlugin plugin;
    private final BountyManager manager;
    private final MiniMessageItems items = new MiniMessageItems();

    BountyMenu(NovoSMPPlugin plugin, BountyManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    void open(Player player) {
        int rows = Math.max(3, Math.min(6, plugin.configs().server().getInt("bounty.gui.rows", 6)));
        String title = plugin.configs().server().getString("bounty.gui.title", "<dark_gray>Kopfgelder</dark_gray>");
        PaginatedGui gui = new PaginatedGui(rows, items.component(title))
                .range(9, rows * 9 - 10)
                .filler(ItemBuilder.of(Material.BLACK_STAINED_GLASS_PANE).name(items.component(" ")).build())
                .navigation(
                        GuiButton.of(items.item(Material.ARROW, "<yellow>Vorherige Seite</yellow>", List.of()), null),
                        GuiButton.of(items.item(Material.ARROW, "<yellow>Nächste Seite</yellow>", List.of()), null));

        List<BountyEntry> entries = manager.entries();
        if (entries.isEmpty()) {
            gui.add(GuiButton.of(items.item(Material.PAPER,
                    plugin.configs().server().getString("bounty.gui.empty-name", "<gray>Keine Kopfgelder</gray>"),
                    plugin.configs().server().getStringList("bounty.gui.empty-lore")), null));
        } else {
            for (BountyEntry entry : entries) gui.add(GuiButton.of(head(entry), null));
        }
        gui.open(player, 0);
    }

    private ItemStack head(BountyEntry entry) {
        boolean online = Bukkit.getPlayer(entry.targetId()) != null;
        Map<String, String> placeholders = Map.of(
                "%player%", BountyManager.escape(entry.targetName()),
                "%amount%", BountyManager.format(entry.amount()),
                "%status%", online ? "<green>Online</green>" : "<gray>Offline</gray>"
        );
        String name = plugin.configs().server().getString(
                "bounty.gui.entry-name", "<yellow><bold>%player%</bold></yellow>");
        List<String> lore = plugin.configs().server().getStringList("bounty.gui.entry-lore");
        if (lore.isEmpty()) lore = List.of("", "<gray>Kopfgeld:</gray> <gold>%amount% Coins</gold>",
                "<gray>Status:</gray> %status%");
        ItemStack head = items.item(Material.PLAYER_HEAD, name, lore.stream()
                .map(line -> replace(line, placeholders)).toList());
        head = ItemBuilder.from(head).name(items.component(replace(name, placeholders))).build();
        if (head.getItemMeta() instanceof SkullMeta meta) {
            Player target = Bukkit.getPlayer(entry.targetId());
            if (target != null) meta.setOwnerProfile(target.getPlayerProfile());
            head.setItemMeta(meta);
        }
        return head;
    }

    private String replace(String input, Map<String, String> placeholders) {
        String result = input == null ? "" : input;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }
}
