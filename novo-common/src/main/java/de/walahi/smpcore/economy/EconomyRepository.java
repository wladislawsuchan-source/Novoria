package de.walahi.smpcore.economy;

import de.walahi.smpcore.storage.StorageManager;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class EconomyRepository {
    private static final int BALANCE_BATCH_SIZE = 200;
    private final StorageManager storage;

    public EconomyRepository(StorageManager storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public long balance(UUID uuid) throws SQLException {
        try (Connection connection = storage.connection()) {
            return balance(connection, uuid);
        }
    }

    public long balance(Connection connection, UUID uuid) throws SQLException {
        ensureAccount(connection, uuid);
        String sql = "SELECT balance FROM " + storage.table("economy_accounts") + " WHERE player_uuid=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        }
    }

    /** Scoreboard read: retain account creation on a miss without an INSERT for existing accounts. */
    public long balanceReadFirst(UUID uuid) throws SQLException {
        try (Connection connection = storage.connection()) {
            Long balance = readExistingBalance(connection, uuid);
            if (balance != null) return balance;
            ensureAccount(connection, uuid);
            balance = readExistingBalance(connection, uuid);
            return balance == null ? 0L : balance;
        }
    }

    private Long readExistingBalance(Connection connection, UUID uuid) throws SQLException {
        String sql = "SELECT balance FROM " + storage.table("economy_accounts") + " WHERE player_uuid=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : null;
            }
        }
    }

    /** Reads a request-local set of balances while preserving balance()'s account-creation semantics. */
    public Map<UUID, Long> balances(Collection<UUID> playerIds) throws SQLException {
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(playerIds));
        if (ids.isEmpty()) return Map.of();
        Map<UUID, Long> balances = new HashMap<>(ids.size());
        String table = storage.table("economy_accounts");
        String insert = storage.dialect() == de.walahi.smpcore.storage.StorageDialect.SQLITE
                ? "INSERT OR IGNORE INTO " : "INSERT IGNORE INTO ";
        try (Connection connection = storage.connection()) {
            for (int start = 0; start < ids.size(); start += BALANCE_BATCH_SIZE) {
                List<UUID> batch = ids.subList(start, Math.min(start + BALANCE_BATCH_SIZE, ids.size()));
                String rows = String.join(",", java.util.Collections.nCopies(batch.size(), "(?,0,?)"));
                try (PreparedStatement statement = connection.prepareStatement(insert + table
                        + " (player_uuid,balance,updated_at) VALUES " + rows)) {
                    long now = System.currentTimeMillis();
                    for (int index = 0; index < batch.size(); index++) {
                        statement.setString(index * 2 + 1, batch.get(index).toString());
                        statement.setLong(index * 2 + 2, now);
                    }
                    statement.executeUpdate();
                }
                String placeholders = String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT player_uuid,balance FROM " + table + " WHERE player_uuid IN (" + placeholders + ")")) {
                    for (int index = 0; index < batch.size(); index++) {
                        statement.setString(index + 1, batch.get(index).toString());
                    }
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            balances.put(UUID.fromString(result.getString(1)), result.getLong(2));
                        }
                    }
                }
            }
        }
        return balances;
    }

    /** Locks an account row for a larger transaction that also owns non-economy state. */
    public long balanceForUpdate(Connection connection, UUID uuid) throws SQLException {
        ensureAccount(connection, uuid);
        String suffix = storage.dialect() == de.walahi.smpcore.storage.StorageDialect.MYSQL
                ? " FOR UPDATE" : "";
        String sql = "SELECT balance FROM " + storage.table("economy_accounts")
                + " WHERE player_uuid=?" + suffix;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        }
    }

    public void setBalance(Connection connection, UUID uuid, long amount) throws SQLException {
        ensureAccount(connection, uuid);
        String sql = "UPDATE " + storage.table("economy_accounts") +
                " SET balance=?, updated_at=? WHERE player_uuid=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, amount);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, uuid.toString());
            statement.executeUpdate();
        }
    }

    private void ensureAccount(Connection connection, UUID uuid) throws SQLException {
        String table = storage.table("economy_accounts");
        String sql = storage.dialect() == de.walahi.smpcore.storage.StorageDialect.SQLITE
                ? "INSERT OR IGNORE INTO " + table + " (player_uuid,balance,updated_at) VALUES (?,0,?)"
                : "INSERT IGNORE INTO " + table + " (player_uuid,balance,updated_at) VALUES (?,0,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setLong(2, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }
}
