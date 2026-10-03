package de.walahi.novosmp.orders;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Dedicated delivery inventory and close processing. */
final class OrderDeliveryListener implements Listener {
    private final OrderMenu owner;
    private final OrderManager orders;
    private final OrderMenuConfig config;
    private final OrderMenuItems items;

    OrderDeliveryListener(OrderMenu owner, OrderManager orders,
                          OrderMenuConfig config, OrderMenuItems items) {
        this.owner = owner;
        this.orders = orders;
        this.config = config;
        this.items = items;
    }

    void open(Player player, UUID orderId) {
        orders.processExpiredSafely();
        try {
            OrderListing listing = orders.repository().findActive(orderId);
            if (listing == null) {
                owner.sendMessage(player, "order-inactive",
                        "<red>Dieser Auftrag ist nicht mehr aktiv.</red>");
                owner.openMain(player);
                return;
            }
            if (listing.ownerId().equals(player.getUniqueId())) {
                owner.sendMessage(player, "own-order-delivery",
                        "<red>Du kannst deinen eigenen Auftrag nicht selbst erfüllen.</red>");
                return;
            }

            int rows = config.rows("menus.delivery.rows", 6);
            DeliveryHolder holder = new DeliveryHolder(player.getUniqueId(), listing.id(), listing.item());
            Inventory inventory = Bukkit.createInventory(holder, rows * 9,
                    config.component("menus.delivery.title", "<dark_gray>Liefern • %item%</dark_gray>",
                            Map.of("%item%", OrderMenuItems.readableName(listing.item().getType()))));
            holder.inventory = inventory;
            owner.setExternalInput(player, true);
            player.openInventory(inventory);
            owner.sendMessage(player, "delivery-instructions",
                    "<gray>Lege passende Items hinein und schließe das Inventar. "
                            + "Falsche Items und Überschüsse erhältst du zurück.</gray>");
        } catch (Exception exception) {
            owner.setExternalInput(player, false);
            owner.fail(player, "Order-Lieferinventar", exception);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof DeliveryHolder holder)) return;
        if (!holder.ownerId.equals(player.getUniqueId()) || holder.completed) return;
        holder.completed = true;
        owner.setExternalInput(player, false);

        List<ItemStack> deposited = new ArrayList<>();
        int matchingAmount = 0;
        for (ItemStack stack : event.getInventory().getContents()) {
            if (stack == null || stack.getType().isAir()) continue;
            ItemStack copy = stack.clone();
            deposited.add(copy);
            if (matches(copy, holder.sampleItem)) matchingAmount += copy.getAmount();
        }
        event.getInventory().clear();

        if (deposited.isEmpty()) return;
        if (matchingAmount <= 0) {
            returnItems(player, deposited);
            owner.sendMessage(player, "delivery-no-match",
                    "<red>Es waren keine passenden Items im Lieferinventar.</red>");
            return;
        }

        OrderManager.FulfillResult result = orders.fulfill(player, holder.orderId, matchingAmount);
        int accepted = result.acceptedAmount();
        returnItems(player, calculateReturns(deposited, holder.sampleItem, accepted));

        Map<String, String> placeholders = Map.of(
                "%amount%", items.format(accepted),
                "%payout%", items.format(result.payout())
        );
        switch (result.status()) {
            case SUCCESS -> {
                owner.sendMessage(player, "delivery-success",
                        "<green>%amount% Items geliefert. Du hast <gold>%payout% Coins</gold> erhalten.</green>",
                        placeholders);
                if (result.completed()) {
                    owner.sendMessage(player, "delivery-completed",
                            "<green>Der Auftrag ist vollständig erfüllt.</green>");
                }
            }
            case PAYOUT_IN_COLLECT -> owner.sendMessage(player, "delivery-collect",
                    "<yellow>%amount% Items geliefert. Deine Auszahlung liegt sicher in Order-Collect.</yellow>",
                    placeholders);
            case NOT_ACTIVE -> owner.sendMessage(player, "delivery-inactive",
                    "<red>Der Auftrag war nicht mehr aktiv. Alle Items wurden zurückgegeben.</red>");
            case NO_MATCHING_ITEMS -> owner.sendMessage(player, "delivery-rejected",
                    "<red>Es wurden keine passenden Items angenommen.</red>");
            case STORAGE_ERROR -> owner.sendMessage(player, "delivery-storage",
                    "<red>Die Lieferung konnte nicht gespeichert werden. Alle Items wurden zurückgegeben.</red>");
        }
    }

    private boolean matches(ItemStack offered, ItemStack sample) {
        if (offered == null || sample == null) return false;
        ItemStack offeredOne = offered.clone();
        ItemStack sampleOne = sample.clone();
        offeredOne.setAmount(1);
        sampleOne.setAmount(1);
        return offeredOne.isSimilar(sampleOne);
    }

    private List<ItemStack> calculateReturns(List<ItemStack> deposited, ItemStack sample, int acceptedAmount) {
        List<ItemStack> returns = new ArrayList<>();
        int remainingToConsume = Math.max(0, acceptedAmount);
        for (ItemStack original : deposited) {
            ItemStack stack = original.clone();
            if (!matches(stack, sample)) {
                returns.add(stack);
                continue;
            }
            int consumed = Math.min(remainingToConsume, stack.getAmount());
            remainingToConsume -= consumed;
            int leftover = stack.getAmount() - consumed;
            if (leftover > 0) {
                stack.setAmount(leftover);
                returns.add(stack);
            }
        }
        return returns;
    }

    private void returnItems(Player player, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) continue;
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
            for (ItemStack leftover : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
    }

    private static final class DeliveryHolder implements InventoryHolder {
        private final UUID ownerId;
        private final UUID orderId;
        private final ItemStack sampleItem;
        private Inventory inventory;
        private boolean completed;

        private DeliveryHolder(UUID ownerId, UUID orderId, ItemStack sampleItem) {
            this.ownerId = ownerId;
            this.orderId = orderId;
            this.sampleItem = sampleItem.clone();
            this.sampleItem.setAmount(1);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
