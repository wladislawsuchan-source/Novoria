package de.walahi.novosmp.bounty;

import de.walahi.smpcore.api.event.ActionContext;
import de.walahi.smpcore.api.event.EconomyTransactionEvent;
import de.walahi.smpcore.economy.EconomyRepository;
import de.walahi.smpcore.economy.EconomyTransactionRepository;
import de.walahi.smpcore.storage.StorageDialect;
import de.walahi.smpcore.storage.StorageManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Owns all row-locked bounty/economy transactions. */
final class BountyRepository {
    enum Status { SUCCESS, NOT_FOUND, INSUFFICIENT_FUNDS, INVALID_AMOUNT, STORAGE_ERROR }
    record KnownPlayer(UUID id, String name) { }
    record Mutation(Status status, BountyEntry entry, long balanceBefore, long balanceAfter) { }

    private final StorageManager storage;
    private final EconomyRepository economy;
    private final EconomyTransactionRepository audit;

    BountyRepository(StorageManager storage) {
        this.storage = storage;
        this.economy = new EconomyRepository(storage);
        this.audit = new EconomyTransactionRepository(storage);
    }

    Map<UUID, BountyEntry> loadAll() throws SQLException {
        Map<UUID, BountyEntry> result = new LinkedHashMap<>();
        String sql = "SELECT target_uuid,target_name,amount,updated_at FROM "
                + storage.table("bounties") + " WHERE amount>0";
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                BountyEntry entry = entry(rows);
                result.put(entry.targetId(), entry);
            }
        }
        return result;
    }

    KnownPlayer findKnownPlayer(String name) throws SQLException {
        String sql = "SELECT player_uuid,player_name FROM " + storage.table("player_stats")
                + " WHERE LOWER(player_name)=LOWER(?) ORDER BY last_seen DESC";
        try (Connection connection = storage.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            statement.setMaxRows(1);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return new KnownPlayer(UUID.fromString(rows.getString(1)), rows.getString(2));
            }
        }
    }

    Mutation place(UUID payer, UUID target, String targetName, long amount, ActionContext context) {
        if (amount <= 0L) return new Mutation(Status.INVALID_AMOUNT, null, 0L, 0L);
        return transaction(connection -> {
            BountyEntry current = ensureAndLock(connection, target, targetName);
            long before = economy.balanceForUpdate(connection, payer);
            if (before < amount) return rollbackResult(connection, Status.INSUFFICIENT_FUNDS);
            long total = Math.addExact(current.amount(), amount);
            long after = Math.subtractExact(before, amount);
            economy.setBalance(connection, payer, after);
            audit.insert(connection, UUID.randomUUID(), payer, EconomyTransactionEvent.Type.WITHDRAW,
                    amount, before, after, "BOUNTY_PLACE", context);
            BountyEntry updated = update(connection, target, targetName, total);
            connection.commit();
            return new Mutation(Status.SUCCESS, updated, before, after);
        });
    }

    Mutation claim(UUID killer, UUID target, ActionContext context) {
        return transaction(connection -> {
            BountyEntry current = lock(connection, target);
            if (current == null || current.amount() <= 0L) return rollbackResult(connection, Status.NOT_FOUND);
            long before = economy.balanceForUpdate(connection, killer);
            long after = Math.addExact(before, current.amount());
            delete(connection, target);
            economy.setBalance(connection, killer, after);
            audit.insert(connection, UUID.randomUUID(), killer, EconomyTransactionEvent.Type.DEPOSIT,
                    current.amount(), before, after, "BOUNTY_CLAIM", context);
            connection.commit();
            return new Mutation(Status.SUCCESS, current, before, after);
        });
    }

    Mutation adminSet(UUID target, String name, long amount) {
        if (amount < 0L) return new Mutation(Status.INVALID_AMOUNT, null, 0L, 0L);
        return transaction(connection -> {
            ensureAndLock(connection, target, name);
            if (amount == 0L) {
                delete(connection, target);
                connection.commit();
                return new Mutation(Status.SUCCESS, null, 0L, 0L);
            }
            BountyEntry updated = update(connection, target, name, amount);
            connection.commit();
            return new Mutation(Status.SUCCESS, updated, 0L, 0L);
        });
    }

    Mutation adminAdd(UUID target, String name, long amount) {
        if (amount <= 0L) return new Mutation(Status.INVALID_AMOUNT, null, 0L, 0L);
        return transaction(connection -> {
            BountyEntry current = ensureAndLock(connection, target, name);
            BountyEntry updated = update(connection, target, name, Math.addExact(current.amount(), amount));
            connection.commit();
            return new Mutation(Status.SUCCESS, updated, 0L, 0L);
        });
    }

    Mutation adminRemove(UUID target) {
        return transaction(connection -> {
            BountyEntry current = lock(connection, target);
            if (current == null) return rollbackResult(connection, Status.NOT_FOUND);
            delete(connection, target);
            connection.commit();
            return new Mutation(Status.SUCCESS, current, 0L, 0L);
        });
    }

    void updateName(UUID target, String name) throws SQLException {
        String sql = "UPDATE " + storage.table("bounties") + " SET target_name=? WHERE target_uuid=?";
        try (Connection connection = storage.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name); statement.setString(2, target.toString()); statement.executeUpdate();
        }
    }

    private BountyEntry ensureAndLock(Connection connection, UUID target, String name) throws SQLException {
        String table = storage.table("bounties");
        String sql = storage.dialect() == StorageDialect.SQLITE
                ? "INSERT OR IGNORE INTO " + table + " (target_uuid,target_name,amount,updated_at) VALUES (?,?,0,?)"
                : "INSERT IGNORE INTO " + table + " (target_uuid,target_name,amount,updated_at) VALUES (?,?,0,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, target.toString()); statement.setString(2, name);
            statement.setLong(3, System.currentTimeMillis()); statement.executeUpdate();
        }
        return lock(connection, target);
    }

    private BountyEntry lock(Connection connection, UUID target) throws SQLException {
        String suffix = storage.dialect() == StorageDialect.MYSQL ? " FOR UPDATE" : "";
        String sql = "SELECT target_uuid,target_name,amount,updated_at FROM "
                + storage.table("bounties") + " WHERE target_uuid=?" + suffix;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, target.toString());
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? entry(rows) : null; }
        }
    }

    private BountyEntry update(Connection connection, UUID target, String name, long amount) throws SQLException {
        long now = System.currentTimeMillis();
        String sql = "UPDATE " + storage.table("bounties")
                + " SET target_name=?,amount=?,updated_at=? WHERE target_uuid=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name); statement.setLong(2, amount); statement.setLong(3, now);
            statement.setString(4, target.toString()); statement.executeUpdate();
        }
        return new BountyEntry(target, name, amount, now);
    }

    private void delete(Connection connection, UUID target) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + storage.table("bounties") + " WHERE target_uuid=?")) {
            statement.setString(1, target.toString()); statement.executeUpdate();
        }
    }

    private Mutation transaction(SqlWork work) {
        try (Connection connection = storage.connection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                return work.run(connection);
            } catch (ArithmeticException exception) {
                connection.rollback();
                return new Mutation(Status.INVALID_AMOUNT, null, 0L, 0L);
            } catch (SQLException exception) {
                try { connection.rollback(); } catch (SQLException failure) { exception.addSuppressed(failure); }
                throw exception;
            } finally {
                try { connection.setAutoCommit(previous); } catch (SQLException ignored) { }
            }
        } catch (SQLException exception) {
            return new Mutation(Status.STORAGE_ERROR, null, 0L, 0L);
        }
    }

    private Mutation rollbackResult(Connection connection, Status status) throws SQLException {
        connection.rollback();
        return new Mutation(status, null, 0L, 0L);
    }

    private BountyEntry entry(ResultSet rows) throws SQLException {
        return new BountyEntry(UUID.fromString(rows.getString("target_uuid")),
                rows.getString("target_name"), rows.getLong("amount"), rows.getLong("updated_at"));
    }

    @FunctionalInterface private interface SqlWork { Mutation run(Connection connection) throws SQLException; }
}
