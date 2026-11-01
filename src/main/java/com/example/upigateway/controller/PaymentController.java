package com.example.upigateway.controller;

import com.example.upigateway.dto.VerifyPaymentResponse;
import com.example.upigateway.dto.VerifySmsRequest;
import com.example.upigateway.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/verify")
    @ResponseStatus(HttpStatus.CREATED)
    public VerifyPaymentResponse verify(@Valid @RequestBody VerifySmsRequest request) {
        try {
            return paymentService.verifySms(request.smsText());
        } catch (DataIntegrityViolationException | OptimisticLockingFailureException e) {
            return paymentService.saveRaceLossAudit(request.smsText());
        }
    }

    @PostMapping(value = "/verify-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public VerifyPaymentResponse verifyImage(@RequestParam("image") MultipartFile image) {
        try {
            return paymentService.verifyImage(image);
        } catch (DataIntegrityViolationException | OptimisticLockingFailureException e) {
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
