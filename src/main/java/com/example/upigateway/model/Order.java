package com.example.upigateway.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    private String id;

    @Column(precision = 10, scale = 2, nullable = false)
    private BigDecimal baseAmount;

    @Column(precision = 10, scale = 2, nullable = false)
    private BigDecimal uniqueAmount;

    @NotBlank
    @Pattern(regexp = "^[\\w.\\-]+@[a-zA-Z0-9]+$", message = "Invalid UPI ID")
    @Column(nullable = false)
    private String merchantUpiId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant paidAt;

    @Version
    private Long version;

    public Order() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public BigDecimal getBaseAmount() { return baseAmount; }
    public void setBaseAmount(BigDecimal baseAmount) { this.baseAmount = baseAmount; }

    public BigDecimal getUniqueAmount() { return uniqueAmount; }
    public void setUniqueAmount(BigDecimal uniqueAmount) { this.uniqueAmount = uniqueAmount; }

    public String getMerchantUpiId() { return merchantUpiId; }
    public void setMerchantUpiId(String merchantUpiId) { this.merchantUpiId = merchantUpiId; }

    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
