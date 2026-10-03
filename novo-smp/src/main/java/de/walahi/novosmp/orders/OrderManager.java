package de.walahi.novosmp.orders;

import de.walahi.novosmp.trade.TradeItemPolicy;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.api.event.ActionContext;
import de.walahi.smpcore.api.event.ActionSource;
import de.walahi.smpcore.economy.EconomyOperationResult;
import de.walahi.smpcore.services.EconomyService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Business logic for order escrow, delivery, collection and expiration. */
public final class OrderManager {
    private static final String REASON_ESCROW = "Order-Escrow";
    private static final String REASON_ESCROW_ROLLBACK = "Order-Escrow-Rollback";
    private static final String REASON_CANCEL = "Order-Abbruch";
    private static final String REASON_DELIVERY = "Order-Lieferung";
    private static final String REASON_EXPIRED = "Order-Abgelaufen";

    private final SMPCorePlugin plugin;
    private final OrderRepository repository;
    private final EconomyService economy;
    private final TradeItemPolicy itemPolicy;

    public OrderManager(SMPCorePlugin plugin, OrderRepository repository, EconomyService economy,
                        TradeItemPolicy itemPolicy) {
        this.plugin = plugin;
        this.repository = repository;
        this.economy = economy;
        this.itemPolicy = itemPolicy;

        long period = Math.max(20L,
                plugin.configs().main().getLong("orders.expiry-check-ticks", 1_200L));
        Bukkit.getScheduler().runTaskTimer(plugin, this::processExpiredSafely, period, period);
    }

    public OrderRepository repository() {
        return repository;
    }

    public TradeItemPolicy itemPolicy() {
        return itemPolicy;
    }

    public int minimumAmount() {
        return Math.max(1, plugin.configs().main().getInt("orders.minimum-amount", 1));
    }

    public int maximumAmount() {
        return Math.max(minimumAmount(),
                plugin.configs().main().getInt("orders.maximum-amount", 1_000_000));
    }

    public long minimumPricePerItem() {
        // Older releases effectively enforced five coins in Java, even when old YAMLs contained 1.
        return Math.max(5L,
                plugin.configs().main().getLong("orders.minimum-price-per-item", 5L));
    }

    public long maximumPricePerItem() {
        return Math.max(minimumPricePerItem(),
                plugin.configs().main().getLong("orders.maximum-price-per-item", 1_000_000_000L));
    }

    public int active(UUID playerId) throws SQLException {
        return repository.countActive(playerId);
    }

    public int collect(UUID playerId) throws SQLException {
        return repository.countCollect(playerId);
    }

    public int limit(Player player) {
        if (player.hasPermission(OrderPermissions.LIMIT_UNLIMITED)) return Integer.MAX_VALUE;

        int highest = Math.max(1,
                plugin.configs().main().getInt("orders.default-order-limit", 5));
        for (int value : plugin.configs().main().getIntegerList("orders.order-limits")) {
            if (value > highest && player.hasPermission(OrderPermissions.limit(value))) {
                highest = value;
            }
        }
        return highest;
    }

    public CreateResult create(Player player, ItemStack sample, int amount, long pricePerItem) {
        if (!validRequest(sample, amount, pricePerItem)) return CreateResult.INVALID;
        if (!itemPolicy.isOrderItemAllowed(sample)) return CreateResult.ITEM_BLOCKED;

        try {
            if (active(player.getUniqueId()) >= limit(player)) return CreateResult.LIMIT_REACHED;

            long total = Math.multiplyExact((long) amount, pricePerItem);
            EconomyOperationResult withdrawal = economy.withdraw(
                    player.getUniqueId(), total, REASON_ESCROW,
                    ActionContext.player(ActionSource.GUI, player.getUniqueId()));
            if (withdrawal == EconomyOperationResult.INSUFFICIENT_FUNDS) {
                return CreateResult.INSUFFICIENT_FUNDS;
            }
            if (withdrawal != EconomyOperationResult.SUCCESS) return CreateResult.STORAGE_ERROR;

            Instant now = Instant.now();
            ItemStack normalized = sample.clone();
            normalized.setAmount(1);
            OrderListing order = new OrderListing(
                    UUID.randomUUID(),
                    player.getUniqueId(),
                    player.getName(),
                    normalized,
                    amount,
                    amount,
                    pricePerItem,
                    total,
                    now,
                    now.plus(expiryHours(), ChronoUnit.HOURS)
            );

            try {
                repository.create(order);
            } catch (Exception exception) {
                economy.deposit(player.getUniqueId(), total, REASON_ESCROW_ROLLBACK,
                        ActionContext.system(player.getUniqueId()));
                plugin.getLogger().severe("Order konnte nicht gespeichert werden: " + exception.getMessage());
                return CreateResult.STORAGE_ERROR;
            }
            broadcastCreated(player, normalized, amount, pricePerItem);
            return CreateResult.SUCCESS;
        } catch (ArithmeticException exception) {
            return CreateResult.INVALID;
        } catch (Exception exception) {
            plugin.getLogger().severe("Order-Erstellung fehlgeschlagen: " + exception.getMessage());
            return CreateResult.STORAGE_ERROR;
        }
    }

    private void broadcastCreated(Player owner, ItemStack item, int amount, long pricePerItem) {
        Component message = Component.text("[ORDER] ", NamedTextColor.AQUA)
                .append(Component.text(owner.getName() + " sucht " + amount + "x ", NamedTextColor.GRAY))
                .append(item.effectiveName())
                .append(Component.text(" für " + pricePerItem + " Coins pro Stück ", NamedTextColor.GRAY))
                .append(Component.text("[Öffnen]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/order")));
        Bukkit.broadcast(message);
    }

    public CancelResult cancel(Player player, UUID orderId) {
        try {
            OrderListing listing = repository.cancelOwned(orderId, player.getUniqueId(), Instant.now());
            if (listing == null) return CancelResult.NOT_ACTIVE;

            long refund = listing.escrowRemaining();
            if (refund <= 0L) return CancelResult.SUCCESS;

            EconomyOperationResult result = economy.deposit(
                    player.getUniqueId(), refund, REASON_CANCEL,
                    ActionContext.player(ActionSource.GUI, player.getUniqueId()));
            if (result == EconomyOperationResult.SUCCESS) return CancelResult.SUCCESS;

            repository.addCoinCollect(player.getUniqueId(), listing.id(), refund,
                    "CANCEL_REFUND", Instant.now());
            plugin.getLogger().warning("Order-Rückzahlung für " + player.getName()
                    + " wurde in Collect gesichert: " + refund);
            return CancelResult.REFUND_IN_COLLECT;
        } catch (Exception exception) {
            plugin.getLogger().severe("Order-Abbruch fehlgeschlagen: " + exception.getMessage());
            return CancelResult.STORAGE_ERROR;
        }
    }

    public FulfillResult fulfill(Player player, UUID orderId, int offeredAmount) {
        if (offeredAmount <= 0) {
            return new FulfillResult(FulfillStatus.NO_MATCHING_ITEMS, 0, 0L, false);
        }
        try {
            OrderRepository.FulfillClaim claim = repository.fulfill(
                    orderId, player.getUniqueId(), player.getName(), offeredAmount, Instant.now());
            if (claim == null || claim.acceptedAmount() <= 0) {
                return new FulfillResult(FulfillStatus.NOT_ACTIVE, 0, 0L,
                        claim != null && claim.completed());
            }

            EconomyOperationResult payout = economy.deposit(
                    player.getUniqueId(), claim.payout(), REASON_DELIVERY,
                    ActionContext.player(ActionSource.GUI, player.getUniqueId()));
            boolean storedInCollect = payout != EconomyOperationResult.SUCCESS;
            if (storedInCollect) {
                repository.addCoinCollect(player.getUniqueId(), orderId, claim.payout(),
                        "DELIVERY_PAYOUT", Instant.now());
                plugin.getLogger().warning("Order-Auszahlung wurde in Collect gesichert: "
                        + orderId + " / " + claim.payout());
            }
            return new FulfillResult(
                    storedInCollect ? FulfillStatus.PAYOUT_IN_COLLECT : FulfillStatus.SUCCESS,
                    claim.acceptedAmount(), claim.payout(), claim.completed());
        } catch (Exception exception) {
            plugin.getLogger().severe("Order-Lieferung fehlgeschlagen: " + exception.getMessage());
            return new FulfillResult(FulfillStatus.STORAGE_ERROR, 0, 0L, false);
        }
    }

    public List<OrderCollectEntry> collectEntries(UUID playerId, int limit) throws Exception {
        return repository.itemCollect(playerId, limit);
    }

    public CollectResult collectOne(Player player, long entryId) {
        try {
            OrderCollectEntry entry = repository.claimItemCollect(
                    entryId, player.getUniqueId(), Instant.now());
            if (entry == null) return new CollectResult(CollectStatus.NOT_FOUND, 0, 0);

            ItemStack stack = entry.item().clone();
            int originalAmount = stack.getAmount();
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
            int remainingAmount = overflow.values().stream().mapToInt(ItemStack::getAmount).sum();
            storeOrDropOverflow(player, entry, overflow.values());

            CollectStatus status = remainingAmount > 0 ? CollectStatus.PARTIAL : CollectStatus.SUCCESS;
            return new CollectResult(status, originalAmount - remainingAmount, remainingAmount);
        } catch (Exception exception) {
            plugin.getLogger().severe("Order-Collect fehlgeschlagen: " + exception.getMessage());
            return new CollectResult(CollectStatus.STORAGE_ERROR, 0, 0);
        }
    }

    public CollectAllResult collectAll(Player player) {
        int collected = 0;
        int remaining = 0;
        try {
            int batchSize = Math.max(1,
                    plugin.configs().main().getInt("orders.collect-batch-size", 1_000));
            for (OrderCollectEntry entry : repository.itemCollect(player.getUniqueId(), batchSize)) {
                CollectResult result = collectOne(player, entry.id());
                if (result.status() == CollectStatus.STORAGE_ERROR) {
                    return new CollectAllResult(CollectStatus.STORAGE_ERROR, collected, remaining);
                }
                collected += result.collectedAmount();
                remaining += result.remainingAmount();
            }
            CollectStatus status = remaining > 0 ? CollectStatus.PARTIAL : CollectStatus.SUCCESS;
            return new CollectAllResult(status, collected, remaining);
        } catch (Exception exception) {
            plugin.getLogger().severe("Order-Collect-All fehlgeschlagen: " + exception.getMessage());
            return new CollectAllResult(CollectStatus.STORAGE_ERROR, collected, remaining);
        }
    }

    public void processExpiredSafely() {
        try {
            int batchSize = Math.max(1,
                    plugin.configs().main().getInt("orders.expiry-batch-size", 100));
            for (OrderListing listing : repository.expiredOrders(batchSize)) {
                refundExpired(listing);
            }
        } catch (Exception exception) {
            plugin.getLogger().severe("Order-Ablaufprüfung fehlgeschlagen: " + exception.getMessage());
        }
    }

    private boolean validRequest(ItemStack sample, int amount, long pricePerItem) {
        return sample != null
                && !sample.getType().isAir()
                && amount >= minimumAmount()
                && amount <= maximumAmount()
                && pricePerItem >= minimumPricePerItem()
                && pricePerItem <= maximumPricePerItem();
    }

    private long expiryHours() {
        return Math.max(1L, plugin.configs().main().getLong("orders.expiry-hours", 168L));
    }

    private void storeOrDropOverflow(Player player, OrderCollectEntry entry,
                                     java.util.Collection<ItemStack> overflow) {
        for (ItemStack leftover : overflow) {
            try {
                repository.addItemCollect(player.getUniqueId(), entry.orderId(), leftover,
                        "INVENTORY_FULL", Instant.now());
            } catch (Exception storageFailure) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                plugin.getLogger().severe("Order-Collect-Rest konnte nicht gespeichert werden "
                        + "und wurde gedroppt: " + storageFailure.getMessage());
            }
        }
    }

    private void refundExpired(OrderListing listing) throws Exception {
        Instant now = Instant.now();
        if (!repository.markExpired(listing, now)) return;

        long refund = listing.escrowRemaining();
        if (refund <= 0L) return;

        EconomyOperationResult result = economy.deposit(
                listing.ownerId(), refund, REASON_EXPIRED, ActionContext.system(listing.ownerId()));
        if (result == EconomyOperationResult.SUCCESS) return;

        repository.addCoinCollect(listing.ownerId(), listing.id(), refund,
                "EXPIRED_REFUND", now);
        plugin.getLogger().warning("Abgelaufene Order-Rückzahlung wurde in Collect gesichert: "
                + listing.id());
    }

    public enum CreateResult {
        SUCCESS, INVALID, ITEM_BLOCKED, LIMIT_REACHED, INSUFFICIENT_FUNDS, STORAGE_ERROR
    }

    public enum FulfillStatus {
        SUCCESS, PAYOUT_IN_COLLECT, NO_MATCHING_ITEMS, NOT_ACTIVE, STORAGE_ERROR
    }

    public record FulfillResult(FulfillStatus status, int acceptedAmount,
                                long payout, boolean completed) {
    }

    public enum CollectStatus {
        SUCCESS, PARTIAL, NOT_FOUND, STORAGE_ERROR
    }

    public record CollectResult(CollectStatus status, int collectedAmount, int remainingAmount) {
    }

    public record CollectAllResult(CollectStatus status, int collectedAmount, int remainingAmount) {
    }

    public enum CancelResult {
        SUCCESS, REFUND_IN_COLLECT, NOT_ACTIVE, STORAGE_ERROR
    }
}
