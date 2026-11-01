package com.example.upigateway.model;

import java.math.BigDecimal;

public record ParsedResult(
        BigDecimal amount,
        String utr,
        String sender,
        String timeText,
        String direction,
        Verdict verdict,
        String reason,
        String rawJson
) {
}
