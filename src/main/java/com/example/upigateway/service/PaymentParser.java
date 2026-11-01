package com.example.upigateway.service;

import com.example.upigateway.model.ParsedResult;
import com.example.upigateway.model.Verdict;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class PaymentParser {

    private static final Logger log = LoggerFactory.getLogger(PaymentParser.class);

    static final String SYSTEM_PROMPT = "You are UPI SMS parser. Extract amount as a decimal number, utr (exactly 12 digits), sender (UPI/name), time, direction credit/debit, verdict VERIFIED|SUSPICIOUS|NOT_A_PAYMENT with reason. Judge only the message content; there is no expected amount. Return JSON only with keys amount,utr,sender,time,direction,verdict,reason.";
    static final String IMAGE_TEXT_PROMPT = "Extract amount as a decimal number, utr (exactly 12 digits), sender (UPI/name), time, direction credit/debit, verdict VERIFIED|SUSPICIOUS|NOT_A_PAYMENT with reason from this UPI payment screenshot. Judge only the image content; there is no expected amount. Return JSON only with keys amount,utr,sender,time,direction,verdict,reason.";
    static final String UNAVAILABLE_REASON = "Verification service unavailable";

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public PaymentParser(
            @Value("${meta.api.key:}") String apiKey,
            @Value("${meta.api.baseUrl:https://api.meta.ai/v1}") String baseUrl,
            @Value("${meta.api.model:muse-spark-1.3}") String model,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public ParsedResult parseSms(String smsText) {
        if (apiKey == null || apiKey.isBlank()
                || baseUrl == null || baseUrl.isBlank()
                || model == null || model.isBlank()) {
            return unavailable();
        }
        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", 0,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", smsText == null ? "" : smsText)
                )
        );
        return postChat(body);
    }

    public ParsedResult parseImage(byte[] bytes, String contentType) {
        if (apiKey == null || apiKey.isBlank()
                || baseUrl == null || baseUrl.isBlank()
                || model == null || model.isBlank()
                || bytes == null || bytes.length == 0
                || contentType == null || contentType.isBlank()) {
            return unavailable();
        }
        String dataUrl = "data:" + contentType + ";base64,"
                + java.util.Base64.getEncoder().encodeToString(bytes);
        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", 0,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", List.of(
                                Map.of("type", "text", "text", IMAGE_TEXT_PROMPT),
                                Map.of("type", "image_url", "image_url", Map.of("url", dataUrl))
                        ))
                )
        );
        return postChat(body);
    }

    private ParsedResult postChat(Map<String, Object> body) {
        try {
            String json = objectMapper.writeValueAsString(body);
            String url = baseUrl.endsWith("/")
                    ? baseUrl + "chat/completions"
                    : baseUrl + "/chat/completions";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Meta Model API chat completions returned status {}", response.statusCode());
                return unavailable();
            }
            return parseContent(response.body());
        } catch (IOException e) {
            log.warn("Verification service call failed: {}", e.getMessage());
            return unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Verification service call interrupted");
            return unavailable();
        }
    }

    private ParsedResult unavailable() {
        return new ParsedResult(null, null, null, null, null, Verdict.SUSPICIOUS, UNAVAILABLE_REASON, null);
    }

    private ParsedResult parseContent(String responseBody) {
        String content = null;
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            if (!contentNode.isTextual()) {
                log.warn("Verification response missing message.content");
                return unavailable();
            }
            content = stripFences(contentNode.asText());
            JsonNode parsed = objectMapper.readTree(content);
            BigDecimal amount = parseAmount(parsed.get("amount"));
            String utr = parseUtr(parsed.get("utr"));
            String sender = textOrNull(parsed.get("sender"));
            JsonNode timeNode = parsed.has("time") ? parsed.get("time") : parsed.get("timeText");
            String timeText = textOrNull(timeNode);
            String direction = textOrNull(parsed.get("direction"));
            Verdict verdict = parseVerdict(parsed.get("verdict"));
            String reason = textOrNull(parsed.get("reason"));
            return new ParsedResult(amount, utr, sender, timeText, direction, verdict, reason, content);
        } catch (IOException e) {
            log.warn("Could not parse verification response");
            return new ParsedResult(null, null, null, null, null, Verdict.SUSPICIOUS,
                    "Verification service returned an invalid response", content);
        }
    }

    static String stripFences(String content) {
        String s = content.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            s = firstNewline >= 0 ? s.substring(firstNewline + 1) : s.substring(3);
            int lastFence = s.lastIndexOf("```");
            if (lastFence >= 0) {
                s = s.substring(0, lastFence);
            }
            s = s.trim();
        }
        return s;
    }

    static BigDecimal parseAmount(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            if (node.isNumber()) {
                return node.decimalValue();
            }
            String text = node.asText().trim().replaceAll("[,\\s]", "");
            if (text.isEmpty()) {
                return null;
            }
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException e) {
                return new BigDecimal(text.replaceAll("[^0-9.\\-]", ""));
            }
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    static String parseUtr(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asText().replaceAll("\\s+", "");
        return text.matches("\\d{12}") ? text : null;
    }

    static Verdict parseVerdict(JsonNode node) {
        if (node == null || node.isNull()) {
            return Verdict.SUSPICIOUS;
        }
        try {
            return Verdict.valueOf(node.asText().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Verdict.SUSPICIOUS;
        }
    }

    static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asText().trim();
        return text.isEmpty() ? null : text;
    }
}
