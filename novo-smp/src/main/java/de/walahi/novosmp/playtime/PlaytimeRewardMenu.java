package de.walahi.novosmp.playtime;

import de.walahi.smpcore.SMPCorePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Zwei Seiten mit jeweils 28 Spielzeit-Meilensteinen wie im gewünschten Vorbild. */
public final class PlaytimeRewardMenu implements Listener {
    private static final int PAGE_SIZE = 28;
    private static final int[] REWARD_SLOTS = rewardSlots();
    private static final int PREVIOUS_SLOT = 45;
    private static final int INFO_SLOT = 49;
    private static final int NEXT_SLOT = 53;

    private final SMPCorePlugin plugin;
    private final PlaytimeRewardManager manager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public PlaytimeRewardMenu(SMPCorePlugin plugin, PlaytimeRewardManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void open(Player player, int requestedPage) {
        List<PlaytimeMilestone> milestones = manager.milestones();
        int maxPage = Math.max(0, Math.min(1, (Math.max(1, milestones.size()) - 1) / PAGE_SIZE));
        int page = Math.max(0, Math.min(maxPage, requestedPage));
        String title = plugin.configs().playtime().getString("menu.title",
                        "<dark_gray>Spielzeit (Seite %page%)</dark_gray>")
                .replace("%page%", Integer.toString(page + 1));

        MenuHolder holder = new MenuHolder(page);
        Inventory inventory = Bukkit.createInventory(holder, 54, component(title));
        holder.inventory = inventory;
        fillFrame(inventory);

        Set<Integer> claimed = manager.claimed(player.getUniqueId());
        long seconds = manager.playtimeSeconds(player.getUniqueId());
        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = start + i;
            if (index >= milestones.size()) break;
            PlaytimeMilestone milestone = milestones.get(index);
            inventory.setItem(REWARD_SLOTS[i], milestoneItem(milestone, seconds, claimed.contains(milestone.id())));
        }

        int claimedConfigured = (int) milestones.stream().filter(m -> claimed.contains(m.id())).count();
        inventory.setItem(INFO_SLOT, infoItem(seconds, claimedConfigured, milestones.size()));
        if (page > 0) inventory.setItem(PREVIOUS_SLOT, navigation(false));
        if (page < maxPage) inventory.setItem(NEXT_SLOT, navigation(true));
        player.openInventory(inventory);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MenuHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()) return;
        event.setCancelled(true);

        int slot = event.getRawSlot();
        if (slot == PREVIOUS_SLOT && holder.page > 0) {
            open(player, holder.page - 1);
            return;
        }
        if (slot == NEXT_SLOT && holder.page < 1) {
            open(player, holder.page + 1);
            return;
        }

        int position = indexOf(REWARD_SLOTS, slot);
        if (position < 0) return;
        int milestoneIndex = holder.page * PAGE_SIZE + position;
        List<PlaytimeMilestone> milestones = manager.milestones();
        if (milestoneIndex < 0 || milestoneIndex >= milestones.size()) return;
        PlaytimeMilestone milestone = milestones.get(milestoneIndex);

        PlaytimeRewardManager.ClaimResult result = manager.claim(player, milestone);
        switch (result) {
            case SUCCESS -> {
                send(player, "messages.claimed",
                        "<green>Spielzeit Level %level% abgeholt: <gold>%coins% Coins</gold>, <aqua>%xp% XP</aqua> und <yellow>1× Spielzeit-Key</yellow>.</green>",
                        milestone);
                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.8F, 1.25F);
                open(player, holder.page);
            }
            case NOT_READY -> send(player, "messages.not-ready",
                    "<red>Diese Spielzeit-Stufe ist noch nicht freigeschaltet.</red>", milestone);
            case ALREADY_CLAIMED -> send(player, "messages.already-claimed",
                    "<gray>Diese Spielzeit-Stufe hast du bereits abgeholt.</gray>", milestone);
            case STORAGE_ERROR, CONFIG_ERROR -> send(player, "messages.storage-error",
                    "<red>Deine Spielzeit-Belohnung konnte nicht gespeichert werden.</red>", milestone);
            case DISABLED -> player.sendRichMessage("<red>Das Spielzeit-System ist deaktiviert.</red>");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder)) return;
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot >= 0 && slot < topSize)) event.setCancelled(true);
    }

    private ItemStack milestoneItem(PlaytimeMilestone milestone, long playtimeSeconds, boolean claimed) {
        boolean ready = playtimeSeconds >= milestone.requiredSeconds();
        Material material;
        String name;
        String status;
        if (claimed) {
            material = material("menu.claimed-material", Material.GRAY_DYE);
            name = "<gray>Level " + milestone.id() + "</gray>";
            status = "<green>Abgeholt</green>";
        } else if (ready) {
            material = material("menu.claimable-material", Material.LIME_DYE);
            name = "<green>Level " + milestone.id() + "</green>";
            status = "<green>Klicken zum Abholen</green>";
        } else {
            material = material("menu.locked-material", Material.CLOCK);
            name = "<yellow>Level " + milestone.id() + "</yellow>";
            status = "<red>Gesperrt</red>";
        }

        List<String> lore = List.of(
                "",
                "<white>Anforderungen:</white>",
                "<yellow>" + manager.format(milestone.hours()) + " Stunden</yellow>",
                "",
                "<white>Belohnungen:</white>",
                "<green>" + manager.format(milestone.coins()) + " Coins</green>",
                "<light_purple>" + manager.format(milestone.xp()) + " XP</light_purple>",
                "<red>1× Spielzeit-Key</red>",
                "",
                status
        );
        return item(material, name, lore);
    }

    private ItemStack infoItem(long seconds, int claimed, int total) {
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        return item(Material.NETHER_STAR, "<gold>Deine Spielzeit</gold>", List.of(
                "<gray>Aktuell:</gray> <yellow>" + manager.format(hours) + "h " + minutes + "m</yellow>",
                "<gray>Abgeholt:</gray> <green>" + claimed + " / " + total + "</green>",
                "",
                "<gray>Jede Stufe enthält immer</gray>",
                "<yellow>1× Spielzeit-Key</yellow><gray>, Coins und XP.</gray>"
        ));
    }

    private ItemStack navigation(boolean next) {
        Material material = material(next ? "menu.next-material" : "menu.previous-material", Material.ARROW);
        return item(material, next ? "<green>Nächste Seite</green>" : "<yellow>Vorherige Seite</yellow>", List.of());
    }

    private void fillFrame(Inventory inventory) {
        ItemStack filler = item(material("menu.filler-material", Material.GRAY_STAINED_GLASS_PANE), " ", List.of());
        for (int slot = 0; slot < 9; slot++) inventory.setItem(slot, filler.clone());
        for (int slot = 45; slot < 54; slot++) inventory.setItem(slot, filler.clone());
        for (int row = 1; row <= 4; row++) {
            inventory.setItem(row * 9, filler.clone());
            inventory.setItem(row * 9 + 8, filler.clone());
        }
    }

    private Material material(String path, Material fallback) {
        Material material = Material.matchMaterial(plugin.configs().playtime().getString(path, fallback.name()));
        return material == null ? fallback : material;
    }

    private ItemStack item(Material material, String name, List<String> loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(component(name));
        List<Component> lore = new ArrayList<>();
        for (String line : loreLines) lore.add(component(line));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private Component component(String input) {
        return miniMessage.deserialize(input == null ? "" : input).decoration(TextDecoration.ITALIC, false);
    }

    private void send(Player player, String path, String fallback, PlaytimeMilestone milestone) {
        String text = plugin.configs().playtime().getString(path, fallback)
                .replace("%level%", Integer.toString(milestone.id()))
                .replace("%hours%", manager.format(milestone.hours()))
                .replace("%coins%", manager.format(milestone.coins()))
                .replace("%xp%", manager.format(milestone.xp()));
        player.sendRichMessage(text);
    }

    private static int[] rewardSlots() {
        int[] slots = new int[PAGE_SIZE];
        int index = 0;
        for (int row = 1; row <= 4; row++) {
            for (int column = 1; column <= 7; column++) slots[index++] = row * 9 + column;
        }
        return slots;
    }

    private static int indexOf(int[] values, int target) {
        for (int i = 0; i < values.length; i++) if (values[i] == target) return i;
        return -1;
    }

    private static final class MenuHolder implements InventoryHolder {
        private final int page;
        private Inventory inventory;

        private MenuHolder(int page) { this.page = page; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
