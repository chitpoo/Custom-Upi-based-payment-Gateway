package com.example.upigateway.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record SmsWebhookRequest(
        @JsonAlias({"sender", "address", "number"})
        String from,

        @NotBlank
        @JsonAlias({"message", "body", "sms"})
        String text
) {
    private static final List<String> TEXT_KEYS = List.of("text", "message", "body", "sms");
    private static final List<String> FROM_KEYS = List.of("from", "sender", "address", "number");

    /**
     * Tolerant parse for forwarder apps (MacroDroid) that paste the SMS in raw,
     * NOT JSON-escaped. Tries normal JSON first; on failure extracts the text
     * between the "text" key and the final closing quote; if even that fails,
     * treats the whole body as the SMS. Returns null only for a missing body.
     * Never throws.
     */
    public static SmsWebhookRequest lenientParse(String rawBody, ObjectMapper mapper) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        String raw = rawBody.strip();
        try {
            return mapper.readValue(raw, SmsWebhookRequest.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            // fall through to tolerant extraction
        }
        for (String key : TEXT_KEYS) {
            String text = extractTextValue(raw, key);
            if (text != null && !text.isBlank()) {
                return new SmsWebhookRequest(extractSimpleValue(raw, FROM_KEYS), text);
            }
        }
        return new SmsWebhookRequest(null, normalizeWhitespace(raw));
    }

    private static String extractTextValue(String raw, String key) {
        int openQuote = openingQuoteOf(raw, key);
        if (openQuote < 0) {
            return null;
        }
        // Closing quote: prefer the one before the final "}" so a raw message
        // containing quotes still ends at the real end of the value.
        int closeQuote = raw.lastIndexOf("\"}");
        if (closeQuote < 0) {
            closeQuote = raw.lastIndexOf('"');
        }
        if (closeQuote <= openQuote) {
            return null;
        }
        return normalizeWhitespace(raw.substring(openQuote + 1, closeQuote));
    }

    private static String extractSimpleValue(String raw, List<String> keys) {
        for (String key : keys) {
            int openQuote = openingQuoteOf(raw, key);
            if (openQuote < 0) {
                continue;
            }
            // Sender IDs don't contain quotes; the next quote ends the value.
            int closeQuote = raw.indexOf('"', openQuote + 1);
            if (closeQuote < 0) {
                continue;
            }
            return raw.substring(openQuote + 1, closeQuote);
        }
        return null;
    }

    private static int openingQuoteOf(String raw, String key) {
        String marker = "\"" + key + "\"";
        int keyAt = raw.indexOf(marker);
        if (keyAt < 0) {
            return -1;
        }
        int colon = raw.indexOf(':', keyAt + marker.length());
        if (colon < 0) {
            return -1;
        }
        return raw.indexOf('"', colon + 1);
    }

    private static String normalizeWhitespace(String text) {
        return text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').strip();
    }
}
