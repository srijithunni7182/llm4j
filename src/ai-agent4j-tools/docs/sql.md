# `sql`

[← all tools](README.md)

## From Java

```java
Tool db = new SqlKind().create("Db", Map.of(
        "url", System.getenv("DB_URL"), "user", System.getenv("DB_USER"), "password", System.getenv("DB_PASSWORD"),
        "max_rows", "200"), Path.of("."));
db.execute(Map.of("sql", "SELECT name FROM people WHERE team = ?", "params", List.of("platform")));
```

Add the JDBC driver for your database to the classpath. A URL whose driver is missing is reported by `check`.

`create` takes the options as strings, with any secret already read from your environment or secret store. Before building,
`new SqlKind().check(options, baseDir)` returns what is wrong with them (or `null`) without touching the network
or the files. The tool takes its call arguments as a `Map` and returns text; it never throws, and a refusal comes back as text
starting `Error:`.

## From a Loom script

```text
tool Db { use: sql  url: env.DB_URL  user: env.DB_USER  password: env.DB_PASSWORD  max_rows: 200  format: json }
```

## Options and arguments

| Option | Meaning |
|---|---|
| `url` | **Secret**: a JDBC URL (it may hold a password) |
| `user`, `password` | `password` is a **secret** |
| `max_rows` | Default 100, at most 1000 |
| `max_bytes` | Result cap, default 64k |
| `format` | `table` (default), `json` or `csv` |
| `timeout` | Query timeout, default 15 s |

The agent gives `action` (`query`, the default, or `schema`); for `query`, `sql` and `params` (values for the `?`
placeholders, which are the only way a value reaches the database); for `schema`, an optional `table`. It only
reads, in two layers: the connection is opened read-only, and each statement is checked: exactly one statement,
starting with `SELECT`, `WITH` or `VALUES`, with no `INSERT`, `UPDATE`, `DELETE`, `DROP`, `INTO`, `CALL` and the like,
and none of the functions that read files or change state from inside a query. **Give the tool a database user that
is itself read-only**: the checks guard against a mistake, not against a determined attacker with a writable
account. The library bundles no JDBC driver (Loom's packaged `weave` JAR includes PostgreSQL); a URL whose
driver isn't installed is a load error.
