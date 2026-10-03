package de.walahi.novosmp.auction;

import de.walahi.novosmp.trade.TradeItemPolicy;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.api.event.ActionContext;
import de.walahi.smpcore.api.event.ActionSource;
import de.walahi.smpcore.economy.EconomyOperationResult;
import de.walahi.smpcore.services.EconomyService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Business operations and operation locks for the auction house. */
final class AuctionTransactionService {
    private final SMPCorePlugin plugin;
    private final AuctionManager auctions;
    private final EconomyService economy;
    private final TradeItemPolicy itemPolicy;
    private final AuctionSettings settings;
    private final ConcurrentHashMap<UUID, Boolean> playerLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> listingLocks = new ConcurrentHashMap<>();

    AuctionTransactionService(SMPCorePlugin plugin, AuctionManager auctions, EconomyService economy,
                              TradeItemPolicy itemPolicy, AuctionSettings settings) {
        this.plugin = plugin;
        this.auctions = auctions;
        this.economy = economy;
        this.itemPolicy = itemPolicy;
        this.settings = settings;
    }

    CreateResult create(Player player, long price, ItemStack snapshot) {
        if (!lockPlayer(player.getUniqueId())) return CreateResult.OPERATION_RUNNING;
        try {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getType().isAir()) return CreateResult.HOLD_ITEM;
            if (snapshot == null || !hand.equals(snapshot)) return CreateResult.ITEM_CHANGED;
            if (!itemPolicy.isAuctionItemAllowed(hand)) return CreateResult.ITEM_BLOCKED;
            if (price < settings.minimumPrice() || price > settings.maximumPrice()) return CreateResult.INVALID_PRICE;
            if (auctions.activeListings(player.getUniqueId()) >= auctions.listingLimit(player)) {
                return CreateResult.LIMIT_REACHED;
            }

            ItemStack item = hand.clone();
            Instant now = Instant.now();
            AuctionListing listing = new AuctionListing(
                    UUID.randomUUID(), player.getUniqueId(), player.getName(), item, price,
                    AuctionStatus.ACTIVE, now, now.plus(settings.listingDurationHours(), ChronoUnit.HOURS)
            );
            auctions.repository().create(listing);
            player.getInventory().setItemInMainHand(null);
            auctions.broadcastCreated(player, item, price);
            return CreateResult.SUCCESS;
        } catch (Exception exception) {
            log("AH-Angebot konnte nicht erstellt werden.", exception);
            return CreateResult.STORAGE_ERROR;
        } finally {
            unlockPlayer(player.getUniqueId());
        }
    }

    CancelResult cancel(Player player, UUID listingId) {
        if (!lockPlayer(player.getUniqueId())) return CancelResult.OPERATION_RUNNING;
        if (!lockListing(listingId)) {
            unlockPlayer(player.getUniqueId());
            return CancelResult.OPERATION_RUNNING;
        }
        try {
            AuctionListing listing = auctions.repository().findActive(listingId).orElse(null);
            if (listing == null || !listing.sellerId().equals(player.getUniqueId())) {
                return CancelResult.NOT_ACTIVE;
            }

            boolean directDelivery = hasSpace(player, listing.item());
            if (!auctions.repository().cancel(listing, System.currentTimeMillis(), !directDelivery)) {
                return CancelResult.NOT_ACTIVE;
            }
            if (!directDelivery) return CancelResult.MOVED_TO_COLLECT;

            DeliveryResult delivery = deliverOrQueue(
                    player, listing.id(), listing.item(), "DELIVERY_FALLBACK");
            return delivery == DeliveryResult.DIRECT
                    ? CancelResult.RETURNED_TO_INVENTORY
                    : CancelResult.MOVED_TO_COLLECT;
        } catch (Exception exception) {
            log("AH-Angebot konnte nicht zurückgenommen werden.", exception);
            return CancelResult.STORAGE_ERROR;
        } finally {
            unlockListing(listingId);
            unlockPlayer(player.getUniqueId());
        }
    }

    CollectResult collect(Player player, AuctionCollectEntry entry) {
        if (!lockPlayer(player.getUniqueId())) return CollectResult.OPERATION_RUNNING;
        try {
            if (!hasSpace(player, entry.item())) return CollectResult.INVENTORY_FULL;
            if (!auctions.repository().markCollected(entry.id(), player.getUniqueId(), System.currentTimeMillis())) {
                return CollectResult.ALREADY_COLLECTED;
            }
            deliverOrQueue(player, null, entry.item(), "DELIVERY_FALLBACK");
            return CollectResult.SUCCESS;
        } catch (Exception exception) {
            log("AH-Collect konnte nicht verarbeitet werden.", exception);
            return CollectResult.STORAGE_ERROR;
        } finally {
            unlockPlayer(player.getUniqueId());
        }
    }

    CollectAllResult collectAll(Player player, int batchSize) {
        if (!lockPlayer(player.getUniqueId())) return new CollectAllResult(0, false, false, true);
        int collected = 0;
        boolean full = false;
        try {
            while (true) {
                List<AuctionCollectEntry> entries = auctions.repository()
                        .collectEntries(player.getUniqueId(), Math.max(1, batchSize), 0);
                if (entries.isEmpty()) break;
                boolean progress = false;
                for (AuctionCollectEntry entry : entries) {
                    if (!hasSpace(player, entry.item())) {
                        full = true;
                        continue;
                    }
                    if (!auctions.repository().markCollected(entry.id(), player.getUniqueId(),
                            System.currentTimeMillis())) continue;
                    deliverOrQueue(player, null, entry.item(), "DELIVERY_FALLBACK");
                    collected++;
                    progress = true;
                }
                if (!progress || entries.size() < Math.max(1, batchSize)) break;
            }
            return new CollectAllResult(collected, full, false, false);
        } catch (Exception exception) {
            log("AH-Collect-All konnte nicht verarbeitet werden.", exception);
            return new CollectAllResult(collected, full, true, false);
        } finally {
            unlockPlayer(player.getUniqueId());
        }
    }

    PurchaseOutcome purchase(Player buyer, UUID listingId) {
        if (!lockPlayer(buyer.getUniqueId())) {
            return new PurchaseOutcome(PurchaseResult.OPERATION_RUNNING, 0L);
        }
        if (!lockListing(listingId)) {
            unlockPlayer(buyer.getUniqueId());
            return new PurchaseOutcome(PurchaseResult.OPERATION_RUNNING, 0L);
        }
        try {
            AuctionListing listing = auctions.repository().findActive(listingId).orElse(null);
            if (listing == null) return new PurchaseOutcome(PurchaseResult.NOT_AVAILABLE, 0L);
            if (listing.sellerId().equals(buyer.getUniqueId())) {
                return new PurchaseOutcome(PurchaseResult.OWN_LISTING, listing.price());
            }
            if (!hasSpace(buyer, listing.item())) {
                return new PurchaseOutcome(PurchaseResult.INVENTORY_FULL, listing.price());
            }
            if (economy.balance(buyer.getUniqueId()) < listing.price()) {
                return new PurchaseOutcome(PurchaseResult.INSUFFICIENT_FUNDS, listing.price());
            }

            long now = System.currentTimeMillis();
            if (!auctions.repository().claimForPurchase(
                    listing.id(), buyer.getUniqueId(), buyer.getName(), now)) {
                return new PurchaseOutcome(PurchaseResult.NOT_AVAILABLE, listing.price());
            }

            EconomyOperationResult transfer = economy.transfer(
                    buyer.getUniqueId(), listing.sellerId(), listing.price(),
                    "AH-Kauf " + listing.id(),
                    ActionContext.actorTarget(ActionSource.GUI, buyer.getUniqueId(), listing.sellerId())
            );
            if (transfer != EconomyOperationResult.SUCCESS) {
                try {
                    auctions.repository().restoreActive(listing.id(), buyer.getUniqueId());
                } catch (Exception restoreFailure) {
                    log("AH-Angebot konnte nach fehlgeschlagener Zahlung nicht reaktiviert werden.", restoreFailure);
                }
                PurchaseResult result = transfer == EconomyOperationResult.INSUFFICIENT_FUNDS
                        ? PurchaseResult.INSUFFICIENT_FUNDS : PurchaseResult.PAYMENT_FAILED;
                return new PurchaseOutcome(result, listing.price());
            }

            try {
                auctions.repository().recordSale(listing, buyer.getUniqueId(), buyer.getName(), now);
            } catch (Exception auditFailure) {
                // Money and listing state are already committed. Never re-open the listing here,
                // otherwise the seller could be paid twice. Delivery continues and the audit issue is logged.
                log("AH-Verkauf wurde abgeschlossen, aber Verlauf/Statistik konnten nicht gespeichert werden.",
                        auditFailure);
            }

            DeliveryResult delivery = deliverOrQueue(
                    buyer, listing.id(), listing.item(), "PURCHASE_DELIVERY");
            PurchaseResult result = delivery == DeliveryResult.DIRECT
                    ? PurchaseResult.SUCCESS
                    : PurchaseResult.SUCCESS_COLLECT;
            return new PurchaseOutcome(result, listing.price());
        } catch (Exception exception) {
            log("AH-Kauf konnte nicht abgeschlossen werden.", exception);
            return new PurchaseOutcome(PurchaseResult.STORAGE_ERROR, 0L);
        } finally {
            unlockListing(listingId);
            unlockPlayer(buyer.getUniqueId());
        }
    }

    void clear(UUID playerId) {
        playerLocks.remove(playerId);
    }

    boolean itemAllowed(ItemStack item) {
        return itemPolicy.isAuctionItemAllowed(item);
    }


    private DeliveryResult deliverOrQueue(Player player, UUID listingId, ItemStack item, String reason) {
        ItemStack[] before = cloneContents(player.getInventory().getStorageContents());
        try {
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item.clone());
            if (leftovers.isEmpty()) return DeliveryResult.DIRECT;
            queueOrDrop(player, listingId, leftovers.values().stream().toList(), reason);
            return DeliveryResult.QUEUED;
        } catch (RuntimeException deliveryFailure) {
            try {
                player.getInventory().setStorageContents(before);
            } catch (RuntimeException restoreFailure) {
                deliveryFailure.addSuppressed(restoreFailure);
            }
            log("AH-Item konnte nicht direkt zugestellt werden.", deliveryFailure);
            queueOrDrop(player, listingId, List.of(item.clone()), reason);
            return DeliveryResult.QUEUED;
        }
    }

    private ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] clone = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            clone[index] = contents[index] == null ? null : contents[index].clone();
        }
        return clone;
    }

    private void queueOrDrop(Player player, UUID listingId, List<ItemStack> items, String reason) {
        for (ItemStack item : items) {
            try {
                auctions.repository().addCollect(player.getUniqueId(), listingId, item, reason,
                        System.currentTimeMillis());
            } catch (Exception storageFailure) {
                log("AH-Fallback-Collect konnte nicht gespeichert werden; Item wird gedroppt.", storageFailure);
                player.getWorld().dropItemNaturally(player.getLocation(), item.clone());
            }
        }
    }

    private boolean hasSpace(Player player, ItemStack item) {
        int remaining = item.getAmount();
        for (ItemStack slot : player.getInventory().getStorageContents()) {
            if (slot == null || slot.getType().isAir()) {
                remaining -= item.getMaxStackSize();
            } else if (slot.isSimilar(item)) {
                remaining -= Math.max(0, slot.getMaxStackSize() - slot.getAmount());
            }
            if (remaining <= 0) return true;
        }
        return false;
    }

    private boolean lockPlayer(UUID playerId) {
        return playerLocks.putIfAbsent(playerId, Boolean.TRUE) == null;
    }

    private boolean lockListing(UUID listingId) {
        return listingLocks.putIfAbsent(listingId, Boolean.TRUE) == null;
    }

    private void unlockPlayer(UUID playerId) {
        playerLocks.remove(playerId);
    }

    private void unlockListing(UUID listingId) {
        listingLocks.remove(listingId);
    }

    private void log(String message, Exception exception) {
        plugin.getLogger().log(Level.SEVERE, message, exception);
    }

    private enum DeliveryResult { DIRECT, QUEUED }

    enum CreateResult {
        SUCCESS, HOLD_ITEM, ITEM_CHANGED, ITEM_BLOCKED, LIMIT_REACHED,
        INVALID_PRICE, OPERATION_RUNNING, STORAGE_ERROR
    }

    enum CancelResult {
        RETURNED_TO_INVENTORY, MOVED_TO_COLLECT, NOT_ACTIVE,
        OPERATION_RUNNING, STORAGE_ERROR
    }

    enum CollectResult {
        SUCCESS, INVENTORY_FULL, ALREADY_COLLECTED, OPERATION_RUNNING, STORAGE_ERROR
    }

    record CollectAllResult(int collected, boolean inventoryFull,
                            boolean storageError, boolean operationRunning) {
    }

    record PurchaseOutcome(PurchaseResult result, long price) {
    }

    enum PurchaseResult {
        SUCCESS, SUCCESS_COLLECT, NOT_AVAILABLE, OWN_LISTING, INVENTORY_FULL,
        INSUFFICIENT_FUNDS, PAYMENT_FAILED, OPERATION_RUNNING, STORAGE_ERROR
    }
}
