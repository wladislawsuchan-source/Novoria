package de.walahi.novosmp.orders;

import de.walahi.novosmp.auction.ItemStackCodec;
import de.walahi.smpcore.database.DatabaseManager;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** SQL persistence for orders, history and collect entries. */
public final class OrderRepository {
    private final DatabaseManager database;

    public OrderRepository(DatabaseManager database) {
        this.database = database;
    }

    public int countActive() throws SQLException {
        return count(
                "SELECT COUNT(*) FROM " + table("orders")
                        + " WHERE status='ACTIVE' AND expires_at>?",
                null,
                true
        );
    }

    public int countActive(UUID ownerId) throws SQLException {
        return count(
                "SELECT COUNT(*) FROM " + table("orders")
                        + " WHERE owner_uuid=? AND status='ACTIVE' AND expires_at>?",
                ownerId,
                true
        );
    }

    public int countCollect(UUID ownerId) throws SQLException {
        return count(
                "SELECT COUNT(*) FROM " + table("order_collect")
                        + " WHERE owner_uuid=? AND collected=0 AND item_data IS NOT NULL",
                ownerId,
                false
        );
    }

    public int countHistory(UUID ownerId) throws SQLException {
        return count(
                "SELECT COUNT(*) FROM " + table("order_history") + " WHERE player_uuid=?",
                ownerId,
                false
        );
    }

    public List<OrderListing> activeOrders(int limit, int offset) throws Exception {
        String sql = "SELECT * FROM " + table("orders")
                + " WHERE status='ACTIVE' AND expires_at>?"
                + " ORDER BY created_at DESC LIMIT ? OFFSET ?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setInt(2, limit);
            statement.setInt(3, offset);
            return readListings(statement);
        }
    }

    public List<OrderListing> ownerOrders(UUID ownerId, int limit, int offset) throws Exception {
        String sql = "SELECT * FROM " + table("orders")
                + " WHERE owner_uuid=? AND status='ACTIVE' AND expires_at>?"
                + " ORDER BY created_at DESC LIMIT ? OFFSET ?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId.toString());
            statement.setLong(2, System.currentTimeMillis());
            statement.setInt(3, limit);
            statement.setInt(4, offset);
            return readListings(statement);
        }
    }

    public OrderListing findActive(UUID orderId) throws Exception {
        String sql = "SELECT * FROM " + table("orders")
                + " WHERE order_uuid=? AND status='ACTIVE' AND expires_at>?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, orderId.toString());
            statement.setLong(2, System.currentTimeMillis());
            return readSingleListing(statement);
        }
    }

    public OrderListing findActiveOwned(UUID orderId, UUID ownerId) throws Exception {
        String sql = "SELECT * FROM " + table("orders")
                + " WHERE order_uuid=? AND owner_uuid=? AND status='ACTIVE' AND expires_at>?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, orderId.toString());
            statement.setString(2, ownerId.toString());
            statement.setLong(3, System.currentTimeMillis());
            return readSingleListing(statement);
        }
    }

    /** Atomically accepts up to {@code offeredAmount} items from a foreign player. */
    public FulfillClaim fulfill(UUID orderId, UUID fulfillerId, String fulfillerName,
                                int offeredAmount, Instant fulfilledAt) throws Exception {
        if (offeredAmount <= 0) return null;

        return inTransaction(connection -> {
            OrderListing listing = findActive(connection, orderId, fulfilledAt);
            if (listing == null || listing.ownerId().equals(fulfillerId)) return null;

            int acceptedAmount = Math.min(offeredAmount, listing.remainingAmount());
            if (acceptedAmount <= 0) return null;

            long payout = Math.multiplyExact((long) acceptedAmount, listing.pricePerItem());
            if (payout > listing.escrowRemaining()) {
                throw new SQLException("Order-Escrow ist inkonsistent: " + orderId);
            }

            int remainingAmount = listing.remainingAmount() - acceptedAmount;
            long remainingEscrow = listing.escrowRemaining() - payout;
            boolean completed = remainingAmount == 0;
            if (!updateAfterFulfillment(connection, listing, remainingAmount,
                    remainingEscrow, completed, fulfilledAt)) {
                return null;
            }

            insertItemCollect(connection, listing.ownerId(), listing.id(), listing.item(),
                    acceptedAmount, "ORDER_DELIVERY", fulfilledAt);
            insertHistory(connection, listing.ownerId(), "RECEIVED", listing, acceptedAmount,
                    fulfillerId, fulfillerName, payout, fulfilledAt);
            insertHistory(connection, fulfillerId, "DELIVERED", listing, acceptedAmount,
                    listing.ownerId(), listing.ownerName(), payout, fulfilledAt);
            return new FulfillClaim(acceptedAmount, payout, completed);
        });
    }

    /** Claims an active order exactly once for cancellation. */
    public OrderListing cancelOwned(UUID orderId, UUID ownerId, Instant closedAt) throws Exception {
        return inTransaction(connection -> {
            OrderListing listing = findActiveOwned(connection, orderId, ownerId);
            if (listing == null) return null;

            String sql = "UPDATE " + table("orders")
                    + " SET status='CANCELLED',closed_at=?"
                    + " WHERE order_uuid=? AND owner_uuid=? AND status='ACTIVE'";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, closedAt.toEpochMilli());
                statement.setString(2, orderId.toString());
                statement.setString(3, ownerId.toString());
                if (statement.executeUpdate() != 1) return null;
            }

            insertHistory(connection, listing.ownerId(), "CANCELLED", listing,
                    listing.remainingAmount(), listing.escrowRemaining(), closedAt);
            return listing;
        });
    }

    public List<OrderListing> expiredOrders(int limit) throws Exception {
        String sql = "SELECT * FROM " + table("orders")
                + " WHERE status='ACTIVE' AND expires_at<=?"
                + " ORDER BY expires_at ASC LIMIT ?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setInt(2, limit);
            return readListings(statement);
        }
    }

    /** Claims an expired order exactly once. */
    public boolean markExpired(OrderListing listing, Instant closedAt) throws Exception {
        return inTransaction(connection -> {
            String sql = "UPDATE " + table("orders")
                    + " SET status='EXPIRED',closed_at=?"
                    + " WHERE order_uuid=? AND status='ACTIVE' AND expires_at<=?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, closedAt.toEpochMilli());
                statement.setString(2, listing.id().toString());
                statement.setLong(3, System.currentTimeMillis());
                if (statement.executeUpdate() != 1) return false;
            }

            insertHistory(connection, listing.ownerId(), "EXPIRED", listing,
                    listing.remainingAmount(), listing.escrowRemaining(), closedAt);
            return true;
        });
    }

    public List<OrderCollectEntry> itemCollect(UUID ownerId, int limit) throws Exception {
        String sql = "SELECT id,owner_uuid,order_uuid,item_data,reason,created_at FROM "
                + table("order_collect")
                + " WHERE owner_uuid=? AND collected=0 AND item_data IS NOT NULL"
                + " ORDER BY created_at ASC LIMIT ?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId.toString());
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<OrderCollectEntry> entries = new ArrayList<>();
                while (resultSet.next()) entries.add(readCollect(resultSet));
                return entries;
            }
        }
    }

    /** Claims one collect entry exactly once. */
    public OrderCollectEntry claimItemCollect(long id, UUID ownerId, Instant collectedAt) throws Exception {
        return inTransaction(connection -> {
            OrderCollectEntry entry = findUncollectedEntry(connection, id, ownerId);
            if (entry == null) return null;

            String sql = "UPDATE " + table("order_collect")
                    + " SET collected=1,collected_at=?"
                    + " WHERE id=? AND owner_uuid=? AND collected=0";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, collectedAt.toEpochMilli());
                statement.setLong(2, id);
                statement.setString(3, ownerId.toString());
                if (statement.executeUpdate() != 1) return null;
            }
            return entry;
        });
    }

    public void addItemCollect(UUID ownerId, UUID orderId, ItemStack item,
                               String reason, Instant createdAt) throws Exception {
        inTransaction(connection -> {
            insertItemCollect(connection, ownerId, orderId, item, item.getAmount(), reason, createdAt);
            return null;
        });
    }

    public void addCoinCollect(UUID ownerId, UUID orderId, long coins,
                               String reason, Instant createdAt) throws SQLException {
        String sql = "INSERT INTO " + table("order_collect")
                + " (owner_uuid,order_uuid,item_data,coins,reason,created_at,collected,collected_at)"
                + " VALUES (?,?,NULL,?,?,?,0,NULL)";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId.toString());
            statement.setString(2, orderId.toString());
            statement.setLong(3, coins);
            statement.setString(4, reason);
            statement.setLong(5, createdAt.toEpochMilli());
            statement.executeUpdate();
        }
    }

    /** Inserts the order and its CREATED history row atomically. */
    public void create(OrderListing order) throws Exception {
        inTransaction(connection -> {
            insertOrder(connection, order);
            insertHistory(connection, order.ownerId(), "CREATED", order,
                    order.requestedAmount(), order.escrowRemaining(), order.createdAt());
            return null;
        });
    }

    private void insertOrder(Connection connection, OrderListing order) throws Exception {
        String sql = "INSERT INTO " + table("orders")
                + " (order_uuid,owner_uuid,owner_name,sample_item,item_type,requested_amount,"
                + "remaining_amount,price_per_item,escrow_remaining,status,created_at,expires_at,closed_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,'ACTIVE',?,?,NULL)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, order.id().toString());
            statement.setString(2, order.ownerId().toString());
            statement.setString(3, order.ownerName());
            statement.setString(4, ItemStackCodec.encode(order.item()));
            statement.setString(5, order.item().getType().name());
            statement.setInt(6, order.requestedAmount());
            statement.setInt(7, order.remainingAmount());
            statement.setLong(8, order.pricePerItem());
            statement.setLong(9, order.escrowRemaining());
            statement.setLong(10, order.createdAt().toEpochMilli());
            statement.setLong(11, order.expiresAt().toEpochMilli());
            statement.executeUpdate();
        }
    }

    private OrderListing findActive(Connection connection, UUID orderId, Instant now) throws Exception {
        String sql = "SELECT * FROM " + table("orders")
                + " WHERE order_uuid=? AND status='ACTIVE' AND expires_at>?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, orderId.toString());
            statement.setLong(2, now.toEpochMilli());
            return readSingleListing(statement);
        }
    }

    private OrderListing findActiveOwned(Connection connection, UUID orderId, UUID ownerId) throws Exception {
        String sql = "SELECT * FROM " + table("orders")
                + " WHERE order_uuid=? AND owner_uuid=? AND status='ACTIVE'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, orderId.toString());
            statement.setString(2, ownerId.toString());
            return readSingleListing(statement);
        }
    }

    private boolean updateAfterFulfillment(Connection connection, OrderListing listing,
                                           int remainingAmount, long remainingEscrow,
                                           boolean completed, Instant fulfilledAt) throws SQLException {
        String sql = "UPDATE " + table("orders")
                + " SET remaining_amount=?,escrow_remaining=?,status=?,closed_at=?"
                + " WHERE order_uuid=? AND status='ACTIVE'"
                + " AND remaining_amount=? AND escrow_remaining=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, remainingAmount);
            statement.setLong(2, remainingEscrow);
            statement.setString(3, completed ? "COMPLETED" : "ACTIVE");
            if (completed) statement.setLong(4, fulfilledAt.toEpochMilli());
            else statement.setNull(4, Types.BIGINT);
            statement.setString(5, listing.id().toString());
            statement.setInt(6, listing.remainingAmount());
            statement.setLong(7, listing.escrowRemaining());
            return statement.executeUpdate() == 1;
        }
    }

    private OrderCollectEntry findUncollectedEntry(Connection connection, long id,
                                                    UUID ownerId) throws Exception {
        String sql = "SELECT id,owner_uuid,order_uuid,item_data,reason,created_at FROM "
                + table("order_collect")
                + " WHERE id=? AND owner_uuid=? AND collected=0 AND item_data IS NOT NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            statement.setString(2, ownerId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readCollect(resultSet) : null;
            }
        }
    }

    private void insertItemCollect(Connection connection, UUID ownerId, UUID orderId,
                                   ItemStack sample, int amount, String reason,
                                   Instant createdAt) throws Exception {
        int remaining = amount;
        int maximumStackSize = Math.max(1, sample.getMaxStackSize());
        String sql = "INSERT INTO " + table("order_collect")
                + " (owner_uuid,order_uuid,item_data,coins,reason,created_at,collected,collected_at)"
                + " VALUES (?,?,?,0,?,?,0,NULL)";

        while (remaining > 0) {
            int part = Math.min(maximumStackSize, remaining);
            ItemStack stack = sample.clone();
            stack.setAmount(part);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, ownerId.toString());
                statement.setString(2, orderId.toString());
                statement.setString(3, ItemStackCodec.encode(stack));
                statement.setString(4, reason);
                statement.setLong(5, createdAt.toEpochMilli());
                statement.executeUpdate();
            }
            remaining -= part;
        }
    }

    private void insertHistory(Connection connection, UUID playerId, String type,
                               OrderListing order, int amount, long coins,
                               Instant createdAt) throws Exception {
        insertHistory(connection, playerId, type, order, amount,
                null, null, coins, createdAt);
    }

    private void insertHistory(Connection connection, UUID playerId, String type,
                               OrderListing order, int amount, UUID counterpartId,
                               String counterpartName, long coins, Instant createdAt) throws Exception {
        String sql = "INSERT INTO " + table("order_history")
                + " (player_uuid,event_type,order_uuid,item_data,item_type,amount,"
                + "counterpart_uuid,counterpart_name,coins,created_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, type);
            statement.setString(3, order.id().toString());
            statement.setString(4, ItemStackCodec.encode(order.item()));
            statement.setString(5, order.item().getType().name());
            statement.setInt(6, amount);
            if (counterpartId == null) statement.setNull(7, Types.VARCHAR);
            else statement.setString(7, counterpartId.toString());
            if (counterpartName == null) statement.setNull(8, Types.VARCHAR);
            else statement.setString(8, counterpartName);
            statement.setLong(9, coins);
            statement.setLong(10, createdAt.toEpochMilli());
            statement.executeUpdate();
        }
    }

    private List<OrderListing> readListings(PreparedStatement statement) throws Exception {
        try (ResultSet resultSet = statement.executeQuery()) {
            List<OrderListing> listings = new ArrayList<>();
            while (resultSet.next()) listings.add(readListing(resultSet));
            return listings;
        }
    }

    private OrderListing readSingleListing(PreparedStatement statement) throws Exception {
        try (ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? readListing(resultSet) : null;
        }
    }

    private OrderListing readListing(ResultSet resultSet) throws Exception {
        return new OrderListing(
                UUID.fromString(resultSet.getString("order_uuid")),
                UUID.fromString(resultSet.getString("owner_uuid")),
                resultSet.getString("owner_name"),
                ItemStackCodec.decode(resultSet.getString("sample_item")),
                resultSet.getInt("requested_amount"),
                resultSet.getInt("remaining_amount"),
                resultSet.getLong("price_per_item"),
                resultSet.getLong("escrow_remaining"),
                Instant.ofEpochMilli(resultSet.getLong("created_at")),
                Instant.ofEpochMilli(resultSet.getLong("expires_at"))
        );
    }

    private OrderCollectEntry readCollect(ResultSet resultSet) throws Exception {
        String orderId = resultSet.getString("order_uuid");
        return new OrderCollectEntry(
                resultSet.getLong("id"),
                UUID.fromString(resultSet.getString("owner_uuid")),
                orderId == null ? null : UUID.fromString(orderId),
                ItemStackCodec.decode(resultSet.getString("item_data")),
                resultSet.getString("reason"),
                Instant.ofEpochMilli(resultSet.getLong("created_at"))
        );
    }

    private int count(String sql, UUID ownerId, boolean bindCurrentTime) throws SQLException {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            if (ownerId != null) statement.setString(index++, ownerId.toString());
            if (bindCurrentTime) statement.setLong(index, System.currentTimeMillis());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        }
    }

    private String table(String name) {
        return database.table(name);
    }

    private <T> T inTransaction(TransactionWork<T> work) throws Exception {
        try (Connection connection = database.connection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    @FunctionalInterface
    private interface TransactionWork<T> {
        T execute(Connection connection) throws Exception;
    }

    public record FulfillClaim(int acceptedAmount, long payout, boolean completed) {
    }
}
