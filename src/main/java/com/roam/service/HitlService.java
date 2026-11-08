package com.roam.service;

import com.roam.domain.Actor;
import com.roam.domain.BookingOrder;
import com.roam.domain.Decision;
import com.roam.domain.HitlReview;
import com.roam.domain.ReviewStatus;
import com.roam.domain.ReviewType;
import com.roam.domain.TripRequest;
import java.time.Clock;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Shared Administrator queue for both human decision types. The first claim wins; unclaimed reviews
 * stay pending and are never auto-decided.
 */
@Component
public class HitlService {

    private final InMemoryStore store;
    private final Ids ids;
    private final AuditService audit;
    private final AccessControl access;
    private final Clock clock;
    private final Map<ReviewType, ReviewDecisionHandler> handlers = new EnumMap<>(ReviewType.class);

    public HitlService(
            InMemoryStore store,
            Ids ids,
            AuditService audit,
            AccessControl access,
            Clock clock,
            List<ReviewDecisionHandler> handlers) {
        this.store = store;
        this.ids = ids;
        this.audit = audit;
        this.access = access;
        this.clock = clock;
        handlers.forEach(handler -> this.handlers.put(handler.supports(), handler));
    }

    public HitlReview requestTripReview(TripRequest trip, Actor employee) {
        if (trip.status() != com.roam.domain.TripRequestStatus.REJECTED) {
            throw RoamException.conflict("only a rejected Trip Request can request Administrator review");
        }
        HitlReview review = new HitlReview(
                ids.next("review"),
                ReviewType.TRIP_REQUEST_REVIEW,
                trip.id(),
                trip.id(),
                trip.currentVersionNumber(),
                employee.userId(),
                clock.instant());
        store.reviews().put(review.id(), review);
        trip.markAwaitingReview(review.id());
        audit.append(employee, "HITL_REVIEW_REQUESTED", "TripRequest", trip.id(),
                "version=" + review.tripRequestVersion(), "employee requested Administrator review");
        return review;
    }

    public HitlReview requestBudgetException(BookingOrder order, String workflowId, String employeeId) {
        HitlReview review = new HitlReview(
                ids.next("review"),
                ReviewType.BUDGET_EXCEPTION,
                order.id(),
                order.tripRequestId(),
                0,
                employeeId,
                clock.instant());
        store.reviews().put(review.id(), review);
        order.claimByWorkflow(workflowId);
        audit.append(Actor.system("master-agent"), "HITL_BUDGET_EXCEPTION_REQUESTED",
                "BookingOrder", order.id(),
                "price=" + order.price() + ";workflow=" + workflowId,
                "order exceeds Budget Ceiling");
        return review;
    }

    public List<HitlReview> queue(Actor administrator) {
        access.requireAdministrator(administrator);
        return store.reviews().values().stream()
                .filter(review -> review.status() == ReviewStatus.UNCLAIMED
                        || (review.status() == ReviewStatus.CLAIMED
                                && administrator.userId().equals(review.claimedBy())))
                .sorted(Comparator.comparing(HitlReview::requestedAt))
                .toList();
    }

    public HitlReview claim(String reviewId, Actor administrator) {
        access.requireAdministrator(administrator);
        HitlReview review = find(reviewId);
        review.claim(administrator.userId(), clock.instant());
        audit.append(administrator, "HITL_REVIEW_CLAIMED", "HitlReview", review.id(),
                "subject=" + review.subjectId(), null);
        return review;
    }

    public HitlReview decide(
            String reviewId, Actor administrator, Decision decision, String reason) {
        access.requireAdministrator(administrator);
        HitlReview review = find(reviewId);
        if (review.claimedBy() != null && !review.claimedBy().equals(administrator.userId())) {
            throw RoamException.forbidden("another Administrator owns this review");
        }
        if (review.status() != ReviewStatus.CLAIMED) {
            throw RoamException.conflict("claim the review before deciding");
        }
        if (reason == null || reason.isBlank()) {
            throw RoamException.invalid("a decision reason is required");
        }
        String dataShown = dataShown(review);
        review.decide(administrator.userId(), decision, reason, dataShown, clock.instant());
        audit.append(administrator, "HITL_DECISION", "HitlReview", review.id(),
                "subject=" + review.subjectId() + ";decision=" + decision
                        + ";dataShown=" + dataShown,
                reason);
        ReviewDecisionHandler handler = handlers.get(review.type());
        if (handler == null) {
            throw new IllegalStateException("no handler for " + review.type());
        }
        handler.onDecision(review);
        return review;
    }

    public HitlReview cancel(String reviewId, Actor employee) {
        HitlReview review = find(reviewId);
        if (!review.requestedBy().equals(employee.userId())) {
            throw RoamException.forbidden("only the requesting employee can cancel the review");
        }
        review.cancel(employee.userId());
        audit.append(employee, "HITL_REVIEW_CANCELLED", "HitlReview", review.id(),
                "subject=" + review.subjectId(), null);
        return review;
    }

    public int remindPending() {
        int reminded = 0;
        for (HitlReview review : store.reviews().values()) {
            if (review.status() == ReviewStatus.UNCLAIMED) {
                int count = review.remind();
                audit.append(Actor.system("master-agent"), "HITL_REMINDER", "HitlReview", review.id(),
                        "reminderCount=" + count, "review remains unclaimed");
                reminded++;
            }
        }
        return reminded;
    }

    public HitlReview find(String reviewId) {
        HitlReview review = store.reviews().get(reviewId);
        if (review == null) {
            throw RoamException.notFound("HitlReview");
        }
        return review;
    }

    public List<HitlReview> reviewsForTrip(String tripRequestId, Actor actor) {
        TripRequest trip = store.trips().get(tripRequestId);
        if (trip == null) {
            throw RoamException.notFound("TripRequest");
        }
        access.requireEmployeeOwnsTrip(actor, trip);
        return store.reviews().values().stream()
                .filter(review -> review.tripRequestId().equals(tripRequestId))
                .toList();
    }

    private String dataShown(HitlReview review) {
        if (review.type() == ReviewType.TRIP_REQUEST_REVIEW) {
            TripRequest trip = store.trips().get(review.tripRequestId());
            if (trip == null) {
                return "tripRequestVersion=" + review.tripRequestVersion();
            }
            return "tripRequestVersion=" + review.tripRequestVersion()
                    + ";status=" + trip.status()
                    + ";destination=" + trip.currentData().firstDestination();
        }
        BookingOrder order = store.orders().get(review.subjectId());
        if (order == null) {
            return "bookingOrder=" + review.subjectId();
        }
        return "bookingOrder=" + order.id()
                + ";state=" + order.state()
                + ";price=" + order.price()
                + ";type=" + order.type();
    }
}
