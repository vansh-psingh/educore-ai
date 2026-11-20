package com.educore.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authentication service: PBKDF2 password hashing, HMAC-signed tokens
 * (JWT-compatible structure), roles, password reset, rate limiting.
 * Users persist in data/store/users.json (single-user/dev deployment).
 */
public final class AuthService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final int ACCESS_TTL_SEC = 15 * 60;          // 15 min
    private static final int REFRESH_TTL_SEC = 7 * 24 * 3600;   // 7 days
    private static final int RESET_TTL_SEC = 30 * 60;           // 30 min
    private static final int RATE_LIMIT = 20;                   // requests
    private static final long RATE_WINDOW_MS = 60_000;

    private static final Map<String, Deque<Long>> HITS = new ConcurrentHashMap<>();

    public record AuthUser(String id, String name, String email, String role) {}

    // ================= passwords =================

    private static String hashPassword(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, 256);
            byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            return "pbkdf2$" + PBKDF2_ITERATIONS + "$"
                    + Base64.getEncoder().encodeToString(salt) + "$"
                    + Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("password hashing failed", e);
        }
    }

    private static boolean verifyPassword(String password, String stored) {
        try {
            String[] parts = stored.split("\\$");
            if (parts.length != 4 || !parts[0].equals("pbkdf2")) return false;
            int iter = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iter, 256);
            byte[] actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    // ================= tokens (JWT-compatible HS256) =================

    private static byte[] secret() {
        String s = firstNonBlank(System.getenv("AUTH_SECRET"), envValue("AUTH_SECRET"));
        if (s == null || s.isBlank()) {
            // dev fallback: persisted random secret so restarts keep sessions valid
            try {
                java.nio.file.Path p = Store.dir().resolve("auth_secret.txt");
                java.nio.file.Files.createDirectories(Store.dir());
                if (java.nio.file.Files.isRegularFile(p)) {
                    s = java.nio.file.Files.readString(p).trim();
                } else {
                    byte[] b = new byte[32];
                    RANDOM.nextBytes(b);
                    s = Base64.getEncoder().encodeToString(b);
                    java.nio.file.Files.writeString(p, s);
                }
            } catch (java.io.IOException e) {
                s = "educore-dev-secret-change-me";
            }
        }
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String b64url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String sign(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret(), "HmacSHA256"));
        return b64url(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static String issueToken(AuthUser user, String type, long ttlSec) throws Exception {
        String header = b64url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        long now = Instant.now().getEpochSecond();
        String payload = "{\"sub\":\"" + user.id() + "\",\"name\":\"" + esc(user.name())
                + "\",\"email\":\"" + esc(user.email()) + "\",\"role\":\"" + user.role()
                + "\",\"type\":\"" + type + "\",\"iat\":" + now + ",\"exp\":" + (now + ttlSec) + "}";
        String body = b64url(payload.getBytes(StandardCharsets.UTF_8));
        return header + "." + body + "." + sign(header + "." + body);
    }

    /** Verifies signature + expiry; returns claims map or null. */
    public static Map<String, String> verifyToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return null;
            String expected = sign(parts[0] + "." + parts[1]);
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    parts[2].getBytes(StandardCharsets.UTF_8))) {
                return null;
            }
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            JsonNode j = MAPPER.readTree(payload);
            if (j.path("exp").asLong() < Instant.now().getEpochSecond()) return null;
            Map<String, String> m = new HashMap<>();
            m.put("sub", j.path("sub").asText());
            m.put("name", j.path("name").asText());
            m.put("email", j.path("email").asText());
            m.put("role", j.path("role").asText());
            m.put("type", j.path("type").asText());
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    // ================= user store =================

    private static ObjectNode users() {
        return Store.obj(Store.read("users.json"));
    }

    private static JsonNode findByEmail(String email) {
        String key = email == null ? "" : email.trim().toLowerCase();
        JsonNode u = users().path(key);
        return u.isMissingNode() ? null : u;
    }

    private static void saveUser(String email, ObjectNode user) {
        ObjectNode root = users();
        root.set(email.trim().toLowerCase(), user);
        Store.write("users.json", root);
    }

    // ================= public API =================

    public static boolean rateLimited(String ip) {
        long now = System.currentTimeMillis();
        Deque<Long> dq = HITS.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (dq) {
            while (!dq.isEmpty() && now - dq.peekFirst() > RATE_WINDOW_MS) dq.pollFirst();
            if (dq.size() >= RATE_LIMIT) return true;
            dq.addLast(now);
            return false;
        }
    }

    public static ObjectNode register(JsonNode req) throws Exception {
        String name = req.path("name").asText("").trim();
        String email = req.path("email").asText("").trim().toLowerCase();
        String password = req.path("password").asText("");
        String confirm = req.path("confirm").asText("");

        if (name.length() < 2) throw new IllegalArgumentException("Full name is required (min 2 characters).");
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw new IllegalArgumentException("Enter a valid email address.");
        if (password.length() < 8) throw new IllegalArgumentException("Password must be at least 8 characters.");
        if (!password.equals(confirm)) throw new IllegalArgumentException("Passwords do not match.");
        // role is NEVER taken from the request - always student on registration
        if (findByEmail(email) != null) throw new IllegalArgumentException("An account with this email already exists.");

        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        String id = UUID.randomUUID().toString().substring(0, 8);

        ObjectNode u = MAPPER.createObjectNode();
        u.put("id", id);
        u.put("name", name);
        u.put("email", email);
        u.put("role", "student");
        u.put("status", "active");
        u.put("pass_hash", hashPassword(password, salt));
        u.put("created", Instant.now().toString());
        saveUser(email, u);

        AuthUser au = new AuthUser(id, name, email, "student");
        return tokenResponse(au);
    }

    public static ObjectNode login(JsonNode req) throws Exception {
        String email = req.path("email").asText("").trim().toLowerCase();
        String password = req.path("password").asText("");
        JsonNode u = findByEmail(email);
        if (u == null || !verifyPassword(password, u.path("pass_hash").asText(""))) {
            throw new IllegalArgumentException("Invalid email or password.");
        }
        if (!"active".equals(u.path("status").asText("active"))) {
            throw new IllegalArgumentException("This account is not active.");
        }
        AuthUser au = new AuthUser(u.path("id").asText(), u.path("name").asText(),
                u.path("email").asText(), u.path("role").asText("student"));
        return tokenResponse(au);
    }

    public static ObjectNode refresh(String refreshToken) throws Exception {
        Map<String, String> claims = verifyToken(refreshToken);
        if (claims == null || !"refresh".equals(claims.get("type"))) {
            throw new IllegalArgumentException("Invalid or expired refresh token.");
        }
        JsonNode u = findById(claims.get("sub"));
        if (u == null || !"active".equals(u.path("status").asText("active"))) {
            throw new IllegalArgumentException("Account not found or inactive.");
        }
        AuthUser au = new AuthUser(u.path("id").asText(), u.path("name").asText(),
                u.path("email").asText(), u.path("role").asText("student"));
        return tokenResponse(au);
    }

    public static void logout() {
        // stateless tokens - client discards them; nothing server-side to clear
    }

    public static ObjectNode forgot(JsonNode req) {
        String email = req.path("email").asText("").trim().toLowerCase();
        JsonNode u = findByEmail(email);
        ObjectNode out = MAPPER.createObjectNode();
        if (u == null) {
            // don't reveal whether the email exists
            out.put("message", "If an account exists for this email, a reset link has been generated.");
            return out;
        }
        String token = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        ObjectNode un = (ObjectNode) u;
        try {
            un.put("reset_token", hashToken(token));
        } catch (Exception e) {
            throw new IllegalStateException("token hashing failed", e);
        }
        un.put("reset_exp", Instant.now().plusSeconds(RESET_TTL_SEC).toString());
        saveUser(email, un);
        out.put("message", "If an account exists for this email, a reset link has been generated.");
        // NOTE: no SMTP configured - return token for local/dev use only
        out.put("dev_reset_token", token);
        return out;
    }

    public static void reset(JsonNode req) throws Exception {
        String email = req.path("email").asText("").trim().toLowerCase();
        String token = req.path("token").asText("");
        String password = req.path("password").asText("");
        if (password.length() < 8) throw new IllegalArgumentException("Password must be at least 8 characters.");
        JsonNode u = findByEmail(email);
        if (u == null) throw new IllegalArgumentException("Invalid reset request.");
        ObjectNode un = (ObjectNode) u;
        String stored = un.path("reset_token").asText("");
        String exp = un.path("reset_exp").asText("");
        if (stored.isEmpty() || !stored.equals(hashToken(token))
                || Instant.parse(exp).isBefore(Instant.now())) {
            throw new IllegalArgumentException("Reset token is invalid or expired.");
        }
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        un.put("pass_hash", hashPassword(password, salt));
        un.remove("reset_token");
        un.remove("reset_exp");
        un.put("updated", Instant.now().toString());
        saveUser(email, un);
    }

    /** Change password for an authenticated user (claims from verified token). */
    public static void changePassword(Map<String, String> claims, JsonNode req) throws Exception {
        String current = req.path("current_password").asText("");
        String next = req.path("new_password").asText("");
        if (next.length() < 8) throw new IllegalArgumentException("New password must be at least 8 characters.");
        JsonNode u = findById(claims.get("sub"));
        if (u == null) throw new IllegalArgumentException("Account not found.");
        if (!verifyPassword(current, u.path("pass_hash").asText(""))) {
            throw new IllegalArgumentException("Current password is incorrect.");
        }
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        ObjectNode un = (ObjectNode) u;
        un.put("pass_hash", hashPassword(next, salt));
        un.put("updated", Instant.now().toString());
        saveUser(u.path("email").asText(), un);
    }

    public static ObjectNode me(Map<String, String> claims) {
        JsonNode u = findById(claims.get("sub"));
        ObjectNode out = MAPPER.createObjectNode();
        out.put("id", claims.get("sub"));
        out.put("name", claims.get("name"));
        out.put("email", claims.get("email"));
        out.put("role", claims.get("role"));
        if (u != null) {
            out.put("status", u.path("status").asText("active"));
            out.put("created", u.path("created").asText(""));
        }
        return out;
    }

    /** Resolves an AuthUser from a token string, or null. */
    public static AuthUser userFromToken(String token) {
        Map<String, String> c = verifyToken(token);
        if (c == null || !"access".equals(c.get("type"))) return null;
        return new AuthUser(c.get("sub"), c.get("name"), c.get("email"), c.get("role"));
    }

    public static String accessToken(AuthUser u) throws Exception {
        return issueToken(u, "access", ACCESS_TTL_SEC);
    }

    public static String refreshToken(AuthUser u) throws Exception {
        return issueToken(u, "refresh", REFRESH_TTL_SEC);
    }

    private static JsonNode findById(String id) {
        JsonNode users = users();
        java.util.Iterator<String> it = users.fieldNames();
        while (it.hasNext()) {
            JsonNode u = users.path(it.next());
            if (u.path("id").asText().equals(id)) return u;
        }
        return null;
    }

    private static ObjectNode tokenResponse(AuthUser u) throws Exception {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("access_token", accessToken(u));
        out.put("refresh_token", refreshToken(u));
        out.put("token_type", "Bearer");
        out.put("expires_in", ACCESS_TTL_SEC);
        ObjectNode user = out.putObject("user");
        user.put("id", u.id());
        user.put("name", u.name());
        user.put("email", u.email());
        user.put("role", u.role());
        return out;
    }

    private static String hashToken(String token) throws Exception {
        return b64url(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    private static String envValue(String key) {
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
}
