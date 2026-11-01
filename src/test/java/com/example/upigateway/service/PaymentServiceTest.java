package com.example.upigateway.service;

import com.example.upigateway.dto.VerifyPaymentResponse;
import com.example.upigateway.model.Order;
import com.example.upigateway.model.OrderStatus;
import com.example.upigateway.model.ParsedResult;
import com.example.upigateway.model.Payment;
import com.example.upigateway.model.Verdict;
import com.example.upigateway.repo.OrderRepository;
import com.example.upigateway.repo.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// Plain fakes (no Mockito: its ByteBuddy version cannot instrument classes on JDK 23).
class PaymentServiceTest {

    // Fake parser: PaymentParser's methods are not final, so a subclass can
    // return canned results without any HTTP call.
    static class FakeParser extends PaymentParser {
        ParsedResult smsResult;
        ParsedResult imageResult;

        FakeParser() {
            super("", "", "", new ObjectMapper());
        }

        @Override
        public ParsedResult parseSms(String smsText) {
            return smsResult;
        }

        @Override
        public ParsedResult parseImage(byte[] bytes, String contentType) {
            return imageResult;
        }
    }

    static class FakeOrderRepo {
        final List<Order> saved = new ArrayList<>();
        List<Order> pending = List.of();
        List<Order> expired = List.of();

        OrderRepository proxy() {
            return (OrderRepository) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class[]{OrderRepository.class},
                    (p, method, args) -> switch (method.getName()) {
                        case "findByUniqueAmountAndStatusOrderByCreatedAtAsc" ->
                                args[1] == OrderStatus.PENDING ? pending : expired;
                        case "save" -> {
                            saved.add((Order) args[0]);
                            yield args[0];
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    static class FakePaymentRepo {
        final List<Payment> saved = new ArrayList<>();
        boolean utrExists;

        PaymentRepository proxy() {
            return (PaymentRepository) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class[]{PaymentRepository.class},
                    (p, method, args) -> switch (method.getName()) {
                        case "existsByUtr" -> utrExists;
                        case "save" -> {
                            saved.add((Payment) args[0]);
                            yield args[0];
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    static class FakeImage implements MultipartFile {
        private final byte[] bytes;

        FakeImage(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public String getName() {
            return "image";
        }

        @Override
        public String getOriginalFilename() {
            return "pay.png";
        }

        @Override
        public String getContentType() {
            return "image/png";
        }

        @Override
        public boolean isEmpty() {
            return bytes.length == 0;
        }

        @Override
        public long getSize() {
            return bytes.length;
        }

        @Override
        public byte[] getBytes() {
            return bytes;
        }

        @Override
        public java.io.InputStream getInputStream() {
            return new java.io.ByteArrayInputStream(bytes);
        }

        @Override
        public void transferTo(java.io.File dest) {
            throw new UnsupportedOperationException();
        }
    }

    private Order pendingOrder(String amount) {
        Order order = new Order();
        order.setId(UUID.randomUUID().toString());
        order.setBaseAmount(new BigDecimal("100.00"));
        order.setUniqueAmount(new BigDecimal(amount));
        order.setMerchantUpiId("shop@okaxis");
        order.setStatus(OrderStatus.PENDING);
        order.setCreatedAt(Instant.now());
        return order;
    }

    @Test
    void duplicateUtrAuditIsSavedWithNulledUtrSoUniqueConstraintHolds() {
        FakeParser parser = new FakeParser();
        parser.smsResult = new ParsedResult(new BigDecimal("100.47"), "482910573641",
                "buyer2@upi", null, "credit", Verdict.VERIFIED, "ok", "{}");
        FakeOrderRepo orders = new FakeOrderRepo();
        orders.pending = List.of(pendingOrder("100.47"));
        FakePaymentRepo payments = new FakePaymentRepo();
        payments.utrExists = true;
        PaymentService service = new PaymentService(parser, orders.proxy(), payments.proxy());

        VerifyPaymentResponse response = service.verifySms("sms");

        assertEquals(Verdict.SUSPICIOUS, response.verdict());
        assertEquals("Duplicate UTR", response.reason());
        assertEquals("482910573641", response.utr());
        assertEquals(1, payments.saved.size());
        assertNull(payments.saved.get(0).getUtr());
        assertEquals(0, orders.saved.size());
    }

    @Test
    void smsDebitDoesNotMatchButImageDebitDoes() {
        ParsedResult debit = new ParsedResult(new BigDecimal("100.90"), "730195846203",
                "buyer3@upi", null, "debit", Verdict.VERIFIED, "ok", "{}");
        FakeParser parser = new FakeParser();
        parser.smsResult = debit;
        parser.imageResult = debit;
        FakeOrderRepo orders = new FakeOrderRepo();
        Order order = pendingOrder("100.90");
        orders.pending = List.of(order);
        FakePaymentRepo payments = new FakePaymentRepo();
        PaymentService service = new PaymentService(parser, orders.proxy(), payments.proxy());

        // SMS path keeps the credit gate: no match, no PAID.
        VerifyPaymentResponse smsResponse = service.verifySms("sms");
        assertNull(smsResponse.matchedOrderId());
        assertNull(smsResponse.orderStatus());
        assertEquals(0, orders.saved.size());

        // Image path skips the credit gate: payer screenshot matches and pays.
        VerifyPaymentResponse imageResponse = service.verifyImage(new FakeImage(new byte[]{1, 2, 3}));
        assertEquals(Verdict.VERIFIED, imageResponse.verdict());
        assertEquals(order.getId(), imageResponse.matchedOrderId());
        assertEquals(OrderStatus.PAID, imageResponse.orderStatus());
        assertEquals(OrderStatus.PAID, order.getStatus());
    }
}
