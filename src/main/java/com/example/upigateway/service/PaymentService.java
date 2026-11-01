package com.example.upigateway.service;

import com.example.upigateway.dto.VerifyPaymentResponse;
import com.example.upigateway.model.Order;
import com.example.upigateway.model.OrderStatus;
import com.example.upigateway.model.ParsedResult;
import com.example.upigateway.model.Payment;
import com.example.upigateway.model.Verdict;
import com.example.upigateway.repo.OrderRepository;
import com.example.upigateway.repo.PaymentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class PaymentService {

    static final String EXPIRED_REASON = "Order expired — 5 min window passed. Please create a new order and pay again.";
    static final String DUPLICATE_UTR_REASON = "Duplicate UTR";
    static final String RACE_LOSS_REASON = "Payment already processed";

    private final PaymentParser paymentParser;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;

    public PaymentService(PaymentParser paymentParser,
                          OrderRepository orderRepository,
                          PaymentRepository paymentRepository) {
        this.paymentParser = paymentParser;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
    }

    @Transactional
    public VerifyPaymentResponse verifySms(String smsText) {
        ParsedResult parsed = paymentParser.parseSms(smsText);
        return matchAndSettle(parsed, smsText, true);
    }

    @Transactional
    public VerifyPaymentResponse verifyImage(MultipartFile image) {
        String contentType = image == null ? null : image.getContentType();
        if (contentType != null && contentType.contains(";")) {
            contentType = contentType.substring(0, contentType.indexOf(';')).trim();
        }
        if (contentType == null
                || !(contentType.equalsIgnoreCase("image/jpeg")
                        || contentType.equalsIgnoreCase("image/jpg")
                        || contentType.equalsIgnoreCase("image/png"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only JPEG/PNG images are accepted");
        }
        if (image.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty image file");
        }
        byte[] bytes;
        try {
            bytes = image.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read uploaded image");
        }
        ParsedResult parsed = paymentParser.parseImage(bytes, contentType);
        return matchAndSettle(parsed, imageDescriptor(image.getOriginalFilename(), contentType, bytes.length), false);
    }

    @Transactional(readOnly = true)
    public List<VerifyPaymentResponse> listPayments(String orderId) {
        List<Payment> payments;
        if (orderId == null || orderId.isBlank()) {
            payments = paymentRepository.findAllByOrderByReceivedAtDesc();
        } else {
            payments = paymentRepository.findByOrder_Id(orderId);
            payments.sort(Comparator.comparing(Payment::getReceivedAt,
                    Comparator.nullsLast(Comparator.reverseOrder())));
        }
        return payments.stream().map(this::toResponse).toList();
    }

    public static String imageDescriptor(String filename, String contentType, long size) {
        return "image:" + (filename == null || filename.isBlank() ? "upload" : filename)
                + ":" + contentType + ":" + size + "B";
    }

    private VerifyPaymentResponse toResponse(Payment payment) {
        Order order = payment.getOrder();
        return new VerifyPaymentResponse(payment.getId(), payment.getVerdict(), payment.getReason(),
                payment.getAmount(), payment.getUtr(),
                order == null ? null : order.getId(),
                order == null ? null : order.getStatus());
    }

    // requireCredit is true for bank SMS (money must arrive at the merchant) and false for
    // screenshots (a payer's success screen legitimately shows a debit/outgoing payment).
    private VerifyPaymentResponse matchAndSettle(ParsedResult parsed, String rawMessage, boolean requireCredit) {
        String utr = parsed.utr();
        if (utr != null && utr.isBlank()) {
            utr = null;
        }
        BigDecimal amount = parsed.amount() == null ? null : parsed.amount().setScale(2, RoundingMode.HALF_UP);
        Verdict verdict = parsed.verdict() == null ? Verdict.SUSPICIOUS : parsed.verdict();

        Payment payment = new Payment();
        payment.setId(UUID.randomUUID().toString());
        payment.setAmount(amount);
        payment.setUtr(utr);
        payment.setSender(parsed.sender());
        payment.setVerdict(verdict);
        payment.setReason(parsed.reason());
        payment.setRawMessage(rawMessage);
        payment.setReceivedAt(Instant.now());

        boolean credit = "credit".equalsIgnoreCase(parsed.direction() == null ? "" : parsed.direction().trim());
        if (verdict != Verdict.VERIFIED || amount == null || (requireCredit && !credit)) {
            paymentRepository.save(payment);
            return new VerifyPaymentResponse(payment.getId(), payment.getVerdict(), payment.getReason(),
                    amount, utr, null, null);
        }

        List<Order> pending = orderRepository.findByUniqueAmountAndStatusOrderByCreatedAtAsc(amount, OrderStatus.PENDING);
        if (pending.isEmpty()) {
            List<Order> expired = orderRepository.findByUniqueAmountAndStatusOrderByCreatedAtAsc(amount, OrderStatus.EXPIRED);
            if (!expired.isEmpty()) {
                Order exp = expired.get(0);
                payment.setVerdict(Verdict.SUSPICIOUS);
                payment.setReason(EXPIRED_REASON);
                if (utr != null && paymentRepository.existsByUtr(utr)) {
                    payment.setUtr(null); // UNIQUE(utr): keep the expired audit saveable on re-submits
                }
                payment.setOrder(exp);
                paymentRepository.save(payment);
                return new VerifyPaymentResponse(payment.getId(), Verdict.SUSPICIOUS, EXPIRED_REASON,
                        amount, utr, exp.getId(), OrderStatus.EXPIRED);
            }
            paymentRepository.save(payment);
            return new VerifyPaymentResponse(payment.getId(), Verdict.VERIFIED, payment.getReason(),
                    amount, utr, null, null);
        }

        if (utr != null && paymentRepository.existsByUtr(utr)) {
            payment.setVerdict(Verdict.SUSPICIOUS);
            payment.setReason(DUPLICATE_UTR_REASON);
            payment.setUtr(null); // UNIQUE(utr): the audit row must not reuse the duplicate value
            paymentRepository.save(payment);
            return new VerifyPaymentResponse(payment.getId(), Verdict.SUSPICIOUS, DUPLICATE_UTR_REASON,
                    amount, utr, null, null);
        }

        Order order = pending.get(0);
        order.setStatus(OrderStatus.PAID);
        order.setPaidAt(Instant.now());
        payment.setOrder(order);
        orderRepository.save(order);
        paymentRepository.save(payment);
        return new VerifyPaymentResponse(payment.getId(), Verdict.VERIFIED, payment.getReason(),
                amount, utr, order.getId(), OrderStatus.PAID);
    }

    @Transactional
    public VerifyPaymentResponse saveRaceLossAudit(String rawMessage) {
        return raceLossAudit(paymentParser.parseSms(rawMessage), rawMessage);
    }

    @Transactional
    public VerifyPaymentResponse saveImageRaceLossAudit(byte[] bytes, String contentType, String rawDescriptor) {
        return raceLossAudit(paymentParser.parseImage(bytes, contentType), rawDescriptor);
    }

    private VerifyPaymentResponse raceLossAudit(ParsedResult parsed, String rawMessage) {
        String utr = parsed.utr();
        if (utr != null && utr.isBlank()) {
            utr = null;
        }
        if (utr != null && paymentRepository.existsByUtr(utr)) {
            utr = null;
        }
        BigDecimal amount = parsed.amount() == null ? null : parsed.amount().setScale(2, RoundingMode.HALF_UP);
        Payment payment = new Payment();
        payment.setId(UUID.randomUUID().toString());
        payment.setAmount(amount);
        payment.setUtr(utr);
        payment.setSender(parsed.sender());
        payment.setVerdict(Verdict.SUSPICIOUS);
        payment.setReason(RACE_LOSS_REASON);
        payment.setRawMessage(rawMessage);
        payment.setReceivedAt(Instant.now());
        paymentRepository.save(payment);
        return new VerifyPaymentResponse(payment.getId(), Verdict.SUSPICIOUS, RACE_LOSS_REASON,
                amount, utr, null, null);
    }
}
