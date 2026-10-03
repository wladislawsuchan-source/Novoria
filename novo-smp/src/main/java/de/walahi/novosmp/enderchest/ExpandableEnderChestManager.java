package de.walahi.novosmp.enderchest;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.playerdata.NativePlayerDataAccess;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.BlockState;
import org.bukkit.block.EnderChest;
import org.bukkit.entity.Player;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.event.block.Action;
import org.bukkit.event.EventPriority;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

public final class ExpandableEnderChestManager implements Listener, de.walahi.smpcore.bridge.EnderChestAccess {
    private final SMPCorePlugin plugin;
    private final EnderChestUpgradeRepository repository;
    private final Map<UUID,Integer> levels = new HashMap<>();
    private static final String PRICE_LINE_MARKER = "smpcore:sell-price";

    private final Map<UUID, Location> openedBlocks = new HashMap<>();
    /** Exactly one authoritative live inventory per EC owner. All viewers share this instance. */
    private final Map<UUID, ActiveSession> activeSessions = new HashMap<>();
    /** Invalidates delayed physical opens when /ec or a newer click wins the race. */
    private final Map<UUID, Long> physicalOpenTokens = new HashMap<>();
    private long nextPhysicalOpenToken;
    private final NamespacedKey legacyRenderedPriceKey;

    private record ActiveSession(ExpandableEnderChestHolder holder, Inventory inventory) {}

    public ExpandableEnderChestManager(SMPCorePlugin plugin) {
        this.plugin = plugin;
        this.repository = new EnderChestUpgradeRepository(plugin.storageManager());
        this.legacyRenderedPriceKey = new NamespacedKey(plugin, "rendered_sell_price");
    }

    public int level(Player player) {
        int loaded = load(player).level();
        levels.put(player.getUniqueId(), loaded);
        return loaded;
    }
    public int size(Player player) { return 27 + level(player) * 9; }

    public void open(Player player) {
        open(player, (OfflinePlayer) player);
    }

    @Override
    public boolean open(Player viewer, OfflinePlayer owner) {
        physicalOpenTokens.remove(viewer.getUniqueId());
        return openInternal(viewer, owner);
    }

    private boolean openInternal(Player viewer, OfflinePlayer owner) {
        ActiveSession existing = activeSessions.get(owner.getUniqueId());
        if (existing != null) {
            if (viewer.getOpenInventory().getTopInventory() != existing.inventory()) {
                viewer.openInventory(existing.inventory());
            }
            return true;
        }

        Player onlineOwner = owner.getPlayer();
        var data = load(owner.getUniqueId(), onlineOwner);
        levels.put(owner.getUniqueId(), data.level());
        int size = 27 + data.level() * 9;
        ExpandableEnderChestHolder holder = new ExpandableEnderChestHolder(owner.getUniqueId());
        String ownerName = owner.getName() == null ? owner.getUniqueId().toString() : owner.getName();
        String title = viewer.getUniqueId().equals(owner.getUniqueId())
                ? "<dark_purple>Enderchest</dark_purple>"
                : "<dark_purple>Enderchest: <light_purple>" + ownerName + "</light_purple></dark_purple>";
        Inventory inventory = Bukkit.createInventory(holder, size,
                net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(title));
        holder.inventory(inventory);

        ItemStack[] source;
        if (data.exists()) {
            // Bereits vorhandene erweiterte EC-Daten bleiben die einzige Plugin-Speicherquelle.
            source = data.contents();
        } else if (onlineOwner != null) {
            source = onlineOwner.getEnderChest().getContents();
        } else {
            try {
                // Kein zweiter Snapshot: direkt aus EnderItems der Vanilla-playerdata lesen.
                source = NativePlayerDataAccess.loadEnderChest(owner, 27);
            } catch (Exception exception) {
                plugin.getLogger().severe("Offline-Enderchest von " + ownerName
                        + " konnte nicht aus playerdata geladen werden: " + exception.getMessage());
                viewer.sendMessage(Component.text("Die Offline-Enderchest konnte nicht geladen werden.", NamedTextColor.RED));
                return false;
            }
        }

        ItemStack[] migrated = normalizePreservingSlots(Arrays.copyOf(source, size));
        inventory.setContents(migrated);

        // Erst öffnen, wenn der Snapshot sicher persistiert werden konnte. Andernfalls könnte
        // der Spieler Items entnehmen, während die Datenbank weiterhin den alten Stand enthält.
        try {
            repository.save(owner.getUniqueId(), data.level(), migrated);
        } catch (Exception ex) {
            plugin.getLogger().severe("EC-Sicherheitsöffnung für " + ownerName
                    + " abgebrochen: Snapshot konnte nicht gespeichert werden: " + ex.getMessage());
            viewer.sendMessage(Component.text("Die Enderchest ist wegen eines Speicherfehlers vorübergehend gesperrt.",
                    NamedTextColor.RED));
            return false;
        }

        activeSessions.put(owner.getUniqueId(), new ActiveSession(holder, inventory));
        viewer.openInventory(inventory);
        return true;
    }

    public boolean upgrade(Player player, int expectedLevel) {
        var data = load(player);
        levels.put(player.getUniqueId(), data.level());
        if (data.level() != expectedLevel - 1) return false;
        try {
            ItemStack[] source = data.exists() ? data.contents() : player.getEnderChest().getContents();
            repository.save(player.getUniqueId(), expectedLevel, Arrays.copyOf(source, 27 + expectedLevel * 9));
            levels.put(player.getUniqueId(), expectedLevel);
            return true;
        } catch (Exception ex) {
            plugin.getLogger().severe("EC-Upgrade konnte nicht gespeichert werden: " + ex.getMessage());
            return false;
        }
    }

    private EnderChestUpgradeRepository.Data load(Player player) {
        return load(player.getUniqueId(), player);
    }

    private EnderChestUpgradeRepository.Data load(UUID ownerId, Player onlineFallback) {
        try {
            return repository.load(ownerId);
        } catch (Exception ex) {
            plugin.getLogger().severe("EC-Daten konnten nicht geladen werden: " + ex.getMessage());
            ItemStack[] fallback = onlineFallback == null
                    ? new ItemStack[0]
                    : onlineFallback.getEnderChest().getContents();
            return new EnderChestUpgradeRepository.Data(0, fallback, false);
        }
    }

    private boolean save(ExpandableEnderChestHolder holder, Inventory inventory) {
        int level = Math.max(0, (inventory.getSize() - 27) / 9);
        ItemStack[] normalized = normalizePreservingSlots(inventory.getContents());
        inventory.setContents(normalized);
        try {
            repository.save(holder.owner(), level, normalized);
            levels.put(holder.owner(), level);
            return true;
        } catch (Exception ex) {
            plugin.getLogger().severe("EC-Inhalt von " + holder.owner()
                    + " konnte nicht gespeichert werden; Live-Sitzung bleibt gesperrt erhalten: " + ex.getMessage());
            return false;
        }
    }

    /**
     * Migriert jeden Slot einzeln, ohne Items zusammenzufassen oder nach links zu verschieben.
     * Dadurch bleiben bewusst gesetzte Leerstellen und die komplette Sortierung der Enderchest erhalten.
     */
    private ItemStack[] normalizePreservingSlots(ItemStack[] source) {
        ItemStack[] result = new ItemStack[source.length];
        for (int slot = 0; slot < source.length; slot++) {
            ItemStack original = source[slot];
            if (original == null || original.getType().isAir()) continue;

            ItemStack item;
            try {
                item = ItemStack.deserializeBytes(original.serializeAsBytes());
            } catch (RuntimeException exception) {
                item = original.clone();
            }

            removeLegacySellPriceMetadata(item);
            result[slot] = item;
        }
        return result;
    }

    /**
     * Entfernt ausschließlich alte, vom Sell-Preis-Renderer erzeugte Metadaten.
     * Echte Namen, Verzauberungen und benutzerdefinierte Lore bleiben erhalten.
     */
    private boolean removeLegacySellPriceMetadata(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;

        boolean changed = false;
        String legacyPrice = meta.getPersistentDataContainer()
                .get(legacyRenderedPriceKey, PersistentDataType.STRING);
        if (legacyPrice != null) {
            meta.getPersistentDataContainer().remove(legacyRenderedPriceKey);
            changed = true;
        }

        List<Component> lore = meta.lore();
        if (lore != null && !lore.isEmpty()) {
            List<Component> cleaned = new ArrayList<>(lore);
            boolean safeVanillaCandidate = !meta.hasCustomName()
                    && !meta.hasCustomModelData()
                    && meta.getPersistentDataContainer().getKeys().isEmpty();

            boolean removed = cleaned.removeIf(line -> isLegacySellPriceLine(line, legacyPrice, safeVanillaCandidate));
            if (removed) {
                meta.lore(cleaned.isEmpty() ? null : cleaned);
                changed = true;
            }
        }

        if (changed) stack.setItemMeta(meta);
        return changed;
    }

    private boolean isLegacySellPriceLine(Component line, String legacyPrice, boolean safeVanillaCandidate) {
        if (line == null) return false;
        if (PRICE_LINE_MARKER.equals(line.insertion())) return true;

        String plain = PlainTextComponentSerializer.plainText().serialize(line).trim();
        if (legacyPrice != null && plain.equals(legacyPrice)) return true;

        // Sehr alte Versionen hatten noch keinen Marker und keinen PDC-Eintrag.
        // Diese Zeilen werden nur auf ansonsten normalen Vanilla-Items entfernt.
        return safeVanillaCandidate
                && NamedTextColor.GOLD.equals(line.color())
                && plain.matches("[0-9][0-9.]* Coin(?:s)?");
    }


    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEnderChestInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null || event.getClickedBlock().getType() != Material.ENDER_CHEST) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        Location blockLocation = event.getClickedBlock().getLocation();

        closePhysicalEnderChest(player.getUniqueId());
        BlockState state = blockLocation.getBlock().getState();
        if (state instanceof EnderChest enderChest) {
            enderChest.open();
            openedBlocks.put(player.getUniqueId(), blockLocation);
        }

        long token = ++nextPhysicalOpenToken;
        physicalOpenTokens.put(player.getUniqueId(), token);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!Long.valueOf(token).equals(physicalOpenTokens.get(player.getUniqueId()))) return;
            physicalOpenTokens.remove(player.getUniqueId());
            if (player.isOnline()) {
                openInternal(player, player);
            } else {
                closePhysicalEnderChest(player.getUniqueId());
            }
        });
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof ExpandableEnderChestHolder holder)) return;
        boolean saved = save(holder, event.getInventory());
        closePhysicalEnderChest(event.getPlayer().getUniqueId());
        if (saved) {
            Inventory closedInventory = event.getInventory();
            Bukkit.getScheduler().runTask(plugin, () -> {
                ActiveSession active = activeSessions.get(holder.owner());
                if (active != null && active.inventory() == closedInventory
                        && active.inventory().getViewers().isEmpty()) {
                    activeSessions.remove(holder.owner(), active);
                }
            });
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        physicalOpenTokens.remove(playerId);
        Inventory top = event.getPlayer().getOpenInventory().getTopInventory();
        if (top.getHolder() instanceof ExpandableEnderChestHolder holder) {
            save(holder, top);
        }
        levels.remove(playerId);
        closePhysicalEnderChest(playerId);
    }

    /** Persists every authoritative live EC before the database is shut down. */
    public void shutdown() {
        Set<Inventory> savedInventories = new HashSet<>();
        for (ActiveSession session : List.copyOf(activeSessions.values())) {
            if (savedInventories.add(session.inventory())) save(session.holder(), session.inventory());
        }
        activeSessions.clear();
        physicalOpenTokens.clear();
        for (UUID playerId : List.copyOf(openedBlocks.keySet())) closePhysicalEnderChest(playerId);
    }

    private void closePhysicalEnderChest(UUID playerId) {
        Location location = openedBlocks.remove(playerId);
        if (location == null || location.getWorld() == null) return;

        BlockState state = location.getBlock().getState();
        if (state instanceof EnderChest enderChest) {
            enderChest.close();
        }
    }
}
