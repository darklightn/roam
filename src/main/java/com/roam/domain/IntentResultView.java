package com.roam.domain;

import java.util.List;
import java.util.Objects;

public record IntentResultView(
        String intentId,
        String taskId,
        IntentType type,
        IntentStatus status,
        String orderId,
        String supplierOrderId,
        FailureReason failure,
        boolean duplicateRiskWarning) {

    public IntentResultView {
        Objects.requireNonNull(intentId, "intentId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
    }
}
