package com.example.upigateway.dto;

import com.example.upigateway.model.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderResponse(
        String id,
        BigDecimal baseAmount,
        BigDecimal uniqueAmount,
        String merchantUpiId,
        OrderStatus status,
        Instant createdAt,
        String upiLink
) {
}
