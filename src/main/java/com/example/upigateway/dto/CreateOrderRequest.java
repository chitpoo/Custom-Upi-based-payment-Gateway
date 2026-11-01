package com.example.upigateway.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public record CreateOrderRequest(
        @NotNull
        @DecimalMin("0.01")
        @Digits(integer = 10, fraction = 2)
        BigDecimal baseAmount,

        @NotBlank
        @Pattern(regexp = "^[\\w.\\-]+@[a-zA-Z0-9]+$", message = "Invalid UPI ID")
        String merchantUpiId
) {
}
