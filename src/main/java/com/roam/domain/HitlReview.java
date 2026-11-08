package com.roam.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A durable human decision checkpoint. The first Administrator to claim owns it; unclaimed reviews
 * remain in the shared queue and are never auto-decided.
 */
public final class HitlReview {

    private final String id;
    private final ReviewType type;
    private final String subjectId;
    private final String tripRequestId;
    private final int tripRequestVersion;
    private final String requestedBy;
    private final Instant requestedAt;
    private ReviewStatus status = ReviewStatus.UNCLAIMED;
    private String claimedBy;
    private Instant claimedAt;
    private Decision decision;
    private String decisionReason;
    private String dataShownAtDecision;
    private Instant decidedAt;
    private int reminderCount;

    public HitlReview(
            String id,
            ReviewType type,
            String subjectId,
            String tripRequestId,
            int tripRequestVersion,
            String requestedBy,
            Instant requestedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.subjectId = Objects.requireNonNull(subjectId, "subjectId");
        this.tripRequestId = Objects.requireNonNull(tripRequestId, "tripRequestId");
        this.tripRequestVersion = tripRequestVersion;
        this.requestedBy = Objects.requireNonNull(requestedBy, "requestedBy");
        this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
    }

    public String id() {
        return id;
    }

    public ReviewType type() {
        return type;
    }

    public String subjectId() {
        return subjectId;
    }

    public String tripRequestId() {
        return tripRequestId;
    }

    public int tripRequestVersion() {
        return tripRequestVersion;
    }

    public String requestedBy() {
        return requestedBy;
    }

    public Instant requestedAt() {
        return requestedAt;
    }

    public ReviewStatus status() {
        return status;
    }

    public String claimedBy() {
        return claimedBy;
    }

    public Instant claimedAt() {
        return claimedAt;
    }

    public Decision decision() {
        return decision;
    }

    public String decisionReason() {
        return decisionReason;
    }

    public String dataShownAtDecision() {
        return dataShownAtDecision;
    }

    public Instant decidedAt() {
        return decidedAt;
    }

    public int reminderCount() {
        return reminderCount;
    }

    public synchronized void claim(String administrator, Instant now) {
        Objects.requireNonNull(administrator, "administrator");
        if (status != ReviewStatus.UNCLAIMED) {
            throw new IllegalStateException(
                    "review is already " + status + (claimedBy == null ? "" : " by " + claimedBy));
        }
        status = ReviewStatus.CLAIMED;
        claimedBy = administrator;
        claimedAt = now;
    }

    public synchronized void decide(
            String administrator,
            Decision decision,
            String reason,
            String dataShown,
            Instant now) {
        if (status == ReviewStatus.DECIDED || status == ReviewStatus.CANCELLED) {
            throw new IllegalStateException("review is already " + status);
        }
        if (status != ReviewStatus.CLAIMED) {
            throw new IllegalStateException("claim the review before deciding");
        }
        if (!claimedBy.equals(administrator)) {
            throw new IllegalStateException("another Administrator owns this review");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a decision reason is required");
        }
        this.decision = Objects.requireNonNull(decision, "decision");
        this.decisionReason = reason;
        this.dataShownAtDecision = dataShown == null ? "" : dataShown;
        this.decidedAt = now;
        this.status = ReviewStatus.DECIDED;
    }

    public synchronized void cancel(String actor) {
        if (!requestedBy.equals(actor)) {
            throw new IllegalStateException("only the requesting employee can cancel the review");
        }
        if (status == ReviewStatus.DECIDED) {
            throw new IllegalStateException("a decided review cannot be cancelled");
        }
        status = ReviewStatus.CANCELLED;
    }

    public synchronized int remind() {
        if (status != ReviewStatus.UNCLAIMED) {
            throw new IllegalStateException("only unclaimed reviews are reminded");
        }
        reminderCount++;
        return reminderCount;
    }

    public long ageSeconds(Instant now) {
        return Math.max(0, now.getEpochSecond() - requestedAt.getEpochSecond());
    }
}
