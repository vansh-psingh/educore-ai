package com.educore.ai.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * PostgreSQL connection pool (HikariCP).
 *
 * Configuration (first match wins):
 *   DATABASE_URL  - e.g. "jdbc:postgresql://host:5432/db?user=u&password=p"
 *                   or the common "postgres://user:pass@host:5432/db" form.
 *   Or discrete vars: PGHOST PGPORT PGDATABASE PGUSER PGPASSWORD
 *
 * When no configuration is present the application runs in legacy
 * JSON-file mode (Store falls back automatically). Db.configured()
 * reports which mode is active.
 */
public final class Db {

    private static volatile HikariDataSource pool;
    private static volatile String jdbcUrl;
    private static volatile boolean configured;
    private static volatile boolean initFailed;
    private static volatile String initError = "";
    private static final long STARTED_AT = System.currentTimeMillis();

    private Db() {
    }

    public static boolean configured() {
        ensureInit();
        return configured;
    }

    public static String mode() {
        return configured() ? "postgresql" : "json-files";
    }

    public static long uptimeMs() {
        return System.currentTimeMillis() - STARTED_AT;
    }

    public static String initError() {
        ensureInit();
        return initError;
    }

    public static Connection conn() throws SQLException {
        ensureInit();
        if (!configured) {
            throw new SQLException("database not configured (set DATABASE_URL)");
        }
        return pool.getConnection();
    }

    /** True when the database answers a trivial query. */
    public static boolean ping() {
        try (Connection c = conn(); var st = c.createStatement();
             var rs = st.executeQuery("SELECT 1")) {
            return rs.next();
        } catch (Exception e) {
            return false;
        }
    }

    private static synchronized void ensureInit() {
        if (pool != null || initFailed) return;
        String raw = envValue("DATABASE_URL");
        Properties props = new Properties();
        Map<String, String> dsProps = new LinkedHashMap<>();

        if (raw != null && (raw.startsWith("postgres://") || raw.startsWith("postgresql://"))) {
            // Neon/Supabase style: postgres://user:pass@host:port/db?params
            String rest = raw.startsWith("postgresql://")
                    ? raw.substring("postgresql://".length())
                    : raw.substring("postgres://".length());

            String query = "";
            int q = rest.indexOf('?');
            if (q >= 0) {
                query = rest.substring(q + 1);
                rest = rest.substring(0, q);
            }

            int slash = rest.indexOf('/');
            String authority = slash >= 0 ? rest.substring(0, slash) : rest;
            String path = slash >= 0 ? rest.substring(slash) : "";
            if (path.isEmpty()) path = "/postgres";

            int at = authority.lastIndexOf('@');
            if (at >= 0) {
                String userinfo = authority.substring(0, at);
                authority = authority.substring(at + 1);
                int colon = userinfo.indexOf(':');
                if (colon >= 0) {
                    props.setProperty("user", urlDecode(userinfo.substring(0, colon)));
                    props.setProperty("password", urlDecode(userinfo.substring(colon + 1)));
                } else {
                    props.setProperty("user", urlDecode(userinfo));
                }
            }
            jdbcUrl = "jdbc:postgresql://" + authority + path;

            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String k = pair.substring(0, eq);
                String v = urlDecode(pair.substring(eq + 1));
                if (k.equals("user")) props.setProperty("user", v);
                else if (k.equals("password")) props.setProperty("password", v);
                else if (k.equals("channel_binding")) dsProps.put("channelBinding", v);
                else if (k.equals("connect_timeout")) dsProps.put("connectTimeout", v);
                else if (k.equals("application_name")) dsProps.put("ApplicationName", v);
                else dsProps.put(k, v);
            }
        } else if (raw != null && raw.startsWith("jdbc:postgresql://")) {
            jdbcUrl = raw;
            int q = raw.indexOf('?');
            if (q >= 0) {
                jdbcUrl = raw.substring(0, q);
                for (String pair : raw.substring(q + 1).split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq <= 0) continue;
                    String k = pair.substring(0, eq);
                    String v = urlDecode(pair.substring(eq + 1));
                    if (k.equals("user")) props.setProperty("user", v);
                    else if (k.equals("password")) props.setProperty("password", v);
                    else dsProps.put(k, v);
                }
            }
        } else {
            String host = envValue("PGHOST");
            if (host == null) { configured = false; return; }
            String port = firstNonBlank(envValue("PGPORT"), "5432");
            String db = firstNonBlank(envValue("PGDATABASE"), envValue("PGUSER"), "educore");
            jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + db;
            props.setProperty("user", firstNonBlank(envValue("PGUSER"), ""));
            props.setProperty("password", firstNonBlank(envValue("PGPASSWORD"), ""));
        }

        // discrete env vars override / fill gaps
        String u = firstNonBlank(envValue("PGUSER"), props.getProperty("user"));
        String p = firstNonBlank(envValue("PGPASSWORD"), props.getProperty("password"));
        if (u != null) props.setProperty("user", u);
        if (p != null) props.setProperty("password", p);

        try {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(jdbcUrl);
            cfg.setUsername(props.getProperty("user", ""));
            cfg.setPassword(props.getProperty("password", ""));
            cfg.setMaximumPoolSize(10);
            cfg.setMinimumIdle(1);
            cfg.setConnectionTimeout(10_000);
            cfg.setPoolName("educore-pg");
            // Neon requires TLS; harmless for local postgres too
            cfg.addDataSourceProperty("sslmode", "prefer");
            cfg.addDataSourceProperty("connectTimeout", "10");
            cfg.addDataSourceProperty("socketTimeout", "60");
            dsProps.forEach(cfg::addDataSourceProperty);
            pool = new HikariDataSource(cfg);
            configured = true;
        } catch (Exception e) {
            initFailed = true;
            configured = false;
            initError = e.getMessage();
            System.err.println("[db] PostgreSQL init failed, falling back to JSON mode: " + e.getMessage());
        }
    }

    private static String envValue(String key) {
        String v = System.getenv(key);
        if (v != null && !v.isBlank()) return v.trim();
        try {
            java.nio.file.Path p = java.nio.file.Path.of(".env");
            if (java.nio.file.Files.isRegularFile(p)) {
                for (String line : java.nio.file.Files.readAllLines(p)) {
                    String t = line.trim();
                    if (t.startsWith(key + "=")) {
                        return t.substring(key.length() + 1).trim();
                    }
                }
            }
        } catch (java.io.IOException ignored) {
        }
        return null;
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }
}
