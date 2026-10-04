package de.walahi.novosmp.quests;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.gui.Gui;
import de.walahi.smpcore.gui.MiniMessageItems;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** Chest GUI is rendered only for viewers; one shared second task exists while someone views it. */
public final class QuestMenu {
    private final NovoSMPPlugin plugin;
    private final QuestService service;
    private final MiniMessageItems items = new MiniMessageItems();
    private final Map<UUID, Inventory> viewers = new HashMap<>();
    private BukkitTask countdown;

    public QuestMenu(NovoSMPPlugin plugin, QuestService service) { this.plugin = plugin; this.service = service; }

    public void open(Player player) {
        if (!service.enabled()) { send(player, "messages.disabled"); return; }
        try {
            service.retryPendingKeys(player);
            QuestState.DailySet daily = service.daily(player);
            service.refreshCooldowns();
            int rows = 1 + service.activeRows();
            Gui gui = new Gui(Math.max(2, rows), items.component(service.config().yaml.getString("menu.title", "<gold>Quests</gold>")));
            Inventory inventory = gui.createInventory();
            render(player, inventory, daily);
            viewers.put(player.getUniqueId(), inventory);
            player.openInventory(inventory);
            startCountdown();
        } catch (SQLException error) {
            plugin.getLogger().log(Level.WARNING, "Quest-Menü konnte nicht geöffnet werden", error);
            send(player, "messages.storage-error");
        }
    }

    private void render(Player player, Inventory inventory, QuestState.DailySet daily) {
        var yaml = service.config().yaml;
        List<Integer> dailySlots = dailySlots(daily.quests.size());
        for (int i = 0; i < daily.quests.size() && i < dailySlots.size(); i++) {
            int slot = dailySlots.get(i);
            if (slot >= 0 && slot < 9) inventory.setItem(slot, dailyItem(daily.quests.get(i)));
        }
        int globals = Math.min(service.activeRows() * 9, inventory.getSize() - 9);
        for (int i = 0; i < globals; i++) {
            QuestState.Global quest = service.slot(i);
            inventory.setItem(i + 9, quest == null ? null : globalItem(player, quest));
        }
    }

    private ItemStack dailyItem(QuestState.Daily daily) {
        var yaml = service.config().yaml;
        QuestDefinition definition = service.config().byId.get(daily.definitionId);
        if (definition == null) return null;
        Map<String, String> values = placeholders(definition, daily.progress);
        values.put("%reset%", clock(service.millisToReset()));
        if (daily.completed) {
            Material completed = material(yaml.getString("menu.completed-material"), Material.LIME_CONCRETE);
            return items.item(completed, yaml.getString("menu.completed-name", "<green>Abgeschlossen</green>"),
                    replace(yaml.getStringList("menu.completed-lore"), values));
        }
        return items.item(definition.item(), definition.name(), replace(yaml.getStringList("menu.daily-lore"), values));
    }

    private ItemStack globalItem(Player player, QuestState.Global slot) {
        var yaml = service.config().yaml;
        if (slot.cooling()) {
            long left = Math.max(0, slot.cooldownLeft - (System.currentTimeMillis() - slot.activeSince));
            return items.item(material(yaml.getString("menu.cooldown-material"), Material.BARRIER),
                    yaml.getString("menu.cooldown-name", "<red>Neue Quest in %time%</red>")
                            .replace("%time%", clock(left)), List.of());
        }
        QuestDefinition definition = service.config().byId.get(slot.definitionId);
        if (definition == null) return null;
        QuestState.Participant own = slot.participants.get(player.getUniqueId());
        Map<String, String> values = placeholders(definition, own == null ? 0 : own.progress);
        List<String> lore = new ArrayList<>(replace(yaml.getStringList("menu.global-lore"), values));
        if (definition.ranking()) {
            List<QuestState.Participant> leaders = slot.topThree();
            if (!leaders.isEmpty()) {
                lore.add(yaml.getString("menu.ranking-header", "<gray>Top 3:</gray>"));
                List<String> lines = yaml.getStringList("menu.ranking-lines");
                for (int i = 0; i < leaders.size() && i < lines.size(); i++) {
                    QuestState.Participant leader = leaders.get(i);
                    lore.add(lines.get(i).replace("%player%", leader.name)
                            .replace("%progress%", number(leader.progress))
                            .replace("%target%", number(definition.target())));
                }
            }
        }
        return items.item(definition.item(), definition.name(), lore);
    }

    private Map<String, String> placeholders(QuestDefinition definition, double progress) {
        Map<String, String> result = new HashMap<>();
        result.put("%name%", definition.name());
        result.put("%progress%", number(progress));
        result.put("%target%", number(definition.target()));
        result.put("%lumis%", Integer.toString(definition.lumis()));
        result.put("%keys%", Integer.toString(service.config().keyAmount));
        return result;
    }

    private static String number(double value) { return Long.toString((long) Math.floor(value)); }
    private static List<String> replace(List<String> source, Map<String, String> values) {
        List<String> result = new ArrayList<>(source.size());
        for (String line : source) {
            for (Map.Entry<String, String> value : values.entrySet()) line = line.replace(value.getKey(), value.getValue());
            result.add(line);
        }
        return result;
    }
    private static Material material(String name, Material fallback) {
        Material found = name == null ? null : Material.getMaterial(name);
        return found == null || found.isAir() ? fallback : found;
    }
    private static String clock(long millis) {
        long seconds = Math.max(0, millis / 1000);
        long hours = seconds / 3600;
        if (hours > 0) return hours + "h " + (seconds % 3600) / 60 + "m";
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }

    private void startCountdown() {
        if (countdown != null) return;
        countdown = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            viewers.entrySet().removeIf(entry -> {
                Player player = Bukkit.getPlayer(entry.getKey());
                return player == null || player.getOpenInventory().getTopInventory() != entry.getValue();
            });
            if (viewers.isEmpty()) { countdown.cancel(); countdown = null; return; }
            service.refreshCooldowns();
            for (Map.Entry<UUID, Inventory> entry : viewers.entrySet()) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player == null) continue;
                try { renderCountdown(player, entry.getValue(), service.daily(player)); }
                catch (SQLException error) { plugin.getLogger().log(Level.WARNING, "Quest-Menü-Update fehlgeschlagen", error); }
            }
        }, 20L, 20L);
    }

    private void send(Player player, String path) {
        String template = service.config().yaml.getString(path, "");
        if (!template.isBlank()) player.sendMessage(items.component(template));
    }

    private void renderCountdown(Player player, Inventory inventory, QuestState.DailySet daily) {
        List<Integer> dailySlots = dailySlots(daily.quests.size());
        for (int i = 0; i < daily.quests.size() && i < dailySlots.size(); i++) {
            int slot = dailySlots.get(i);
            if (slot >= 0 && slot < 9) inventory.setItem(slot, dailyItem(daily.quests.get(i)));
        }
        for (int i = 0; i < service.activeRows() * 9; i++) {
            QuestState.Global quest = service.slot(i);
            if (quest == null) continue;
            if (quest.cooling() || (inventory.getItem(i + 9) != null && inventory.getItem(i + 9).getType()
                    == material(service.config().yaml.getString("menu.cooldown-material"), Material.BARRIER)))
                inventory.setItem(i + 9, globalItem(player, quest));
        }
    }

    public void reopenViewers() {
        for (UUID id : List.copyOf(viewers.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || player.getOpenInventory().getTopInventory() != viewers.get(id)) {
                viewers.remove(id);
                continue;
            }
            open(player);
        }
    }

    private List<Integer> dailySlots(int count) {
        List<Integer> configured = service.config().yaml.getIntegerList("menu.daily-slots");
        if (configured.size() >= count) return configured;
        List<Integer> centered = new ArrayList<>();
        for (int i = 0, start = (9 - count) / 2; i < count; i++) centered.add(start + i);
        return centered;
    }

    public void stop() { if (countdown != null) countdown.cancel(); countdown = null; viewers.clear(); }
}
