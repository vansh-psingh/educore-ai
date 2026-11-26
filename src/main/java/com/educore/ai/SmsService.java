package com.educore.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * SMS gateway adapter for mobile OTP delivery.
 *
 * Provider is selected via env (see .env.example):
 *   SMS_PROVIDER=log     -> dev mode: OTP printed to server console only
 *   SMS_PROVIDER=msg91   -> MSG91 transactional SMS (paid credits required)
 *
 * There is NO free production SMS API for Indian numbers - until paid
 * credits are configured the system runs in "log" mode and OTPs are only
 * visible in data/server.log (never in API responses).
 */
public final class SmsService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private SmsService() {
    }

    /** True when a real gateway is configured (not the dev log provider). */
    public static boolean gatewayConfigured() {
        return "msg91".equalsIgnoreCase(provider());
    }

    /** Name of the active provider for diagnostics: log | msg91 | none. */
    public static String provider() {
        String p = envValue("SMS_PROVIDER");
        return p == null ? "" : p.trim().toLowerCase();
    }

    /**
     * Sends an OTP SMS. Returns true only when the provider confirms
     * submission. In "log" mode the message goes to the server console
     * (dev fallback, same pattern as SMTP) and returns true.
     */
    public static boolean sendOtp(String mobile, String otp) {
        String p = provider();
        try {
            return switch (p) {
                case "log" -> {
                    // dev fallback - server console ONLY, never the API
                    System.out.println("[sms] SMS_PROVIDER=log - OTP for " + mobile + ": " + otp
                            + " (dev mode, no SMS gateway configured)");
                    yield true;
                }
                case "msg91" -> sendMsg91(mobile, otp);
                default -> false;
            };
        } catch (Exception e) {
            System.err.println("[sms] send failed for " + mobile + ": " + e.getMessage());
            return false;
        }
    }

    /** MSG91 Send HTTP API (transactional SMS). Requires paid credits. */
    private static boolean sendMsg91(String mobile, String otp) throws Exception {
        String authKey = envValue("MSG91_AUTH_KEY");
        String senderId = envValue("MSG91_SENDER_ID");   // 6-char approved header
        String route = envValue("MSG91_ROUTE");          // e.g. 4 (transactional)
        String templateId = envValue("MSG91_TEMPLATE_ID"); // DLT template (India)
        if (authKey == null || senderId == null) {
            System.err.println("[sms] msg91 selected but MSG91_AUTH_KEY/MSG91_SENDER_ID missing");
            return false;
        }
        String message = "Your EDUCORE AI verification code is " + otp
                + ". Valid for 5 minutes. Do not share it.";
        // MSG91 flow API: template + mobile + variables
        String body;
        String url;
        if (templateId != null) {
            url = "https://control.msg91.com/api/v5/flow/";
            body = MAPPER.writeValueAsString(MAPPER.createObjectNode()
                    .put("template_id", templateId)
                    .put("short_url", "0")
                    .put("realTimeResponse", "1")
                    .set("recipients", MAPPER.createArrayNode().add(MAPPER.createObjectNode()
                            .put("mobiles", mobile.replace("+", ""))
                            .put("OTP", otp))));
        } else {
            url = "https://api.msg91.com/api/sendhttp.php";
            String enc = java.net.URLEncoder.encode(message, StandardCharsets.UTF_8);
            String encSender = java.net.URLEncoder.encode(senderId, StandardCharsets.UTF_8);
            body = null; // form-encoded below
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("authkey", authKey)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "authkey=" + java.net.URLEncoder.encode(authKey, StandardCharsets.UTF_8)
                                    + "&mobiles=" + mobile.replace("+", "")
                                    + "&message=" + enc
                                    + "&sender=" + encSender
                                    + (route != null ? "&route=" + route : "")))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 && resp.body() != null
                    && resp.body().toUpperCase().contains("SUCCESS");
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("authkey", authKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            System.err.println("[sms] msg91 HTTP " + resp.statusCode());
            return false;
        }
        JsonNode j = MAPPER.readTree(resp.body());
        // flow API returns {"type":"success",...} on acceptance
        return "success".equalsIgnoreCase(j.path("type").asText(""))
                || j.path("request_id").asText("").length() > 0;
    }

    private static String envValue(String key) {
        return Env.get(key);
    }
}
