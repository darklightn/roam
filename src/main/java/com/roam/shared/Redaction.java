package com.roam.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Uniform redaction for every human-visible or diagnostic surface. Plaintext sensitive values are
 * never returned; payment links and API keys become opaque references.
 */
@Component
public class Redaction {

    private static final Pattern EMAIL =
            Pattern.compile("([A-Za-z0-9._%+-]+)@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");
    private static final Pattern PHONE =
            Pattern.compile("(?<!\\d)(\\+?\\d{3})(\\d{3,})(\\d{4})(?!\\d)");
    private static final Pattern PAYMENT_URL =
            Pattern.compile("https?://[^\\s\"']*(?:pay|payment|orderDetail|token)[^\\s\"']*",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern API_KEY =
            Pattern.compile("(?i)(api[_-]?key|secret|token)\\s*[=:]\\s*[A-Za-z0-9._-]{6,}");

    public String maskIdNumber(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("\\s", "");
        if (digits.length() <= 7) {
            return "*".repeat(digits.length());
        }
        return digits.substring(0, 3) + "*".repeat(digits.length() - 7)
                + digits.substring(digits.length() - 4);
    }

    public String maskPhone(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("[^0-9+]", "");
        if (digits.length() <= 7) {
            return "*".repeat(digits.length());
        }
        return digits.substring(0, 3) + "*".repeat(digits.length() - 7)
                + digits.substring(digits.length() - 4);
    }

    public String maskEmail(String value) {
        if (value == null) {
            return null;
        }
        int at = value.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return value.charAt(0) + "***" + value.substring(at);
    }

    /** Opaque, stable reference for payment links, API keys, and other secrets. */
    public String opaqueRef(String kind, String raw) {
        return "opaque:" + kind + ":" + sha256(raw).substring(0, 16);
    }

    public String redactText(String text) {
        if (text == null) {
            return null;
        }
        String redacted = EMAIL.matcher(text).replaceAll(match ->
                match.group(1).charAt(0) + "***@" + match.group(2));
        redacted = PAYMENT_URL.matcher(redacted).replaceAll("opaque:payment-link:redacted");
        redacted = API_KEY.matcher(redacted).replaceAll("$1=opaque:credential:redacted");
        redacted = PHONE.matcher(redacted).replaceAll(match ->
                match.group(1) + "****" + match.group(3));
        return redacted;
    }

    public Map<String, String> redactMap(Map<String, String> values) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, redactText(value)));
        return Map.copyOf(result);
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
