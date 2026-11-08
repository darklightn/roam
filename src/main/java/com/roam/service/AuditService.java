package com.roam.service;

import com.roam.domain.Actor;
import com.roam.domain.AuditEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * The only writer of audit events. There is deliberately no update or delete operation. Events form
 * a hash chain so tampering is detectable.
 */
@Component
public class AuditService {

    private final InMemoryStore store;
    private final Ids ids;
    private final Redaction redaction;
    private final Clock clock;

    public AuditService(InMemoryStore store, Ids ids, Redaction redaction, Clock clock) {
        this.store = store;
        this.ids = ids;
        this.redaction = redaction;
        this.clock = clock;
    }

    public AuditEvent append(
            Actor actor,
            String action,
            String subjectType,
            String subjectId,
            String detail,
            String reason) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        synchronized (store.auditSink()) {
            Instant now = clock.instant();
            String id = ids.next("audit");
            String redactedDetail = redaction.redactText(detail == null ? "" : detail);
            String redactedReason = redaction.redactText(reason == null ? "" : reason);
            List<AuditEvent> events = store.auditSink();
            long sequence = events.size() + 1L;
            String previousHash = events.isEmpty() ? "GENESIS" : events.get(events.size() - 1).hash();
            String hash = hash(sequence, id, now, actor, action, subjectType, subjectId,
                    redactedDetail, redactedReason, previousHash);
            AuditEvent event = new AuditEvent(
                    sequence, id, now, actor.userId(), actor.role(), action, subjectType, subjectId,
                    redactedDetail, redactedReason, previousHash, hash);
            events.add(event);
            return event;
        }
    }

    public List<AuditEvent> forSubject(Actor actor, String subjectId) {
        requireAdministrator(actor);
        return store.auditSink().stream()
                .filter(event -> event.subjectId().equals(subjectId))
                .toList();
    }

    public List<AuditEvent> all(Actor actor) {
        requireAdministrator(actor);
        return List.copyOf(store.auditSink());
    }

    public boolean verifyIntegrity() {
        List<AuditEvent> events = store.auditSink();
        String previous = "GENESIS";
        long expectedSequence = 1;
        for (AuditEvent event : events) {
            if (event.sequence() != expectedSequence
                    || !event.previousHash().equals(previous)) {
                return false;
            }
            String recomputed = hash(event.sequence(), event.id(), event.occurredAt(),
                    new Actor(event.actorId(), event.actorRole()), event.action(),
                    event.subjectType(), event.subjectId(), event.detail(), event.reason(),
                    event.previousHash());
            if (!recomputed.equals(event.hash())) {
                return false;
            }
            previous = event.hash();
            expectedSequence++;
        }
        return true;
    }

    public int size() {
        return store.auditSink().size();
    }

    private static String hash(
            long sequence,
            String id,
            Instant at,
            Actor actor,
            String action,
            String subjectType,
            String subjectId,
            String detail,
            String reason,
            String previousHash) {
        String content = String.join("|",
                Long.toString(sequence), id, at.toString(), actor.userId(), actor.role().name(),
                action, subjectType, subjectId, detail, reason, previousHash);
        return Redaction.sha256(content);
    }

    private void requireAdministrator(Actor actor) {
        if (!actor.isAdministrator()) {
            throw RoamException.forbidden("only Administrators may read audit events");
        }
    }
}
