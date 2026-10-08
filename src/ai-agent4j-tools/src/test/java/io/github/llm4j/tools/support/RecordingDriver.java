package io.github.llm4j.tools.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * A JDBC driver for URLs starting {@code jdbc:recording:} that hands out H2 connections and records what the
 * caller did to them: whether it was made read-only, and whether it was closed.
 */
public final class RecordingDriver implements Driver {

    public static final String PREFIX = "jdbc:recording:";
    public static final List<String> events = Collections.synchronizedList(new ArrayList<>());
    public static volatile Properties lastProperties;
    private static final RecordingDriver INSTANCE = new RecordingDriver();

    public static void register() throws SQLException {
        try {
            DriverManager.getDriver(PREFIX + "x");
        } catch (SQLException notRegistered) {
            DriverManager.registerDriver(INSTANCE);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) return null;
        lastProperties = info;
        Connection real = DriverManager.getConnection("jdbc:h2:" + url.substring(PREFIX.length()), info);
        events.add("open");
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "setReadOnly" -> events.add("readOnly=" + args[0]);
                case "setAutoCommit" -> events.add("autoCommit=" + args[0]);
                case "close" -> events.add("close");
                default -> { }
            }
            try {
                return method.invoke(real, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    @Override public boolean acceptsURL(String url) { return url != null && url.startsWith(PREFIX); }
    @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
    @Override public int getMajorVersion() { return 1; }
    @Override public int getMinorVersion() { return 0; }
    @Override public boolean jdbcCompliant() { return false; }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
}
