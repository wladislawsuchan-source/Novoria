package de.walahi.novosmp.economy.sell;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.BukkitConverters;
import com.comphenix.protocol.wrappers.Converters;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rendert den Gesamtpreis ausschließlich in ausgehenden Paketen.
 *
 * Moderne Clients senden bei Inventarklicks die von ihnen gesehenen Item-
 * Komponenten wieder an den Server zurück. Deshalb wird die virtuelle
 * Preis-Lore aus WINDOW_CLICK/SET_CREATIVE_SLOT entfernt, bevor Vanilla den
 * Klick validiert. So bleibt das serverseitige Item vollständig unverändert
 * und kann trotz mengenabhängiger Gesamtpreis-Anzeige normal gestapelt werden.
 */
final class SellPricePacketListener extends PacketAdapter {
    private final SellManager sellManager;

    SellPricePacketListener(SellManager sellManager) {
        super(sellManager.plugin(), ListenerPriority.NORMAL,
                PacketType.Play.Server.WINDOW_ITEMS,
                PacketType.Play.Server.SET_SLOT,
                PacketType.Play.Client.WINDOW_CLICK,
                PacketType.Play.Client.SET_CREATIVE_SLOT);
        this.sellManager = sellManager;
    }

    @Override
    public void onPacketSending(PacketEvent event) {
        // Preise werden nur im normalen Spielerinventar und im /sell-Menü
        // gerendert. Kisten, Enderchests, Shulkerboxen, Hopper und andere GUIs
        // erhalten bewusst keine virtuelle Preis-Lore.
        if (!isPriceDisplayContext(event)) return;

        if (event.getPacketType() == PacketType.Play.Server.WINDOW_ITEMS) {
            rewriteWindowItems(event);
        } else if (event.getPacketType() == PacketType.Play.Server.SET_SLOT) {
            rewriteSetSlot(event);
        }
    }

    @Override
    public void onPacketReceiving(PacketEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.WINDOW_CLICK) {
            sanitizeWindowClick(event);
        } else if (event.getPacketType() == PacketType.Play.Client.SET_CREATIVE_SLOT) {
            sanitizeAllDirectItemFields(event);
        }
    }


    private boolean isPriceDisplayContext(PacketEvent event) {
        if (event.getPlayer() == null) return false;
        var view = event.getPlayer().getOpenInventory();
        if (view.getTopInventory().getHolder() instanceof SellInventoryHolder) return true;
        InventoryType type = view.getTopInventory().getType();
        return type == InventoryType.CRAFTING || type == InventoryType.PLAYER;
    }

    private void rewriteWindowItems(PacketEvent event) {
        List<ItemStack> original = event.getPacket().getItemListModifier().readSafely(0);
        if (original == null || original.isEmpty()) return;

        List<ItemStack> rendered = new ArrayList<>(original.size());
        for (ItemStack stack : original) {
            rendered.add(sellManager.createDisplayStack(event.getPlayer(), stack));
        }
        event.getPacket().getItemListModifier().writeSafely(0, rendered);

        // WINDOW_ITEMS enthält in modernen Versionen zusätzlich den Cursor-Stack.
        rewriteAllDirectItemFields(event, true);
    }

    private void rewriteSetSlot(PacketEvent event) {
        rewriteAllDirectItemFields(event, true);
    }

    private void sanitizeWindowClick(PacketEvent event) {
        // Der mitgeführte Cursor-Stack ist ein direktes ItemStack-Feld.
        sanitizeAllDirectItemFields(event);

        // Seit 1.17 enthält WINDOW_CLICK außerdem eine Map aus allen vom Client
        // als verändert gemeldeten Slots. Auch diese Kopien müssen von der rein
        // visuellen Lore bereinigt werden, bevor der Server sie vergleicht.
        StructureModifier<Map<Integer, ItemStack>> maps = event.getPacket().getMaps(
                Converters.passthrough(Integer.class),
                BukkitConverters.getItemStackConverter());

        for (int index = 0; index < maps.size(); index++) {
            try {
                Map<Integer, ItemStack> original = maps.readSafely(index);
                if (original == null || original.isEmpty()) continue;

                Map<Integer, ItemStack> cleaned = new LinkedHashMap<>(original.size());
                original.forEach((slot, stack) -> cleaned.put(slot,
                        stack == null ? null : sellManager.removeDisplayPrice(stack)));
                maps.writeSafely(index, cleaned);
            } catch (IllegalArgumentException ignored) {
                // ProtocolLib 5.x kann die modernen 1.21.x DataComponent-
                // Einträge <empty>/ActualItem in changedSlots nicht immer in
                // Bukkit-ItemStacks konvertieren. Der Klick darf deswegen nicht
                // fehlschlagen und die Konsole nicht zugespammt werden.
            }
        }
    }

    private void rewriteAllDirectItemFields(PacketEvent event, boolean display) {
        StructureModifier<ItemStack> items = event.getPacket().getItemModifier();
        for (int index = 0; index < items.size(); index++) {
            try {
                ItemStack stack = items.readSafely(index);
                if (stack == null || stack.getType().isAir()) continue;
                items.writeSafely(index, display
                        ? sellManager.createDisplayStack(event.getPlayer(), stack)
                        : sellManager.removeDisplayPrice(stack));
            } catch (IllegalArgumentException ignored) {
                // Leere bzw. neue DataComponent-Itemwerte sind in manchen
                // ProtocolLib/Paper-Kombinationen kein Bukkit-ItemStack.
            }
        }
    }

    private void sanitizeAllDirectItemFields(PacketEvent event) {
        rewriteAllDirectItemFields(event, false);
    }
}
