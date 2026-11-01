package com.example.upigateway.service;

import com.example.upigateway.model.Order;
import com.example.upigateway.model.OrderStatus;
import com.example.upigateway.repo.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
public class ExpiryService {

    private static final Logger log = LoggerFactory.getLogger(ExpiryService.class);

    private final OrderRepository orderRepository;
    private final long expiryMinutes;

    public ExpiryService(OrderRepository orderRepository,
                         @Value("${app.expiry-minutes:5}") long expiryMinutes) {
        this.orderRepository = orderRepository;
        this.expiryMinutes = expiryMinutes;
    }

    @Scheduled(fixedDelayString = "${app.expiry-interval-ms:60000}")
    public void expireStaleOrders() {
        Instant cutoff = Instant.now().minus(expiryMinutes, ChronoUnit.MINUTES);
        List<Order> stale = orderRepository.findByStatusAndCreatedAtBefore(OrderStatus.PENDING, cutoff);
        for (Order order : stale) {
            order.setStatus(OrderStatus.EXPIRED);
        }
        orderRepository.saveAll(stale);
        if (!stale.isEmpty()) {
            log.info("Expired {} stale orders", stale.size());
        }
    }
}
