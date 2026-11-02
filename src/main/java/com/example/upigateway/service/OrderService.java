package com.example.upigateway.service;

import com.example.upigateway.dto.OrderResponse;
import com.example.upigateway.model.Order;
import com.example.upigateway.model.OrderStatus;
import com.example.upigateway.repo.OrderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class  OrderService {

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public synchronized OrderResponse createOrder(BigDecimal baseAmount, String merchantUpiId) {
        BigDecimal base = baseAmount.setScale(2, RoundingMode.HALF_UP);
        if (base.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "baseAmount must be > 0");
        }

        BigDecimal unique = null;
        for (int i = 0; i < 100; i++) {
            int paise = ThreadLocalRandom.current().nextInt(1, 100);
            BigDecimal candidate = base.add(BigDecimal.valueOf(paise).movePointLeft(2));
            if (!orderRepository.existsByUniqueAmountAndStatus(candidate, OrderStatus.PENDING)) {
                unique = candidate;
                break;
            }
        }
        if (unique == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "All paise variants in use — try again");
        }

        Order order = new Order();
        order.setId(UUID.randomUUID().toString());
        order.setBaseAmount(base);
        order.setUniqueAmount(unique);
        order.setMerchantUpiId(merchantUpiId);
        order.setStatus(OrderStatus.PENDING);
        order.setCreatedAt(Instant.now());
        orderRepository.save(order);
        return toResponse(order);
    }

    public OrderResponse getOrderById(String id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        return toResponse(order);
    }

    private OrderResponse toResponse(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getBaseAmount(),
                order.getUniqueAmount(),
                order.getMerchantUpiId(),
                order.getStatus(),
                order.getCreatedAt(),
                buildUpiLink(order.getMerchantUpiId(), order.getUniqueAmount(), order.getId())
        );
    }

    static String buildUpiLink(String merchantUpiId, BigDecimal amount, String orderId) {
        return "upi://pay?pa=" + URLEncoder.encode(merchantUpiId, StandardCharsets.UTF_8)
                + "&am=" + amount.toPlainString()
                + "&tn=" + URLEncoder.encode(orderId, StandardCharsets.UTF_8)
                + "&cu=INR";
    }
}
