package com.roam.domain.audit;

import com.roam.domain.shared.Role;
import java.time.Instant;
import java.util.Objects;

/** Append-only audit evidence. The persistence layer alone writes these records. */
public record AuditEvent(
        long sequence,
        String id,
        Instant occurredAt,
        String actorId,
        Role actorRole,
        String action,
        String subjectType,
        String subjectId,
        String detail,
        String reason,
        String previousHash,
        String hash) {

    public AuditEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(actorRole, "actorRole");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(subjectType, "subjectType");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(previousHash, "previousHash");
        Objects.requireNonNull(hash, "hash");
    }
}
