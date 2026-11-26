package com.educore.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Cloudflare Turnstile CAPTCHA verification (server-side only).
 *
 * Env vars:
 *   TURNSTILE_SITE_KEY    - public site key (sent to the browser via
 *                           /api/auth/captcha-config; site keys are public by design)
 *   TURNSTILE_SECRET_KEY  - secret key, backend only, NEVER exposed to clients
 *
 * If the secret is not configured the check is skipped (development mode) -
 * production deployments MUST set both keys. Verification always happens
 * against https://challenges.cloudflare.com/turnstile/v0/siteverify; the
 * frontend token alone is never trusted.
 */
public final class CaptchaService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private static final String VERIFY_URL =
            "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    private CaptchaService() {
    }

    public static boolean configured() {
        return envValue("TURNSTILE_SECRET_KEY") != null;
    }

    /** Public config for the frontend widget - site key only, never the secret. */
    public static boolean siteKeyConfigured() {
        return envValue("TURNSTILE_SITE_KEY") != null;
    }

    public static String siteKey() {
        return envValue("TURNSTILE_SITE_KEY");
    }

    /**
     * Verifies a Turnstile response token. Returns true when verification
     * passes. Throws IllegalArgumentException with a user-safe message on
     * failure. When no secret is configured (dev), returns true.
     */
    public static boolean verify(String token, String remoteIp) {
        String secret = envValue("TURNSTILE_SECRET_KEY");
        if (secret == null) return true; // dev mode - CAPTCHA not configured
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("CAPTCHA verification is required. Please complete the challenge.");
        }
        try {
            String form = "secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8)
                    + "&response=" + URLEncoder.encode(token, StandardCharsets.UTF_8)
                    + (remoteIp != null && !remoteIp.isBlank()
                            ? "&remoteip=" + URLEncoder.encode(remoteIp, StandardCharsets.UTF_8) : "");
            HttpRequest req = HttpRequest.newBuilder(URI.create(VERIFY_URL))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode j = MAPPER.readTree(res.body());
            if (j.path("success").asBoolean(false)) return true;
            String codes = j.path("error-codes").toString();
            System.err.println("[captcha] verification failed: " + codes);
            throw new IllegalArgumentException("CAPTCHA verification failed. Please try again.");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            // fail closed: if we cannot reach the verifier, reject the attempt
            System.err.println("[captcha] siteverify error: " + e.getMessage());
            throw new IllegalStateException("CAPTCHA service unavailable. Please try again later.");
        }
    }

    private static String envValue(String key) {
        return Env.get(key);
    }
}
