package com.roam.domain.workflow;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Durable workflow coordination state, separate from AgentScope execution messages. */
public final class WorkflowInstance {

    private final String id;
    private final String sessionId;
    private final String tripRequestId;
    private final Instant createdAt;
    private final Map<String, IntentRecord> intents = new LinkedHashMap<>();
    private final Map<String, com.roam.domain.workflow.IntentRequest> requests = new LinkedHashMap<>();
    private WorkflowStatus status = WorkflowStatus.IN_PROGRESS;
    private String ownerAdministrator;
    private String summary;

    public WorkflowInstance(String id, String sessionId, String tripRequestId, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.tripRequestId = Objects.requireNonNull(tripRequestId, "tripRequestId");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.summary = "in progress";
    }

    public String id() {
        return id;
    }

    public String sessionId() {
        return sessionId;
    }

    public String tripRequestId() {
        return tripRequestId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public WorkflowStatus status() {
        return status;
    }

    public void setStatus(WorkflowStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    public String ownerAdministrator() {
        return ownerAdministrator;
    }

    public void takeOver(String administrator, String reason) {
        this.ownerAdministrator = Objects.requireNonNull(administrator, "administrator");
        this.status = WorkflowStatus.TAKEN_OVER;
        this.summary = "taken over: " + reason;
    }

    public void setSummary(String summary) {
        this.summary = Objects.requireNonNull(summary, "summary");
    }

    public String summary() {
        return summary;
    }

    public void addIntent(IntentRecord record, com.roam.domain.workflow.IntentRequest request) {
        intents.put(record.id(), record);
        requests.put(record.id(), request);
    }

    public com.roam.domain.workflow.IntentRequest request(String intentId) {
        com.roam.domain.workflow.IntentRequest request = requests.get(intentId);
        if (request == null) {
            throw new IllegalArgumentException("unknown intent " + intentId);
        }
        return request;
    }

    public List<com.roam.domain.workflow.IntentRequest> requests() {
        return List.copyOf(requests.values());
    }

    public IntentRecord intent(String intentId) {
        IntentRecord record = intents.get(intentId);
        if (record == null) {
            throw new IllegalArgumentException("unknown intent " + intentId);
        }
        return record;
    }

    public List<IntentRecord> intents() {
        return List.copyOf(intents.values());
    }

    public boolean allCompleted() {
        return !intents.isEmpty()
                && intents.values().stream().allMatch(i -> i.status() == IntentStatus.COMPLETED);
    }

    public boolean anyCompleted() {
        return intents.values().stream().anyMatch(i -> i.status() == IntentStatus.COMPLETED);
    }

    public boolean anyWaiting() {
        return intents.values().stream().anyMatch(i -> i.status() == IntentStatus.WAITING_HITL
                || i.status() == IntentStatus.PENDING
                || i.status() == IntentStatus.WAITING_DEPENDENCY);
    }

    public boolean anyUnknown() {
        return intents.values().stream()
                .anyMatch(i -> i.status() == IntentStatus.UNKNOWN_EXTERNAL_OUTCOME);
    }

    public boolean anyFailed() {
        return intents.values().stream().anyMatch(i -> i.status() == IntentStatus.FAILED);
    }
}
