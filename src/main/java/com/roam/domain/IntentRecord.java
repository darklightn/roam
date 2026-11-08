package com.roam.domain;

import java.util.Objects;

/** Per-intent task record. Each intent keeps an independent, traceable outcome. */
public final class IntentRecord {

    private final String id;
    private final IntentType type;
    private final String taskId;
    private final String dependsOn;
    private IntentStatus status;
    private String orderId;
    private String supplierOrderId;
    private FailureReason failure;
    private String summary;

    public IntentRecord(String id, IntentType type, String taskId, String dependsOn) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.dependsOn = dependsOn;
        this.status = dependsOn == null ? IntentStatus.PENDING : IntentStatus.WAITING_DEPENDENCY;
        this.summary = "not started";
    }

    public String id() {
        return id;
    }

    public IntentType type() {
        return type;
    }

    public String taskId() {
        return taskId;
    }

    public String dependsOn() {
        return dependsOn;
    }

    public IntentStatus status() {
        return status;
    }

    public String orderId() {
        return orderId;
    }

    public String supplierOrderId() {
        return supplierOrderId;
    }

    public FailureReason failure() {
        return failure;
    }

    public String summary() {
        return summary;
    }

    public void markOrder(String orderId) {
        this.orderId = orderId;
    }

    public void markStatus(IntentStatus next, String summary) {
        this.status = Objects.requireNonNull(next, "next");
        this.summary = Objects.requireNonNull(summary, "summary");
    }

    public void markCompleted(String supplierOrderId) {
        this.supplierOrderId = supplierOrderId;
        this.failure = null;
        markStatus(IntentStatus.COMPLETED, "completed");
    }

    public void markFailure(IntentStatus next, FailureReason failure) {
        this.failure = Objects.requireNonNull(failure, "failure");
        markStatus(next, failure.reason());
    }
}
