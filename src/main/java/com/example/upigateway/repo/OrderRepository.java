package com.example.upigateway.repo;

import com.example.upigateway.model.Order;
import com.example.upigateway.model.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface OrderRepository extends JpaRepository<Order, String> {

    List<Order> findByStatusAndCreatedAtBefore(OrderStatus status, Instant cutoff);

    boolean existsByUniqueAmountAndStatus(BigDecimal uniqueAmount, OrderStatus status);

    List<Order> findByUniqueAmountAndStatusOrderByCreatedAtAsc(BigDecimal uniqueAmount, OrderStatus status);

    List<Order> findByStatus(OrderStatus status);
}
