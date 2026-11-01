package com.example.upigateway.dto;

import com.example.upigateway.model.OrderStatus;
import com.example.upigateway.model.Verdict;

import java.math.BigDecimal;

public record VerifyPaymentResponse(
        String paymentId,
        Verdict verdict,
        String reason,
        BigDecimal amount,
        String utr,
        String matchedOrderId,
        OrderStatus orderStatus
) {
}
