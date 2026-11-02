package com.example.upigateway.controller;

import com.example.upigateway.dto.SmsWebhookRequest;
import com.example.upigateway.dto.VerifyPaymentResponse;
import com.example.upigateway.dto.VerifySmsRequest;
import com.example.upigateway.service.PaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final PaymentService paymentService;
    private final String webhookSecret;
    private final ObjectMapper objectMapper;

    public PaymentController(PaymentService paymentService,
            @Value("${webhook.secret:}") String webhookSecret,
            ObjectMapper objectMapper) {
        this.paymentService = paymentService;
        this.webhookSecret = webhookSecret;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/verify")
    @ResponseStatus(HttpStatus.CREATED)
    public VerifyPaymentResponse verify(@Valid @RequestBody VerifySmsRequest request) {
        try {
            return paymentService.verifySms(request.smsText());
        } catch (DataIntegrityViolationException | OptimisticLockingFailureException e) {
            log.warn("Verify-SMS race/constraint loss, saving fallback audit: {}", e.getMessage());
            return paymentService.saveRaceLossAudit(request.smsText());
        }
    }

    // Android SMS-forwarder target: reads the raw body because MacroDroid pastes
    // the SMS in unescaped (raw quotes/newlines would break strict JSON parsing).
    @PostMapping("/sms-webhook")
    @ResponseStatus(HttpStatus.CREATED)
    public VerifyPaymentResponse smsWebhook(
            @RequestBody(required = false) String rawBody,
            @RequestHeader(value = "X-Webhook-Secret", required = false) String secret) {
        if (webhookSecret != null && !webhookSecret.isBlank() && !webhookSecret.equals(secret)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook secret");
        }
        SmsWebhookRequest request = SmsWebhookRequest.lenientParse(rawBody, objectMapper);
        if (request == null || request.text() == null || request.text().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing SMS text");
        }
        return verify(new VerifySmsRequest(request.text()));
    }

    @PostMapping(value = "/verify-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public VerifyPaymentResponse verifyImage(@RequestParam("image") MultipartFile image) {
        try {
            return paymentService.verifyImage(image);
        } catch (DataIntegrityViolationException | OptimisticLockingFailureException e) {
            log.warn("Verify-image race/constraint loss, saving fallback audit: {}", e.getMessage());
            try {
                byte[] bytes = image.getBytes();
                return paymentService.saveImageRaceLossAudit(bytes, image.getContentType(),
                        PaymentService.imageDescriptor(image.getOriginalFilename(), image.getContentType(), bytes.length));
            } catch (IOException ioe) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read uploaded image");
            }
        }
    }

    @GetMapping
    public List<VerifyPaymentResponse> list(@RequestParam(required = false) String orderId) {
        return paymentService.listPayments(orderId);
    }
}
