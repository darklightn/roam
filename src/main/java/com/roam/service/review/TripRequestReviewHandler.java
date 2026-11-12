package com.roam.service.review;

import com.roam.domain.review.Decision;
import com.roam.domain.review.HitlReview;
import com.roam.domain.review.ReviewType;
import com.roam.domain.shared.Actor;
import com.roam.domain.shared.Role;
import com.roam.domain.trip.ApprovalRecord;
import com.roam.domain.trip.TripRequest;
import com.roam.infrastructure.persistence.InMemoryStore;
import com.roam.service.audit.AuditService;
import com.roam.shared.Ids;
import org.springframework.stereotype.Component;

/** Applies a human Trip Request decision. It never rewrites the reviewed version. */
@Component
public class TripRequestReviewHandler implements ReviewDecisionHandler {

    private final InMemoryStore store;
    private final Ids ids;
    private final AuditService audit;

    public TripRequestReviewHandler(InMemoryStore store, Ids ids, AuditService audit) {
        this.store = store;
        this.ids = ids;
        this.audit = audit;
    }

    @Override
    public ReviewType supports() {
        return ReviewType.TRIP_REQUEST_REVIEW;
    }

    @Override
    public void onDecision(HitlReview review) {
        TripRequest trip = store.trips().get(review.tripRequestId());
        if (trip == null) {
            throw new IllegalStateException("Trip Request missing for review " + review.id());
        }
        Actor administrator = Actor.administrator(review.claimedBy());
        if (review.decision() == Decision.APPROVED) {
            ApprovalRecord approval = new ApprovalRecord(
                    ids.next("approval"),
                    trip.id(),
                    review.tripRequestVersion(),
                    review.decidedAt(),
                    review.claimedBy(),
                    Role.ADMINISTRATOR,
                    false,
                    "Administrator review of version " + review.tripRequestVersion());
            trip.markApproved(approval);
            audit.append(administrator, "TRIP_REQUEST_APPROVED_BY_ADMIN", "TripRequest", trip.id(),
                    "version=" + review.tripRequestVersion() + ";review=" + review.id(),
                    review.decisionReason());
        } else {
            trip.markRejected();
            audit.append(administrator, "TRIP_REQUEST_REJECTED_BY_ADMIN", "TripRequest", trip.id(),
                    "version=" + review.tripRequestVersion() + ";review=" + review.id(),
                    review.decisionReason());
        }
    }
}
