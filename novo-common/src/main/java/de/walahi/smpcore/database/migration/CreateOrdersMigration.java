package de.walahi.smpcore.database.migration;

import de.walahi.smpcore.storage.StorageDialect;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** 11.10.0: durable buy-order foundation with escrow, collect and history. */
public final class CreateOrdersMigration implements SchemaMigration {
    @Override public int version() { return 5; }
    @Override public String description() { return "Create order market foundation"; }

    @Override public void apply(Connection connection, StorageDialect dialect, String p) throws SQLException {
        String id = dialect == StorageDialect.SQLITE ? "INTEGER PRIMARY KEY AUTOINCREMENT" : "BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY";
        String bool = dialect == StorageDialect.SQLITE ? "INTEGER" : "TINYINT";
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS "+p+"orders ("+
                    "order_uuid VARCHAR(36) NOT NULL PRIMARY KEY,"+
                    "owner_uuid VARCHAR(36) NOT NULL,"+
                    "owner_name VARCHAR(16) NOT NULL,"+
                    "sample_item TEXT NOT NULL,"+
                    "item_type VARCHAR(64) NOT NULL,"+
                    "requested_amount INTEGER NOT NULL,"+
                    "remaining_amount INTEGER NOT NULL,"+
                    "price_per_item BIGINT NOT NULL,"+
                    "escrow_remaining BIGINT NOT NULL,"+
                    "status VARCHAR(16) NOT NULL,"+
                    "created_at BIGINT NOT NULL,"+
                    "expires_at BIGINT NOT NULL,"+
                    "closed_at BIGINT)" );
            s.executeUpdate("CREATE TABLE IF NOT EXISTS "+p+"order_collect ("+
                    "id "+id+",owner_uuid VARCHAR(36) NOT NULL,order_uuid VARCHAR(36),"+
                    "item_data TEXT,coins BIGINT NOT NULL DEFAULT 0,reason VARCHAR(24) NOT NULL,"+
                    "created_at BIGINT NOT NULL,collected "+bool+" NOT NULL DEFAULT 0,collected_at BIGINT)" );
            s.executeUpdate("CREATE TABLE IF NOT EXISTS "+p+"order_history ("+
                    "id "+id+",player_uuid VARCHAR(36) NOT NULL,event_type VARCHAR(24) NOT NULL,"+
                    "order_uuid VARCHAR(36),item_data TEXT NOT NULL,item_type VARCHAR(64) NOT NULL,"+
                    "amount INTEGER NOT NULL,counterpart_uuid VARCHAR(36),counterpart_name VARCHAR(16),"+
                    "coins BIGINT NOT NULL DEFAULT 0,created_at BIGINT NOT NULL)" );
            index(s,dialect,p+"idx_orders_status_expiry",p+"orders","status, expires_at");
            index(s,dialect,p+"idx_orders_owner",p+"orders","owner_uuid, status");
            index(s,dialect,p+"idx_order_collect_owner",p+"order_collect","owner_uuid, collected");
            index(s,dialect,p+"idx_order_history_player",p+"order_history","player_uuid, created_at");
        }
    }
    private static void index(Statement s, StorageDialect d, String name, String table, String columns) throws SQLException {
        if(d==StorageDialect.SQLITE){s.executeUpdate("CREATE INDEX IF NOT EXISTS "+name+" ON "+table+"("+columns+")");return;}
        try{s.executeUpdate("CREATE INDEX "+name+" ON "+table+"("+columns+")");}catch(SQLException e){if(e.getErrorCode()!=1061)throw e;}
    }
}
