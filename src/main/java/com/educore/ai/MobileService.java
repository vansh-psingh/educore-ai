package com.educore.ai;

import com.educore.ai.db.Db;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * Mobile number verification via SMS OTP.
 *
 * The mobile number is stored on the user record (private - only ever
 * returned to its owner via /api/profile). OTPs are HMAC-hashed, expire
 * after 5 minutes, allow 5 attempts, single-use, invalidate previous
 * codes, and are throttled per user (60s resend, 5/hour cap). Codes are
 * delivered through {@link SmsService}; without a configured gateway the
 * dev "log" provider writes them to the server console only.
 */
public final class MobileService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final int OTP_TTL_SEC = 5 * 60;
    private static final int OTP_MAX_ATTEMPTS = 5;
    private static final long OTP_RESEND_COOLDOWN_MS = 60_000;
    private static final int OTP_MAX_PER_HOUR = 5;

    private MobileService() {
    }

    // ================= number save / validation =================

    /**
     * Validates and stores the mobile number. Accepts either a full
     * international number ("+91...") or country_code + national digits.
     * Changing an already-verified number resets verification.
     */
    public static ObjectNode saveMobile(AuthService.AuthUser user, JsonNode req)
            throws Exception {
        String num = req.path("mobile").asText("").trim()
                .replaceAll("[\\s\\-()]", "");
        String cc = req.path("country_code").asText("+91").trim();
        if (num.isEmpty()) throw new IllegalArgumentException("Mobile number is required.");

        String full;
        if (num.startsWith("+")) {
            full = num;
        } else {
            if (!cc.matches("^\\+\\d{1,4}$"))
                throw new IllegalArgumentException("Invalid country code.");
            full = cc + num;
        }
        validateMobile(full);

        JsonNode u = ProfileService.findUser(user.id());
        if (u == null) throw new IllegalArgumentException("Account not found.");
        ObjectNode un = (ObjectNode) u.deepCopy();
        String old = u.path("mobile").asText("");
        if (!full.equals(old)) {
            un.put("mobile", full);
            un.put("mobile_verified", false);
            un.remove("mobile_verified_at");
            // any pending OTP was for the old number - drop it
            invalidatePending(user.id(), full);
        }
        un.put("mobile_updated", Instant.now().toString());
        ProfileService.persistUser(un);
        AuditService.log(user.id(), user.email(), "mobile_updated", user.id());
        return ProfileService.get(user);
    }

    /** E.164-ish validation; India (+91) gets the strict 10-digit rule. */
    static void validateMobile(String full) {
        if (!full.matches("^\\+[1-9]\\d{7,14}$"))
            throw new IllegalArgumentException(
                    "Enter a valid mobile number with country code (e.g. +91 98765 43210).");
        if (full.startsWith("+91")) {
            String sub = full.substring(3);
            if (sub.length() != 10 || !sub.matches("^[6-9]\\d{9}$"))
                throw new IllegalArgumentException(
                        "Indian mobile numbers must be 10 digits starting with 6-9.");
        }
    }

    // ================= OTP request =================

    /** Sends a verification OTP to the user's stored mobile number. */
    public static ObjectNode requestOtp(AuthService.AuthUser user, String clientIp)
            throws Exception {
        JsonNode u = ProfileService.findUser(user.id());
        if (u == null) throw new IllegalArgumentException("Account not found.");
        String mobile = u.path("mobile").asText("");
        if (mobile.isEmpty())
            throw new IllegalArgumentException("Save a mobile number first.");
        if (u.path("mobile_verified").asBoolean(false))
            throw new IllegalArgumentException("This mobile number is already verified.");

        // throttle: resend cooldown + hourly cap (checked against stored rows)
        if (Db.configured()) {
            try (var c = Db.conn()) {
                try (var ps = c.prepareStatement(
                        "SELECT created_at FROM mobile_otps WHERE user_id = ? "
                                + "ORDER BY id DESC LIMIT 1")) {
                    ps.setString(1, user.id());
                    try (var rs = ps.executeQuery()) {
                        if (rs.next() && System.currentTimeMillis()
                                - rs.getTimestamp(1).getTime() < OTP_RESEND_COOLDOWN_MS)
                            throw new IllegalArgumentException(
                                    "Please wait 60 seconds before resending the code.");
                    }
                }
                try (var ps = c.prepareStatement(
                        "SELECT count(*) FROM mobile_otps WHERE user_id = ? "
                                + "AND created_at > now() - interval '1 hour'")) {
                    ps.setString(1, user.id());
                    try (var rs = ps.executeQuery()) {
                        if (rs.next() && rs.getInt(1) >= OTP_MAX_PER_HOUR)
                            throw new IllegalArgumentException(
                                    "Too many codes requested. Try again later.");
                    }
                }
            }
        } else {
            String lastSent = u.path("mob_otp_last_sent").asText("");
            if (!lastSent.isEmpty()) {
                try {
                    if (Instant.parse(lastSent).plusMillis(OTP_RESEND_COOLDOWN_MS)
                            .isAfter(Instant.now()))
                        throw new IllegalArgumentException(
                                "Please wait 60 seconds before resending the code.");
                } catch (java.time.format.DateTimeParseException ignored) {
                }
            }
        }

        String otp = String.format("%06d", RANDOM.nextInt(1_000_000));
        String hash = otpHash(user.id() + ":" + mobile, otp);
        Instant expires = Instant.now().plusSeconds(OTP_TTL_SEC);

        if (Db.configured()) {
            try (var c = Db.conn()) {
                c.setAutoCommit(false);
                // new OTP invalidates all previous unused ones
                try (var ps = c.prepareStatement(
                        "UPDATE mobile_otps SET used_at = now() "
                                + "WHERE user_id = ? AND used_at IS NULL")) {
                    ps.setString(1, user.id());
                    ps.executeUpdate();
                }
                try (var ps = c.prepareStatement(
                        "INSERT INTO mobile_otps(user_id, mobile, otp_hash, expires_at, created_ip) "
                                + "VALUES (?,?,?,?,?)")) {
                    ps.setString(1, user.id());
                    ps.setString(2, mobile);
                    ps.setString(3, hash);
                    ps.setObject(4, Timestamp.from(expires));
                    ps.setString(5, clientIp);
                    ps.executeUpdate();
                }
                c.commit();
            } catch (Exception e) {
                throw new IllegalStateException("Could not store verification code.", e);
            }
        } else {
            ObjectNode un = (ObjectNode) u.deepCopy();
            un.put("mob_otp_hash", hash);
            un.put("mob_otp_mobile", mobile);
            un.put("mob_otp_exp", expires.toString());
            un.put("mob_otp_attempts", 0);
            un.put("mob_otp_last_sent", Instant.now().toString());
            un.remove("mob_otp_used");
            ProfileService.persistUser(un);
        }

        if (!SmsService.sendOtp(mobile, otp)) {
            // gateway rejected/failed - do not leave a pending code the user can't use
            invalidatePending(user.id(), mobile);
            AuditService.log(user.id(), user.email(), "mobile_otp_send_failed", mobile);
            throw new IllegalStateException(
                    "SMS could not be sent right now. Please try again later.");
        }
        AuditService.log(user.id(), user.email(), "mobile_otp_sent", mobile);
        ObjectNode out = MAPPER.createObjectNode();
        out.put("sent", true);
        out.put("expires_in", OTP_TTL_SEC);
        out.put("resend_after", OTP_RESEND_COOLDOWN_MS / 1000);
        return out;
    }

    // ================= OTP verify =================

    /** Verifies the OTP and marks the mobile number verified on success. */
    public static ObjectNode verifyOtp(AuthService.AuthUser user, JsonNode req)
            throws Exception {
        String otp = req.path("otp").asText("").trim();
        if (!otp.matches("^\\d{6}$"))
            throw new IllegalArgumentException("Enter the 6-digit code.");

        JsonNode u = ProfileService.findUser(user.id());
        if (u == null) throw new IllegalArgumentException("Account not found.");
        String mobile = u.path("mobile").asText("");

        if (Db.configured()) {
            return verifyDb(user, u, mobile, otp);
        }
        return verifyJson(user, u, mobile, otp);
    }

    private static ObjectNode verifyDb(AuthService.AuthUser user, JsonNode u,
                                       String mobile, String otp) throws Exception {
        long rowId = -1;
        String hash = null;
        int attempts = 0;
        Timestamp expires = null;
        boolean used = false;
        try (var c = Db.conn();
             var ps = c.prepareStatement(
                     "SELECT id, otp_hash, attempts, expires_at, used_at "
                             + "FROM mobile_otps WHERE user_id = ? AND mobile = ? "
                             + "ORDER BY id DESC LIMIT 1")) {
            ps.setString(1, user.id());
            ps.setString(2, mobile);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    rowId = rs.getLong(1);
                    hash = rs.getString(2);
                    attempts = rs.getInt(3);
                    expires = rs.getTimestamp(4);
                    used = rs.getTimestamp(5) != null;
                }
            }
        }
        final long rid = rowId;
        return finishVerify(user, u, mobile, otp, rowId, hash, attempts, expires, used,
                () -> markRowUsed(rid));
    }

    private static ObjectNode verifyJson(AuthService.AuthUser user, JsonNode u,
                                         String mobile, String otp) throws Exception {
        String mob = u.path("mob_otp_mobile").asText("");
        if (!mobile.equals(mob) || u.path("mob_otp_used").asBoolean(false))
            return failVerify(user, "Invalid or expired code. Request a new one.");
        String hash = u.path("mob_otp_hash").asText("");
        int attempts = u.path("mob_otp_attempts").asInt(0);
        Instant exp;
        try {
            exp = Instant.parse(u.path("mob_otp_exp").asText(""));
        } catch (Exception e) {
            return failVerify(user, "Invalid or expired code. Request a new one.");
        }
        long rowId = -2; // sentinel: JSON-mode row
        return finishVerify(user, u, mobile, otp, rowId, hash, attempts,
                Timestamp.from(exp), false,
                () -> {
                    ObjectNode un = (ObjectNode) u.deepCopy();
                    un.put("mob_otp_used", true);
                    ProfileService.persistUser(un);
                });
    }

    private interface MarkUsed { void run() throws Exception; }

    private static ObjectNode finishVerify(AuthService.AuthUser user, JsonNode u,
                                           String mobile, String otp, long rowId,
                                           String hash, int attempts, Timestamp expires,
                                           boolean used, MarkUsed markUsed) throws Exception {
        if (used || hash == null || expires == null)
            return failVerify(user, "Invalid or expired code. Request a new one.");
        if (expires.toInstant().isBefore(Instant.now()))
            return failVerify(user, "Code expired. Request a new one.");
        if (attempts >= OTP_MAX_ATTEMPTS) {
            markUsed.run();
            return failVerify(user, "Too many incorrect attempts. Request a new code.");
        }
        if (!constantTimeEquals(hash, otpHash(user.id() + ":" + mobile, otp))) {
            bumpAttempts(rowId, user.id(), mobile, u);
            return failVerify(user, "Incorrect verification code.");
        }
        markUsed.run();
        ObjectNode un = (ObjectNode) u.deepCopy();
        un.put("mobile_verified", true);
        un.put("mobile_verified_at", Instant.now().toString());
        ProfileService.persistUser(un);
        AuditService.log(user.id(), user.email(), "mobile_verified", mobile);
        return ProfileService.get(user);
    }

    private static void bumpAttempts(long rowId, String userId, String mobile, JsonNode u) {
        if (Db.configured()) {
            try (var c = Db.conn();
                 var ps = c.prepareStatement(
                         "UPDATE mobile_otps SET attempts = attempts + 1 WHERE id = ?")) {
                ps.setLong(1, rowId);
                ps.executeUpdate();
            } catch (Exception ignored) {
            }
        } else if (rowId == -2) {
            ObjectNode un = (ObjectNode) u.deepCopy();
            un.put("mob_otp_attempts", u.path("mob_otp_attempts").asInt(0) + 1);
            ProfileService.persistUser(un);
        }
    }

    private static void markRowUsed(long rowId) {
        if (rowId <= 0) return;
        try (var c = Db.conn();
             var ps = c.prepareStatement(
                     "UPDATE mobile_otps SET used_at = now() WHERE id = ? AND used_at IS NULL")) {
            ps.setLong(1, rowId);
            ps.executeUpdate();
        } catch (Exception ignored) {
        }
    }

    /** Marks all unused OTPs for a user+mobile as consumed. */
    private static void invalidatePending(String userId, String mobile) {
        if (Db.configured()) {
            try (var c = Db.conn();
                 var ps = c.prepareStatement(
                         "UPDATE mobile_otps SET used_at = now() "
                                 + "WHERE user_id = ? AND mobile = ? AND used_at IS NULL")) {
                ps.setString(1, userId);
                ps.setString(2, mobile);
                ps.executeUpdate();
            } catch (Exception ignored) {
            }
        }
    }

    private static ObjectNode failVerify(AuthService.AuthUser user, String msg)
            throws Exception {
        AuditService.log(user.id(), user.email(), "mobile_verify_failed", msg);
        throw new IllegalArgumentException(msg);
    }

    // ================= status =================

    /** "verified" | "pending" | "none" for the profile payload. */
    static String status(JsonNode u) {
        if (u == null) return "none";
        if (u.path("mobile_verified").asBoolean(false)) return "verified";
        String mobile = u.path("mobile").asText("");
        if (mobile.isEmpty()) return "none";
        if (Db.configured()) {
            try (var c = Db.conn();
                 var ps = c.prepareStatement(
                         "SELECT 1 FROM mobile_otps WHERE user_id = ? AND mobile = ? "
                                 + "AND used_at IS NULL AND expires_at > now() LIMIT 1")) {
                ps.setString(1, u.path("id").asText(""));
                ps.setString(2, mobile);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? "pending" : "none";
                }
            } catch (Exception e) {
                return "none";
            }
        }
        boolean pending = !u.path("mob_otp_used").asBoolean(false)
                && mobile.equals(u.path("mob_otp_mobile").asText(""));
        try {
            pending = pending && Instant.parse(u.path("mob_otp_exp").asText(""))
                    .isAfter(Instant.now());
        } catch (Exception e) {
            pending = false;
        }
        return pending ? "pending" : "none";
    }

    // ================= helpers =================

    private static String otpHash(String key, String otp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(AuthService.secret(), "HmacSHA256"));
            byte[] d = mac.doFinal((key + ":" + otp).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("otp hashing failed", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(x, y);
    }
}
