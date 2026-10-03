package de.walahi.smpcore.services;

import de.walahi.smpcore.api.event.ActionContext;
import de.walahi.smpcore.api.event.EconomyTransactionEvent;
import de.walahi.smpcore.database.migration.CreateEconomyMigration;
import de.walahi.smpcore.database.migration.CreateEconomyTransactionLogMigration;
import de.walahi.smpcore.economy.EconomyOperationResult;
import de.walahi.smpcore.storage.StorageDialect;
import de.walahi.smpcore.storage.StorageManager;
import de.walahi.smpcore.storage.StorageProvider;
import org.bukkit.Bukkit;
import org.bukkit.Server;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/** Standalone SQLite check, run explicitly after test-compile with a minimal Bukkit event stub. */
public final class EconomyServiceSqliteCheck {
    private static final UUID A = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final MemoryStorage provider;
    private final StorageManager storage;
    private final List<EconomyTransactionEvent> events = new ArrayList<>();
    private final EconomyService economy;

    private EconomyServiceSqliteCheck() throws Exception {
        provider = new MemoryStorage();
        provider.initialize();
        storage = new StorageManager(null);
        Field field = StorageManager.class.getDeclaredField("provider");
        field.setAccessible(true);
        field.set(storage, provider);
        economy = new EconomyService(storage, event -> events.add((EconomyTransactionEvent) event),
                Logger.getLogger("EconomyServiceSqliteCheck"));
    }

    public static void main(String[] args) throws Exception {
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(),
                new Class<?>[] { Server.class }, (proxy, method, parameters) -> {
                    if (method.getName().equals("isPrimaryThread")) return true;
                    throw new UnsupportedOperationException("Unexpected test server call: " + method.getName());
                });
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, server);
        EconomyServiceSqliteCheck check = new EconomyServiceSqliteCheck();
        try {
            check.run();
            System.out.println("EconomyService SQLite checks passed");
        } finally {
            check.provider.close();
        }
    }

    private void run() throws Exception {
        reset(1_000L, 400L);
        expect(economy.withdraw(A, 200L, "first", context(A)), EconomyOperationResult.SUCCESS);
        expect(economy.withdraw(A, 300L, "second", context(A)), EconomyOperationResult.SUCCESS);
        check(balance(A) == 500L, "two withdrawals");
        check(logs().equals(List.of("WITHDRAW:200:1000:800", "WITHDRAW:300:800:500")),
                "withdraw audit values");
        check(events.size() == 2, "withdraw events");

        reset(1_000L, 400L);
        expect(economy.deposit(A, 500L, "deposit", context(A)), EconomyOperationResult.SUCCESS);
        expect(economy.withdraw(A, 200L, "withdraw", context(A)), EconomyOperationResult.SUCCESS);
        check(balance(A) == 1_300L, "deposit plus withdrawal");
        check(logs().equals(List.of("DEPOSIT:500:1000:1500", "WITHDRAW:200:1500:1300")),
                "deposit and withdrawal audit values");

        reset(1_000L, 400L);
        expect(economy.transfer(A, B, 300L, "transfer", context(A)), EconomyOperationResult.SUCCESS);
        check(balance(A) == 700L && balance(B) == 700L, "transfer roles despite reverse UUID order");
        check(logs().equals(List.of("TRANSFER:300:1000:700", "TRANSFER:300:400:700")),
                "transfer audit values");
        check(distinctTransactionIds() == 1 && events.size() == 2, "paired transfer audit/events");

        reset(100L, 400L);
        expect(economy.withdraw(A, 200L, "insufficient", context(A)),
                EconomyOperationResult.INSUFFICIENT_FUNDS);
        check(balance(A) == 100L && logs().isEmpty() && events.isEmpty(), "insufficient funds unchanged");

        reset(Long.MAX_VALUE - 1L, 400L);
        expect(economy.deposit(A, 2L, "overflow", context(A)), EconomyOperationResult.INVALID_AMOUNT);
        check(balance(A) == Long.MAX_VALUE - 1L && logs().isEmpty(), "deposit overflow unchanged");

        reset(1_000L, Long.MAX_VALUE - 100L);
        expect(economy.transfer(A, B, 300L, "transfer overflow", context(A)),
                EconomyOperationResult.INVALID_AMOUNT);
        check(balance(A) == 1_000L && balance(B) == Long.MAX_VALUE - 100L && logs().isEmpty(),
                "transfer overflow unchanged");

        reset(1_000L, 400L);
        expect(economy.setBalance(A, 250L, "set", context(A)), EconomyOperationResult.SUCCESS);
        check(balance(A) == 250L && logs().equals(List.of("SET:750:1000:250")),
                "absolute set and audit");

        reset(1_000L, 400L);
        try (Connection connection = storage.connection()) {
            connection.setAutoCommit(false);
            EconomyService.ConnectionChange change = economy.withdraw(connection, A, 200L,
                    "outer rollback", context(A));
            expect(change.result(), EconomyOperationResult.SUCCESS);
            check(balance(connection, A) == 800L && events.isEmpty(),
                    "connection helper neither commits nor publishes");
            connection.rollback();
        }
        check(balance(A) == 1_000L && logs().isEmpty() && events.isEmpty(),
                "outer rollback includes balance and audit");

        try (Connection connection = storage.connection()) {
            connection.setAutoCommit(false);
            EconomyService.ConnectionChange change = economy.deposit(connection, A, 100L,
                    "outer commit", context(A));
            expect(change.result(), EconomyOperationResult.SUCCESS);
            connection.commit();
            economy.publishCommitted(change);
        }
        check(balance(A) == 1_100L && logs().equals(List.of("DEPOSIT:100:1000:1100"))
                && events.size() == 1, "outer commit and publish");
    }

    private void reset(long aBalance, long bBalance) throws SQLException {
        try (Connection connection = storage.connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM economy_transactions");
            statement.executeUpdate("DELETE FROM economy_accounts");
            insertBalance(connection, A, aBalance);
            insertBalance(connection, B, bBalance);
        }
        events.clear();
    }

    private void insertBalance(Connection connection, UUID uuid, long amount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO economy_accounts(player_uuid,balance,updated_at) VALUES(?,?,0)")) {
            statement.setString(1, uuid.toString());
            statement.setLong(2, amount);
            statement.executeUpdate();
        }
    }

    private long balance(UUID uuid) throws SQLException {
        try (Connection connection = storage.connection()) {
            return balance(connection, uuid);
        }
    }

    private long balance(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT balance FROM economy_accounts WHERE player_uuid=?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rows = statement.executeQuery()) {
                check(rows.next(), "missing account");
                return rows.getLong(1);
            }
        }
    }

    private List<String> logs() throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection connection = storage.connection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT transaction_type,amount,balance_before,balance_after"
                     + " FROM economy_transactions ORDER BY rowid")) {
            while (rows.next()) {
                result.add(rows.getString(1) + ":" + rows.getLong(2) + ":" + rows.getLong(3)
                        + ":" + rows.getLong(4));
            }
        }
        return result;
    }

    private int distinctTransactionIds() throws SQLException {
        try (Connection connection = storage.connection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT COUNT(DISTINCT transaction_uuid) FROM economy_transactions")) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private static ActionContext context(UUID playerId) {
        return ActionContext.system(playerId);
    }

    private static void expect(EconomyOperationResult actual, EconomyOperationResult expected) {
        check(actual == expected, "expected " + expected + " but got " + actual);
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static final class MemoryStorage implements StorageProvider {
        private final String url = "jdbc:sqlite:file:economy-check-" + UUID.randomUUID()
                + "?mode=memory&cache=shared";
        private Connection keeper;

        @Override public void initialize() throws SQLException {
            keeper = DriverManager.getConnection(url);
            new CreateEconomyMigration().apply(keeper, StorageDialect.SQLITE, "");
            new CreateEconomyTransactionLogMigration().apply(keeper, StorageDialect.SQLITE, "");
        }

        @Override public Connection connection() throws SQLException { return DriverManager.getConnection(url); }
        @Override public boolean isAvailable() { return keeper != null; }
        @Override public String type() { return "sqlite"; }
        @Override public StorageDialect dialect() { return StorageDialect.SQLITE; }
        @Override public void close() {
            if (keeper != null) {
                try { keeper.close(); } catch (SQLException exception) { throw new IllegalStateException(exception); }
                keeper = null;
            }
        }
    }
}
