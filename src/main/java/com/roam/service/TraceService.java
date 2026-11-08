package com.roam.service;

import com.roam.domain.Actor;
import com.roam.domain.BookingOrder;
import com.roam.domain.IntentRecord;
import com.roam.domain.WorkflowInstance;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** Operational correlation from session through Trip Request, intent, task, order, and supplier order. */
@Service
public class TraceService {

    private final InMemoryStore store;
    private final AccessControl access;

    public TraceService(InMemoryStore store, AccessControl access) {
        this.store = store;
        this.access = access;
    }

    public TraceChain trace(String sessionId, Actor actor) {
        access.requireAdministrator(actor);
        WorkflowInstance workflow = store.workflows().values().stream()
                .filter(candidate -> candidate.sessionId().equals(sessionId))
                .findFirst()
                .orElseThrow(() -> RoamException.notFound("Workflow for session " + sessionId));
        List<IntentTrace> intents = new ArrayList<>();
        for (IntentRecord intent : workflow.intents()) {
            BookingOrder order = intent.orderId() == null
                    ? null : store.orders().get(intent.orderId());
            intents.add(new IntentTrace(
                    intent.id(),
                    intent.taskId(),
                    intent.type().name(),
                    intent.status().name(),
                    intent.orderId(),
                    order == null ? null : order.supplierOrderId(),
                    intent.failure() == null ? null : intent.failure().type().name(),
                    intent.failure() == null ? null : intent.failure().supplierCode()));
        }
        return new TraceChain(
                sessionId, workflow.id(), workflow.tripRequestId(), intents);
    }

    public record TraceChain(
            String sessionId,
            String workflowId,
            String tripRequestId,
            List<IntentTrace> intents) {}

    public record IntentTrace(
            String intentId,
            String taskId,
            String type,
            String status,
            String bookingOrderId,
            String supplierOrderId,
            String failureType,
            String supplierCode) {}
}
