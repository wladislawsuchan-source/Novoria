package de.walahi.novosmp.items;

import com.destroystokyo.paper.event.player.PlayerElytraBoostEvent;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.gui.MiniMessageItems;
import io.papermc.paper.event.entity.EntityLoadCrossbowEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Crate-exklusive "Unendliche Rakete".
 *
 * <p>Das Custom-Item selbst wird nie als Elytra-Treibstoff verbraucht. Stattdessen speichert es
 * gewöhnliche Feuerwerksraketen als Ladungen. Jede Benutzung verbraucht exakt eine eingelagerte
 * Rakete. Die Stufe des Launchers bestimmt die Flugdauer, nicht die ursprünglich eingelagerte
 * Rakete.</p>
 *
 * <p>Progression im Amboss: I + I -> II und II + II -> III.</p>
 */
public final class InfiniteRocketListener implements Listener {
    private static final String CONFIG_ROOT = "infinite-rocket";
    private static final String ITEM_ID_1 = "infinite_rocket_1";
    private static final String ITEM_ID_2 = "infinite_rocket_2";
    private static final String ITEM_ID_3 = "infinite_rocket_3";

    private static final int INPUT_END_EXCLUSIVE = 45;
    private static final int INFO_SLOT = 49;
    private static final int CLOSE_SLOT = 53;

    private final SMPCorePlugin plugin;
    private final CustomItemManager customItems;
    private final MiniMessageItems items = new MiniMessageItems();
    private final PlainTextComponentSerializer plainText = PlainTextComponentSerializer.plainText();
    private final NumberFormat numbers = NumberFormat.getIntegerInstance(Locale.GERMANY);
    private final NamespacedKey storedKey;
    private final NamespacedKey instanceKey;

    public InfiniteRocketListener(SMPCorePlugin plugin, CustomItemManager customItems) {
        this.plugin = plugin;
        this.customItems = customItems;
        this.storedKey = new NamespacedKey(plugin, "infinite_rocket_stored");
        this.instanceKey = new NamespacedKey(plugin, "infinite_rocket_instance");
    }

    // ---------------------------------------------------------------------
    // Benutzung / Boost
    // ---------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Bereits vor 1.51.0 vorhandene Raketen erhalten die neue Tankleiste beim nächsten Join.
        Player player = event.getPlayer();
        for (ItemStack item : player.getInventory().getContents()) {
            Tier tier = tier(item);
            if (tier != null) refreshDisplay(item, tier);
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        Tier offhandTier = tier(offhand);
        if (offhandTier != null) refreshDisplay(offhand, offhandTier);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() == null || !event.getAction().isRightClick()) return;
        ItemStack launcher = event.getItem();
        Tier tier = tier(launcher);
        if (tier == null || !enabled()) return;

        ensureInstance(launcher);
        refreshDisplay(launcher, tier);

        if (event.getPlayer().isSneaking()) {
            event.setCancelled(true);
            openStorage(event.getPlayer(), launcher, tier);
            return;
        }

        int stored = stored(launcher);
        if (!event.getPlayer().isGliding()) {
            // Nur die Item-Benutzung blockieren. Eine Truhe/Tür o. ä. darf trotzdem normal öffnen.
            event.setUseItemInHand(Event.Result.DENY);
            event.getPlayer().sendActionBar(Component.text("Shift + Rechtsklick: Raketen einlagern", NamedTextColor.GRAY));
            return;
        }
        if (stored <= 0) {
            event.setUseItemInHand(Event.Result.DENY);
            event.getPlayer().sendActionBar(Component.text("Keine Raketen eingelagert.", NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onElytraBoost(PlayerElytraBoostEvent event) {
        ItemStack used = event.getItemStack();
        Tier tier = tier(used);
        if (tier == null || !enabled()) return;

        ItemStack launcher = handItem(event.getPlayer(), event.getHand());
        if (tier(launcher) == null) launcher = used;

        int stored = stored(launcher);
        if (stored <= 0) {
            event.setCancelled(true);
            event.setShouldConsume(false);
            event.getPlayer().sendActionBar(Component.text("Keine Raketen eingelagert.", NamedTextColor.RED));
            return;
        }

        // Paper kann den eigentlichen Firework-Verbrauch nativ unterbinden.
        event.setShouldConsume(false);

        FireworkMeta fireworkMeta = event.getFirework().getFireworkMeta();
        fireworkMeta.clearEffects();
        fireworkMeta.setPower(tier.flightDuration(this));
        event.getFirework().setFireworkMeta(fireworkMeta);

        setStored(launcher, stored - 1, tier);
        if (showBoostActionbar()) {
            event.getPlayer().sendActionBar(Component.text(
                    "Raketen: " + numbers.format(stored - 1) + " / " + numbers.format(tier.capacity(this)),
                    NamedTextColor.LIGHT_PURPLE));
        }
    }

    // ---------------------------------------------------------------------
    // Einlagerungs-GUI
    // ---------------------------------------------------------------------

    private void openStorage(Player player, ItemStack launcher, Tier tier) {
        String instance = ensureInstance(launcher);
        StorageHolder holder = new StorageHolder(player.getUniqueId(), instance, tier.level);
        String title = configString("menu.title", "<dark_gray>Unendliche Rakete %level%</dark_gray>")
                .replace("%level%", roman(tier.level));
        Inventory inventory = Bukkit.createInventory(holder, 54, items.component(title));
        holder.inventory = inventory;

        ItemStack filler = items.item(Material.PURPLE_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = INPUT_END_EXCLUSIVE; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);
        inventory.setItem(INFO_SLOT, infoItem(launcher, tier));
        inventory.setItem(CLOSE_SLOT, items.item(Material.BARRIER, "<red>Schließen</red>",
                List.of("<gray>Alle Raketen oben werden beim Schließen eingelagert.</gray>")));
        player.openInventory(inventory);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onStorageClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof StorageHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player) || !holder.ownerId.equals(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        int raw = event.getRawSlot();
        if (raw >= INPUT_END_EXCLUSIVE && raw < top.getSize()) {
            event.setCancelled(true);
            if (raw == CLOSE_SLOT) player.closeInventory();
            return;
        }

        // Die gerade geöffnete Unendliche Rakete kann während des Menüs nicht verschoben/gedroppt werden.
        if (raw >= top.getSize() && belongsToInstance(event.getCurrentItem(), holder.instanceId)) {
            event.setCancelled(true);
            return;
        }

        // Shift-Klick aus dem Spielerinventar: nur echte Feuerwerksraketen in die oberen Slots bewegen.
        if (event.isShiftClick() && raw >= top.getSize()) {
            event.setCancelled(true);
            ItemStack current = event.getCurrentItem();
            if (!isFuel(current)) return;
            int moved = moveIntoInput(top, current);
            if (moved >= current.getAmount()) event.setCurrentItem(null);
            else current.setAmount(current.getAmount() - moved);
            return;
        }

        // In die Einlagerungsfläche dürfen ausschließlich normale Raketen gelegt werden.
        if (raw >= 0 && raw < INPUT_END_EXCLUSIVE) {
            ItemStack cursor = event.getCursor();
            if (cursor != null && !cursor.getType().isAir() && !isFuel(cursor)) {
                event.setCancelled(true);
                return;
            }
            if (event.getHotbarButton() >= 0) {
                ItemStack hotbar = player.getInventory().getItem(event.getHotbarButton());
                if (hotbar != null && !hotbar.getType().isAir() && !isFuel(hotbar)) event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onStorageDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof StorageHolder)) return;
        if (event.getRawSlots().stream().anyMatch(slot -> slot >= INPUT_END_EXCLUSIVE && slot < top.getSize())) {
            event.setCancelled(true);
            return;
        }
        if (event.getRawSlots().stream().anyMatch(slot -> slot >= 0 && slot < INPUT_END_EXCLUSIVE)
                && !isFuel(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onStorageClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof StorageHolder holder)) return;
        if (!holder.ownerId.equals(player.getUniqueId()) || holder.completed) return;
        holder.completed = true;

        Tier tier = Tier.byLevel(holder.tierLevel);
        ItemStack launcher = findByInstance(player, holder.instanceId);
        List<ItemStack> deposited = new ArrayList<>();
        for (int slot = 0; slot < INPUT_END_EXCLUSIVE; slot++) {
            ItemStack stack = event.getInventory().getItem(slot);
            if (stack == null || stack.getType().isAir()) continue;
            deposited.add(stack.clone());
            event.getInventory().setItem(slot, null);
        }

        if (tier == null || launcher == null || tier(launcher) != tier) {
            returnItems(player, deposited);
            player.sendActionBar(Component.text("Rakete nicht gefunden – Einlagerung abgebrochen.", NamedTextColor.RED));
            return;
        }

        int before = stored(launcher);
        int space = Math.max(0, tier.capacity(this) - before);
        int accepted = 0;
        List<ItemStack> leftovers = new ArrayList<>();
        for (ItemStack stack : deposited) {
            if (!isFuel(stack)) {
                leftovers.add(stack);
                continue;
            }
            int take = Math.min(stack.getAmount(), Math.max(0, space - accepted));
            accepted += take;
            int rest = stack.getAmount() - take;
            if (rest > 0) {
                ItemStack leftover = stack.clone();
                leftover.setAmount(rest);
                leftovers.add(leftover);
            }
        }

        if (accepted > 0) setStored(launcher, before + accepted, tier);
        else refreshDisplay(launcher, tier);
        returnItems(player, leftovers);

        if (accepted > 0) {
            player.sendActionBar(Component.text(
                    numbers.format(accepted) + " Raketen eingelagert • "
                            + numbers.format(before + accepted) + " / " + numbers.format(tier.capacity(this)),
                    NamedTextColor.LIGHT_PURPLE));
        }
    }

    // ---------------------------------------------------------------------
    // Amboss-Progression
    // ---------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack left = event.getInventory().getItem(0);
        ItemStack right = event.getInventory().getItem(1);
        Tier leftTier = tier(left);
        Tier rightTier = tier(right);
        if (leftTier == null && rightTier == null) return;

        // Sobald zwei Infinite-Rockets beteiligt sind, gelten ausschließlich unsere Upgrade-Regeln.
        if (leftTier == null || rightTier == null || leftTier != rightTier || leftTier == Tier.THREE) {
            if (leftTier != null && rightTier != null) event.setResult(null);
            return;
        }

        Tier resultTier = leftTier.next();
        if (resultTier == null) {
            event.setResult(null);
            return;
        }
        ItemStack result = customItems.create(resultTier.itemId, 1);
        if (result == null) {
            event.setResult(null);
            return;
        }

        // Wie abgesprochen: Inhalte werden addiert, aber niemals über das neue Stufenlimit hinaus.
        long combined = (long) stored(left) + stored(right);
        int storedResult = (int) Math.min(resultTier.capacity(this), Math.max(0L, combined));
        ensureInstance(result);
        setStored(result, storedResult, resultTier);
        event.getView().setRepairCost(leftTier.combineCost(this));
        event.getView().setRepairItemCountCost(1);
        event.setResult(result);
    }

    // ---------------------------------------------------------------------
    // Schutz vor versehentlichem Verbrauch außerhalb des Elytra-Flugs
    // ---------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (tier(event.getItem()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLoadCrossbow(EntityLoadCrossbowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        ItemStack opposite = event.getHand() == EquipmentSlot.HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        if (tier(opposite) != null) {
            event.setCancelled(true);
            player.sendActionBar(Component.text("Die Unendliche Rakete kann nicht als Armbrustmunition benutzt werden.", NamedTextColor.RED));
            return;
        }

        // Falls Minecraft eine Rakete aus einem normalen Inventarslot auswählt, erkennen wir die
        // geladene PDC-Kopie einen Tick später und geben genau ein Exemplar zurück.
        ItemStack crossbow = event.getCrossbow();
        EquipmentSlot hand = event.getHand();
        Bukkit.getScheduler().runTask(plugin, () -> cleanLoadedInfiniteRocket(player, hand, crossbow));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCrossbowShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemStack bow = event.getBow();
        ItemStack loaded = loadedInfiniteRocket(bow);
        if (loaded == null) return;

        event.setCancelled(true);
        clearChargedProjectiles(bow);
        restoreLauncherIfMissing(player, loaded);
        player.sendActionBar(Component.text("Die Unendliche Rakete kann nicht als Armbrustmunition benutzt werden.", NamedTextColor.RED));
    }

    private void cleanLoadedInfiniteRocket(Player player, EquipmentSlot hand, ItemStack eventCrossbow) {
        ItemStack handItem = handItem(player, hand);
        ItemStack crossbow = handItem != null && handItem.getType() == Material.CROSSBOW ? handItem : eventCrossbow;
        ItemStack loaded = loadedInfiniteRocket(crossbow);
        if (loaded == null) return;
        clearChargedProjectiles(crossbow);
        restoreLauncherIfMissing(player, loaded);
        player.updateInventory();
        player.sendActionBar(Component.text("Die Unendliche Rakete kann nicht als Armbrustmunition benutzt werden.", NamedTextColor.RED));
    }

    private ItemStack loadedInfiniteRocket(ItemStack crossbow) {
        if (crossbow == null || crossbow.getType() != Material.CROSSBOW || !(crossbow.getItemMeta() instanceof CrossbowMeta meta)) {
            return null;
        }
        for (ItemStack projectile : meta.getChargedProjectiles()) {
            if (tier(projectile) != null) return projectile.clone();
        }
        return null;
    }

    private void clearChargedProjectiles(ItemStack crossbow) {
        if (crossbow == null || !(crossbow.getItemMeta() instanceof CrossbowMeta meta)) return;
        meta.setChargedProjectiles(null);
        crossbow.setItemMeta(meta);
    }

    private void restoreLauncherIfMissing(Player player, ItemStack launcher) {
        String instance = instance(launcher);
        if (instance != null && findByInstance(player, instance) != null) return;
        if (instance == null) {
            Tier expectedTier = tier(launcher);
            int expectedStored = stored(launcher);
            for (ItemStack existing : player.getInventory().getContents()) {
                if (tier(existing) == expectedTier && stored(existing) == expectedStored) return;
            }
        }
        addOrDrop(player, launcher.clone());
    }

    // ---------------------------------------------------------------------
    // Item-/PDC-Helfer
    // ---------------------------------------------------------------------

    private Tier tier(ItemStack item) {
        String id = customItems.identify(item);
        if (ITEM_ID_1.equals(id)) return Tier.ONE;
        if (ITEM_ID_2.equals(id)) return Tier.TWO;
        if (ITEM_ID_3.equals(id)) return Tier.THREE;
        return null;
    }

    private boolean isFuel(ItemStack item) {
        return item != null && item.getType() == Material.FIREWORK_ROCKET && customItems.identify(item) == null;
    }

    private int stored(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        Integer value = item.getItemMeta().getPersistentDataContainer().get(storedKey, PersistentDataType.INTEGER);
        return value == null ? 0 : Math.max(0, value);
    }

    private void setStored(ItemStack item, int amount, Tier tier) {
        if (item == null || tier == null) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        int clamped = Math.max(0, Math.min(tier.capacity(this), amount));
        meta.getPersistentDataContainer().set(storedKey, PersistentDataType.INTEGER, clamped);
        item.setItemMeta(meta);
        refreshDisplay(item, tier);
    }

    private String ensureInstance(ItemStack item) {
        String existing = instance(item);
        if (existing != null && !existing.isBlank()) return existing;
        if (item == null || !item.hasItemMeta()) return null;
        String generated = UUID.randomUUID().toString();
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(instanceKey, PersistentDataType.STRING, generated);
        item.setItemMeta(meta);
        return generated;
    }

    private String instance(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(instanceKey, PersistentDataType.STRING);
    }

    private boolean belongsToInstance(ItemStack item, String expected) {
        String actual = instance(item);
        return actual != null && actual.equals(expected);
    }

    private ItemStack findByInstance(Player player, String instanceId) {
        if (instanceId == null) return null;
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (belongsToInstance(item, instanceId)) return item;
        }
        return null;
    }

    private void refreshDisplay(ItemStack item, Tier tier) {
        if (item == null || tier == null) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        // Die reale Firework-Meta entspricht immer der Launcher-Stufe. Explosions-Effekte besitzt
        // der Launcher selbst nie; eingelagerte Raketen werden nur als Zähler gespeichert.
        if (meta instanceof FireworkMeta fireworkMeta) {
            fireworkMeta.clearEffects();
            fireworkMeta.setPower(tier.flightDuration(this));
            meta = fireworkMeta;
        }

        int stored = stored(item);
        Component storageLine = Component.text("Speicher: ", NamedTextColor.AQUA)
                .append(Component.text(numbers.format(stored) + " / " + numbers.format(tier.capacity(this)), NamedTextColor.WHITE))
                .decoration(TextDecoration.ITALIC, false);

        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        boolean replaced = false;
        for (int i = 0; i < lore.size(); i++) {
            String plain = plainText.serialize(lore.get(i)).trim().toLowerCase(Locale.ROOT);
            if (plain.startsWith("speicher:")) {
                lore.set(i, storageLine);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            lore.add(Component.empty());
            lore.add(storageLine);
        }
        // Sichtbare Tankanzeige über die Vanilla-Durability-Leiste. Paper-Metas implementieren
        // Damageable auch für Items ohne normale Haltbarkeit; MAX_DAMAGE/DAMAGE dienen hier nur
        // als Anzeige und werden nie durch normalen Itemverschleiß verändert.
        int capacity = tier.capacity(this);
        int visualDamage = Math.max(0, Math.min(capacity, capacity - stored));
        if (meta instanceof Damageable damageable) {
            // +1 verhindert, dass ein komplett leerer Tank technisch auf exakt 0 Haltbarkeit
            // fällt. Die Leiste sieht trotzdem praktisch leer aus und der Launcher kann nie
            // versehentlich als "zerbrochenes" Item behandelt werden.
            damageable.setMaxDamage(capacity + 1);
            damageable.setDamage(visualDamage);
            meta = damageable;
        }

        meta.lore(lore);
        item.setItemMeta(meta);
    }

    private ItemStack infoItem(ItemStack launcher, Tier tier) {
        int stored = stored(launcher);
        return items.item(Material.HOPPER, "<light_purple>Raketen-Speicher</light_purple>", List.of(
                "<gray>Lege oben beliebige normale Feuerwerksraketen hinein.</gray>",
                "<gray>Beim Einlagern zählen sie jeweils als <white>1 Ladung</white>.</gray>",
                "",
                "<gray>Ursprüngliche Flugdauer und Explosionseffekte</gray>",
                "<gray>werden nicht übernommen. Dieser Launcher feuert immer</gray>",
                "<gray>mit <aqua>Flugdauer " + roman(tier.flightDuration(this)) + "</aqua>.</gray>",
                "",
                "<aqua>Gespeichert:</aqua> <white>" + numbers.format(stored) + " / " + numbers.format(tier.capacity(this)) + "</white>"
        ));
    }

    private ItemStack handItem(Player player, EquipmentSlot hand) {
        return hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
    }

    private int moveIntoInput(Inventory inventory, ItemStack source) {
        int remaining = source.getAmount();
        int max = source.getMaxStackSize();
        for (int slot = 0; slot < INPUT_END_EXCLUSIVE && remaining > 0; slot++) {
            ItemStack target = inventory.getItem(slot);
            if (target == null || target.getType().isAir() || !target.isSimilar(source)) continue;
            int free = Math.max(0, Math.min(max, target.getMaxStackSize()) - target.getAmount());
            if (free <= 0) continue;
            int moved = Math.min(free, remaining);
            target.setAmount(target.getAmount() + moved);
            remaining -= moved;
        }
        for (int slot = 0; slot < INPUT_END_EXCLUSIVE && remaining > 0; slot++) {
            ItemStack target = inventory.getItem(slot);
            if (target != null && !target.getType().isAir()) continue;
            int moved = Math.min(max, remaining);
            ItemStack placed = source.clone();
            placed.setAmount(moved);
            inventory.setItem(slot, placed);
            remaining -= moved;
        }
        return source.getAmount() - remaining;
    }

    private void returnItems(Player player, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) addOrDrop(player, stack);
    }

    private void addOrDrop(Player player, ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) return;
        player.getInventory().addItem(stack).values().forEach(leftover ->
                player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    // ---------------------------------------------------------------------
    // Config
    // ---------------------------------------------------------------------

    private boolean enabled() {
        return plugin.configs().items().getBoolean(CONFIG_ROOT + ".enabled", true);
    }

    private boolean showBoostActionbar() {
        return plugin.configs().items().getBoolean(CONFIG_ROOT + ".show-boost-actionbar", true);
    }

    private String configString(String path, String fallback) {
        return plugin.configs().items().getString(CONFIG_ROOT + "." + path, fallback);
    }

    private int capacity(int level, int fallback) {
        return Math.max(1, plugin.configs().items().getInt(CONFIG_ROOT + ".tiers." + level + ".capacity", fallback));
    }

    private int flightDuration(int level) {
        return Math.max(1, Math.min(3,
                plugin.configs().items().getInt(CONFIG_ROOT + ".tiers." + level + ".flight-duration", level)));
    }

    private int combineCost(int level, int fallback) {
        return Math.max(1, plugin.configs().items().getInt(CONFIG_ROOT + ".tiers." + level + ".combine-cost-levels", fallback));
    }

    private static String roman(int value) {
        return switch (value) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            default -> Integer.toString(value);
        };
    }

    private enum Tier {
        ONE(1, ITEM_ID_1, 2304, 5),
        TWO(2, ITEM_ID_2, 4608, 10),
        THREE(3, ITEM_ID_3, 6912, 0);

        private final int level;
        private final String itemId;
        private final int defaultCapacity;
        private final int defaultCombineCost;

        Tier(int level, String itemId, int defaultCapacity, int defaultCombineCost) {
            this.level = level;
            this.itemId = itemId;
            this.defaultCapacity = defaultCapacity;
            this.defaultCombineCost = defaultCombineCost;
        }

        private int capacity(InfiniteRocketListener listener) {
            return listener.capacity(level, defaultCapacity);
        }

        private int flightDuration(InfiniteRocketListener listener) {
            return listener.flightDuration(level);
        }

        private int combineCost(InfiniteRocketListener listener) {
            return listener.combineCost(level, defaultCombineCost);
        }

        private Tier next() {
            return switch (this) {
                case ONE -> TWO;
                case TWO -> THREE;
                case THREE -> null;
            };
        }

        private static Tier byLevel(int level) {
            return switch (level) {
                case 1 -> ONE;
                case 2 -> TWO;
                case 3 -> THREE;
                default -> null;
            };
        }
    }

    private static final class StorageHolder implements InventoryHolder {
        private final UUID ownerId;
        private final String instanceId;
        private final int tierLevel;
        private Inventory inventory;
        private boolean completed;

        private StorageHolder(UUID ownerId, String instanceId, int tierLevel) {
            this.ownerId = ownerId;
            this.instanceId = instanceId;
            this.tierLevel = tierLevel;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
