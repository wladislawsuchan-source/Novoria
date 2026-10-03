package de.walahi.novosmp.referral;

import de.walahi.smpcore.storage.StorageDialect;
import de.walahi.smpcore.storage.StorageManager;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** SQL-Persistenz für das Refer-a-Friend-System. */
public final class ReferralRepository {
    private static final char[] CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private final StorageManager storage;
    private final SecureRandom random = new SecureRandom();

    public ReferralRepository(StorageManager storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public Account account(UUID playerId, String playerName) throws SQLException {
        Account existing = findAccount(playerId);
        if (existing != null) {
            if (playerName != null && !playerName.isBlank() && !playerName.equals(existing.playerName())) {
                updateName(playerId, playerName);
                return new Account(existing.playerId(), playerName, existing.code(), existing.points(), existing.pendingKeys(), existing.codeUnlocked());
            }
            return existing;
        }
        for (int attempt = 0; attempt < 64; attempt++) {
            String code = randomCode(5);
            try (Connection connection = storage.connection();
                 PreparedStatement statement = connection.prepareStatement(
                         "INSERT INTO " + storage.table("referral_accounts") +
                                 " (player_uuid,player_name,referral_code,points,pending_keys,created_at,code_unlocked) VALUES (?,?,?,?,?,?,?)")) {
                statement.setString(1, playerId.toString());
                statement.setString(2, safeName(playerName, playerId));
                statement.setString(3, code);
                statement.setInt(4, 0);
                statement.setInt(5, 0);
                statement.setLong(6, System.currentTimeMillis());
                statement.setInt(7, 0);
                statement.executeUpdate();
                return new Account(playerId, safeName(playerName, playerId), code, 0, 0, false);
            } catch (SQLException collision) {
                Account raced = findAccount(playerId);
                if (raced != null) return raced;
                if (attempt == 63) throw collision;
            }
        }
        throw new SQLException("Referral-Code konnte nicht erzeugt werden.");
    }

    public Account findAccount(UUID playerId) throws SQLException {
        String sql = "SELECT player_name,referral_code,points,pending_keys,code_unlocked FROM " + storage.table("referral_accounts") + " WHERE player_uuid=?";
        try (Connection connection = storage.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                return new Account(playerId, row.getString("player_name"), row.getString("referral_code"),
                        row.getInt("points"), row.getInt("pending_keys"), row.getInt("code_unlocked") != 0);
            }
        }
    }

    /** Schaltet den bereits serverseitig reservierten persönlichen Code dauerhaft frei. */
    public boolean unlockCode(UUID playerId) throws SQLException {
        String sql = "UPDATE " + storage.table("referral_accounts") + " SET code_unlocked=1 WHERE player_uuid=? AND code_unlocked=0";
        try (Connection connection = storage.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            return statement.executeUpdate() == 1;
        }
    }

    /** Zählt ausschließlich vollständig verifizierte Referrals. */
    public int inviteCount(UUID playerId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + storage.table("referrals") + " WHERE referrer_uuid=? AND verified_at>0";
        try (Connection connection = storage.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            try (ResultSet row = statement.executeQuery()) { return row.next() ? row.getInt(1) : 0; }
        }
    }

    public ReferralState referral(UUID referredId) throws SQLException {
        String sql = "SELECT referrer_uuid,redeemed_at,verified_at FROM " + storage.table("referrals") + " WHERE referred_uuid=?";
        try (Connection connection = storage.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, referredId.toString());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                return new ReferralState(referredId, UUID.fromString(row.getString("referrer_uuid")),
                        row.getLong("redeemed_at"), row.getLong("verified_at"));
            }
        }
    }

    public boolean alreadyReferred(UUID playerId) throws SQLException {
        return referral(playerId) != null;
    }

    /**
     * Merkt einen Referral nur vor. Punkte und Keys werden erst bei erfolgreicher
     * Aktivitätsprüfung vergeben.
     */
    public RedeemResult redeem(UUID referredId, String code) throws SQLException {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        try (Connection connection = storage.connection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                UUID referrer = null;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT player_uuid FROM " + storage.table("referral_accounts") + " WHERE referral_code=? AND code_unlocked<>0")) {
                    statement.setString(1, normalized);
                    try (ResultSet row = statement.executeQuery()) {
                        if (row.next()) referrer = UUID.fromString(row.getString("player_uuid"));
                    }
                }
                if (referrer == null) { connection.rollback(); return RedeemResult.INVALID_CODE; }
                if (referrer.equals(referredId)) { connection.rollback(); return RedeemResult.OWN_CODE; }
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT 1 FROM " + storage.table("referrals") + " WHERE referred_uuid=?")) {
                    statement.setString(1, referredId.toString());
                    try (ResultSet row = statement.executeQuery()) {
                        if (row.next()) { connection.rollback(); return RedeemResult.ALREADY_REDEEMED; }
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO " + storage.table("referrals") +
                                " (referred_uuid,referrer_uuid,redeemed_at,verified_at) VALUES (?,?,?,0)")) {
                    statement.setString(1, referredId.toString());
                    statement.setString(2, referrer.toString());
                    statement.setLong(3, System.currentTimeMillis());
                    statement.executeUpdate();
                }
                connection.commit();
                return new RedeemResult.Success(referrer);
            } catch (SQLException | RuntimeException exception) {
                try { connection.rollback(); } catch (SQLException rollback) { exception.addSuppressed(rollback); }
                throw exception;
            } finally {
                try { connection.setAutoCommit(previous); } catch (SQLException ignored) { }
            }
        }
    }

    /**
     * Schließt einen vorgemerkten Referral atomar ab. Der Werber bekommt +1 Punkt;
     * beide Spieler erhalten je einen ausstehenden Novo-Key, der beim nächsten
     * Online-Kontakt sicher ausgeliefert wird.
     */
    public VerifyResult verify(UUID referredId) throws SQLException {
        try (Connection connection = storage.connection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                UUID referrer = null;
                long verifiedAt = 0L;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT referrer_uuid,verified_at FROM " + storage.table("referrals") + " WHERE referred_uuid=?")) {
                    statement.setString(1, referredId.toString());
                    try (ResultSet row = statement.executeQuery()) {
                        if (row.next()) {
                            referrer = UUID.fromString(row.getString("referrer_uuid"));
                            verifiedAt = row.getLong("verified_at");
                        }
                    }
                }
                if (referrer == null) { connection.rollback(); return VerifyResult.NO_REFERRAL; }
                if (verifiedAt > 0L) { connection.rollback(); return new VerifyResult.AlreadyVerified(referrer); }

                long now = System.currentTimeMillis();
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE " + storage.table("referrals") + " SET verified_at=? WHERE referred_uuid=? AND verified_at=0")) {
                    statement.setLong(1, now);
                    statement.setString(2, referredId.toString());
                    if (statement.executeUpdate() != 1) {
                        connection.rollback();
                        return new VerifyResult.AlreadyVerified(referrer);
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE " + storage.table("referral_accounts") +
                                " SET points=points+1,pending_keys=pending_keys+1 WHERE player_uuid=?")) {
                    statement.setString(1, referrer.toString());
                    if (statement.executeUpdate() != 1) throw new SQLException("Referral-Konto des Werbers fehlt.");
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE " + storage.table("referral_accounts") + " SET pending_keys=pending_keys+1 WHERE player_uuid=?")) {
                    statement.setString(1, referredId.toString());
                    if (statement.executeUpdate() != 1) throw new SQLException("Referral-Konto des geworbenen Spielers fehlt.");
                }
                connection.commit();
                return new VerifyResult.Success(referrer);
            } catch (SQLException | RuntimeException exception) {
                try { connection.rollback(); } catch (SQLException rollback) { exception.addSuppressed(rollback); }
                throw exception;
            } finally {
                try { connection.setAutoCommit(previous); } catch (SQLException ignored) { }
            }
        }
    }

    /** Nimmt alle aktuell ausstehenden Referral-Keys atomar aus dem Konto. */
    public int takePendingKeys(UUID playerId) throws SQLException {
        try (Connection connection = storage.connection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                int amount = 0;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT pending_keys FROM " + storage.table("referral_accounts") + " WHERE player_uuid=?")) {
                    statement.setString(1, playerId.toString());
                    try (ResultSet row = statement.executeQuery()) {
                        if (row.next()) amount = Math.max(0, row.getInt(1));
                    }
                }
                if (amount <= 0) { connection.rollback(); return 0; }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE " + storage.table("referral_accounts") + " SET pending_keys=0 WHERE player_uuid=? AND pending_keys=?")) {
                    statement.setString(1, playerId.toString());
                    statement.setInt(2, amount);
                    if (statement.executeUpdate() != 1) { connection.rollback(); return 0; }
                }
                connection.commit();
                return amount;
            } catch (SQLException | RuntimeException exception) {
                try { connection.rollback(); } catch (SQLException rollback) { exception.addSuppressed(rollback); }
                throw exception;
            } finally {
                try { connection.setAutoCommit(previous); } catch (SQLException ignored) { }
            }
        }
    }

    public void addPendingKeys(UUID playerId, int amount) throws SQLException {
        if (amount <= 0) return;
        try (Connection connection = storage.connection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + storage.table("referral_accounts") + " SET pending_keys=pending_keys+? WHERE player_uuid=?")) {
            statement.setInt(1, amount);
            statement.setString(2, playerId.toString());
            statement.executeUpdate();
        }
    }

    public boolean spendPoints(UUID playerId, int amount) throws SQLException {
        if (amount <= 0) return true;
        String sql = "UPDATE " + storage.table("referral_accounts") + " SET points=points-? WHERE player_uuid=? AND points>=?";
        try (Connection connection = storage.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, amount);
            statement.setString(2, playerId.toString());
            statement.setInt(3, amount);
            return statement.executeUpdate() == 1;
        }
    }

    public void addPoints(UUID playerId, int amount) throws SQLException {
        if (amount <= 0) return;
        try (Connection connection = storage.connection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + storage.table("referral_accounts") + " SET points=points+? WHERE player_uuid=?")) {
            statement.setInt(1, amount);
            statement.setString(2, playerId.toString());
            statement.executeUpdate();
        }
    }

    public Set<Integer> claimedTiers(UUID playerId) throws SQLException {
        Set<Integer> result = new LinkedHashSet<>();
        String sql = "SELECT tier_id FROM " + storage.table("referral_reward_claims") + " WHERE player_uuid=? ORDER BY tier_id";
        try (Connection connection = storage.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(rows.getInt("tier_id"));
            }
        }
        return result;
    }

    public boolean reserveTier(UUID playerId, int tierId) throws SQLException {
        String sql = storage.dialect() == StorageDialect.SQLITE
                ? "INSERT OR IGNORE INTO " + storage.table("referral_reward_claims") + " (player_uuid,tier_id,claimed_at) VALUES (?,?,?)"
                : "INSERT IGNORE INTO " + storage.table("referral_reward_claims") + " (player_uuid,tier_id,claimed_at) VALUES (?,?,?)";
        try (Connection connection = storage.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setInt(2, tierId);
            statement.setLong(3, System.currentTimeMillis());
            return statement.executeUpdate() == 1;
        }
    }

    public void releaseTier(UUID playerId, int tierId) throws SQLException {
        try (Connection connection = storage.connection(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + storage.table("referral_reward_claims") + " WHERE player_uuid=? AND tier_id=?")) {
            statement.setString(1, playerId.toString());
            statement.setInt(2, tierId);
            statement.executeUpdate();
        }
    }

    /** Liefert die vollständige Bestenliste; die GUI übernimmt die Seitennavigation. */
    public List<LeaderboardEntry> leaderboard() throws SQLException {
        List<LeaderboardEntry> result = new ArrayList<>();
        String sql = "SELECT a.player_uuid,a.player_name,COUNT(r.referred_uuid) AS invites " +
                "FROM " + storage.table("referral_accounts") + " a JOIN " + storage.table("referrals") +
                " r ON r.referrer_uuid=a.player_uuid AND r.verified_at>0 GROUP BY a.player_uuid,a.player_name " +
                "ORDER BY invites DESC,a.player_name ASC";
        try (Connection connection = storage.connection(); PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) result.add(new LeaderboardEntry(
                    UUID.fromString(rows.getString("player_uuid")), rows.getString("player_name"), rows.getInt("invites")));
        }
        return result;
    }

    private void updateName(UUID playerId, String playerName) throws SQLException {
        try (Connection connection = storage.connection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + storage.table("referral_accounts") + " SET player_name=? WHERE player_uuid=?")) {
            statement.setString(1, playerName);
            statement.setString(2, playerId.toString());
            statement.executeUpdate();
        }
    }

    private String randomCode(int length) {
        StringBuilder result = new StringBuilder(length);
        for (int i = 0; i < length; i++) result.append(CODE_CHARS[random.nextInt(CODE_CHARS.length)]);
        return result.toString();
    }

    private String safeName(String name, UUID uuid) {
        return name == null || name.isBlank() ? uuid.toString() : name;
    }

    public record Account(UUID playerId, String playerName, String code, int points, int pendingKeys, boolean codeUnlocked) { }
    public record ReferralState(UUID referredId, UUID referrerId, long redeemedAt, long verifiedAt) {
        public boolean verified() { return verifiedAt > 0L; }
    }
    public record LeaderboardEntry(UUID playerId, String playerName, int invites) { }

    public sealed interface RedeemResult permits RedeemResult.Simple, RedeemResult.Success {
        RedeemResult INVALID_CODE = new Simple("INVALID_CODE");
        RedeemResult OWN_CODE = new Simple("OWN_CODE");
        RedeemResult ALREADY_REDEEMED = new Simple("ALREADY_REDEEMED");
        record Simple(String name) implements RedeemResult { }
        record Success(UUID referrerId) implements RedeemResult { }
    }

    public sealed interface VerifyResult permits VerifyResult.Simple, VerifyResult.Success, VerifyResult.AlreadyVerified {
        VerifyResult NO_REFERRAL = new Simple("NO_REFERRAL");
        record Simple(String name) implements VerifyResult { }
        record Success(UUID referrerId) implements VerifyResult { }
        record AlreadyVerified(UUID referrerId) implements VerifyResult { }
    }
}
