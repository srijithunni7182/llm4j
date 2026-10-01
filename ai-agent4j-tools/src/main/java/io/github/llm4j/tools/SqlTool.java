package io.github.llm4j.tools;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Runs read-only queries and describes the schema. One connection per call. */
final class SqlTool extends GenericTool {

    private static final int MAX_TABLES = 200;

    /** A declaration, parsed and checked. */
    record Config(String url, String user, String password, int maxRows, long maxBytes, String format, Duration timeout, SqlGuard.Dialect dialect) {

        static Config parse(Options o) {
            String url = o.require("url");
            if (!url.regionMatches(true, 0, "jdbc:", 0, 5)) throw new OptionException("url: must be a JDBC URL (jdbc:…)");
            return new Config(url, o.get("user"), o.get("password"), o.integer("max_rows", 100, 1, 1000),
                    o.size("max_bytes", 64 * 1024, 4L * 1024 * 1024), o.choice("format", "table", "table", "json", "csv"),
                    o.duration("timeout", Duration.ofSeconds(15), Duration.ofMinutes(5)), SqlGuard.dialectFor(url));
        }
    }

    private final Config config;

    SqlTool(String name, Config config, Redactor redactor, EffectContext context) {
        super(name, "sql", description(config), redactor, context);
        this.config = config;
    }

    private static String description(Config c) {
        return "Looks things up in a database; it only reads. Arguments: action (query, the default, or schema), "
                + "for query: sql (one SELECT statement; use ? for values) and params (a list of values for the ?s); "
                + "for schema: table (optional). Returns up to " + c.maxRows() + " rows as " + c.format() + ".";
    }

    @Override
    public String target(Map<String, Object> args) {
        return "schema".equals(String.valueOf(args.get("action"))) ? "schema" : "query";
    }

    @Override
    protected String run(Map<String, Object> args, String idempotencyKey) throws SQLException {
        String action = args.get("action") == null ? "query" : String.valueOf(args.get("action")).trim().toLowerCase();
        return switch (action) {
            case "query" -> query(text(args, "sql"), args.get("params"));
            case "schema" -> schema(optionalText(args, "table"));
            default -> throw new ToolRefusal("action must be query or schema");
        };
    }

    private Connection connect() throws SQLException {
        Properties props = new Properties();
        if (config.user() != null) props.setProperty("user", config.user());
        if (config.password() != null) props.setProperty("password", config.password());
        Connection connection = DriverManager.getConnection(config.url(), props);
        connection.setReadOnly(true);
        connection.setAutoCommit(true);
        return connection;
    }

    private String query(String sql, Object params) throws SQLException {
        SqlGuard.check(sql, config.dialect());
        List<Object> values = new ArrayList<>();
        if (params instanceof List<?> list) values.addAll(list);
        else if (params != null) throw new ToolRefusal("params must be a list of values");
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout((int) Math.max(1, config.timeout().toSeconds()));
            statement.setMaxRows(config.maxRows() + 1);
            for (int i = 0; i < values.size(); i++) statement.setObject(i + 1, values.get(i));
            try (ResultSet rs = statement.executeQuery()) {
                return Limits.cut(ResultTable.read(rs, config.maxRows()).render(config.format()), config.maxBytes());
            }
        } catch (SQLException e) {
            throw new ToolRefusal("the database refused the query: " + Limits.excerpt(String.valueOf(e.getMessage()), 300));
        }
    }

    private String schema(String table) throws SQLException {
        try (Connection connection = connect()) {
            DatabaseMetaData meta = connection.getMetaData();
            Map<String, List<String>> tables = new LinkedHashMap<>();
            try (ResultSet rs = meta.getTables(null, null, table == null ? "%" : table, new String[] {"TABLE", "VIEW"})) {
                while (rs.next() && tables.size() < MAX_TABLES) {
                    String schema = rs.getString("TABLE_SCHEM");
                    if (schema != null && (schema.equalsIgnoreCase("INFORMATION_SCHEMA") || schema.equalsIgnoreCase("pg_catalog"))) continue;
                    tables.put((schema == null ? "" : schema + ".") + rs.getString("TABLE_NAME"), new ArrayList<>());
                }
            }
            if (tables.isEmpty()) return table == null ? "(no tables)" : "no table named " + table;
            for (Map.Entry<String, List<String>> t : tables.entrySet()) {
                String full = t.getKey();
                String schemaPattern = full.contains(".") ? full.substring(0, full.indexOf('.')) : null;
                String name = full.contains(".") ? full.substring(full.indexOf('.') + 1) : full;
                try (ResultSet rs = meta.getColumns(null, schemaPattern, name, "%")) {
                    while (rs.next()) t.getValue().add(rs.getString("COLUMN_NAME") + " " + rs.getString("TYPE_NAME"));
                }
            }
            StringBuilder sb = new StringBuilder();
            tables.forEach((name, cols) -> sb.append(name).append('(').append(String.join(", ", cols)).append(")\n"));
            return Limits.cut(sb.toString().stripTrailing(), config.maxBytes());
        } catch (SQLException e) {
            throw new ToolRefusal("the database refused the request: " + Limits.excerpt(String.valueOf(e.getMessage()), 300));
        }
    }
}
