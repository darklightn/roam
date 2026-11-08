package com.roam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.roam.domain.Actor;
import com.roam.domain.BookingOrder;
import com.roam.domain.Decision;
import com.roam.domain.HitlReview;
import com.roam.domain.Money;
import com.roam.domain.ReviewStatus;
import com.roam.domain.ReviewType;
import com.roam.domain.TripRequest;
import com.roam.domain.TripRequestStatus;
import com.roam.service.RoamException;
import com.roam.service.WorkflowService;
import org.junit.jupiter.api.Test;

/** Ticket #15: pause and resume an over-budget Booking Order. */
class BudgetExceptionTest extends AcceptanceTestBase {

    private HitlReview budgetReview(String orderId) {
        return store.reviews().values().stream()
                .filter(review -> review.type() == ReviewType.BUDGET_EXCEPTION
                        && review.subjectId().equals(orderId))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void overBudgetOrderIsBlockedBeforeSupplierCallAndCreatesASharedReview() {
        budget.setCeiling("emp-1", Money.cny("500"));
        WorkflowService.WorkflowPreparation preparation =
                prepareAndAssertApproved("session-budget-pause", sampleTrip(), flight("flight-1"));
        var result = workflow.execute(preparation.workflowId(), employee);

        BookingOrder order = store.orders().values().iterator().next();
        assertThat(order.state().name()).isEqualTo("PENDING");
        assertThat(order.lastFailure().type().name()).isEqualTo("OVER_BUDGET");
        assertThat(result.workflowStatus().name()).isEqualTo("WAITING_HITL");
        assertThat(result.intents().get(0).status().name()).isEqualTo("WAITING_HITL");
        assertThat(adapter.callCount(order.id(), com.roam.domain.OperationType.BOOK)).isZero();
        HitlReview review = budgetReview(order.id());
        assertThat(review.status()).isEqualTo(ReviewStatus.UNCLAIMED);
        assertThat(hitl.queue(admin)).hasSize(1);
        assertThat(hitl.queue(secondAdmin)).hasSize(1);
    }

    @Test
    void approvalResumesOnlyTheReviewedOrderAndDoesNotChangeTripApproval() {
        budget.setCeiling("emp-1", Money.cny("500"));
        WorkflowService.WorkflowPreparation preparation =
                prepareAndAssertApproved("session-budget-approve", sampleTrip(), flight("flight-1"));
        workflow.execute(preparation.workflowId(), employee);
        BookingOrder order = store.orders().values().iterator().next();
        TripRequest trip = tripRequests.get(preparation.tripRequestId(), employee);
        int approvalsBefore = trip.approvalRecords().size();

        HitlReview review = budgetReview(order.id());
        hitl.claim(review.id(), admin);
        hitl.decide(review.id(), admin, Decision.APPROVED, "Client escalation; allow this fare");

        assertThat(order.state().name()).isEqualTo("SUBMITTED");
        assertThat(order.supplierOrderId()).isNotBlank();
        assertThat(trip.approvalRecords()).hasSize(approvalsBefore);
        assertThat(trip.isApprovedForCurrentVersion()).isTrue();
        assertThat(trip.status()).isEqualTo(TripRequestStatus.FULFILLED);
        assertThat(store.auditEvents())
                .anyMatch(event -> event.action().equals("BUDGET_EXCEPTION_APPROVED")
                        && event.reason().contains("Client escalation"));
        assertThat(store.auditEvents())
                .anyMatch(event -> event.action().equals("HITL_DECISION")
                        && event.detail().contains("bookingOrder=" + order.id()));
    }

    @Test
    void rejectionLeavesTheOrderPendingAndUnsubmitted() {
        budget.setCeiling("emp-1", Money.cny("500"));
        WorkflowService.WorkflowPreparation preparation =
                prepareAndAssertApproved("session-budget-reject", sampleTrip(), flight("flight-1"));
        workflow.execute(preparation.workflowId(), employee);
        BookingOrder order = store.orders().values().iterator().next();
        TripRequest trip = tripRequests.get(preparation.tripRequestId(), employee);

        HitlReview review = budgetReview(order.id());
        hitl.claim(review.id(), admin);
        hitl.decide(review.id(), admin, Decision.REJECTED, "Fare exceeds policy for this route");

        assertThat(order.state().name()).isEqualTo("PENDING");
        assertThat(adapter.callCount(order.id(), com.roam.domain.OperationType.BOOK)).isZero();
        assertThat(store.workflows().values().iterator().next().intent("flight-1").status().name())
                .isEqualTo("FAILED");
        assertThat(trip.isApprovedForCurrentVersion()).isTrue();
        assertThat(trip.status()).isEqualTo(TripRequestStatus.FAILED);
    }

    @Test
    void decisionRequiresAReasonAndOnlyTheClaimingAdministratorCanDecide() {
        budget.setCeiling("emp-1", Money.cny("500"));
        WorkflowService.WorkflowPreparation preparation =
                prepareAndAssertApproved("session-budget-guard", sampleTrip(), flight("flight-1"));
        workflow.execute(preparation.workflowId(), employee);
        BookingOrder order = store.orders().values().iterator().next();
        HitlReview review = budgetReview(order.id());
        hitl.claim(review.id(), admin);

        assertThatThrownBy(() -> hitl.claim(review.id(), secondAdmin))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> hitl.decide(review.id(), admin, Decision.APPROVED, "  "))
                .isInstanceOf(RoamException.class)
                .hasMessageContaining("reason");
        assertThatThrownBy(() -> hitl.decide(
                review.id(), secondAdmin, Decision.APPROVED, "not mine"))
                .isInstanceOf(RoamException.class);
        assertThat(review.status()).isEqualTo(ReviewStatus.CLAIMED);
    }

    @Test
    void approvalOfOneOrderDoesNotResumeItsSiblingOrder() {
        budget.setCeiling("emp-1", Money.cny("300"));
        WorkflowService.WorkflowPreparation preparation = prepareAndAssertApproved(
                "session-budget-sibling", sampleTrip(), flight("flight-1"), hotel("hotel-1"));
        workflow.execute(preparation.workflowId(), employee);

        BookingOrder flightOrder = store.orders().values().stream()
                .filter(order -> order.type() == com.roam.domain.IntentType.FLIGHT)
                .findFirst().orElseThrow();
        BookingOrder hotelOrder = store.orders().values().stream()
                .filter(order -> order.type() == com.roam.domain.IntentType.HOTEL)
                .findFirst().orElseThrow();
        HitlReview flightReview = budgetReview(flightOrder.id());
        hitl.claim(flightReview.id(), admin);
        hitl.decide(flightReview.id(), admin, Decision.APPROVED, "Flight exception approved");

        assertThat(flightOrder.state().name()).isEqualTo("SUBMITTED");
        assertThat(hotelOrder.state().name()).isEqualTo("PENDING");
        assertThat(store.reviews().values().stream()
                .filter(review -> review.type() == ReviewType.BUDGET_EXCEPTION)
                .filter(review -> review.status() == ReviewStatus.UNCLAIMED)
                .count()).isEqualTo(1);
        assertThat(store.workflows().values().iterator().next().status().name())
                .isEqualTo("WAITING_HITL");
    }

    @Test
    void pendingReviewRemainsPendingWithReminders() {
        budget.setCeiling("emp-1", Money.cny("500"));
        WorkflowService.WorkflowPreparation preparation =
                prepareAndAssertApproved("session-budget-remind", sampleTrip(), flight("flight-1"));
        workflow.execute(preparation.workflowId(), employee);
        BookingOrder order = store.orders().values().iterator().next();
        HitlReview review = budgetReview(order.id());

        assertThat(hitl.remindPending()).isEqualTo(1);
        assertThat(review.status()).isEqualTo(ReviewStatus.UNCLAIMED);
        assertThat(review.reminderCount()).isEqualTo(1);
        clock.advanceSeconds(120);
        assertThat(alerts.evaluate()).anyMatch(alert -> alert.code().equals("HITL_AGING"));
    }

    @Test
    void selfReviewIsAllowedAndAudited() {
        budget.setCeiling("admin-1", Money.cny("500"));
        Actor self = Actor.employee("admin-1");
        var preparation = workflow.prepare(pkg("session-self", sampleTrip(), flight("flight-1")), self);
        workflow.execute(preparation.workflowId(), self);
        BookingOrder order = store.orders().values().iterator().next();
        HitlReview review = budgetReview(order.id());

        hitl.claim(review.id(), admin);
        hitl.decide(review.id(), admin, Decision.APPROVED, "Self-review recorded");

        assertThat(order.state().name()).isEqualTo("SUBMITTED");
        assertThat(store.auditEvents())
                .anyMatch(event -> event.action().equals("HITL_DECISION")
                        && event.actorId().equals("admin-1"));
    }
}
