package com.roam.domain;

import java.util.List;
import java.util.Objects;

/** Employee-facing result of submitting a Trip Request for approval. */
public record SubmissionResult(
        String tripRequestId,
        int version,
        TripRequestStatus status,
        List<String> missingFields,
        List<Suggestion> suggestions,
        String nextStep,
        ApprovalRecord approvalRecord) {

    public SubmissionResult {
        Objects.requireNonNull(tripRequestId, "tripRequestId");
        missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        Objects.requireNonNull(nextStep, "nextStep");
    }
}
