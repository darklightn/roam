package com.roam.domain;

import java.util.Objects;

public record BookingResult(
        String orderId,
        BookingOrderState state,
        String supplierOrderId,
        String paymentLinkReference,
        String confirmationReference,
        java.util.Map<String, String> supplierReferences,
        FailureReason failure,
        boolean duplicateRiskWarning,
        int supplierCalls) {

    public BookingResult {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(state, "state");
        supplierReferences = supplierReferences == null ? java.util.Map.of() : java.util.Map.copyOf(supplierReferences);
    }
}
