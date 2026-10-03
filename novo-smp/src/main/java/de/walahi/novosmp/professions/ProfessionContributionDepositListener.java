package de.walahi.novosmp.professions;

import de.walahi.smpcore.gui.MiniMessageItems;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Editable material deposit inventory for profession milestones. */
final class ProfessionContributionDepositListener implements Listener {
    private static final int DEPOSIT_END_EXCLUSIVE = 45;
    private static final int BACK_SLOT = 45;
    private static final int INFO_SLOT = 49;
    private static final int SUBMIT_SLOT = 53;

    private final ProfessionManager manager;
    private final ProfessionConfig config;
    private final MiniMessageItems items = new MiniMessageItems();

    ProfessionContributionDepositListener(ProfessionManager manager, ProfessionConfig config) {
        this.manager = manager;
        this.config = config;
    }

    void open(Player player, int milestone) {
        open(player, ProfessionManager.LUMBERJACK, milestone);
    }

    void open(Player player, String professionId, int milestone) {
        DepositHolder holder = new DepositHolder(player.getUniqueId(), professionId, milestone);
        String title = config.string("menus.deposit.title", "<dark_gray>%profession% • Materialien einzahlen</dark_gray>")
                .replace("%profession%", switch (professionId) {
                    case ProfessionManager.MINER -> "Bergarbeiter";
                    case ProfessionManager.HUNTER -> "Jäger";
                    case ProfessionManager.ANGLER -> "Angler";
                    default -> "Holzfäller";
                });
        Inventory inventory = Bukkit.createInventory(holder, 54, items.component(title));
        holder.inventory = inventory;

        Material fillerMaterial = professionId.equals(ProfessionManager.MINER)
                ? Material.CYAN_STAINED_GLASS_PANE
                : professionId.equals(ProfessionManager.ANGLER) ? Material.BLUE_STAINED_GLASS_PANE
                : professionId.equals(ProfessionManager.HUNTER) ? Material.RED_STAINED_GLASS_PANE
                : Material.BROWN_STAINED_GLASS_PANE;
        ItemStack filler = items.item(fillerMaterial, " ", List.of());
        for (int slot = DEPOSIT_END_EXCLUSIVE; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);
        inventory.setItem(BACK_SLOT, items.item(Material.ARROW, "<yellow>Einzahlen & zurück</yellow>",
                List.of("<gray>Alle passenden Items werden eingezahlt.</gray>",
                        "<gray>Falsche Items und Überschüsse kommen zurück.</gray>")));
        inventory.setItem(INFO_SLOT, items.item(Material.HOPPER, "<gold>Einzahlungsinventar</gold>",
                List.of("<gray>Lege alle gewünschten Materialien oben hinein.</gray>",
                        "<gray>Beim Schließen werden passende Items eingezahlt.</gray>")));
        inventory.setItem(SUBMIT_SLOT, items.item(Material.LIME_DYE, "<green>Einzahlen</green>",
                List.of("<gray>Fortschritt speichern und zur Übersicht zurückkehren.</gray>")));
        player.openInventory(inventory);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof DepositHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player) || !holder.ownerId.equals(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        int raw = event.getRawSlot();
        if (raw >= DEPOSIT_END_EXCLUSIVE && raw < top.getSize()) {
            event.setCancelled(true);
            if (raw == BACK_SLOT || raw == SUBMIT_SLOT) {
                holder.reopenOverview = true;
                player.closeInventory();
            }
            return;
        }

        // Shift-clicks from the player inventory may only move into the 45 deposit slots.
        if (event.isShiftClick() && raw >= top.getSize()) {
            event.setCancelled(true);
            ItemStack current = event.getCurrentItem();
            if (current == null || current.getType().isAir()) return;
            int moved = moveIntoDepositSlots(top, current);
            if (moved <= 0) return;
            if (moved >= current.getAmount()) event.setCurrentItem(null);
            else current.setAmount(current.getAmount() - moved);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof DepositHolder)) return;
        if (event.getRawSlots().stream().anyMatch(slot -> slot >= DEPOSIT_END_EXCLUSIVE
                && slot < event.getView().getTopInventory().getSize())) {
            event.setCancelled(true);
        }
    }

    private int moveIntoDepositSlots(Inventory inventory, ItemStack source) {
        int remaining = source.getAmount();
        int maximum = source.getMaxStackSize();
        for (int slot = 0; slot < DEPOSIT_END_EXCLUSIVE && remaining > 0; slot++) {
            ItemStack target = inventory.getItem(slot);
            if (target == null || target.getType().isAir() || !target.isSimilar(source)) continue;
            int capacity = Math.max(0, Math.min(maximum, target.getMaxStackSize()) - target.getAmount());
            if (capacity <= 0) continue;
            int moved = Math.min(capacity, remaining);
            target.setAmount(target.getAmount() + moved);
            remaining -= moved;
        }
        for (int slot = 0; slot < DEPOSIT_END_EXCLUSIVE && remaining > 0; slot++) {
            ItemStack target = inventory.getItem(slot);
            if (target != null && !target.getType().isAir()) continue;
            int moved = Math.min(maximum, remaining);
            ItemStack placed = source.clone();
            placed.setAmount(moved);
            inventory.setItem(slot, placed);
            remaining -= moved;
        }
        return source.getAmount() - remaining;
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof DepositHolder holder)) return;
        if (!holder.ownerId.equals(player.getUniqueId()) || holder.completed) return;
        holder.completed = true;

        List<ItemStack> deposited = new ArrayList<>();
        for (int slot = 0; slot < DEPOSIT_END_EXCLUSIVE; slot++) {
            ItemStack stack = event.getInventory().getItem(slot);
            if (stack == null || stack.getType().isAir()) continue;
            deposited.add(stack.clone());
            event.getInventory().setItem(slot, null);
        }

        List<ItemStack> leftovers = manager.depositContributionItems(player, holder.professionId, holder.milestone, deposited);
        manager.returnItems(player, leftovers);

        if (holder.reopenOverview && player.isOnline()) {
            Bukkit.getScheduler().runTask(manager.plugin(), () -> {
                if (player.isOnline()) manager.openContribution(player, holder.professionId, holder.milestone);
            });
        }
    }

    private static final class DepositHolder implements InventoryHolder {
        private final UUID ownerId;
        private final String professionId;
        private final int milestone;
        private Inventory inventory;
        private boolean completed;
        private boolean reopenOverview;

        private DepositHolder(UUID ownerId, String professionId, int milestone) {
            this.ownerId = ownerId;
            this.professionId = professionId;
            this.milestone = milestone;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
