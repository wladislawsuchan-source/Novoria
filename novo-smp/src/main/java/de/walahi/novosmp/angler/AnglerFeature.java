package de.walahi.novosmp.angler;

import de.walahi.novosmp.professions.ProfessionManager;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.gui.Gui;
import de.walahi.smpcore.gui.GuiButton;
import de.walahi.smpcore.gui.MaterialResolver;
import de.walahi.smpcore.gui.MiniMessageItems;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Angler Block 1 GUI and catch storage; no fishing event handling. */
public final class AnglerFeature implements Listener {
    private static final List<String> BIOMES = List.of("river", "plains", "forest", "ocean", "swamp", "cold");
    private static final List<Integer> FISH_SLOTS = fishSlots();
    private final SMPCorePlugin plugin;
    private final ProfessionManager professions;
    private final FishRegistry fish;
    private final FishItemFactory factory;
    private final AnglerCatchStorage storage;
    private final MiniMessageItems items = new MiniMessageItems();

    public AnglerFeature(SMPCorePlugin plugin, ProfessionManager professions, FishRegistry fish) {
        this.plugin = plugin;
        this.professions = professions;
        this.fish = fish;
        this.factory = new FishItemFactory(fish);
        this.storage = new AnglerCatchStorage(plugin);
    }

    public AnglerCatchStorage storage() { return storage; }
    /** Entry point for later fishing catches and the existing admin test command. */
    public ItemStack storeCatch(Player player, ItemStack catchItem) {
        return storage.storeCatch(player, catchItem,
                () -> professions.angler(player.getUniqueId()).prestige());
    }
    public boolean enabled() { return plugin.configs().angler().getBoolean("enabled", true); }

    public void openFish(Player player) { openFish(player, Filter.ALL, 0, 0); }

    private void openFish(Player player, Filter filter, int page, int biomeIndex) {
        if (!enabled()) { message(player, "messages.disabled", "<red>Angler ist derzeit deaktiviert.</red>"); return; }
        int safeBiome = Math.floorMod(biomeIndex, BIOMES.size());
        List<FishDefinition> matching = fish.definitions().stream()
                .filter(definition -> matches(definition, filter, BIOMES.get(safeBiome)))
                .sorted(Comparator.comparing(FishDefinition::id)).toList();
        int pages = Math.max(1, (matching.size() + FISH_SLOTS.size() - 1) / FISH_SLOTS.size());
        int current = Math.max(0, Math.min(page, pages - 1));
        var config = plugin.configs().angler();
        Gui gui = new Gui(6, items.component(config.getString("gui.fish.title", "<dark_aqua>Fische</dark_aqua>")));
        gui.filler(MaterialResolver.resolve(config.getString("gui.fish.filler", "CYAN_STAINED_GLASS_PANE"),
                Material.CYAN_STAINED_GLASS_PANE));
        Filter[] filters = Filter.values();
        for (int index = 0; index < filters.length; index++) {
            Filter selected = filters[index];
            String label = config.getString("gui.fish.filters." + selected.name().toLowerCase(Locale.ROOT),
                    selected.display);
            if (selected == Filter.BIOME) label += " · " + config.getString(
                    "gui.fish.biome-labels." + BIOMES.get(safeBiome), BIOMES.get(safeBiome));
            gui.button(index + 1, GuiButton.of(items.item(selected == filter ? Material.LIME_DYE : Material.PAPER,
                    (selected == filter ? "<green>" : "<yellow>") + label, List.of()),
                    event -> openFish(player, selected, 0,
                            selected == Filter.BIOME && filter == Filter.BIOME ? safeBiome + 1 : safeBiome)));
        }
        for (int index = 0; index < FISH_SLOTS.size(); index++) {
            int fishIndex = current * FISH_SLOTS.size() + index;
            if (fishIndex >= matching.size()) break;
            FishDefinition definition = matching.get(fishIndex);
            List<String> lore = new ArrayList<>();
            lore.add(config.getString("gui.fish.rarity-line", "<gray>Seltenheit: <white>%value%</white></gray>")
                    .replace("%value%", definition.rarityDisplay()));
            lore.add(config.getString(definition.exclusive() ? "gui.fish.location-line" : "gui.fish.preferred-line",
                    definition.exclusive() ? "<gray>Fundort: <white>%value%</white></gray>"
                            : "<gray>Bevorzugt: <white>%value%</white></gray>")
                    .replace("%value%", definition.preferredBiomes()));
            if (!"Keine".equalsIgnoreCase(definition.bestConditions()))
                lore.add(config.getString("gui.fish.condition-line", "<gray>Beste Bedingungen: <white>%value%</white></gray>")
                        .replace("%value%", definition.bestConditions()));
            lore.add(config.getString("gui.fish.exclusive-line", "<gray>Exklusiv: <white>%value%</white></gray>")
                    .replace("%value%", definition.exclusive() ? "Ja" : "Nein"));
            gui.item(FISH_SLOTS.get(index), items.item(definition.material(),
                    definition.color() + definition.displayName(), lore));
        }
        gui.button(45, GuiButton.of(items.item(Material.ARROW,
                config.getString("gui.fish.back-name", "<yellow>Zurück</yellow>"), List.of()),
                event -> professions.openAngler(player)));
        if (current > 0) gui.button(48, GuiButton.of(items.item(Material.ARROW,
                        config.getString("gui.fish.previous-name", "<yellow>Vorherige Seite</yellow>"), List.of()),
                event -> openFish(player, filter, current - 1, safeBiome)));
        gui.item(49, items.item(Material.BOOK,
                config.getString("gui.fish.page-name", "<aqua>Seite %page%/%pages%</aqua>")
                        .replace("%page%", Integer.toString(current + 1))
                        .replace("%pages%", Integer.toString(pages)), List.of()));
        if (current + 1 < pages) gui.button(50, GuiButton.of(
                items.item(Material.ARROW, config.getString("gui.fish.next-name", "<yellow>Nächste Seite</yellow>"), List.of()),
                event -> openFish(player, filter, current + 1, safeBiome)));
        gui.open(player);
    }

    private boolean matches(FishDefinition fish, Filter filter, String biome) {
        String rarity = fish.rarity();
        return switch (filter) {
            case ALL -> true;
            case COMMON -> rarity.equals("common") || rarity.equals("ordinary");
            case RARE -> rarity.equals("rare") || rarity.equals("very_rare");
            case NIGHT -> fish.conditionTags().contains("night");
            case RAIN -> fish.conditionTags().contains("rain");
            case BIOME -> fish.biomeTags().contains(biome);
            case EXCLUSIVE -> fish.exclusive();
        };
    }

    public void openStorage(Player player) {
        if (!enabled()) { message(player, "messages.disabled", "<red>Angler ist derzeit deaktiviert.</red>"); return; }
        if (!professions.isAnglerActive(player.getUniqueId())) {
            message(player, "messages.storage-locked", "<red>Das Fanglager ist nur mit dem Beruf Angler verfügbar.</red>");
            return;
        }
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof CatchHolder) return;
        UUID playerId = player.getUniqueId();
        int prestige = professions.angler(playerId).prestige();
        try {
            ItemStack[] snapshot = storage.snapshot(playerId, prestige);
            CatchHolder holder = new CatchHolder(playerId);
            Inventory inventory = Bukkit.createInventory(holder, snapshot.length,
                    items.component(plugin.configs().angler().getString("gui.storage.title", "<dark_aqua>Fanglager</dark_aqua>")));
            holder.inventory = inventory;
            inventory.setContents(snapshot);
            storage.viewer(playerId, () -> {
                try { inventory.setContents(Arrays.copyOf(
                        storage.snapshot(playerId, professions.angler(playerId).prestige()), inventory.getSize())); }
                catch (IOException | SQLException exception) {
                    plugin.getLogger().warning("Fanglager-Anzeige konnte nicht aktualisiert werden: " + exception.getMessage());
                }
            });
            player.openInventory(inventory);
        } catch (IOException | SQLException exception) {
            plugin.getLogger().severe("Fanglager konnte nicht geöffnet werden: " + exception.getMessage());
            message(player, "messages.storage-error", "<red>Dein Fanglager ist wegen eines Speicherfehlers vorübergehend gesperrt.</red>");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof CatchHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player) || !player.getUniqueId().equals(holder.playerId)) {
            event.setCancelled(true);
            return;
        }
        Inventory top = event.getView().getTopInventory();
        InventoryAction action = event.getAction();
        int slot = event.getRawSlot();
        if (slot < 0) return; // Clicking outside only changes the player's cursor.
        if (slot >= top.getSize()) {
            // Bottom-inventory clicks remain vanilla, except actions that could move items into the top.
            if (blocksBottomAction(action)) event.setCancelled(true);
            else if (action == InventoryAction.COLLECT_TO_CURSOR && hasMatchingTop(top, event.getCursor())) {
                event.setCancelled(true);
                collectToCursor(player, holder, top, event.getCursor());
            }
            return;
        }
        event.setCancelled(true); // Every top action is handled explicitly; unknown writes fail closed.
        if (action == InventoryAction.COLLECT_TO_CURSOR) {
            collectToCursor(player, holder, top, event.getCursor());
            return;
        }
        ItemStack current = top.getItem(slot);
        if (current == null || current.getType().isAir()) return;
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            shiftOut(player, holder, top, slot, current);
            return;
        }
        if (!isTopPickupAction(action)) return;
        ItemStack cursor = event.getCursor();
        if (cursor != null && !cursor.getType().isAir() && !cursor.isSimilar(current)) return;
        int space = cursor == null || cursor.getType().isAir()
                ? current.getMaxStackSize() : cursor.getMaxStackSize() - cursor.getAmount();
        int wanted = switch (action) {
            case PICKUP_HALF -> (current.getAmount() + 1) / 2;
            case PICKUP_ONE -> 1;
            case PICKUP_SOME -> space;
            default -> current.getAmount();
        };
        int amount = Math.min(Math.min(wanted, space), current.getAmount());
        if (amount <= 0) return;
        ItemStack taken = storage.takeMatching(holder.playerId, Map.of(slot, amount), current);
        if (taken == null) { storageError(player); return; }
        if (cursor != null && !cursor.getType().isAir()) {
            ItemStack merged = cursor.clone();
            merged.setAmount(cursor.getAmount() + taken.getAmount());
            player.setItemOnCursor(merged);
        } else player.setItemOnCursor(taken);
        refreshStorageView(player, holder, top);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof CatchHolder holder)) return;
        if (!event.getWhoClicked().getUniqueId().equals(holder.playerId)
                || dragTouchesTop(event.getRawSlots(), event.getView().getTopInventory().getSize())) {
            event.setCancelled(true);
        }
    }

    static boolean isTopPickupAction(InventoryAction action) {
        return action == InventoryAction.PICKUP_ALL || action == InventoryAction.PICKUP_HALF
                || action == InventoryAction.PICKUP_ONE || action == InventoryAction.PICKUP_SOME;
    }

    static boolean blocksBottomAction(InventoryAction action) {
        return action == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || action == InventoryAction.UNKNOWN;
    }

    static boolean dragTouchesTop(Set<Integer> rawSlots, int topSize) {
        return rawSlots.stream().anyMatch(slot -> slot >= 0 && slot < topSize);
    }

    private void shiftOut(Player player, CatchHolder holder, Inventory top, int slot, ItemStack source) {
        ItemStack[] bottom = copyItems(player.getInventory().getStorageContents());
        int moved = moveInto(bottom, source, source.getAmount());
        if (moved <= 0) return;
        ItemStack taken = storage.takeMatching(holder.playerId, Map.of(slot, moved), source);
        if (taken == null) { storageError(player); return; }
        player.getInventory().setStorageContents(bottom);
        refreshStorageView(player, holder, top);
    }

    private void collectToCursor(Player player, CatchHolder holder, Inventory top, ItemStack cursor) {
        if (cursor == null || cursor.getType().isAir()) return;
        int space = cursor.getMaxStackSize() - cursor.getAmount();
        if (space <= 0) return;
        ItemStack[] bottom = copyItems(player.getInventory().getStorageContents());
        int collected = 0;
        for (int slot = 0; slot < bottom.length && collected < space; slot++) {
            ItemStack stack = bottom[slot];
            if (stack == null || !stack.isSimilar(cursor)) continue;
            int amount = Math.min(stack.getAmount(), space - collected);
            collected += amount;
            if (amount == stack.getAmount()) bottom[slot] = null;
            else stack.setAmount(stack.getAmount() - amount);
        }
        Map<Integer, Integer> fromTop = new LinkedHashMap<>();
        for (int slot = 0; slot < top.getSize() && collected < space; slot++) {
            ItemStack stack = top.getItem(slot);
            if (stack == null || !stack.isSimilar(cursor)) continue;
            int amount = Math.min(stack.getAmount(), space - collected);
            fromTop.put(slot, amount);
            collected += amount;
        }
        if (collected == 0) return;
        if (!fromTop.isEmpty() && storage.takeMatching(holder.playerId, fromTop, cursor) == null) {
            storageError(player);
            return;
        }
        player.getInventory().setStorageContents(bottom);
        ItemStack merged = cursor.clone();
        merged.setAmount(cursor.getAmount() + collected);
        player.setItemOnCursor(merged);
        if (!fromTop.isEmpty()) refreshStorageView(player, holder, top);
    }

    private boolean hasMatchingTop(Inventory top, ItemStack cursor) {
        if (cursor == null || cursor.getType().isAir()) return false;
        for (ItemStack stack : top.getContents()) if (stack != null && stack.isSimilar(cursor)) return true;
        return false;
    }

    private ItemStack[] copyItems(ItemStack[] original) {
        ItemStack[] copy = Arrays.copyOf(original, original.length);
        for (int slot = 0; slot < copy.length; slot++) if (copy[slot] != null) copy[slot] = copy[slot].clone();
        return copy;
    }

    /** Existing stacks first, free player slots second; returns only the actually movable amount. */
    private int moveInto(ItemStack[] bottom, ItemStack source, int amount) {
        int remaining = amount;
        for (ItemStack stack : bottom) {
            if (remaining == 0) break;
            if (stack == null || !stack.isSimilar(source)) continue;
            int moved = Math.min(remaining, stack.getMaxStackSize() - stack.getAmount());
            if (moved <= 0) continue;
            stack.setAmount(stack.getAmount() + moved);
            remaining -= moved;
        }
        for (int slot = 0; slot < bottom.length && remaining > 0; slot++) {
            if (bottom[slot] != null && !bottom[slot].getType().isAir()) continue;
            int moved = Math.min(remaining, source.getMaxStackSize());
            bottom[slot] = source.clone();
            bottom[slot].setAmount(moved);
            remaining -= moved;
        }
        return amount - remaining;
    }

    private void refreshStorageView(Player player, CatchHolder holder, Inventory top) {
        try {
            ItemStack[] snapshot = storage.snapshot(holder.playerId, professions.angler(holder.playerId).prestige());
            top.setContents(Arrays.copyOf(snapshot, top.getSize()));
        } catch (IOException | SQLException exception) {
            plugin.getLogger().warning("Fanglager-Anzeige konnte nicht aktualisiert werden: " + exception.getMessage());
            player.closeInventory();
        }
    }

    private void storageError(Player player) {
        message(player, "messages.storage-error", "<red>Fanglager konnte nicht gespeichert werden.</red>");
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof CatchHolder holder)) return;
        storage.viewer(holder.playerId, null);
        storage.flush(holder.playerId);
    }

    public void onQuit(UUID playerId) { storage.forget(playerId); }
    public void shutdown() { storage.flushDirty(); }

    private void message(Player player, String path, String fallback) {
        plugin.messages().sendConfiguredAuto(player, plugin.configs().angler(), path, fallback);
    }

    private static List<Integer> fishSlots() {
        List<Integer> slots = new ArrayList<>();
        for (int index = 9; index < 45; index++) slots.add(index);
        return List.copyOf(slots);
    }

    private enum Filter {
        ALL("Alle"), COMMON("Häufig"), RARE("Selten"), NIGHT("Nacht"),
        RAIN("Regen"), BIOME("Biom"), EXCLUSIVE("Exklusiv");
        private final String display;
        Filter(String display) { this.display = display; }
    }

    private static final class CatchHolder implements InventoryHolder {
        private final UUID playerId;
        private Inventory inventory;
        private CatchHolder(UUID playerId) { this.playerId = playerId; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
