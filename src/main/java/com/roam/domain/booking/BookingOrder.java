package com.roam.domain.booking;

import com.roam.domain.shared.FailureReason;
import com.roam.domain.shared.Money;
import com.roam.domain.workflow.IntentType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Local supplier-facing order. Created and persisted as Pending before the first supplier call.
 * Submitted means a supplier order id was received; it does not mean paid or fulfilled.
 */
public final class BookingOrder {

    private final String id;
    private final String tripRequestId;
    private final String intentId;
    private final IntentType type;
    private final String planId;
    private final String optionId;
    private final String quoteId;
    private final Money price;
    private final Instant createdAt;
    private final List<BookingAttempt> attempts = new ArrayList<>();
    private BookingOrderState state = BookingOrderState.PENDING;
    private String supplierOrderId;
    private Map<String, String> supplierReferences = Map.of();
    private String paymentLinkReference;
    private String confirmationReference;
    private FailureReason lastFailure;
    private boolean duplicateRiskWarned;
    private boolean paidObserved;
    private String claimedByWorkflow;
    private Instant updatedAt;

    public BookingOrder(
            String id,
            String tripRequestId,
            String intentId,
            IntentType type,
            String planId,
            String optionId,
            String quoteId,
            Money price,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.tripRequestId = Objects.requireNonNull(tripRequestId, "tripRequestId");
        this.intentId = Objects.requireNonNull(intentId, "intentId");
        this.type = Objects.requireNonNull(type, "type");
        this.planId = Objects.requireNonNull(planId, "planId");
        this.optionId = Objects.requireNonNull(optionId, "optionId");
        this.quoteId = Objects.requireNonNull(quoteId, "quoteId");
        this.price = Objects.requireNonNull(price, "price");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = createdAt;
    }

    public String id() {
        return id;
    }

    public String tripRequestId() {
        return tripRequestId;
    }

    public String intentId() {
        return intentId;
    }

    public IntentType type() {
        return type;
    }

    public String planId() {
        return planId;
    }

    public String optionId() {
        return optionId;
    }

    public String quoteId() {
        return quoteId;
    }

    public Money price() {
        return price;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public BookingOrderState state() {
        return state;
    }

    public String supplierOrderId() {
        return supplierOrderId;
    }

    public Map<String, String> supplierReferences() {
        return Map.copyOf(supplierReferences);
    }

    public String paymentLinkReference() {
        return paymentLinkReference;
    }

    public String confirmationReference() {
        return confirmationReference;
    }

    public FailureReason lastFailure() {
        return lastFailure;
    }

    public boolean duplicateRiskWarned() {
        return duplicateRiskWarned;
    }

    public boolean paidObserved() {
        return paidObserved;
    }

    public List<BookingAttempt> attempts() {
        return List.copyOf(attempts);
    }

    public int supplierCallCount(OperationType operation) {
        return (int) attempts.stream().filter(a -> a.operation() == operation).count();
    }

    public void addAttempt(BookingAttempt attempt, Instant now) {
        attempts.add(Objects.requireNonNull(attempt, "attempt"));
        updatedAt = now;
    }

    public void markSubmitted(
            String supplierOrderId,
            Map<String, String> references,
            String paymentLinkReference,
            String confirmationReference,
            Instant now) {
        if (state != BookingOrderState.PENDING) {
            throw new IllegalStateException("only a Pending order can be Submitted");
        }
        if (supplierOrderId == null || supplierOrderId.isBlank()) {
            throw new IllegalArgumentException("a supplier order id is required for Submitted");
        }
        this.supplierOrderId = supplierOrderId;
        this.supplierReferences = references == null ? Map.of() : Map.copyOf(references);
        this.paymentLinkReference = paymentLinkReference;
        this.confirmationReference = confirmationReference;
        this.state = BookingOrderState.SUBMITTED;
        this.lastFailure = null;
        this.updatedAt = now;
    }

    public void markCancelled(Instant now) {
        if (state != BookingOrderState.SUBMITTED) {
            throw new IllegalStateException("only a Submitted order can be Cancelled");
        }
        this.state = BookingOrderState.CANCELLED;
        this.updatedAt = now;
    }

    public void recordFailure(FailureReason failure, Instant now) {
        this.lastFailure = Objects.requireNonNull(failure, "failure");
        this.updatedAt = now;
    }

    public void warnDuplicateRisk() {
        this.duplicateRiskWarned = true;
    }

    public void markPaidObserved() {
        this.paidObserved = true;
    }

    public String claimedByWorkflow() {
        return claimedByWorkflow;
    }

    public void claimByWorkflow(String workflowId) {
        this.claimedByWorkflow = workflowId;
    }
}
