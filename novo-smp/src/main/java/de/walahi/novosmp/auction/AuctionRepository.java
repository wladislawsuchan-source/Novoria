package de.walahi.novosmp.auction;

import de.walahi.smpcore.database.DatabaseManager;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC persistence layer. Conditional status updates prevent two buyers claiming one listing. */
public final class AuctionRepository {
    private final DatabaseManager database;

    public AuctionRepository(DatabaseManager database) {
        this.database = database;
    }

    /** Listing and listings-created statistics are committed atomically. */
    public void create(AuctionListing listing) throws SQLException, IOException {
        String itemData = ItemStackCodec.encode(listing.item());
        inTransaction(connection -> {
            insertListing(connection, listing, itemData);
            incrementListingsCreated(connection, listing.sellerId(), System.currentTimeMillis());
            return null;
        });
    }

    public List<AuctionListing> activeListings(int limit, int offset) throws SQLException, IOException {
        String sql = "SELECT * FROM " + database.table("ah_listings")
                + " WHERE status='ACTIVE' AND expires_at>? ORDER BY created_at DESC LIMIT ? OFFSET ?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setInt(2, limit);
            statement.setInt(3, offset);
            return readListings(statement);
        }
    }

    public List<AuctionListing> sellerListings(UUID sellerId, int limit, int offset)
            throws SQLException, IOException {
        String sql = "SELECT * FROM " + database.table("ah_listings")
                + " WHERE seller_uuid=? AND status='ACTIVE' AND expires_at>?"
                + " ORDER BY created_at DESC LIMIT ? OFFSET ?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sellerId.toString());
            statement.setLong(2, System.currentTimeMillis());
            statement.setInt(3, limit);
            statement.setInt(4, offset);
            return readListings(statement);
        }
    }

    public int countAllActive() throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + database.table("ah_listings")
                + " WHERE status='ACTIVE' AND expires_at>?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, System.currentTimeMillis());
            return readCount(statement);
        }
    }

    public int countActive(UUID sellerId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + database.table("ah_listings")
                + " WHERE seller_uuid=? AND status='ACTIVE' AND expires_at>?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sellerId.toString());
            statement.setLong(2, System.currentTimeMillis());
            return readCount(statement);
        }
    }

    public Optional<AuctionListing> findActive(UUID id) throws SQLException, IOException {
        String sql = "SELECT * FROM " + database.table("ah_listings")
                + " WHERE listing_uuid=? AND status='ACTIVE' AND expires_at>?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id.toString());
            statement.setLong(2, System.currentTimeMillis());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        }
    }

    public boolean claimForPurchase(UUID id, UUID buyerId, String buyerName, long now) throws SQLException {
        String sql = "UPDATE " + database.table("ah_listings")
                + " SET status='SOLD',closed_at=?,buyer_uuid=?,buyer_name=?"
                + " WHERE listing_uuid=? AND status='ACTIVE' AND expires_at>?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, now);
            statement.setString(2, buyerId.toString());
            statement.setString(3, buyerName);
            statement.setString(4, id.toString());
            statement.setLong(5, now);
            return statement.executeUpdate() == 1;
        }
    }

    public boolean restoreActive(UUID id, UUID buyerId) throws SQLException {
        String sql = "UPDATE " + database.table("ah_listings")
                + " SET status='ACTIVE',closed_at=NULL,buyer_uuid=NULL,buyer_name=NULL"
                + " WHERE listing_uuid=? AND status='SOLD' AND buyer_uuid=?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id.toString());
            statement.setString(2, buyerId.toString());
            return statement.executeUpdate() == 1;
        }
    }

    /** Sale history and both statistics rows are committed together. */
    public void recordSale(AuctionListing listing, UUID buyerId, String buyerName, long now)
            throws SQLException, IOException {
        String itemData = ItemStackCodec.encode(listing.item());
        inTransaction(connection -> {
            history(connection, listing.sellerId(), "SOLD", listing,
                    buyerId, buyerName, itemData, now);
            history(connection, buyerId, "BOUGHT", listing,
                    listing.sellerId(), listing.sellerName(), itemData, now);
            upsertStats(connection, listing.sellerId(), listing.price(), 0L,
                    listing.item().getAmount(), 0L, now);
            upsertStats(connection, buyerId, 0L, listing.price(),
                    0L, listing.item().getAmount(), now);
            return null;
        });
    }

    public boolean cancel(AuctionListing listing, long now, boolean moveToCollect)
            throws SQLException, IOException {
        String itemData = ItemStackCodec.encode(listing.item());
        return inTransaction(connection -> {
            String update = "UPDATE " + database.table("ah_listings")
                    + " SET status='CANCELLED',closed_at=?"
                    + " WHERE listing_uuid=? AND seller_uuid=? AND status='ACTIVE'";
            try (PreparedStatement statement = connection.prepareStatement(update)) {
                statement.setLong(1, now);
                statement.setString(2, listing.id().toString());
                statement.setString(3, listing.sellerId().toString());
                if (statement.executeUpdate() != 1) return false;
            }
            if (moveToCollect) {
                insertCollect(connection, listing.sellerId(), listing.id(), itemData, "CANCELLED", now);
            }
            history(connection, listing.sellerId(), "CANCELLED", listing,
                    null, null, itemData, now);
            return true;
        });
    }

    public void addCollect(UUID ownerId, UUID listingId, ItemStack item, String reason, long now)
            throws SQLException, IOException {
        String itemData = ItemStackCodec.encode(item);
        try (Connection connection = database.connection()) {
            insertCollect(connection, ownerId, listingId, itemData, reason, now);
        }
    }

    public List<AuctionCollectEntry> collectEntries(UUID ownerId, int limit, int offset)
            throws SQLException, IOException {
        String sql = "SELECT id,item_data,reason,created_at FROM " + database.table("ah_collect")
                + " WHERE owner_uuid=? AND collected=0 ORDER BY created_at ASC LIMIT ? OFFSET ?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId.toString());
            statement.setInt(2, limit);
            statement.setInt(3, offset);
            try (ResultSet rows = statement.executeQuery()) {
                List<AuctionCollectEntry> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new AuctionCollectEntry(
                            rows.getLong("id"),
                            ItemStackCodec.decode(rows.getString("item_data")),
                            rows.getString("reason"),
                            Instant.ofEpochMilli(rows.getLong("created_at"))
                    ));
                }
                return result;
            }
        }
    }

    public int countCollect(UUID ownerId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + database.table("ah_collect")
                + " WHERE owner_uuid=? AND collected=0";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId.toString());
            return readCount(statement);
        }
    }

    public boolean markCollected(long id, UUID ownerId, long now) throws SQLException {
        String sql = "UPDATE " + database.table("ah_collect")
                + " SET collected=1,collected_at=? WHERE id=? AND owner_uuid=? AND collected=0";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, now);
            statement.setLong(2, id);
            statement.setString(3, ownerId.toString());
            return statement.executeUpdate() == 1;
        }
    }

    public List<AuctionHistoryEntry> historyEntries(UUID playerId, int limit, int offset)
            throws SQLException, IOException {
        String sql = "SELECT id,event_type,listing_uuid,item_data,counterpart_uuid,"
                + "counterpart_name,price,created_at FROM " + database.table("ah_history")
                + " WHERE player_uuid=? ORDER BY created_at DESC LIMIT ? OFFSET ?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setInt(2, limit);
            statement.setInt(3, offset);
            try (ResultSet rows = statement.executeQuery()) {
                List<AuctionHistoryEntry> result = new ArrayList<>();
                while (rows.next()) {
                    String listingId = rows.getString("listing_uuid");
                    String counterpartId = rows.getString("counterpart_uuid");
                    result.add(new AuctionHistoryEntry(
                            rows.getLong("id"),
                            rows.getString("event_type"),
                            listingId == null ? null : UUID.fromString(listingId),
                            ItemStackCodec.decode(rows.getString("item_data")),
                            counterpartId == null ? null : UUID.fromString(counterpartId),
                            rows.getString("counterpart_name"),
                            rows.getLong("price"),
                            Instant.ofEpochMilli(rows.getLong("created_at"))
                    ));
                }
                return result;
            }
        }
    }

    public int countHistory(UUID playerId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + database.table("ah_history") + " WHERE player_uuid=?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            return readCount(statement);
        }
    }

    public AuctionStats stats(UUID playerId) throws SQLException {
        String sql = "SELECT coins_earned,coins_spent,items_sold,items_bought,listings_created FROM "
                + database.table("ah_stats") + " WHERE player_uuid=?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return AuctionStats.empty();
                return new AuctionStats(
                        rows.getLong("coins_earned"),
                        rows.getLong("coins_spent"),
                        rows.getLong("items_sold"),
                        rows.getLong("items_bought"),
                        rows.getLong("listings_created")
                );
            }
        }
    }

    public int expireDue(long now, int batchSize) throws SQLException {
        String select = "SELECT listing_uuid,seller_uuid,item_data,item_type,item_amount,price FROM "
                + database.table("ah_listings")
                + " WHERE status='ACTIVE' AND expires_at<=? ORDER BY expires_at ASC LIMIT ?";
        List<ExpiringListing> due = new ArrayList<>();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(select)) {
            statement.setLong(1, now);
            statement.setInt(2, Math.max(1, batchSize));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    due.add(new ExpiringListing(
                            rows.getString("listing_uuid"),
                            rows.getString("seller_uuid"),
                            rows.getString("item_data"),
                            rows.getString("item_type"),
                            rows.getInt("item_amount"),
                            rows.getLong("price")
                    ));
                }
            }
        }
        if (due.isEmpty()) return 0;

        return inTransaction(connection -> {
            int expired = 0;
            for (ExpiringListing listing : due) {
                if (!claimExpired(connection, listing.id(), now)) continue;
                insertCollect(connection, UUID.fromString(listing.sellerId()),
                        UUID.fromString(listing.id()), listing.itemData(), "EXPIRED", now);
                insertExpiredHistory(connection, listing, now);
                expired++;
            }
            return expired;
        });
    }

    private void insertListing(Connection connection, AuctionListing listing, String itemData)
            throws SQLException {
        String sql = "INSERT INTO " + database.table("ah_listings")
                + " (listing_uuid,seller_uuid,seller_name,item_data,item_type,item_amount,price,status,created_at,expires_at)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, listing.id().toString());
            statement.setString(2, listing.sellerId().toString());
            statement.setString(3, listing.sellerName());
            statement.setString(4, itemData);
            statement.setString(5, listing.item().getType().getKey().asString());
            statement.setInt(6, listing.item().getAmount());
            statement.setLong(7, listing.price());
            statement.setString(8, listing.status().name());
            statement.setLong(9, listing.createdAt().toEpochMilli());
            statement.setLong(10, listing.expiresAt().toEpochMilli());
            statement.executeUpdate();
        }
    }

    private List<AuctionListing> readListings(PreparedStatement statement) throws SQLException, IOException {
        try (ResultSet rows = statement.executeQuery()) {
            List<AuctionListing> result = new ArrayList<>();
            while (rows.next()) result.add(read(rows));
            return result;
        }
    }

    private AuctionListing read(ResultSet rows) throws SQLException, IOException {
        return new AuctionListing(
                UUID.fromString(rows.getString("listing_uuid")),
                UUID.fromString(rows.getString("seller_uuid")),
                rows.getString("seller_name"),
                ItemStackCodec.decode(rows.getString("item_data")),
                rows.getLong("price"),
                AuctionStatus.valueOf(rows.getString("status")),
                Instant.ofEpochMilli(rows.getLong("created_at")),
                Instant.ofEpochMilli(rows.getLong("expires_at"))
        );
    }

    private int readCount(PreparedStatement statement) throws SQLException {
        try (ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private boolean claimExpired(Connection connection, String id, long now) throws SQLException {
        String sql = "UPDATE " + database.table("ah_listings")
                + " SET status='EXPIRED',closed_at=? WHERE listing_uuid=? AND status='ACTIVE'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, now);
            statement.setString(2, id);
            return statement.executeUpdate() == 1;
        }
    }

    private void insertCollect(Connection connection, UUID ownerId, UUID listingId,
                               String itemData, String reason, long now) throws SQLException {
        String sql = "INSERT INTO " + database.table("ah_collect")
                + " (owner_uuid,listing_uuid,item_data,reason,created_at,collected) VALUES(?,?,?,?,?,0)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId.toString());
            if (listingId == null) statement.setNull(2, Types.VARCHAR);
            else statement.setString(2, listingId.toString());
            statement.setString(3, itemData);
            statement.setString(4, reason);
            statement.setLong(5, now);
            statement.executeUpdate();
        }
    }

    private void insertExpiredHistory(Connection connection, ExpiringListing listing, long now)
            throws SQLException {
        String sql = "INSERT INTO " + database.table("ah_history")
                + " (player_uuid,event_type,listing_uuid,item_data,item_type,item_amount,price,created_at)"
                + " VALUES(?,'EXPIRED',?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, listing.sellerId());
            statement.setString(2, listing.id());
            statement.setString(3, listing.itemData());
            statement.setString(4, listing.itemType());
            statement.setInt(5, listing.itemAmount());
            statement.setLong(6, listing.price());
            statement.setLong(7, now);
            statement.executeUpdate();
        }
    }

    private void history(Connection connection, UUID playerId, String eventType,
                         AuctionListing listing, UUID counterpartId, String counterpartName,
                         String itemData, long now) throws SQLException {
        String sql = "INSERT INTO " + database.table("ah_history")
                + " (player_uuid,event_type,listing_uuid,item_data,item_type,item_amount,"
                + "counterpart_uuid,counterpart_name,price,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, eventType);
            statement.setString(3, listing.id().toString());
            statement.setString(4, itemData);
            statement.setString(5, listing.item().getType().getKey().asString());
            statement.setInt(6, listing.item().getAmount());
            if (counterpartId == null) statement.setNull(7, Types.VARCHAR);
            else statement.setString(7, counterpartId.toString());
            if (counterpartName == null) statement.setNull(8, Types.VARCHAR);
            else statement.setString(8, counterpartName);
            statement.setLong(9, listing.price());
            statement.setLong(10, now);
            statement.executeUpdate();
        }
    }

    private void upsertStats(Connection connection, UUID playerId, long earned, long spent,
                             long sold, long bought, long now) throws SQLException {
        if (updateStats(connection, playerId, earned, spent, sold, bought, 0L, now) == 1) return;
        String insert = "INSERT INTO " + database.table("ah_stats")
                + " (player_uuid,coins_earned,coins_spent,items_sold,items_bought,listings_created,updated_at)"
                + " VALUES(?,?,?,?,?,0,?)";
        try (PreparedStatement statement = connection.prepareStatement(insert)) {
            statement.setString(1, playerId.toString());
            statement.setLong(2, earned);
            statement.setLong(3, spent);
            statement.setLong(4, sold);
            statement.setLong(5, bought);
            statement.setLong(6, now);
            statement.executeUpdate();
        } catch (SQLException concurrentInsert) {
            if (updateStats(connection, playerId, earned, spent, sold, bought, 0L, now) != 1) {
                throw concurrentInsert;
            }
        }
    }

    private void incrementListingsCreated(Connection connection, UUID playerId, long now)
            throws SQLException {
        if (updateStats(connection, playerId, 0L, 0L, 0L, 0L, 1L, now) == 1) return;
        String insert = "INSERT INTO " + database.table("ah_stats")
                + " (player_uuid,listings_created,updated_at) VALUES(?,1,?)";
        try (PreparedStatement statement = connection.prepareStatement(insert)) {
            statement.setString(1, playerId.toString());
            statement.setLong(2, now);
            statement.executeUpdate();
        } catch (SQLException concurrentInsert) {
            if (updateStats(connection, playerId, 0L, 0L, 0L, 0L, 1L, now) != 1) {
                throw concurrentInsert;
            }
        }
    }

    private int updateStats(Connection connection, UUID playerId, long earned, long spent,
                            long sold, long bought, long listingsCreated, long now) throws SQLException {
        String update = "UPDATE " + database.table("ah_stats")
                + " SET coins_earned=coins_earned+?,coins_spent=coins_spent+?,"
                + "items_sold=items_sold+?,items_bought=items_bought+?,"
                + "listings_created=listings_created+?,updated_at=? WHERE player_uuid=?";
        try (PreparedStatement statement = connection.prepareStatement(update)) {
            statement.setLong(1, earned);
            statement.setLong(2, spent);
            statement.setLong(3, sold);
            statement.setLong(4, bought);
            statement.setLong(5, listingsCreated);
            statement.setLong(6, now);
            statement.setString(7, playerId.toString());
            return statement.executeUpdate();
        }
    }

    private <T> T inTransaction(SqlWork<T> work) throws SQLException {
        try (Connection connection = database.connection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private record ExpiringListing(String id, String sellerId, String itemData,
                                   String itemType, int itemAmount, long price) {
    }
}
