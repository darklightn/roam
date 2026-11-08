package com.roam.domain;

import java.time.Instant;
import java.util.Objects;

/** Evidence that a specific Trip Request version was approved. */
public record ApprovalRecord(
        String id,
        String tripRequestId,
        int version,
        Instant decidedAt,
        String decidedBy,
        Role decidedByRole,
        boolean automatic,
        String basis) {

    public ApprovalRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tripRequestId, "tripRequestId");
        Objects.requireNonNull(decidedAt, "decidedAt");
        Objects.requireNonNull(decidedBy, "decidedBy");
        Objects.requireNonNull(decidedByRole, "decidedByRole");
        Objects.requireNonNull(basis, "basis");
    }
}
