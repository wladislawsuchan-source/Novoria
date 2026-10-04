package de.walahi.novosmp.lumi;

import de.walahi.smpcore.storage.StorageDialect;
import de.walahi.smpcore.storage.StorageManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class LumiRepository {
    private static final int BALANCE_BATCH_SIZE = 200;
    public enum TransferResult {
        SUCCESS,
        INVALID_AMOUNT,
        SAME_ACCOUNT,
        INSUFFICIENT_FUNDS,
        STORAGE_ERROR
    }

    private final StorageManager storage;

    public LumiRepository(StorageManager storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public long balance(UUID uuid) {
        try (Connection connection = storage.connection()) {
            ensure(connection, uuid);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT balance FROM " + storage.table("lumi_accounts") + " WHERE player_uuid=?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getLong(1) : 0L;
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Lumi-Kontostand konnte nicht geladen werden", exception);
        }
    }

    /** Scoreboard read: retain account creation on a miss without an INSERT for existing accounts. */
    public long balanceReadFirst(UUID uuid) {
        try (Connection connection = storage.connection()) {
            Long balance = readExistingBalance(connection, uuid);
            if (balance != null) return balance;
            ensure(connection, uuid);
            balance = readExistingBalance(connection, uuid);
            return balance == null ? 0L : balance;
        } catch (SQLException exception) {
            throw new IllegalStateException("Lumi-Kontostand konnte nicht geladen werden", exception);
        }
    }

    private Long readExistingBalance(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT balance FROM " + storage.table("lumi_accounts") + " WHERE player_uuid=?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : null;
            }
        }
    }

    /** Reads balances in bounded batches and creates missing accounts just as balance() does. */
    public Map<UUID, Long> balances(Collection<UUID> playerIds) {
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(playerIds));
        if (ids.isEmpty()) return Map.of();
        Map<UUID, Long> balances = new HashMap<>(ids.size());
        String table = storage.table("lumi_accounts");
        String insert = storage.dialect() == StorageDialect.SQLITE
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
        } catch (SQLException exception) {
            throw new IllegalStateException("Lumi-Kontostände konnten nicht geladen werden", exception);
        }
        return balances;
    }

    public boolean add(UUID uuid, long amount) {
        if (amount <= 0) return false;
        try (Connection connection = storage.connection()) {
            ensure(connection, uuid);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE " + storage.table("lumi_accounts") + " SET balance=balance+?, updated_at=? WHERE player_uuid=?")) {
                statement.setLong(1, amount);
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, uuid.toString());
                return statement.executeUpdate() == 1;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Lumis konnten nicht gutgeschrieben werden", exception);
        }
    }

    /** Credits the existing Lumi account inside a caller-owned reward transaction. */
    public boolean add(Connection connection, UUID uuid, long amount) throws SQLException {
        if (amount <= 0) return false;
        ensure(connection, uuid);
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + storage.table("lumi_accounts")
                        + " SET balance=balance+?, updated_at=? WHERE player_uuid=? AND balance<=?")) {
            statement.setLong(1, amount);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, uuid.toString());
            statement.setLong(4, Long.MAX_VALUE - amount);
            return statement.executeUpdate() == 1;
        }
    }

    public boolean set(UUID uuid, long amount) {
        if (amount < 0) return false;
        try (Connection connection = storage.connection()) {
            ensure(connection, uuid);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE " + storage.table("lumi_accounts") + " SET balance=?, updated_at=? WHERE player_uuid=?")) {
                statement.setLong(1, amount);
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, uuid.toString());
                return statement.executeUpdate() == 1;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Lumi-Kontostand konnte nicht gesetzt werden", exception);
        }
    }

    public boolean withdraw(UUID uuid, long amount) {
        if (amount <= 0) return false;
        try (Connection connection = storage.connection()) {
            ensure(connection, uuid);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE " + storage.table("lumi_accounts") + " SET balance=balance-?, updated_at=? WHERE player_uuid=? AND balance>=?")) {
                statement.setLong(1, amount);
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, uuid.toString());
                statement.setLong(4, amount);
                return statement.executeUpdate() == 1;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Lumis konnten nicht abgezogen werden", exception);
        }
    }

    public TransferResult transfer(UUID sender, UUID recipient, long amount) {
        if (amount <= 0L) return TransferResult.INVALID_AMOUNT;
        if (sender.equals(recipient)) return TransferResult.SAME_ACCOUNT;

        try (Connection connection = storage.connection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                ensure(connection, sender);
                ensure(connection, recipient);

                long now = System.currentTimeMillis();
                try (PreparedStatement withdraw = connection.prepareStatement(
                        "UPDATE " + storage.table("lumi_accounts")
                                + " SET balance=balance-?, updated_at=? WHERE player_uuid=? AND balance>=?")) {
                    withdraw.setLong(1, amount);
                    withdraw.setLong(2, now);
                    withdraw.setString(3, sender.toString());
                    withdraw.setLong(4, amount);
                    if (withdraw.executeUpdate() != 1) {
                        connection.rollback();
                        return TransferResult.INSUFFICIENT_FUNDS;
                    }
                }

                try (PreparedStatement deposit = connection.prepareStatement(
                        "UPDATE " + storage.table("lumi_accounts")
                                + " SET balance=balance+?, updated_at=? WHERE player_uuid=? AND balance<=?")) {
                    deposit.setLong(1, amount);
                    deposit.setLong(2, now);
                    deposit.setString(3, recipient.toString());
                    deposit.setLong(4, Long.MAX_VALUE - amount);
                    if (deposit.executeUpdate() != 1) {
                        connection.rollback();
                        return TransferResult.INVALID_AMOUNT;
                    }
                }
                connection.commit();
                return TransferResult.SUCCESS;
            } catch (SQLException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            } finally {
                try {
                    connection.setAutoCommit(previousAutoCommit);
                } catch (SQLException ignored) {
                }
            }
        } catch (SQLException exception) {
            return TransferResult.STORAGE_ERROR;
        }
    }

    private void ensure(Connection connection, UUID uuid) throws SQLException {
        String sql = storage.dialect() == StorageDialect.SQLITE
                ? "INSERT OR IGNORE INTO " + storage.table("lumi_accounts") + " (player_uuid,balance,updated_at) VALUES (?,0,?)"
                : "INSERT IGNORE INTO " + storage.table("lumi_accounts") + " (player_uuid,balance,updated_at) VALUES (?,0,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setLong(2, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }
}
