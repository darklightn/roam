package com.roam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.roam.domain.AttemptOutcome;
import com.roam.domain.BookingOrder;
import com.roam.domain.IntentRequest;
import com.roam.domain.IntentType;
import com.roam.domain.TripRequestData;
import com.roam.domain.TripRequestStatus;
import com.roam.domain.TripStop;
import com.roam.domain.WorkflowResult;
import com.roam.service.RoamException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Ticket #16: complete multi-city trips with independent intent outcomes. */
class MultiCityTest extends AcceptanceTestBase {

    private static final LocalDate D1 = LocalDate.parse("2026-12-01");
    private static final LocalDate D2 = LocalDate.parse("2026-12-02");
    private static final LocalDate D3 = LocalDate.parse("2026-12-03");

    private TripRequestData multiCityTrip() {
        return new TripRequestData(
                "Li Wei",
                "110101199001011234",
                "Beijing",
                List.of(
                        new TripStop("Shanghai", D1, D2),
                        new TripStop("Hangzhou", D2, D3)),
                false,
                "Two-city client visits");
    }

    private IntentRequest intent(
            String id, IntentType type, String origin, String destination,
            LocalDate start, LocalDate end, String dependsOn) {
        return new IntentRequest(
                id, type, origin, destination, start, end,
                dependsOn == null ? List.of() : List.of(dependsOn), Map.of());
    }

    private IntentRequest[] fourIntents() {
        return new IntentRequest[] {
            intent("train-1", IntentType.TRAIN, "Beijing", "Shanghai", D1, D2, null),
            intent("hotel-1", IntentType.HOTEL, "Shanghai", "Shanghai", D1, D2, "train-1"),
            intent("flight-2", IntentType.FLIGHT, "Shanghai", "Hangzhou", D2, D3, "train-1"),
            intent("hotel-2", IntentType.HOTEL, "Hangzhou", "Hangzhou", D2, D3, "flight-2")
        };
    }

    @Test
    void multiCityTripCoordinatesDependentIntentsAndKeepsIndependentOutcomes() {
        WorkflowResult result = run("session-multi", multiCityTrip(), fourIntents());

        assertThat(result.workflowStatus().name()).isEqualTo("COMPLETED");
        assertThat(result.tripRequestStatus()).isEqualTo(TripRequestStatus.FULFILLED);
        assertThat(result.intents()).hasSize(4);
        assertThat(result.intents()).allMatch(item -> item.status().name().equals("COMPLETED"));
        assertThat(result.intents()).allMatch(item -> item.supplierOrderId() != null);
        assertThat(result.intents()).allMatch(item -> item.orderId() != null);
        assertThat(result.intents()).allMatch(item -> item.taskId() != null);

        assertThat(store.orders().values()).hasSize(4);
        assertThat(store.orders().values()).allMatch(order -> order.state().name().equals("SUBMITTED"));
        assertThat(store.orders().values()).anyMatch(order -> order.supplierOrderId().startsWith("TN-T-"));
        assertThat(store.orders().values()).anyMatch(order -> order.supplierOrderId().startsWith("TN-F-"));
        assertThat(store.orders().values().stream()
                .filter(order -> order.type() == IntentType.HOTEL).count()).isEqualTo(2);

        var chain = trace.trace("session-multi", admin);
        assertThat(chain.tripRequestId()).isEqualTo(result.tripRequestId());
        assertThat(chain.intents()).hasSize(4);
        assertThat(chain.intents()).allMatch(item -> item.bookingOrderId() != null);
        assertThat(chain.intents()).allMatch(item -> item.supplierOrderId() != null);
        assertThat(chain.intents()).allMatch(item -> item.taskId() != null);

        assertThat(store.auditEvents())
                .anyMatch(event -> event.action().equals("TASK_COMPLETED")
                        && event.detail().contains("session=session-multi")
                        && event.detail().contains("supplierOrder=TN-"));
        assertThat(audit.verifyIntegrity()).isTrue();
    }

    @Test
    void dependencyCompletesBeforeDependentIntentStarts() {
        run("session-deps", multiCityTrip(), fourIntents());

        List<String> completedOrder = store.auditEvents().stream()
                .filter(event -> event.action().equals("TASK_COMPLETED"))
                .map(event -> event.detail())
                .toList();
        int train = indexOf(completedOrder, "intent=train-1");
        int hotel1 = indexOf(completedOrder, "intent=hotel-1");
        int flight2 = indexOf(completedOrder, "intent=flight-2");
        int hotel2 = indexOf(completedOrder, "intent=hotel-2");

        assertThat(train).isLessThan(hotel1);
        assertThat(train).isLessThan(flight2);
        assertThat(flight2).isLessThan(hotel2);
    }

    @Test
    void partialFailureKeepsSuccessfulOrdersSubmittedAndDoesNotCompensate() {
        fixtures.scriptBookingForType(
                IntentType.HOTEL, AttemptOutcome.TERMINAL_FAILURE, AttemptOutcome.TERMINAL_FAILURE);

        WorkflowResult result = run("session-partial", multiCityTrip(), fourIntents());

        assertThat(result.workflowStatus().name()).isEqualTo("PARTIALLY_FAILED");
        assertThat(result.tripRequestStatus()).isEqualTo(TripRequestStatus.PARTIALLY_FULFILLED);
        assertThat(result.completedCount()).isEqualTo(2);
        assertThat(result.failedCount()).isEqualTo(2);
        assertThat(result.summary()).contains("not complete");

        for (BookingOrder order : store.orders().values()) {
            if (order.type() == IntentType.HOTEL) {
                assertThat(order.state().name()).isEqualTo("PENDING");
            } else {
                assertThat(order.state().name()).isEqualTo("SUBMITTED");
            }
            assertThat(order.state().name()).isNotEqualTo("CANCELLED");
            assertThat(adapter.callCount(order.id(), com.roam.domain.OperationType.CANCEL)).isZero();
        }
        assertThat(store.auditEvents())
                .noneMatch(event -> event.action().equals("BOOKING_ORDER_CANCELLED"));
    }

    @Test
    void multiCityResultsAreProtectedByEmployeeOwnership() {
        WorkflowResult result = run("session-multi-access", multiCityTrip(), fourIntents());

        assertThatThrownBy(() -> workflow.result(result.workflowId(), otherEmployee))
                .isInstanceOf(RoamException.class)
                .hasMessageContaining("own");
        assertThatThrownBy(() -> trace.trace("session-multi-access", employee))
                .isInstanceOf(RoamException.class);
        assertThat(trace.trace("session-multi-access", admin).intents()).hasSize(4);
    }

    private int indexOf(List<String> events, String needle) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }
}
