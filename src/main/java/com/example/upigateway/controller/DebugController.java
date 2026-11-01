package com.example.upigateway.controller;

import com.example.upigateway.model.ParsedResult;
import com.example.upigateway.service.PaymentParser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/debug")
public class DebugController {

    private final PaymentParser paymentParser;

    public DebugController(PaymentParser paymentParser) {
        this.paymentParser = paymentParser;
    }

    @PostMapping("/parse")
    public ParsedResult parse(@Valid @RequestBody ParseRequest request) {
        return paymentParser.parseSms(request.smsText());
    }

    public record ParseRequest(@NotBlank String smsText) {
    }
}
