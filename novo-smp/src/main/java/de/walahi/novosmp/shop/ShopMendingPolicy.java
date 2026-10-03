package de.walahi.novosmp.shop;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.messages.MessageChannel;
import de.walahi.novosmp.items.CustomItemManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Kennzeichnet im Custom-Item-Shop gekaufte Werkzeuge, auf denen Mending gesperrt ist.
 *
 * <p>Die Sperre wird absichtlich beim Shop-Kauf auf das konkrete Item geschrieben. Dadurch
 * bleibt z. B. eine gleichnamige Berufsaxt, die nicht aus dem Shop stammt, unabhängig davon.
 * Die Kennzeichnung liegt im PersistentDataContainer und überlebt Umbenennen, Lagern und
 * Serialisieren des Items.</p>
 */
public final class ShopMendingPolicy implements Listener {
    private static final byte LOCKED = 1;

    private final SMPCorePlugin plugin;
    private final NamespacedKey lockKey;
    private final CustomItemManager customItems;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final PlainTextComponentSerializer plainText = PlainTextComponentSerializer.plainText();
    private final Enchantment mending;

    public ShopMendingPolicy(SMPCorePlugin plugin, CustomItemManager customItems) {
        this.plugin = plugin;
        this.customItems = customItems;
        this.lockKey = new NamespacedKey(plugin, "shop_mending_locked");
        this.mending = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("mending"));
    }

    /**
     * Wendet die Shop-Regel auf ein frisch erzeugtes Produkt an.
     * Bei erlaubtem Mending wird keine Sperre gesetzt; ansonsten erhält das Item zusätzlich
     * die sichtbare Warnung aus custom-item-shop.yml.
     */
    public ItemStack apply(ItemStack item, boolean allowMending) {
        if (item == null || item.getType().isAir()) return item;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        if (allowMending) {
            meta.getPersistentDataContainer().remove(lockKey);
            item.setItemMeta(meta);
            return item;
        }

        return markLocked(item, true);
    }

    public boolean isLocked(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        Byte value = item.getItemMeta().getPersistentDataContainer().get(lockKey, PersistentDataType.BYTE);
        if (value != null && value == LOCKED) return true;
        String customId = customItems == null ? null : customItems.identify(item);
        return customId != null && customItems.find(customId).map(def -> def.mendingBlocked()).orElse(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack left = event.getInventory().getItem(0);
        ItemStack right = event.getInventory().getItem(1);
        if (!isLocked(left) && !isLocked(right)) return;

        ItemStack result = event.getResult();
        if (result == null) return;
        // Sobald eine gesperrte Shop-Fähigkeit in das Ergebnis eingeht, bleibt
        // auch das kombinierte Werkzeug dauerhaft Mending-gesperrt.
        if (hasMending(result)) {
            event.setResult(null);
            return;
        }
        boolean customDefinitionLock = isDefinitionLocked(left) || isDefinitionLocked(right);
        event.setResult(customDefinitionLock
                ? markLocked(result.clone(), false)
                : apply(result.clone(), false));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        if (!isLocked(event.getItem()) || mending == null) return;
        if (!event.getEnchantsToAdd().containsKey(mending)) return;
        event.setCancelled(true);
        sendBlocked(event.getEnchanter(), event.getItem());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAnvilResultClick(InventoryClickEvent event) {
        if (!(event.getInventory() instanceof AnvilInventory anvil)) return;
        if (event.getRawSlot() != 2) return;
        if (!isLocked(anvil.getItem(0)) && !isLocked(anvil.getItem(1))) return;
        ItemStack result = anvil.getItem(2);
        if (!hasMending(result) && !hasMending(anvil.getItem(1))) return;
        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player player) {
            ItemStack locked = isLocked(anvil.getItem(0)) ? anvil.getItem(0) : anvil.getItem(1);
            sendBlocked(player, locked);
        }
    }

    private boolean isDefinitionLocked(ItemStack item) {
        String customId = customItems == null ? null : customItems.identify(item);
        return customId != null && customItems.find(customId).map(def -> def.mendingBlocked()).orElse(false);
    }

    private ItemStack markLocked(ItemStack item, boolean appendConfiguredWarning) {
        if (item == null || item.getType().isAir()) return item;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.getPersistentDataContainer().set(lockKey, PersistentDataType.BYTE, LOCKED);
        if (mending != null) meta.removeEnchant(mending);
        if (appendConfiguredWarning) appendWarning(meta);
        item.setItemMeta(meta);
        return item;
    }

    private boolean hasMending(ItemStack item) {
        if (item == null || item.getType().isAir() || mending == null) return false;
        if (item.containsEnchantment(mending)) return true;
        ItemMeta meta = item.getItemMeta();
        return meta instanceof EnchantmentStorageMeta storage && storage.hasStoredEnchant(mending);
    }

    private void appendWarning(ItemMeta meta) {
        List<String> configured = plugin.configs().customItemShop().getStringList("mending-policy.warning-lore");
        if (configured.isEmpty()) {
            configured = List.of(
                    "",
                    "<red>⚠ Kein Mending möglich</red>",
                    "<gray>Dieses Shop-Werkzeug kann nicht mit</gray>",
                    "<gray>Reparatur verzaubert werden.</gray>"
            );
        }

        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        boolean alreadyVisible = lore.stream()
                .map(plainText::serialize)
                .anyMatch(line -> line.toLowerCase().contains("kein mending möglich"));
        if (alreadyVisible) return;

        for (String line : configured) {
            lore.add(miniMessage.deserialize(line).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
    }

    private void sendBlocked(Player player, ItemStack item) {
        if (isDefinitionLocked(item)) {
            player.sendRichMessage("<red>Dieses Custom-Werkzeug kann nicht mit Reparatur verzaubert werden.</red>");
            return;
        }
        plugin.messages().sendConfigured(
                player,
                plugin.configs().customItemShop(),
                "mending-policy.blocked-message",
                MessageChannel.SHOP,
                "<red>Dieses Custom-Werkzeug kann nicht mit Reparatur verzaubert werden.</red>"
        );
    }
}
