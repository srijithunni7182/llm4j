package io.github.llm4j.tools.support;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** Small in-memory H2 data sources for tests that need a real SQL database. */
public final class Databases {

    private Databases() {}

    /** A shared in-memory database that lives for the JVM. */
    public static DataSource h2(String name) {
        String url = "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1";
        return new DataSource() {
            @Override public Connection getConnection() throws SQLException { return DriverManager.getConnection(url, "sa", ""); }
            @Override public Connection getConnection(String u, String p) throws SQLException { return DriverManager.getConnection(url, u, p); }
            @Override public PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(PrintWriter out) { }
            @Override public void setLoginTimeout(int seconds) { }
            @Override public int getLoginTimeout() { return 0; }
            @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
            @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };
    }
}
