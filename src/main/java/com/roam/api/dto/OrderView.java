package com.roam.api.dto;

import java.util.Map;

public record OrderView(
        String id,
        String tripRequestId,
        String intentId,
        String type,
        String state,
        String supplierOrderId,
        String paymentLinkReference,
        String confirmationReference,
        Map<String, String> supplierReferences,
        String failureType,
        boolean duplicateRiskWarning,
        boolean paidObserved,
        int attempts) {}
