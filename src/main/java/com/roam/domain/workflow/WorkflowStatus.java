package com.roam.domain.workflow;

public enum WorkflowStatus {
    IN_PROGRESS,
    WAITING_HITL,
    PARTIALLY_COMPLETED,
    PARTIALLY_FAILED,
    COMPLETED,
    FAILED,
    CANCELLED,
    TAKEN_OVER
}
