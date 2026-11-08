package com.roam.service;

import com.roam.domain.Actor;
import com.roam.domain.AuditEvent;
import com.roam.domain.BookingOrder;
import com.roam.domain.BookingOrderState;
import com.roam.domain.WorkflowInstance;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Administrator manual recovery. Recovery appends evidence and never rewrites historical business
 * events; only an explicit supplier confirmation can advance a Booking Order state.
 */
@Service
public class RecoveryService {

    private final InMemoryStore store;
    private final AccessControl access;
    private final AuditService audit;
    private final Clock clock;
    private final Ids ids;

    public RecoveryService(
            InMemoryStore store, AccessControl access, AuditService audit, Clock clock, Ids ids) {
        this.store = store;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
        this.ids = ids;
    }

    public WorkflowInstance takeOver(String workflowId, Actor administrator, String reason) {
        access.requireAdministrator(administrator);
        if (reason == null || reason.isBlank()) {
            throw RoamException.invalid("a takeover reason is required");
        }
        WorkflowInstance workflow = requireWorkflow(workflowId);
        String before = workflow.status().name();
        workflow.takeOver(administrator.userId(), reason);
        audit.append(administrator, "HUMAN_TAKEOVER", "Workflow", workflow.id(),
                "previousStatus=" + before + ";intents=" + workflow.intents().size(),
                reason);
        return workflow;
    }

    /**
     * Resolves an uncertain outcome from explicit supplier evidence. Without supplier confirmation
     * the order keeps its last confirmed state and only an observation is recorded.
     */
    public BookingOrder resolveUncertainOutcome(
            String orderId,
            Actor administrator,
            String reason,
            String confirmedSupplierOrderId) {
        access.requireAdministrator(administrator);
        if (reason == null || reason.isBlank()) {
            throw RoamException.invalid("a resolution reason is required");
        }
        BookingOrder order = store.orders().get(orderId);
        if (order == null) {
            throw RoamException.notFound("BookingOrder");
        }
        BookingOrderState before = order.state();
        if (confirmedSupplierOrderId != null && !confirmedSupplierOrderId.isBlank()) {
            if (order.state() == BookingOrderState.PENDING) {
                order.markSubmitted(
                        confirmedSupplierOrderId, order.supplierReferences(),
                        order.paymentLinkReference(), order.confirmationReference(), clock.instant());
                store.workflows().values().stream()
                        .filter(workflow -> workflow.id().equals(order.claimedByWorkflow()))
                        .forEach(workflow -> {
                            var intent = workflow.intent(order.intentId());
                            intent.markCompleted(confirmedSupplierOrderId);
                            workflow.setSummary("recovered: order " + orderId + " confirmed");
                        });
                audit.append(administrator, "RECOVERY_SUPPLIER_CONFIRMATION", "BookingOrder", order.id(),
                        "previousState=" + before + ";newState=" + order.state()
                                + ";supplierOrderId=" + confirmedSupplierOrderId,
                        reason);
            } else {
                audit.append(administrator, "RECOVERY_SUPPLIER_CONFIRMATION_IGNORED",
                        "BookingOrder", order.id(),
                        "state=" + order.state() + ";supplierOrderId=" + confirmedSupplierOrderId,
                        reason);
            }
        } else {
            audit.append(administrator, "RECOVERY_OBSERVATION", "BookingOrder", order.id(),
                    "state=" + order.state() + ";confirmedSupplierOrderId=none",
                    reason);
        }
        return order;
    }

    /**
     * P0 manual recovery drill: take over a stuck/uncertain workflow, record the evidence, and leave
     * every historical business event intact.
     */
    public RecoveryDrillReport runRecoveryDrill(
            String workflowId, String orderId, Actor administrator, String reason) {
        access.requireAdministrator(administrator);
        int before = audit.size();
        WorkflowInstance workflow = takeOver(workflowId, administrator, reason);
        BookingOrder order = store.orders().get(orderId);
        BookingOrderState stateBefore = order == null ? null : order.state();
        String drillId = ids.next("drill");
        audit.append(administrator, "RECOVERY_DRILL_COMPLETED", "Workflow", workflow.id(),
                "drill=" + drillId + ";order=" + orderId + ";orderState=" + stateBefore
                        + ";previousStatus=" + workflow.status(),
                reason);
        return new RecoveryDrillReport(
                drillId, workflowId, orderId, stateBefore, audit.size() - before,
                audit.verifyIntegrity());
    }

    public List<AuditEvent> history(String subjectId, Actor administrator) {
        return audit.forSubject(administrator, subjectId);
    }

    private WorkflowInstance requireWorkflow(String workflowId) {
        WorkflowInstance workflow = store.workflows().get(workflowId);
        if (workflow == null) {
            throw RoamException.notFound("Workflow");
        }
        return workflow;
    }

    public record RecoveryDrillReport(
            String drillId,
            String workflowId,
            String orderId,
            BookingOrderState orderStateBefore,
            int appendedEvents,
            boolean auditIntegrityVerified) {}
}
