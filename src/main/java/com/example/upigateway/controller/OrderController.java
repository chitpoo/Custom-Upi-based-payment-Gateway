package com.example.upigateway.controller;

import com.example.upigateway.dto.CreateOrderRequest;
import com.example.upigateway.dto.OrderResponse;
import com.example.upigateway.model.Order;
import com.example.upigateway.model.OrderStatus;
import com.example.upigateway.repo.OrderRepository;
import com.example.upigateway.service.OrderService;
import com.example.upigateway.service.QrService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;
    private final QrService qrService;
    private final OrderRepository orderRepository;

    public OrderController(OrderService orderService, QrService qrService, OrderRepository orderRepository) {
        this.orderService = orderService;
        this.qrService = qrService;
        this.orderRepository = orderRepository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse create(@Valid @RequestBody CreateOrderRequest request) {
        return orderService.createOrder(request.baseAmount(), request.merchantUpiId());
    }

    @GetMapping("/{id}")
    public OrderResponse getById(@PathVariable String id) {
        return orderService.getOrderById(id);
    }

    @GetMapping
    public List<OrderResponse> list(@RequestParam(required = false) OrderStatus status) {
        List<Order> orders = status == null ? orderRepository.findAll() : orderRepository.findByStatus(status);
        return orders.stream().map(this::toResponse).toList();
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

    private static String buildUpiLink(String merchantUpiId, BigDecimal amount, String orderId) {
        return "upi://pay?pa=" + URLEncoder.encode(merchantUpiId, StandardCharsets.UTF_8)
                + "&am=" + amount.toPlainString()
                + "&tn=" + URLEncoder.encode(orderId, StandardCharsets.UTF_8)
                + "&cu=INR";
    }

    @GetMapping(value = "/{id}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> getQr(@PathVariable String id) {
        OrderResponse order = orderService.getOrderById(id);
        byte[] png = qrService.generateQrPng(order.upiLink());
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png);
    }
}
