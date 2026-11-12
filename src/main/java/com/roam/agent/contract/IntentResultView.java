package com.roam.agent.contract;

import com.roam.domain.shared.FailureReason;
import com.roam.domain.workflow.IntentStatus;
import com.roam.domain.workflow.IntentType;
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
