package com.roam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.roam.domain.AttemptOutcome;
import com.roam.domain.BookingOrder;
import com.roam.domain.BookingResult;
import com.roam.domain.IntentType;
import com.roam.domain.Money;
import com.roam.domain.OperationType;
import com.roam.domain.TravelPlan;
import com.roam.domain.TripRequestStatus;
import com.roam.service.RoamException;
import com.roam.service.WorkflowService;
import org.junit.jupiter.api.Test;

/** Ticket #14: submit in-budget flight and hotel Booking Orders. */
class BookingTest extends AcceptanceTestBase {

    private BookingOrder prepareFlightOrder(WorkflowService.WorkflowPreparation preparation) {
        TravelPlan plan = planning.get(preparation.planId(), employee);
        var option = plan.options().stream()
                .filter(candidate -> candidate.type() == IntentType.FLIGHT)
                .findFirst()
                .orElseThrow();
        planning.selectOption(plan.id(), option.id(), option.quote().id(), employee);
        return booking.prepareOrder(
                preparation.tripRequestId(), plan.id(), "flight-1",
                option.id(), option.quote().id(), employee);
    }

    @Test
    void flightAndHotelOrdersAreSubmittedWithSupplierIdentifiersAndRedactedLinks() {
        var result = run("session-happy", sampleTrip(), flight("flight-1"), hotel("hotel-1"));

        assertThat(result.workflowStatus().name()).as(result.summary()).isEqualTo("COMPLETED");
        assertThat(result.tripRequestStatus()).isEqualTo(TripRequestStatus.FULFILLED);
        assertThat(result.intents()).hasSize(2);
        assertThat(result.intents()).allMatch(intent -> intent.status().name().equals("COMPLETED"));
        assertThat(result.intents()).allMatch(intent -> intent.supplierOrderId() != null);

        assertThat(store.orders().values()).hasSize(2);
        for (BookingOrder order : store.orders().values()) {
            assertThat(order.state().name()).isEqualTo("SUBMITTED");
            assertThat(order.supplierOrderId()).startsWith("TN-");
            assertThat(order.paymentLinkReference()).startsWith("opaque:payment-link:");
            assertThat(order.supplierReferences().values())
                    .noneMatch(value -> value.contains("https://pay.tuniu.com"));
        }
        assertThat(audit.verifyIntegrity()).isTrue();
        assertThat(store.auditEvents())
                .anyMatch(event -> event.action().equals("BOOKING_ORDER_SUBMITTED"));
        assertThat(store.auditEvents())
                .anyMatch(event -> event.action().equals("SUPPLIER_BOOKING_RESPONSE"));
    }

    @Test
    void localOrderIsPersistedAsPendingBeforeTheFirstSupplierCall() {
        var preparation = prepareAndAssertApproved("session-persist", sampleTrip(), flight("flight-1"));
        BookingOrder order = prepareFlightOrder(preparation);
        final boolean[] observed = {false};
        adapter.setBeforeSupplierCall(() -> {
            BookingOrder atCall = store.orders().get(order.id());
            observed[0] = atCall != null && atCall.state().name().equals("PENDING");
        });

        booking.submit(order.id(), employee);

        assertThat(observed[0]).isTrue();
        assertThat(order.state().name()).isEqualTo("SUBMITTED");
    }

    @Test
    void budgetCeilingIsCheckedImmediatelyBeforeSupplierSubmission() {
        budget.setCeiling("emp-1", Money.cny("500"));
        var preparation = prepareAndAssertApproved("session-budget", sampleTrip(), flight("flight-1"));
        BookingOrder order = prepareFlightOrder(preparation);

        BookingResult result = booking.submit(order.id(), employee);

        assertThat(result.state().name()).isEqualTo("PENDING");
        assertThat(result.failure().type().name()).isEqualTo("OVER_BUDGET");
        assertThat(result.supplierCalls()).isZero();
        assertThat(adapter.callCount(order.id(), OperationType.BOOK)).isZero();
        assertThat(store.auditEvents())
                .anyMatch(event -> event.action().equals("BOOKING_BLOCKED_OVER_BUDGET"));
    }

    @Test
    void retriesAreCappedAtThreeSupplierCallsAndOrderStaysPending() {
        var preparation = prepareAndAssertApproved("session-retry", sampleTrip(), flight("flight-1"));
        BookingOrder order = prepareFlightOrder(preparation);
        fixtures.scriptBooking(
                order.id(),
                AttemptOutcome.RETRYABLE_FAILURE,
                AttemptOutcome.RETRYABLE_FAILURE,
                AttemptOutcome.RETRYABLE_FAILURE,
                AttemptOutcome.SUCCESS);

        BookingResult result = booking.submit(order.id(), employee);

        assertThat(result.state().name()).isEqualTo("PENDING");
        assertThat(result.supplierCalls()).isEqualTo(3);
        assertThat(adapter.callCount(order.id(), OperationType.BOOK)).isEqualTo(3);
        assertThat(order.attempts()).hasSize(3);
        assertThat(order.supplierOrderId()).isNull();
        assertThat(metrics.snapshot().retryDistribution()).containsEntry(3, 1L);
        assertThat(audit.verifyIntegrity()).isTrue();
    }

    @Test
    void uncertainOutcomeKeepsOrderPendingAndWarnsAboutDuplicateRisk() {
        var preparation = prepareAndAssertApproved("session-unknown", sampleTrip(), flight("flight-1"));
        BookingOrder order = prepareFlightOrder(preparation);
        fixtures.scriptBooking(
                order.id(),
                AttemptOutcome.UNKNOWN,
                AttemptOutcome.UNKNOWN,
                AttemptOutcome.UNKNOWN);

        BookingResult result = booking.submit(order.id(), employee);

        assertThat(result.state().name()).isEqualTo("PENDING");
        assertThat(result.duplicateRiskWarning()).isTrue();
        assertThat(result.failure().type().name()).isEqualTo("UNKNOWN_EXTERNAL_OUTCOME");
        assertThat(adapter.callCount(order.id(), OperationType.BOOK)).isEqualTo(3);
        assertThat(metrics.snapshot().unknownExternalOutcomes()).isGreaterThan(0);
        assertThat(store.auditEvents())
                .anyMatch(event -> event.action().equals("DUPLICATE_RISK_WARNING"));
    }

    @Test
    void uncertainThenSuccessfulRetryKeepsDuplicateWarningAndRecordsSupplierOrder() {
        var preparation = prepareAndAssertApproved("session-unknown-ok", sampleTrip(), flight("flight-1"));
        BookingOrder order = prepareFlightOrder(preparation);
        fixtures.scriptBooking(order.id(), AttemptOutcome.UNKNOWN, AttemptOutcome.SUCCESS);

        BookingResult result = booking.submit(order.id(), employee);

        assertThat(result.state().name()).isEqualTo("SUBMITTED");
        assertThat(result.duplicateRiskWarning()).isTrue();
        assertThat(result.supplierOrderId()).isNotBlank();
        assertThat(adapter.callCount(order.id(), OperationType.BOOK)).isEqualTo(2);
    }

    @Test
    void terminalSupplierFailureStopsImmediatelyAndStaysPending() {
        var preparation = prepareAndAssertApproved("session-terminal", sampleTrip(), flight("flight-1"));
        BookingOrder order = prepareFlightOrder(preparation);
        fixtures.scriptBooking(order.id(), AttemptOutcome.TERMINAL_FAILURE, AttemptOutcome.SUCCESS);

        BookingResult result = booking.submit(order.id(), employee);

        assertThat(result.state().name()).isEqualTo("PENDING");
        assertThat(result.failure().type().name()).isEqualTo("TERMINAL");
        assertThat(adapter.callCount(order.id(), OperationType.BOOK)).isEqualTo(1);
    }

    @Test
    void bookingRequiresApprovalAndExplicitSelectionAndOwnerAccess() {
        var preparation = prepareAndAssertApproved("session-guards", sampleTrip(), flight("flight-1"));
        TravelPlan plan = planning.get(preparation.planId(), employee);
        var option = plan.options().get(0);

        assertThatThrownBy(() -> booking.prepareOrder(
                preparation.tripRequestId(), plan.id(), "flight-1",
                option.id(), option.quote().id(), employee))
                .isInstanceOf(RoamException.class)
                .hasMessageContaining("select");

        planning.selectOption(plan.id(), option.id(), option.quote().id(), employee);
        BookingOrder order = booking.prepareOrder(
                preparation.tripRequestId(), plan.id(), "flight-1",
                option.id(), option.quote().id(), employee);
        booking.submit(order.id(), employee);

        assertThatThrownBy(() -> booking.order(order.id(), otherEmployee))
                .isInstanceOf(RoamException.class)
                .hasMessageContaining("own");
        assertThat(booking.order(order.id(), admin)).isNotNull();
    }
}
