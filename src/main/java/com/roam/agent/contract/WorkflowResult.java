package com.roam.agent.contract;

import com.roam.domain.trip.TripRequestStatus;
import com.roam.domain.workflow.IntentStatus;
import com.roam.domain.workflow.WorkflowStatus;
import java.util.List;
import java.util.Objects;

/** Employee-facing aggregate result. Completion is never claimed early. */
public record WorkflowResult(
        String workflowId,
        String sessionId,
        String tripRequestId,
        TripRequestStatus tripRequestStatus,
        WorkflowStatus workflowStatus,
        int planVersion,
        String planId,
        String selectedOptionId,
        List<IntentResultView> intents,
        String summary) {

    public WorkflowResult {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(tripRequestId, "tripRequestId");
        Objects.requireNonNull(tripRequestStatus, "tripRequestStatus");
        Objects.requireNonNull(workflowStatus, "workflowStatus");
        intents = intents == null ? List.of() : List.copyOf(intents);
        Objects.requireNonNull(summary, "summary");
    }

    public long completedCount() {
        return intents.stream().filter(i -> i.status() == IntentStatus.COMPLETED).count();
    }

    public long failedCount() {
        return intents.stream().filter(i -> i.status() == IntentStatus.FAILED).count();
    }

    public long unknownCount() {
        return intents.stream()
                .filter(i -> i.status() == IntentStatus.UNKNOWN_EXTERNAL_OUTCOME)
                .count();
    }
}
