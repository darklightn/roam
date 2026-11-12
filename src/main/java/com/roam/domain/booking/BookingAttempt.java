package com.roam.domain.booking;

import java.time.Instant;
import java.util.Objects;

/** Append-only evidence for one supplier call. */
public record BookingAttempt(
        String attemptId,
        OperationType operation,
        AttemptOutcome outcome,
        String supplierCode,
        Instant occurredAt,
        String detail) {

    public BookingAttempt {
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(detail, "detail");
    }
}
