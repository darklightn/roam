package com.roam.domain.workflow;

public enum IntentStatus {
    PENDING,
    WAITING_DEPENDENCY,
    WAITING_HITL,
    COMPLETED,
    FAILED,
    UNKNOWN_EXTERNAL_OUTCOME,
    CANCELLED
}
