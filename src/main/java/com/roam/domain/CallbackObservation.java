package com.roam.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Redacted payment-status observation. It does not change Booking Order business state. */
public record CallbackObservation(
        String id,
        String callbackId,
        String supplier,
        String supplierOrderId,
        CallbackStatus status,
        String paymentStatus,
        Instant receivedAt,
        String redactedDetail) {

    public CallbackObservation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(callbackId, "callbackId");
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(supplierOrderId, "supplierOrderId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(redactedDetail, "redactedDetail");
    }
}
