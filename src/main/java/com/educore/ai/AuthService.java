package com.educore.ai;

import com.educore.ai.db.Db;
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
    private static final int AI_RATE_LIMIT = 30;                 // AI calls per user
    private static final long AI_RATE_WINDOW_MS = 60_000;
    private static final Map<String, Deque<Long>> AI_HITS = new ConcurrentHashMap<>();

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

    static byte[] secret() {
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
        // hold Store's monitor across read+write so concurrent signups/updates
        // cannot lose records (Store.read/write synchronize on Store.class)
        synchronized (Store.class) {
            ObjectNode root = users();
            root.set(email.trim().toLowerCase(), user);
            Store.write("users.json", root);
        }
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

    /** Per-user sliding-window limit for AI-cost endpoints (chat, essays, docs, quizzes). */
    public static boolean aiRateLimited(String userId) {
        long now = System.currentTimeMillis();
        Deque<Long> dq = AI_HITS.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (dq) {
            while (!dq.isEmpty() && now - dq.peekFirst() > AI_RATE_WINDOW_MS) dq.pollFirst();
            if (dq.size() >= AI_RATE_LIMIT) return true;
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
        AuditService.log(id, email, "user_registered", id);
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
        markLogin(au.id());
        AuditService.log(au.id(), au.email(), "user_login", au.id());
        return tokenResponse(au);
    }

    private static void markLogin(String userId) {
        if (!Db.configured() || userId == null) return;
        try (var c = Db.conn();
             var ps = c.prepareStatement(
                     "UPDATE app_users SET last_login_at = now() WHERE id = ?")) {
            ps.setString(1, userId);
            ps.executeUpdate();
        } catch (Exception ignored) {
        }
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

    /**
     * Initiates a password reset. Always returns the same generic message so
     * account existence is never revealed. The raw token is NEVER returned in
     * the API response - it is only delivered by email (or server log in dev).
     */
    public static ObjectNode forgot(JsonNode req, String clientIp) {
        String email = req.path("email").asText("").trim().toLowerCase();
        JsonNode u = findByEmail(email);
        ObjectNode out = MAPPER.createObjectNode();
        out.put("message", "If an account exists for this email, a reset link has been sent.");
        if (u == null) {
            AuditService.log(null, email, "password_reset_requested_unknown", "");
            return out;
        }

        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String tokenHash;
        try {
            tokenHash = hashToken(token);
        } catch (Exception e) {
            throw new IllegalStateException("token hashing failed", e);
        }
        String userId = u.path("id").asText();
        Instant expires = Instant.now().plusSeconds(RESET_TTL_SEC);

        if (Db.configured()) {
            // single-use tokens in password_reset_tokens; invalidate older ones
            try (var c = Db.conn()) {
                c.setAutoCommit(false);
                try (var ps = c.prepareStatement(
                        "UPDATE password_reset_tokens SET used_at = now() "
                                + "WHERE user_id = ? AND used_at IS NULL")) {
                    ps.setString(1, userId);
                    ps.executeUpdate();
                }
                try (var ps = c.prepareStatement(
                        "INSERT INTO password_reset_tokens(user_id, token_hash, expires_at, created_ip) "
                                + "VALUES (?,?,?,?)")) {
                    ps.setString(1, userId);
                    ps.setString(2, tokenHash);
                    ps.setObject(3, java.sql.Timestamp.from(expires));
                    ps.setString(4, clientIp);
                    ps.executeUpdate();
                }
                c.commit();
            } catch (Exception e) {
                System.err.println("[auth] reset token store failed: " + e.getMessage());
                return out;
            }
        } else {
            // JSON mode: keep single active hashed token on the user record
            ObjectNode un = (ObjectNode) u;
            un.put("reset_token", tokenHash);
            un.put("reset_exp", expires.toString());
            saveUser(email, un);
        }

        deliverResetEmail(u, email, token);
        AuditService.log(userId, email, "password_reset_requested", userId);
        return out;
    }

    /** Sends the reset email; falls back to a server-side log line in dev. */
    private static void deliverResetEmail(JsonNode u, String email, String token) {
        String base = firstNonBlank(System.getenv("APP_BASE_URL"), envValue("APP_BASE_URL"),
                "http://localhost:8080");
        String url = base.replaceAll("/$", "")
                + "/#/reset?token=" + token + "&email=" + email;
        try {
            if (SmtpMailer.configured()) {
                SmtpMailer.sendHtml(email, "Reset your EDUCORE AI password",
                        SmtpMailer.resetEmailHtml(u.path("name").asText(""), url));
                AuditService.log(u.path("id").asText(), email,
                        "password_reset_email_sent", u.path("id").asText());
            } else {
                // dev mode: no SMTP - log the link server-side only (never in API)
                System.out.println("[auth] SMTP not configured - password reset link for "
                        + email + ": " + url);
                AuditService.log(u.path("id").asText(), email,
                        "password_reset_email_dev_logged", u.path("id").asText());
            }
        } catch (Exception e) {
            System.err.println("[auth] reset email failed for " + email + ": " + e.getMessage());
            AuditService.log(u.path("id").asText(), email,
                    "password_reset_email_failed", u.path("id").asText());
        }
    }

    public static void reset(JsonNode req) throws Exception {
        String email = req.path("email").asText("").trim().toLowerCase();
        String token = req.path("token").asText("");
        String password = req.path("password").asText("");
        if (password.length() < 8) throw new IllegalArgumentException("Password must be at least 8 characters.");
        JsonNode u = findByEmail(email);
        if (u == null) throw new IllegalArgumentException("Invalid reset request.");
        String tokenHash = hashToken(token);
        String userId = u.path("id").asText();

        boolean valid;
        if (Db.configured()) {
            valid = consumeDbResetToken(userId, tokenHash);
        } else {
            ObjectNode un = (ObjectNode) u;
            String stored = un.path("reset_token").asText("");
            String exp = un.path("reset_exp").asText("");
            valid = !stored.isEmpty() && stored.equals(tokenHash)
                    && Instant.parse(exp).isAfter(Instant.now());
            if (valid) {
                un.remove("reset_token");
                un.remove("reset_exp");
            }
        }
        if (!valid) throw new IllegalArgumentException("Reset token is invalid, expired or already used.");

        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        ObjectNode un = (ObjectNode) u;
        un.put("pass_hash", hashPassword(password, salt));
        un.put("updated", Instant.now().toString());
        saveUser(email, un);
        AuditService.log(userId, email, "password_reset_completed", userId);
    }

    /**
     * Validates and consumes a DB reset token atomically: the row is marked
     * used in the same statement that checks it, so a token can never be
     * replayed even under concurrent requests.
     */
    private static boolean consumeDbResetToken(String userId, String tokenHash) {
        try (var c = Db.conn();
             var ps = c.prepareStatement(
                     "UPDATE password_reset_tokens SET used_at = now() "
                             + "WHERE user_id = ? AND token_hash = ? AND used_at IS NULL "
                             + "AND expires_at > now()")) {
            ps.setString(1, userId);
            ps.setString(2, tokenHash);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            System.err.println("[auth] reset token verify failed: " + e.getMessage());
            return false;
        }
    }

    // ================= email OTP password reset =================
    // 6-digit code, 5-minute expiry, single use, max 5 verify attempts,
    // 60s resend cooldown, max 3 sends/hour per email. Only a keyed hash of
    // the OTP is stored - the plaintext code is never persisted or logged
    // (except the dev fallback below, server-side only, never in the API).

    private static final int OTP_TTL_SEC = 5 * 60;
    private static final int OTP_TICKET_TTL_SEC = 10 * 60;
    private static final int OTP_MAX_ATTEMPTS = 5;
    private static final long OTP_RESEND_COOLDOWN_MS = 60_000;
    private static final int OTP_MAX_PER_HOUR = 3;

    /** Keyed hash of an OTP - binds the code to the user, constant-time comparable. */
    private static String otpHash(String userId, String otp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret(), "HmacSHA256"));
            byte[] d = mac.doFinal((userId + ":" + otp).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("otp hashing failed", e);
        }
    }

    /**
     * Step 1: request an OTP. ALWAYS returns the same generic message -
     * account existence is never revealed. Throttling (60s cooldown,
     * 3/hour cap) is applied silently before generating/sending.
     */
    public static ObjectNode otpRequest(JsonNode req, String clientIp) {
        String email = req.path("email").asText("").trim().toLowerCase();
        ObjectNode out = MAPPER.createObjectNode();
        out.put("message", "If an account exists for this email, a verification code will be sent.");
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) return out;
        JsonNode u = findByEmail(email);
        if (u == null) {
            AuditService.log(null, email, "otp_requested_unknown", "");
            return out;
        }
        String userId = u.path("id").asText();

        // per-email throttle: resend cooldown + hourly cap (silent - no info leak)
        if (Db.configured()) {
            try (var c = Db.conn()) {
                try (var ps = c.prepareStatement(
                        "SELECT created_at FROM otp_codes WHERE user_id = ? "
                                + "ORDER BY id DESC LIMIT 1")) {
                    ps.setString(1, userId);
                    try (var rs = ps.executeQuery()) {
                        if (rs.next()) {
                            long last = rs.getTimestamp(1).getTime();
                            if (System.currentTimeMillis() - last < OTP_RESEND_COOLDOWN_MS) return out;
                        }
                    }
                }
                try (var ps = c.prepareStatement(
                        "SELECT count(*) FROM otp_codes WHERE user_id = ? "
                                + "AND created_at > now() - interval '1 hour'")) {
                    ps.setString(1, userId);
                    try (var rs = ps.executeQuery()) {
                        if (rs.next() && rs.getInt(1) >= OTP_MAX_PER_HOUR) return out;
                    }
                }
            } catch (Exception e) {
                System.err.println("[auth] otp throttle check failed: " + e.getMessage());
                return out;
            }
        } else {
            JsonNode un = users().path(email);
            String lastSent = un.path("otp_last_sent").asText("");
            if (!lastSent.isEmpty()) {
                try {
                    if (Instant.parse(lastSent).plusMillis(OTP_RESEND_COOLDOWN_MS)
                            .isAfter(Instant.now())) return out;
                } catch (Exception ignored) {
                }
            }
        }

        // generate 6-digit OTP
        String otp = String.format("%06d", RANDOM.nextInt(1_000_000));
        String hash = otpHash(userId, otp);
        Instant expires = Instant.now().plusSeconds(OTP_TTL_SEC);

        if (Db.configured()) {
            try (var c = Db.conn()) {
                c.setAutoCommit(false);
                try (var ps = c.prepareStatement(
                        "UPDATE otp_codes SET used_at = now() WHERE user_id = ? AND used_at IS NULL")) {
                    ps.setString(1, userId);
                    ps.executeUpdate();
                }
                try (var ps = c.prepareStatement(
                        "INSERT INTO otp_codes(user_id, otp_hash, expires_at, created_ip) "
                                + "VALUES (?,?,?,?)")) {
                    ps.setString(1, userId);
                    ps.setString(2, hash);
                    ps.setObject(3, java.sql.Timestamp.from(expires));
                    ps.setString(4, clientIp);
                    ps.executeUpdate();
                }
                c.commit();
            } catch (Exception e) {
                System.err.println("[auth] otp store failed: " + e.getMessage());
                return out;
            }
        } else {
            ObjectNode un = (ObjectNode) u;
            un.put("otp_hash", hash);
            un.put("otp_exp", expires.toString());
            un.put("otp_attempts", 0);
            un.put("otp_last_sent", Instant.now().toString());
            un.remove("otp_used");
            saveUser(email, un);
        }

        deliverOtpEmail(u, email, otp);
        AuditService.log(userId, email, "otp_requested", userId);
        return out;
    }

    /** Dev fallback: OTP visible in server console ONLY when SMTP is absent. */
    private static void deliverOtpEmail(JsonNode u, String email, String otp) {
        String name = u.path("name").asText("");
        try {
            if (SmtpMailer.configured()) {
                SmtpMailer.sendHtml(email, "Your EDUCORE AI verification code",
                        SmtpMailer.otpEmailHtml(name, otp, OTP_TTL_SEC / 60));
                AuditService.log(u.path("id").asText(), email, "otp_email_sent", u.path("id").asText());
            } else {
                // dev mode only - server console, never the API response
                System.out.println("[auth] SMTP not configured - OTP for " + email + ": " + otp
                        + " (expires in " + (OTP_TTL_SEC / 60) + " min)");
                AuditService.log(u.path("id").asText(), email, "otp_email_dev_logged", u.path("id").asText());
            }
        } catch (Exception e) {
            System.err.println("[auth] otp email failed for " + email + ": " + e.getMessage());
            AuditService.log(u.path("id").asText(), email, "otp_email_failed", u.path("id").asText());
        }
    }

    /**
     * Step 2: verify the OTP. On success returns a short-lived single-use
     * reset ticket (never the OTP itself). Wrong codes increment the attempt
     * counter; at 5 attempts the code is invalidated.
     */
    public static ObjectNode otpVerify(JsonNode req) throws Exception {
        String email = req.path("email").asText("").trim().toLowerCase();
        String otp = req.path("otp").asText("").trim();
        ObjectNode out = MAPPER.createObjectNode();
        JsonNode u = findByEmail(email);
        if (u == null || otp.length() != 6 || !otp.matches("\\d{6}")) {
            throw new IllegalArgumentException("Invalid or expired verification code.");
        }
        String userId = u.path("id").asText();

        if (Db.configured()) {
            // find the newest active code for this user
            long rowId = -1;
            String storedHash = null;
            int attempts = 0;
            try (var c = Db.conn();
                 var ps = c.prepareStatement(
                         "SELECT id, otp_hash, attempts FROM otp_codes "
                                 + "WHERE user_id = ? AND used_at IS NULL AND expires_at > now() "
                                 + "ORDER BY id DESC LIMIT 1")) {
                ps.setString(1, userId);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        rowId = rs.getLong(1);
                        storedHash = rs.getString(2);
                        attempts = rs.getInt(3);
                    }
                }
            }
            if (rowId < 0) throw new IllegalArgumentException("Invalid or expired verification code.");
            if (attempts >= OTP_MAX_ATTEMPTS) {
                invalidateOtpRow(rowId);
                throw new IllegalArgumentException("Too many incorrect attempts. Request a new code.");
            }
            String candidate = otpHash(userId, otp);
            if (!MessageDigest.isEqual(
                    storedHash.getBytes(StandardCharsets.UTF_8),
                    candidate.getBytes(StandardCharsets.UTF_8))) {
                try (var c = Db.conn();
                     var ps = c.prepareStatement("UPDATE otp_codes SET attempts = attempts + 1 WHERE id = ?")) {
                    ps.setLong(1, rowId);
                    ps.executeUpdate();
                }
                throw new IllegalArgumentException("Incorrect verification code.");
            }
            // atomic single-use consume
            try (var c = Db.conn();
                 var ps = c.prepareStatement(
                         "UPDATE otp_codes SET used_at = now() WHERE id = ? AND used_at IS NULL")) {
                ps.setLong(1, rowId);
                if (ps.executeUpdate() != 1) {
                    throw new IllegalArgumentException("Invalid or expired verification code.");
                }
            }
        } else {
            ObjectNode un = (ObjectNode) u;
            String stored = un.path("otp_hash").asText("");
            String exp = un.path("otp_exp").asText("");
            int attempts = un.path("otp_attempts").asInt(0);
            boolean expired;
            try { expired = exp.isEmpty() || Instant.parse(exp).isBefore(Instant.now()); }
            catch (Exception e) { expired = true; }
            if (stored.isEmpty() || expired) {
                throw new IllegalArgumentException("Invalid or expired verification code.");
            }
            if (attempts >= OTP_MAX_ATTEMPTS) {
                un.remove("otp_hash"); un.remove("otp_exp");
                un.remove("otp_attempts"); un.remove("otp_last_sent");
                saveUser(email, un);
                throw new IllegalArgumentException("Too many incorrect attempts. Request a new code.");
            }
            String candidate = otpHash(userId, otp);
            if (!MessageDigest.isEqual(
                    stored.getBytes(StandardCharsets.UTF_8),
                    candidate.getBytes(StandardCharsets.UTF_8))) {
                un.put("otp_attempts", attempts + 1);
                saveUser(email, un);
                throw new IllegalArgumentException("Incorrect verification code.");
            }
            un.remove("otp_hash"); un.remove("otp_exp");
            un.remove("otp_attempts"); un.remove("otp_last_sent");
            saveUser(email, un);
        }

        // success: issue a short-lived single-use reset ticket
        String ticket = issueResetTicket(userId, "");
        AuditService.log(userId, email, "otp_verified", userId);
        out.put("reset_ticket", ticket);
        out.put("expires_in", OTP_TICKET_TTL_SEC);
        return out;
    }

    private static void invalidateOtpRow(long rowId) {
        try (var c = Db.conn();
             var ps = c.prepareStatement("UPDATE otp_codes SET used_at = now() WHERE id = ?")) {
            ps.setLong(1, rowId);
            ps.executeUpdate();
        } catch (Exception ignored) {
        }
    }

    /**
     * Issues a single-use reset ticket (random 32-byte token, only its hash is
     * stored). Reuses password_reset_tokens in DB mode; JSON mode stores the
     * hash on the user record like the link flow.
     */
    private static String issueResetTicket(String userId, String clientIp) throws Exception {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String hash = hashToken(ticket);
        Instant expires = Instant.now().plusSeconds(OTP_TICKET_TTL_SEC);
        if (Db.configured()) {
            try (var c = Db.conn()) {
                c.setAutoCommit(false);
                try (var ps = c.prepareStatement(
                        "UPDATE password_reset_tokens SET used_at = now() "
                                + "WHERE user_id = ? AND used_at IS NULL")) {
                    ps.setString(1, userId);
                    ps.executeUpdate();
                }
                try (var ps = c.prepareStatement(
                        "INSERT INTO password_reset_tokens(user_id, token_hash, expires_at, created_ip) "
                                + "VALUES (?,?,?,?)")) {
                    ps.setString(1, userId);
                    ps.setString(2, hash);
                    ps.setObject(3, java.sql.Timestamp.from(expires));
                    ps.setString(4, clientIp);
                    ps.executeUpdate();
                }
                c.commit();
            }
        } else {
            JsonNode u = findById(userId);
            if (u != null) {
                ObjectNode un = (ObjectNode) u;
                un.put("reset_token", hash);
                un.put("reset_exp", expires.toString());
                saveUser(un.path("email").asText(), un);
            }
        }
        return ticket;
    }

    /**
     * Step 3: set the new password using the verified reset ticket.
     * The ticket is single-use - it is consumed atomically on success.
     */
    public static void otpReset(JsonNode req) throws Exception {
        String email = req.path("email").asText("").trim().toLowerCase();
        String ticket = req.path("ticket").asText("");
        String password = req.path("password").asText("");
        String confirm = req.path("confirm").asText("");
        if (password.length() < 8) throw new IllegalArgumentException("Password must be at least 8 characters.");
        if (!password.equals(confirm)) throw new IllegalArgumentException("Passwords do not match.");
        if (ticket.isEmpty()) throw new IllegalArgumentException("Reset session expired. Verify your code again.");
        JsonNode u = findByEmail(email);
        if (u == null) throw new IllegalArgumentException("Invalid reset request.");
        String userId = u.path("id").asText();
        String tokenHash = hashToken(ticket);

        boolean valid;
        if (Db.configured()) {
            valid = consumeDbResetToken(userId, tokenHash);
        } else {
            ObjectNode un = (ObjectNode) u;
            String stored = un.path("reset_token").asText("");
            String exp = un.path("reset_exp").asText("");
            valid = !stored.isEmpty() && stored.equals(tokenHash)
                    && Instant.parse(exp).isAfter(Instant.now());
            if (valid) {
                un.remove("reset_token");
                un.remove("reset_exp");
            }
        }
        if (!valid) throw new IllegalArgumentException("Reset session is invalid or expired. Verify your code again.");

        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        ObjectNode un = (ObjectNode) u;
        un.put("pass_hash", hashPassword(password, salt));
        un.put("updated", Instant.now().toString());
        saveUser(email, un);
        AuditService.log(userId, email, "otp_password_reset_completed", userId);
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
        AuditService.log(claims.get("sub"), claims.get("email"), "password_changed", claims.get("sub"));
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
        return Env.get(key);
    }
}
