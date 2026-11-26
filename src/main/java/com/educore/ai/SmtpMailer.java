package com.educore.ai;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.Properties;

/**
 * SMTP email delivery for transactional mail (password resets, notifications).
 *
 * Configuration via environment variables or .env:
 *   SMTP_HOST      - e.g. smtp.gmail.com
 *   SMTP_PORT      - 587 (STARTTLS) or 465 (SSL)
 *   SMTP_USER      - account username
 *   SMTP_PASS      - app password / API secret
 *   SMTP_FROM      - sender address (defaults to SMTP_USER)
 *   SMTP_TLS       - "starttls" (default) | "ssl" | "none"
 *
 * configured() is false until all required vars are present; callers must
 * degrade gracefully (log the event, never expose tokens).
 */
public final class SmtpMailer {

    private SmtpMailer() {
    }

    public static boolean configured() {
        return envValue("SMTP_HOST") != null
                && envValue("SMTP_USER") != null
                && envValue("SMTP_PASS") != null;
    }

    /** Sends an HTML email. Throws on failure. */
    public static void sendHtml(String to, String subject, String htmlBody) throws Exception {
        String host = envValue("SMTP_HOST");
        String user = envValue("SMTP_USER");
        String pass = envValue("SMTP_PASS");
        String from = firstNonBlank(envValue("SMTP_FROM"), user);
        String port = firstNonBlank(envValue("SMTP_PORT"), "587");
        String tls = firstNonBlank(envValue("SMTP_TLS"), "starttls");

        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", port);
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "15000");
        switch (tls) {
            case "ssl" -> {
                props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
                props.put("mail.smtp.socketFactory.port", port);
            }
            case "none" -> props.put("mail.smtp.starttls.enable", "false");
            default -> {
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.starttls.required", "true");
            }
        }

        Session session = Session.getInstance(props, new jakarta.mail.Authenticator() {
            @Override
            protected jakarta.mail.PasswordAuthentication getPasswordAuthentication() {
                return new jakarta.mail.PasswordAuthentication(user, pass);
            }
        });

        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(from));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress(to));
        msg.setSubject(subject, "UTF-8");
        msg.setContent(htmlBody, "text/html; charset=UTF-8");
        Transport.send(msg);
    }

    /** Professional password-reset email template. */
    public static String resetEmailHtml(String name, String resetUrl) {
        String safeName = name == null ? "there" : name.replace("&", "&amp;").replace("<", "&lt;");
        return """
                <!DOCTYPE html>
                <html><body style="margin:0;padding:0;background:#0b1020;font-family:Arial,Helvetica,sans-serif;">
                <div style="max-width:560px;margin:24px auto;background:#121a33;border-radius:16px;padding:32px;color:#e8ecf8;">
                  <div style="font-size:22px;font-weight:bold;color:#a78bfa;">EDUCORE AI</div>
                  <h2 style="margin:20px 0 8px;">Reset your password</h2>
                  <p style="color:#aab3d0;">Hi %s,</p>
                  <p style="color:#aab3d0;">We received a request to reset the password for your EDUCORE AI account.
                     This link expires in 30 minutes and can be used only once.</p>
                  <p style="text-align:center;margin:28px 0;">
                    <a href="%s" style="background:linear-gradient(135deg,#7c3aed,#06b6d4);color:#fff;
                       text-decoration:none;padding:14px 28px;border-radius:10px;font-weight:bold;display:inline-block;">
                       Reset Password</a>
                  </p>
                  <p style="color:#aab3d0;font-size:13px;">If the button doesn't work, copy this link:<br>
                     <a href="%s" style="color:#67e8f9;word-break:break-all;">%s</a></p>
                  <p style="color:#aab3d0;font-size:13px;">If you didn't request this, you can safely ignore this email
                     - your password will not change.</p>
                  <hr style="border:none;border-top:1px solid #26304f;margin:24px 0;">
                  <p style="color:#5b6580;font-size:12px;">EDUCORE AI - Learn Smarter. Understand Deeper.</p>
                </div></body></html>
                """.formatted(safeName, resetUrl, resetUrl, resetUrl);
    }

    /** Branded OTP verification email for password reset. */
    public static String otpEmailHtml(String name, String otp, int minutes) {
        String safeName = name == null ? "there" : name.replace("&", "&amp;").replace("<", "&lt;");
        return """
                <!DOCTYPE html>
                <html><body style="margin:0;padding:0;background:#0b1020;font-family:Arial,Helvetica,sans-serif;">
                <div style="max-width:560px;margin:24px auto;background:#121a33;border-radius:16px;padding:32px;color:#e8ecf8;">
                  <div style="font-size:22px;font-weight:bold;color:#a78bfa;">EDUCORE AI</div>
                  <h2 style="margin:20px 0 8px;">Verify your identity</h2>
                  <p style="color:#aab3d0;">Hi %s,</p>
                  <p style="color:#aab3d0;">We received a request to reset the password for your EDUCORE AI account.
                     Enter this verification code to continue:</p>
                  <div style="text-align:center;margin:28px 0;">
                    <span style="display:inline-block;background:linear-gradient(135deg,#7c3aed,#06b6d4);
                         color:#fff;font-size:34px;font-weight:bold;letter-spacing:10px;
                         padding:16px 28px;border-radius:12px;">%s</span>
                  </div>
                  <p style="color:#aab3d0;text-align:center;">This code expires in <strong>%d minutes</strong>
                     and can be used only once.</p>
                  <p style="color:#fbbf24;font-size:13px;background:#fbbf2415;border-radius:8px;
                     padding:10px 14px;">Never share this code with anyone - EDUCORE AI staff will never ask for it.</p>
                  <p style="color:#aab3d0;font-size:13px;">If you didn't request a password reset, you can safely
                     ignore this email - your password will not change. If you're concerned about your account,
                     contact support.</p>
                  <hr style="border:none;border-top:1px solid #26304f;margin:24px 0;">
                  <p style="color:#5b6580;font-size:12px;">EDUCORE AI - Learn Smarter. Understand Deeper.</p>
                </div></body></html>
                """.formatted(safeName, otp, minutes);
    }

    private static String envValue(String key) {
        return Env.get(key);
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }
}
