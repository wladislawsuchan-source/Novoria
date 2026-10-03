package de.walahi.smpcore.economy;

import de.walahi.smpcore.api.event.ActionContext;
import de.walahi.smpcore.api.event.EconomyTransactionEvent;
import de.walahi.smpcore.storage.StorageManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/** Writes immutable economy audit records. Records are inserted in the same DB transaction as the balance change. */
public final class EconomyTransactionRepository {
    private final StorageManager storage;

    public EconomyTransactionRepository(StorageManager storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public void insert(Connection connection, UUID transactionUuid, UUID playerUuid,
                       EconomyTransactionEvent.Type type, long amount, long before, long after,
                       String reason, ActionContext context) throws SQLException {
        ActionContext effective = context == null ? ActionContext.system(playerUuid) : context;
        String sql = "INSERT INTO " + storage.table("economy_transactions") +
                " (log_uuid, transaction_uuid, player_uuid, transaction_type, amount, balance_before, " +
                "balance_after, reason, source, actor_uuid, target_uuid, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, transactionUuid.toString());
            statement.setString(3, playerUuid.toString());
            statement.setString(4, type.name());
            statement.setLong(5, amount);
            statement.setLong(6, before);
            statement.setLong(7, after);
            statement.setString(8, truncate(reason, 255));
            statement.setString(9, effective.source().name());
            statement.setString(10, effective.actorUuid() == null ? null : effective.actorUuid().toString());
            statement.setString(11, effective.targetUuid() == null ? null : effective.targetUuid().toString());
            statement.setLong(12, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private static String truncate(String value, int maxLength) {
        String safe = value == null ? "" : value;
        return safe.length() <= maxLength ? safe : safe.substring(0, maxLength);
    }
}
