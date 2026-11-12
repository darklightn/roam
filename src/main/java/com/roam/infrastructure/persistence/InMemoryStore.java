package com.roam.infrastructure.persistence;

import com.roam.domain.audit.AuditEvent;
import com.roam.domain.booking.BookingOrder;
import com.roam.domain.booking.CallbackObservation;
import com.roam.domain.plan.TravelPlan;
import com.roam.domain.review.HitlReview;
import com.roam.domain.trip.EmployeeProfile;
import com.roam.domain.trip.TripRequest;
import com.roam.domain.workflow.WorkflowInstance;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * In-memory persistence boundary. Business and workflow records live here, not in Agent context.
 * The map-based implementation is the seam a MySQL adapter replaces; append-only audit semantics are
 * enforced by the audit service rather than by callers.
 */
@Component
public class InMemoryStore {

    private final Map<String, TripRequest> trips = new ConcurrentHashMap<>();
    private final Map<String, TravelPlan> plans = new ConcurrentHashMap<>();
    private final Map<String, BookingOrder> orders = new ConcurrentHashMap<>();
    private final Map<String, HitlReview> reviews = new ConcurrentHashMap<>();
    private final Map<String, WorkflowInstance> workflows = new ConcurrentHashMap<>();
    private final Map<String, CallbackObservation> callbacks = new ConcurrentHashMap<>();
    private final Map<String, EmployeeProfile> profiles = new ConcurrentHashMap<>();
    private final List<AuditEvent> auditEvents = new CopyOnWriteArrayList<>();

    public Map<String, TripRequest> trips() {
        return trips;
    }

    public Map<String, TravelPlan> plans() {
        return plans;
    }

    public Map<String, BookingOrder> orders() {
        return orders;
    }

    public Map<String, HitlReview> reviews() {
        return reviews;
    }

    public Map<String, WorkflowInstance> workflows() {
        return workflows;
    }

    public Map<String, CallbackObservation> callbacks() {
        return callbacks;
    }

    public Map<String, EmployeeProfile> profiles() {
        return profiles;
    }

    public List<AuditEvent> auditEvents() {
        return List.copyOf(auditEvents);
    }

    /** Atomically appends an audit event; callers can inspect the log but cannot mutate it. */
    public synchronized AuditEvent appendAudit(
            Function<List<AuditEvent>, AuditEvent> eventFactory) {
        AuditEvent event = eventFactory.apply(List.copyOf(auditEvents));
        auditEvents.add(event);
        return event;
    }

    public void reset() {
        trips.clear();
        plans.clear();
        orders.clear();
        reviews.clear();
        workflows.clear();
        callbacks.clear();
        profiles.clear();
        auditEvents.clear();
    }
}
