package io.github.llm4j.loom.tools.generic;

import io.github.llm4j.agent.Tool;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code tool Db { use: sql  url: env.DB_URL  user: env.DB_USER  password: env.DB_PASSWORD }}: lets an agent
 * look things up in a database. It only reads: the connection is read-only and each statement is checked.
 * Loom bundles no JDBC driver beyond what is on the classpath.
 */
public final class SqlKind extends GenericKind {

    private static final Pattern PASSWORD_PARAM = Pattern.compile("(?i)(?:password|pwd)=([^&;]+)");
    private static final Pattern USERINFO = Pattern.compile("//[^/:@]+:([^@/]+)@");

    @Override
    public String name() {
        return "sql";
    }

    @Override
    public Set<String> required() {
        return Set.of("url");
    }

    @Override
    public Set<String> optional() {
        return Set.of("user", "password", "max_rows", "max_bytes", "format", "timeout");
    }

    @Override
    public Set<String> secrets() {
        return Set.of("url", "password");
    }

    @Override
    protected void validate(Options o, Path baseDir) {
        SqlTool.Config c = SqlTool.Config.parse(o);
        try {
            DriverManager.getDriver(c.url());
        } catch (SQLException e) {
            throw new OptionException("url: no JDBC driver is installed for " + scheme(c.url()) + " (add the driver to the classpath)");
        }
    }

    @Override
    protected Tool build(String name, Options o, Path baseDir, EffectContext context) {
        SqlTool.Config c = SqlTool.Config.parse(o);
        List<String> hidden = new ArrayList<>();
        Matcher m = PASSWORD_PARAM.matcher(c.url());
        while (m.find()) hidden.add(m.group(1));
        m = USERINFO.matcher(c.url());
        while (m.find()) hidden.add(m.group(1));
        return new SqlTool(name, c, redactor(o, hidden.toArray(String[]::new)), context);
    }

    /** {@code jdbc:postgresql}: enough to name the driver, never the host or credentials. */
    private static String scheme(String url) {
        String[] parts = url.split(":", 3);
        return parts.length >= 2 ? parts[0] + ":" + parts[1] : "that kind of URL";
    }
}
