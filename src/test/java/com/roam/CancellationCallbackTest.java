package com.roam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.roam.domain.AttemptOutcome;
import com.roam.domain.BookingOrder;
import com.roam.domain.BookingResult;
import com.roam.domain.CallbackObservation;
import com.roam.domain.CallbackRequest;
import com.roam.domain.CallbackStatus;
import com.roam.domain.IntentType;
import com.roam.domain.OperationType;
import com.roam.domain.WorkflowResult;
import com.roam.service.CallbackSignatures;
import com.roam.service.RoamException;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Ticket #17: cancel supported orders and observe payment callbacks. */
class CancellationCallbackTest extends AcceptanceTestBase {

    private BookingOrder orderOfType(IntentType type) {
        return store.orders().values().stream()
                .filter(order -> order.type() == type)
                .findFirst()
                .orElseThrow();
    }

    private CallbackRequest callback(String callbackId, String supplierOrderId, String status) {
        return new CallbackRequest(
                callbackId,
                "TUNIU",
                supplierOrderId,
                status,
                new BigDecimal("880.00"),
                "CNY",
                CallbackSignatures.sign(callbackId),
                Map.of("contact", "li.wei@example.com", "phone", "13812345678",
                        "payUrl", "https://pay.tuniu.com/flight/secret"));
    }

    @Test
    void confirmedUnpaidFlightCancellationMovesOrderToCancelled() {
        WorkflowResult result = run("session-cancel", sampleTrip(), flight("flight-1"));
        BookingOrder order = orderOfType(IntentType.FLIGHT);

        BookingResult cancellation = booking.cancel(order.id(), employee);

        assertThat(cancellation.state().name()).isEqualTo("CANCELLED");
        assertThat(order.state().name()).isEqualTo("CANCELLED");
        assertThat(adapter.callCount(order.id(), OperationType.CANCEL)).isEqualTo(1);
        assertThat(store.auditEvents())
                .anyMatch(event -> event.action().equals("BOOKING_ORDER_CANCELLED"));
        assertThat(audit.verifyIntegrity()).isTrue();
        assertThat(result.tripRequestId()).isNotBlank();
        assertThat(tripRequests.get(result.tripRequestId(), employee).status().name())
                .isEqualTo("CANCELLED");
        assertThat(store.workflows().get(result.workflowId()).status().name())
                .isEqualTo("CANCELLED");
    }

    @Test
    void explicitAlreadyCancelledSupplierResponseIsAccepted() {
        run("session-already", sampleTrip(), flight("flight-1"));
        BookingOrder order = orderOfType(IntentType.FLIGHT);
        fixtures.scriptCancellation(order.id(), AttemptOutcome.ALREADY_CANCELLED);

        BookingResult cancellation = booking.cancel(order.id(), employee);

        assertThat(cancellation.state().name()).isEqualTo("CANCELLED");
        assertThat(order.attempts()).anyMatch(attempt ->
                attempt.operation() == OperationType.CANCEL
                        && attempt.outcome() == AttemptOutcome.ALREADY_CANCELLED);
    }

    @Test
    void cancellationRetriesAreBoundedAndTransientFailureCanRecover() {
        run("session-cancel-retry", sampleTrip(), flight("flight-1"));
        BookingOrder order = orderOfType(IntentType.FLIGHT);
        fixtures.scriptCancellation(
                order.id(), AttemptOutcome.RETRYABLE_FAILURE, AttemptOutcome.SUCCESS);

        BookingResult cancellation = booking.cancel(order.id(), employee);

        assertThat(cancellation.state().name()).isEqualTo("CANCELLED");
        assertThat(adapter.callCount(order.id(), OperationType.CANCEL)).isEqualTo(2);
    }

    @Test
    void uncertainCancellationLeavesTheLastConfirmedStateUnchanged() {
        run("session-cancel-timeout", sampleTrip(), flight("flight-1"));
        BookingOrder order = orderOfType(IntentType.FLIGHT);
        fixtures.scriptCancellation(
                order.id(),
                AttemptOutcome.UNKNOWN,
                AttemptOutcome.UNKNOWN,
                AttemptOutcome.UNKNOWN);

        BookingResult cancellation = booking.cancel(order.id(), employee);

        assertThat(cancellation.state().name()).isEqualTo("SUBMITTED");
        assertThat(order.state().name()).isEqualTo("SUBMITTED");
        assertThat(cancellation.failure().type().name()).isEqualTo("UNKNOWN_EXTERNAL_OUTCOME");
        assertThat(adapter.callCount(order.id(), OperationType.CANCEL)).isEqualTo(3);
        assertThat(store.auditEvents())
                .noneMatch(event -> event.action().equals("BOOKING_ORDER_CANCELLED"));
    }

    @Test
    void hotelCancellationIsClearlyUnsupportedAndNeverCallsTheSupplier() {
        run("session-cancel-hotel", sampleTrip(), flight("flight-1"), hotel("hotel-1"));
        BookingOrder hotelOrder = orderOfType(IntentType.HOTEL);

        BookingResult cancellation = booking.cancel(hotelOrder.id(), employee);

        assertThat(cancellation.state().name()).isEqualTo("SUBMITTED");
        assertThat(cancellation.failure().type().name()).isEqualTo("UNSUPPORTED");
        assertThat(cancellation.failure().reason()).contains("hotel");
        assertThat(adapter.callCount(hotelOrder.id(), OperationType.CANCEL)).isZero();
        assertThat(hotelOrder.state().name()).isEqualTo("SUBMITTED");
    }

    @Test
    void paidOrderCancellationIsUnsupportedAfterObservedPaymentCallback() {
        run("session-cancel-paid", sampleTrip(), flight("flight-1"));
        BookingOrder order = orderOfType(IntentType.FLIGHT);
        booking.recordPaymentCallback(callback("cb-paid", order.supplierOrderId(), "PAID"));

        BookingResult cancellation = booking.cancel(order.id(), employee);

        assertThat(cancellation.state().name()).isEqualTo("SUBMITTED");
        assertThat(cancellation.failure().type().name()).isEqualTo("UNSUPPORTED");
        assertThat(adapter.callCount(order.id(), OperationType.CANCEL)).isZero();
    }

    @Test
    void partialCancellationPreservesSiblingOrdersWithoutCompensation() {
        WorkflowResult result = run("session-cancel-partial", sampleTrip(), flight("flight-1"), hotel("hotel-1"));
        BookingOrder flightOrder = orderOfType(IntentType.FLIGHT);
        BookingOrder hotelOrder = orderOfType(IntentType.HOTEL);

        booking.cancel(flightOrder.id(), employee);

        assertThat(flightOrder.state().name()).isEqualTo("CANCELLED");
        assertThat(hotelOrder.state().name()).isEqualTo("SUBMITTED");
        assertThat(adapter.callCount(hotelOrder.id(), OperationType.CANCEL)).isZero();
        assertThat(store.auditEvents())
                .anyMatch(event -> event.action().equals("BOOKING_ORDER_CANCELLED")
                        && event.subjectId().equals(flightOrder.id()));
        assertThat(tripRequests.get(result.tripRequestId(), employee).status().name())
                .isEqualTo("PARTIALLY_FULFILLED");
        assertThat(store.workflows().get(result.workflowId()).status().name())
                .isEqualTo("PARTIALLY_COMPLETED");
    }

    @Test
    void paymentCallbackIsValidatedDeduplicatedAndRecordedAsObservationOnly() {
        run("session-callback", sampleTrip(), flight("flight-1"));
        BookingOrder order = orderOfType(IntentType.FLIGHT);
        String rawPayUrl = "https://pay.tuniu.com/flight/" + order.supplierOrderId();

        CallbackObservation received =
                booking.recordPaymentCallback(callback("cb-1", order.supplierOrderId(), "PAID"));
        CallbackObservation duplicate =
                booking.recordPaymentCallback(callback("cb-1", order.supplierOrderId(), "PAID"));
        CallbackObservation invalid =
                booking.recordPaymentCallback(new CallbackRequest(
                        "cb-2", "TUNIU", order.supplierOrderId(), "PAID",
                        BigDecimal.TEN, "CNY", "bad-signature", Map.of()));

        assertThat(received.status()).isEqualTo(CallbackStatus.RECEIVED);
        assertThat(received.redactedDetail()).doesNotContain("li.wei@example.com");
        assertThat(received.redactedDetail()).doesNotContain("13812345678");
        assertThat(received.redactedDetail()).doesNotContain(rawPayUrl);
        assertThat(duplicate.status()).isEqualTo(CallbackStatus.DUPLICATE);
        assertThat(invalid.status()).isEqualTo(CallbackStatus.INVALID);
        assertThat(order.state().name()).isEqualTo("SUBMITTED");
        assertThat(order.paidObserved()).isTrue();
        assertThat(metrics.snapshot().callbacksReceived()).isEqualTo(1);
        assertThat(metrics.snapshot().callbacksDeduplicated()).isEqualTo(1);
        assertThat(metrics.snapshot().callbacksInvalid()).isEqualTo(1);
        assertThat(store.auditEvents())
                .noneMatch(event -> event.action().equals("BOOKING_ORDER_CANCELLED"));
        assertThat(alerts.evaluate())
                .anyMatch(alert -> alert.code().equals("INVALID_OR_DUPLICATE_CALLBACK"));
    }

    @Test
    void callbackValidationRejectsUnknownOrdersAndDoesNotPersistRawPayload() {
        run("session-callback-unknown", sampleTrip(), flight("flight-1"));
        BookingOrder order = orderOfType(IntentType.FLIGHT);

        CallbackObservation unknownOrder =
                booking.recordPaymentCallback(callback("cb-x", "TN-UNKNOWN", "PAID"));

        assertThat(unknownOrder.status()).isEqualTo(CallbackStatus.INVALID);
        assertThat(store.callbacks().values())
                .allMatch(observation -> !observation.redactedDetail().contains("li.wei@example.com"));
        assertThat(order.state().name()).isEqualTo("SUBMITTED");
    }

    @Test
    void changeAndRefundRequestsAreReportedAsUnsupportedWithoutChangingState() {
        run("session-unsupported-change", sampleTrip(), flight("flight-1"));
        BookingOrder order = orderOfType(IntentType.FLIGHT);

        BookingResult change = booking.requestUnsupportedOperation(order.id(), "CHANGE", employee);
        BookingResult refund = booking.requestUnsupportedOperation(order.id(), "REFUND", employee);

        assertThat(change.state().name()).isEqualTo("SUBMITTED");
        assertThat(change.failure().type().name()).isEqualTo("UNSUPPORTED");
        assertThat(refund.state().name()).isEqualTo("SUBMITTED");
        assertThat(refund.failure().type().name()).isEqualTo("UNSUPPORTED");
        assertThat(adapter.callCount(order.id(), OperationType.BOOK)).isEqualTo(1);
        assertThat(order.state().name()).isEqualTo("SUBMITTED");
        assertThat(store.auditEvents())
                .anyMatch(event -> event.action().equals("UNSUPPORTED_OPERATION_REQUESTED"));
    }

    @Test
    void cancellationRequiresEmployeeOwnership() {
        run("session-cancel-access", sampleTrip(), flight("flight-1"));
        BookingOrder order = orderOfType(IntentType.FLIGHT);

        assertThatThrownBy(() -> booking.cancel(order.id(), otherEmployee))
                .isInstanceOf(RoamException.class)
                .hasMessageContaining("own");
        assertThatThrownBy(() -> booking.cancel(order.id(), admin))
                .isInstanceOf(RoamException.class);
    }
}
