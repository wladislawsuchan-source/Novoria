package de.walahi.smpcore.storage;

import java.sql.Connection;
import java.sql.SQLException;

/** Owns a JDBC connection pool. Every borrowed connection must be closed by the caller. */
public interface StorageProvider extends AutoCloseable {
    void initialize() throws SQLException;
    Connection connection() throws SQLException;
    boolean isAvailable();
    String type();
    StorageDialect dialect();
    @Override void close();
}
