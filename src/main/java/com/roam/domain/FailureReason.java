package com.roam.domain;

import java.time.Instant;
import java.util.Objects;

/** Structured failure evidence shared by every workflow path. */
public record FailureReason(
        FailureType type,
        String reason,
        String supplierCode,
        Instant occurredAt,
        String attemptId) {

    public FailureReason {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
