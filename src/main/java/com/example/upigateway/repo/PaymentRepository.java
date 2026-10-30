package com.example.upigateway.repo;

import com.example.upigateway.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, String> {

    Optional<Payment> findByUtr(String utr);

    boolean existsByUtr(String utr);
}
