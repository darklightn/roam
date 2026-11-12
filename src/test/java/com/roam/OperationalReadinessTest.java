package com.roam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.roam.agent.contract.WorkflowResult;
import com.roam.domain.booking.AttemptOutcome;
import com.roam.domain.booking.BookingOrder;
import com.roam.domain.booking.CallbackRequest;
import com.roam.domain.shared.Money;
import com.roam.domain.workflow.IntentType;
import com.roam.service.observability.TravelAlert;
import com.roam.shared.RoamException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Ticket #18: operational readiness, recovery, audit protection, and redaction. */
class OperationalReadinessTest extends AcceptanceTestBase {

    private BookingOrder firstOrder() {
        return store.orders().values().iterator().next();
    }

    @Test
    void correlationIsAvailableFromSessionThroughSupplierOrder() {
        WorkflowResult result = run(
                "session-trace", sampleTrip(), flight("flight-1"), hotel("hotel-1"));

        var chain = trace.trace("session-trace", admin);

        assertThat(chain.sessionId()).isEqualTo("session-trace");
        assertThat(chain.workflowId()).isEqualTo(result.workflowId());
        assertThat(chain.tripRequestId()).isEqualTo(result.tripRequestId());
        assertThat(chain.intents()).hasSize(2);
        assertThat(chain.intents()).allMatch(item -> item.taskId() != null);
        assertThat(chain.intents()).allMatch(item -> item.bookingOrderId() != null);
        assertThat(chain.intents()).allMatch(item -> item.supplierOrderId() != null);
        assertThatThrownBy(() -> trace.trace("session-trace", employee))
                .isInstanceOf(RoamException.class);
        assertThatThrownBy(() -> trace.trace("session-trace", otherEmployee))
                .isInstanceOf(RoamException.class);
    }

    @Test
    void p0MetricsCoverSubmissionRetriesHitlAndUnknownOutcomes() {
        run("session-metrics", sampleTrip(), flight("flight-1"), hotel("hotel-1"));
        var happy = metrics.snapshot();
        assertThat(happy.orderSubmissionAttempts()).isEqualTo(2);
        assertThat(happy.orderSubmissionSuccesses()).isEqualTo(2);
        assertThat(happy.retryDistribution()).containsEntry(1, 2L);

        budget.setCeiling("emp-1", Money.cny("300"));
        run("session-metrics-hitl", sampleTrip(), flight("flight-2"));
        var withHitl = metrics.snapshot();
        assertThat(withHitl.hitlQueueLength()).isGreaterThan(0);

        fixtures.scriptBookingForType(
                IntentType.TRAIN, AttemptOutcome.UNKNOWN, AttemptOutcome.UNKNOWN,
                AttemptOutcome.UNKNOWN);
        run("session-metrics-unknown", sampleTrip(),
                train("train-x", "Beijing", "Shanghai", DEPART, RETURN));
        var withUnknown = metrics.snapshot();
        assertThat(withUnknown.unknownExternalOutcomes()).isGreaterThan(0);
        assertThat(withUnknown.retryDistribution()).containsKey(3);
        assertThat(withUnknown.supplierErrors()).isGreaterThan(0);
    }

    @Test
    void everyP0AlertHasTriggerOwnerRunbookPointerAndMappedAction() throws Exception {
        budget.setCeiling("emp-1", Money.cny("300"));
        run("session-alert-hitl", sampleTrip(), flight("flight-1"));
        clock.advanceSeconds(120);
        fixtures.scriptBookingForType(
                IntentType.TRAIN, AttemptOutcome.UNKNOWN, AttemptOutcome.UNKNOWN,
                AttemptOutcome.UNKNOWN);
        run("session-alert-unknown", sampleTrip(),
                train("train-alert", "Beijing", "Shanghai", DEPART, RETURN));
        booking.recordPaymentCallback(new CallbackRequest(
                "cb-invalid", "TUNIU", "TN-UNKNOWN", "PAID", BigDecimal.ONE, "CNY", "bad", Map.of()));

        List<TravelAlert> triggered = alerts.evaluate();

        assertThat(triggered).extracting(TravelAlert::code).contains(
                "UNKNOWN_OUTCOME_BACKLOG",
                "HITL_AGING",
                "SUPPLIER_ERROR_SPIKE",
                "INVALID_OR_DUPLICATE_CALLBACK");
        assertThat(triggered).allMatch(alert ->
                !alert.trigger().isBlank()
                        && !alert.owner().isBlank()
                        && alert.runbookPointer().startsWith("docs/runbook/operations.md#"));
        String runbook = Files.readString(Path.of("docs/runbook/operations.md"));
        for (TravelAlert alert : triggered) {
            assertThat(runbook).contains(alert.code());
            assertThat(runbook).contains(alert.runbookPointer().substring(
                    alert.runbookPointer().indexOf('#')));
        }
        assertThat(runbook).contains(
                "## Unknown external outcomes",
                "## Aging HITL queue",
                "## Supplier error spike",
                "## Supplier callbacks",
                "## Manual approval",
                "## Stuck workflow takeover",
                "## Supplier-side manual cancellation",
                "## Recovery drill");
    }

    @Test
    void manualRecoveryDrillPreservesHistoryAndOnlyExplicitEvidenceAdvancesState() {
        fixtures.scriptBookingForType(
                IntentType.FLIGHT, AttemptOutcome.UNKNOWN, AttemptOutcome.UNKNOWN,
                AttemptOutcome.UNKNOWN);
        WorkflowResult result = run("session-drill", sampleTrip(), flight("flight-1"));
        BookingOrder order = firstOrder();
        assertThat(order.state().name()).isEqualTo("PENDING");
        List<com.roam.domain.audit.AuditEvent> before = audit.all(admin);

        var report = recovery.runRecoveryDrill(
                result.workflowId(), order.id(), admin, "P0 recovery drill for uncertain order");

        assertThat(report.appendedEvents()).isGreaterThanOrEqualTo(2);
        assertThat(report.auditIntegrityVerified()).isTrue();
        assertThat(report.orderStateBefore().name()).isEqualTo("PENDING");
        assertThat(order.state().name()).isEqualTo("PENDING");
        assertThat(store.workflows().get(result.workflowId()).ownerAdministrator())
                .isEqualTo("admin-1");
        assertThat(store.workflows().get(result.workflowId()).status().name())
                .isEqualTo("TAKEN_OVER");
        List<com.roam.domain.audit.AuditEvent> after = audit.all(admin);
        assertThat(after.subList(0, before.size())).isEqualTo(before);
        assertThat(after).anyMatch(event -> event.action().equals("HUMAN_TAKEOVER"));
        assertThat(after).anyMatch(event -> event.action().equals("RECOVERY_DRILL_COMPLETED"));

        recovery.resolveUncertainOutcome(order.id(), admin, "no supplier reference", null);
        assertThat(order.state().name()).isEqualTo("PENDING");

        recovery.resolveUncertainOutcome(
                order.id(), admin, "supplier confirmed the order", "TN-RECOVERED-1");
        assertThat(order.state().name()).isEqualTo("SUBMITTED");
        assertThat(order.supplierOrderId()).isEqualTo("TN-RECOVERED-1");
        assertThat(store.workflows().get(result.workflowId()).intent("flight-1").status().name())
                .isEqualTo("COMPLETED");
        assertThat(audit.verifyIntegrity()).isTrue();
    }

    @Test
    void auditEventsAreAppendOnlyAndTamperEvident() {
        run("session-audit", sampleTrip(), flight("flight-1"));
        List<com.roam.domain.audit.AuditEvent> events = store.auditEvents();

        assertThatThrownBy(events::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(store.auditEvents()).hasSameSizeAs(events);
        assertThat(audit.verifyIntegrity()).isTrue();
        assertThatThrownBy(() -> audit.all(employee)).isInstanceOf(RoamException.class);
        assertThat(audit.all(admin)).isNotEmpty();
        assertThat(events).allMatch(event ->
                event.previousHash() != null && event.hash() != null && event.occurredAt() != null);
    }

    @Test
    void structuredFailuresIncludeTypeReasonSupplierCodeTimeAndAttempt() {
        fixtures.scriptBookingForType(
                IntentType.FLIGHT, AttemptOutcome.UNKNOWN, AttemptOutcome.UNKNOWN,
                AttemptOutcome.UNKNOWN);
        WorkflowResult result = run("session-failure", sampleTrip(), flight("flight-1"));
        BookingOrder order = firstOrder();

        assertThat(order.lastFailure()).isNotNull();
        assertThat(order.lastFailure().type().name()).isEqualTo("UNKNOWN_EXTERNAL_OUTCOME");
        assertThat(order.lastFailure().reason()).isNotBlank();
        assertThat(order.lastFailure().supplierCode()).isEqualTo("TUNIU_TIMEOUT");
        assertThat(order.lastFailure().occurredAt()).isEqualTo(NOW);
        assertThat(order.lastFailure().attemptId()).isNotBlank();
        assertThat(result.intents().get(0).duplicateRiskWarning()).isTrue();
        assertThat(result.summary()).contains("Duplicate-risk warning");
        assertThat(store.auditEvents()).anyMatch(event ->
                event.action().equals("SUPPLIER_BOOKING_RESPONSE")
                        && event.detail().contains("attempt=")
                        && event.detail().contains("supplierCode=TUNIU_TIMEOUT"));
    }

    @Test
    void redactionMasksIdentityContactPaymentLinksAndApiKeysExactly() {
        assertThat(redaction.maskIdNumber("110101199001011234"))
                .isEqualTo("110***********1234");
        assertThat(redaction.maskPhone("13812345678")).isEqualTo("138****5678");
        assertThat(redaction.maskEmail("alice@example.com")).isEqualTo("a***@example.com");

        String text = "email alice@example.com phone 13812345678 "
                + "pay https://pay.tuniu.com/flight/TN-1?token=secret api_key=sk-live-secret";
        String redacted = redaction.redactText(text);

        assertThat(redacted).doesNotContain("alice@example.com");
        assertThat(redacted).doesNotContain("13812345678");
        assertThat(redacted).doesNotContain("https://pay.tuniu.com");
        assertThat(redacted).doesNotContain("sk-live-secret");
        assertThat(redaction.opaqueRef("payment-link", "https://pay.tuniu.com/secret"))
                .startsWith("opaque:payment-link:")
                .doesNotContain("https://");
    }

    @Test
    void auditAndOrderSurfacesNeverContainRawSensitiveValues() {
        run("session-redaction", sampleTrip(), flight("flight-1"));
        BookingOrder order = firstOrder();

        assertThat(store.auditEvents()).allMatch(event ->
                !event.detail().contains("110101199001011234")
                        && !event.detail().contains("https://pay.tuniu.com"));
        assertThat(order.supplierReferences().values())
                .noneMatch(value -> value.contains("https://") || value.contains("token="));
        assertThat(order.paymentLinkReference()).startsWith("opaque:payment-link:");

        assertThatThrownBy(() -> tripRequests.get(
                store.trips().values().iterator().next().id(), otherEmployee))
                .isInstanceOf(RoamException.class);
    }
}
