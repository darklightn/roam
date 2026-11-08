package com.roam.domain;

public enum IntentStatus {
    PENDING,
    WAITING_DEPENDENCY,
    WAITING_HITL,
    COMPLETED,
    FAILED,
    UNKNOWN_EXTERNAL_OUTCOME,
    CANCELLED
}
