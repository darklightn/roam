package com.roam.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Validated supplier payment-status callback input. Never executes payment. */
public record CallbackRequest(
        String callbackId,
        String supplier,
        String supplierOrderId,
        String paymentStatus,
        BigDecimal amount,
        String currency,
        String signature,
        Map<String, String> payload) {

    public CallbackRequest {
        Objects.requireNonNull(callbackId, "callbackId");
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(supplierOrderId, "supplierOrderId");
        Objects.requireNonNull(paymentStatus, "paymentStatus");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
