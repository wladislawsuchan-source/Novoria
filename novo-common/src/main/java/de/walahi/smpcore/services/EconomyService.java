package de.walahi.smpcore.services;

import de.walahi.smpcore.api.EventPublisher;
import de.walahi.smpcore.api.event.ActionContext;
import de.walahi.smpcore.api.event.EconomyTransactionEvent;
import de.walahi.smpcore.economy.EconomyOperationResult;
import de.walahi.smpcore.economy.EconomyRepository;
import de.walahi.smpcore.economy.EconomyTransactionRepository;
import de.walahi.smpcore.storage.StorageManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

public final class EconomyService implements Service {
    private final StorageManager storage;
    private final EconomyRepository repository;
    private final EconomyTransactionRepository transactionRepository;
    private final EventPublisher events;
    private final Logger logger;

    public EconomyService(StorageManager storage, EventPublisher events, Logger logger) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.repository = new EconomyRepository(storage);
        this.transactionRepository = new EconomyTransactionRepository(storage);
        this.events = Objects.requireNonNull(events, "events");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public boolean available() { return storage.isAvailable(); }

    public long balance(UUID uuid) {
        try { return repository.balance(uuid); }
        catch (SQLException exception) { log(exception); return 0L; }
    }

    public long balanceReadFirst(UUID uuid) {
        try { return repository.balanceReadFirst(uuid); }
        catch (SQLException exception) { log(exception); return 0L; }
    }

    public Map<UUID, Long> balances(Collection<UUID> playerIds) {
        try { return repository.balances(playerIds); }
        catch (SQLException exception) {
            log(exception);
            // Like balance(), a failed read is displayed as zero; never persist fallback values.
            return Map.of();
        }
    }

    public EconomyOperationResult deposit(UUID uuid, long amount, String reason, ActionContext context) {
        return change(uuid, amount, true, reason, context);
    }

    public EconomyOperationResult withdraw(UUID uuid, long amount, String reason, ActionContext context) {
        return change(uuid, amount, false, reason, context);
    }

    public EconomyOperationResult setBalance(UUID uuid, long amount, String reason, ActionContext context) {
        if (amount < 0L) return EconomyOperationResult.INVALID_AMOUNT;
        try (Connection connection = storage.connection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                long before = repository.balanceForUpdate(connection, uuid);
                repository.setBalance(connection, uuid, amount);
                UUID transactionUuid = UUID.randomUUID();
                long difference = absoluteDifference(before, amount);
                transactionRepository.insert(connection, transactionUuid, uuid, EconomyTransactionEvent.Type.SET,
                        difference, before, amount, reason, context);
                connection.commit();
                publish(uuid, EconomyTransactionEvent.Type.SET, difference, before, amount, reason, context);
                return EconomyOperationResult.SUCCESS;
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                restoreAutoCommit(connection, previousAutoCommit);
            }
        } catch (SQLException exception) {
            log(exception);
            return EconomyOperationResult.STORAGE_ERROR;
        }
    }

    public EconomyOperationResult transfer(UUID from, UUID to, long amount, String reason, ActionContext context) {
        if (amount <= 0L) return EconomyOperationResult.INVALID_AMOUNT;
        if (from.equals(to)) return EconomyOperationResult.SAME_ACCOUNT;

        try (Connection connection = storage.connection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                // Lock both rows in a stable order, regardless of transfer direction.
                UUID first = from.compareTo(to) < 0 ? from : to;
                UUID second = from.compareTo(to) < 0 ? to : from;
                long firstBalance = repository.balanceForUpdate(connection, first);
                long secondBalance = repository.balanceForUpdate(connection, second);
                long fromBefore = from.equals(first) ? firstBalance : secondBalance;
                if (fromBefore < amount) {
                    connection.rollback();
                    return EconomyOperationResult.INSUFFICIENT_FUNDS;
                }
                long toBefore = to.equals(first) ? firstBalance : secondBalance;
                long fromAfter = Math.subtractExact(fromBefore, amount);
                long toAfter = Math.addExact(toBefore, amount);
                UUID transactionUuid = UUID.randomUUID();

                repository.setBalance(connection, from, fromAfter);
                repository.setBalance(connection, to, toAfter);
                transactionRepository.insert(connection, transactionUuid, from, EconomyTransactionEvent.Type.TRANSFER,
                        amount, fromBefore, fromAfter, reason, context);
                transactionRepository.insert(connection, transactionUuid, to, EconomyTransactionEvent.Type.TRANSFER,
                        amount, toBefore, toAfter, reason, context);
                connection.commit();

                publish(from, EconomyTransactionEvent.Type.TRANSFER, amount, fromBefore, fromAfter, reason, context);
                publish(to, EconomyTransactionEvent.Type.TRANSFER, amount, toBefore, toAfter, reason, context);
                return EconomyOperationResult.SUCCESS;
            } catch (ArithmeticException exception) {
                connection.rollback();
                return EconomyOperationResult.INVALID_AMOUNT;
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                restoreAutoCommit(connection, previousAutoCommit);
            }
        } catch (SQLException exception) {
            log(exception);
            return EconomyOperationResult.STORAGE_ERROR;
        }
    }

    private EconomyOperationResult change(UUID uuid, long amount, boolean add, String reason, ActionContext context) {
        if (amount <= 0L) return EconomyOperationResult.INVALID_AMOUNT;
        try (Connection connection = storage.connection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                ConnectionChange change = add
                        ? deposit(connection, uuid, amount, reason, context)
                        : withdraw(connection, uuid, amount, reason, context);
                if (change.result() != EconomyOperationResult.SUCCESS) {
                    connection.rollback();
                    return change.result();
                }
                connection.commit();
                publishCommitted(change);
                return EconomyOperationResult.SUCCESS;
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                restoreAutoCommit(connection, previousAutoCommit);
            }
        } catch (SQLException exception) {
            log(exception);
            return EconomyOperationResult.STORAGE_ERROR;
        }
    }

    /** Prepares a deposit on the caller's transaction; never commits, rolls back, closes, or publishes. */
    public ConnectionChange deposit(Connection connection, UUID uuid, long amount,
                                    String reason, ActionContext context) throws SQLException {
        return changeOnConnection(connection, uuid, amount, true, reason, context);
    }

    /** Prepares a withdrawal on the caller's transaction; never commits, rolls back, closes, or publishes. */
    public ConnectionChange withdraw(Connection connection, UUID uuid, long amount,
                                     String reason, ActionContext context) throws SQLException {
        return changeOnConnection(connection, uuid, amount, false, reason, context);
    }

    private ConnectionChange changeOnConnection(Connection connection, UUID uuid, long amount,
                                                boolean add, String reason, ActionContext context)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        if (amount <= 0L) return new ConnectionChange(EconomyOperationResult.INVALID_AMOUNT,
                null, null, 0L, 0L, 0L, null, null);
        long before = repository.balanceForUpdate(connection, uuid);
        if (!add && before < amount) {
            return new ConnectionChange(EconomyOperationResult.INSUFFICIENT_FUNDS,
                    null, null, 0L, 0L, 0L, null, null);
        }
        final long after;
        try {
            after = add ? Math.addExact(before, amount) : Math.subtractExact(before, amount);
        } catch (ArithmeticException exception) {
            return new ConnectionChange(EconomyOperationResult.INVALID_AMOUNT,
                    null, null, 0L, 0L, 0L, null, null);
        }
        EconomyTransactionEvent.Type type = add
                ? EconomyTransactionEvent.Type.DEPOSIT : EconomyTransactionEvent.Type.WITHDRAW;
        repository.setBalance(connection, uuid, after);
        transactionRepository.insert(connection, UUID.randomUUID(), uuid, type,
                amount, before, after, reason, context);
        return new ConnectionChange(EconomyOperationResult.SUCCESS, uuid, type, amount,
                before, after, reason, context);
    }

    /** Call only after the caller has confirmed the transaction commit. */
    public void publishCommitted(ConnectionChange change) {
        if (change != null && change.result() == EconomyOperationResult.SUCCESS) {
            publish(change.uuid(), change.type(), change.amount(), change.before(), change.after(),
                    change.reason(), change.context());
        }
    }

    public record ConnectionChange(EconomyOperationResult result, UUID uuid,
                                   EconomyTransactionEvent.Type type, long amount, long before,
                                   long after, String reason, ActionContext context) { }

    private void publish(UUID uuid, EconomyTransactionEvent.Type type, long amount, long before, long after,
                         String reason, ActionContext context) {
        events.publish(new EconomyTransactionEvent(uuid, type, amount, before, after, reason,
                context == null ? ActionContext.system(uuid) : context));
    }

    private static long absoluteDifference(long first, long second) {
        try { return Math.abs(Math.subtractExact(first, second)); }
        catch (ArithmeticException exception) { return Long.MAX_VALUE; }
    }

    private static void rollback(Connection connection, SQLException original) {
        try { connection.rollback(); }
        catch (SQLException rollbackFailure) { original.addSuppressed(rollbackFailure); }
    }

    private static void restoreAutoCommit(Connection connection, boolean previousAutoCommit) {
        try { connection.setAutoCommit(previousAutoCommit); }
        catch (SQLException ignored) { }
    }

    private void log(SQLException exception) {
        logger.severe("Economy-Datenbankfehler: " + exception.getMessage());
    }
}
