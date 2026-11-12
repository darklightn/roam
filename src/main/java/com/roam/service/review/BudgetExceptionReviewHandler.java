package com.roam.service.review;

import com.roam.domain.booking.BookingOrder;
import com.roam.domain.review.Decision;
import com.roam.domain.review.HitlReview;
import com.roam.domain.review.ReviewType;
import com.roam.domain.shared.Actor;
import com.roam.domain.shared.FailureReason;
import com.roam.domain.shared.FailureType;
import com.roam.domain.trip.TripRequest;
import com.roam.domain.trip.TripRequestStatus;
import com.roam.domain.workflow.IntentRecord;
import com.roam.domain.workflow.IntentStatus;
import com.roam.domain.workflow.WorkflowInstance;
import com.roam.domain.workflow.WorkflowStatus;
import com.roam.infrastructure.persistence.InMemoryStore;
import com.roam.service.BookingService;
import com.roam.service.audit.AuditService;
import com.roam.service.dto.BookingResult;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * Applies a budget-exception decision to exactly one Booking Order. It never changes Trip Request
 * approval and never resumes any sibling order.
 */
@Component
public class BudgetExceptionReviewHandler implements ReviewDecisionHandler {

    private final InMemoryStore store;
    private final BookingService booking;
    private final AuditService audit;
    private final Clock clock;

    public BudgetExceptionReviewHandler(
            InMemoryStore store, BookingService booking, AuditService audit, Clock clock) {
        this.store = store;
        this.booking = booking;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public ReviewType supports() {
        return ReviewType.BUDGET_EXCEPTION;
    }

    @Override
    public void onDecision(HitlReview review) {
        Actor administrator = Actor.administrator(review.claimedBy());
        var order = store.orders().get(review.subjectId());
        if (order == null) {
            throw new IllegalStateException("Booking Order missing for review " + review.id());
        }
        WorkflowInstance workflow = order.claimedByWorkflow() == null
                ? null : store.workflows().get(order.claimedByWorkflow());
        IntentRecord intent = workflow == null ? null : workflow.intent(order.intentId());
        if (review.decision() == Decision.APPROVED) {
            BookingResult result = booking.resumeAfterBudgetApproval(order.id(), administrator);
            if (intent != null) {
                if (result.state() == com.roam.domain.booking.BookingOrderState.SUBMITTED) {
                    intent.markCompleted(result.supplierOrderId());
                } else {
                    intent.markFailure(IntentStatus.FAILED, result.failure());
                }
            }
            audit.append(administrator, "BUDGET_EXCEPTION_APPROVED", "BookingOrder", order.id(),
                    "state=" + result.state() + ";onlyThisOrder=true",
                    review.decisionReason());
        } else {
            FailureReason failure = new FailureReason(
                    FailureType.OVER_BUDGET,
                    "Administrator rejected the budget exception",
                    "BUDGET_REJECTED",
                    clock.instant(),
                    review.id());
            order.recordFailure(failure, clock.instant());
            if (intent != null) {
                intent.markFailure(IntentStatus.FAILED, failure);
            }
            audit.append(administrator, "BUDGET_EXCEPTION_REJECTED", "BookingOrder", order.id(),
                    "state=" + order.state() + ";unsubmitted=true",
                    review.decisionReason());
        }
        if (workflow != null) {
            refreshWorkflow(workflow);
        }
    }

    private void refreshWorkflow(WorkflowInstance workflow) {
        TripRequest trip = store.trips().get(workflow.tripRequestId());
        if (workflow.allCompleted()) {
            workflow.setStatus(WorkflowStatus.COMPLETED);
            if (trip != null && trip.isApprovedForCurrentVersion()) {
                trip.markExecution(TripRequestStatus.FULFILLED);
            }
        } else if (workflow.anyWaiting()) {
            workflow.setStatus(WorkflowStatus.WAITING_HITL);
        } else if (workflow.anyCompleted() && workflow.anyFailed()) {
            workflow.setStatus(WorkflowStatus.PARTIALLY_FAILED);
            if (trip != null && trip.isApprovedForCurrentVersion()) {
                trip.markExecution(TripRequestStatus.PARTIALLY_FULFILLED);
            }
        } else if (workflow.anyFailed()) {
            workflow.setStatus(WorkflowStatus.FAILED);
            if (trip != null && trip.isApprovedForCurrentVersion()) {
                trip.markExecution(TripRequestStatus.FAILED);
            }
        }
    }
}
