package com.example.upigateway.dto;

import jakarta.validation.constraints.NotBlank;

public record VerifySmsRequest(
        @NotBlank String smsText
) {
}
