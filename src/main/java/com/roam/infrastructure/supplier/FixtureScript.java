package com.roam.infrastructure.supplier;

import com.roam.domain.booking.AttemptOutcome;
import com.roam.domain.booking.OperationType;
import com.roam.domain.port.SupplierAdapter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic fixture script. An empty queue means the adapter's default success path.
 * Scripts are keyed by local order id so retries stay attached to the same operation.
 */
public final class FixtureScript {

    private final Map<String, Deque<AttemptOutcome>> booking = new ConcurrentHashMap<>();
    private final Map<String, Deque<AttemptOutcome>> cancellation = new ConcurrentHashMap<>();
    private final Map<String, Deque<SupplierAdapter.QuoteCheck>> reprice = new ConcurrentHashMap<>();
    private final Map<com.roam.domain.workflow.IntentType, Deque<AttemptOutcome>> bookingByType =
            new ConcurrentHashMap<>();

    public void scriptBooking(String orderId, AttemptOutcome... outcomes) {
        Deque<AttemptOutcome> queue = new ArrayDeque<>();
        for (AttemptOutcome outcome : outcomes) {
            queue.add(outcome);
        }
        booking.put(orderId, queue);
    }

    public void scriptCancellation(String orderId, AttemptOutcome... outcomes) {
        Deque<AttemptOutcome> queue = new ArrayDeque<>();
        for (AttemptOutcome outcome : outcomes) {
            queue.add(outcome);
        }
        cancellation.put(orderId, queue);
    }

    public void scriptBookingForType(
            com.roam.domain.workflow.IntentType type, AttemptOutcome... outcomes) {
        Deque<AttemptOutcome> queue = new ArrayDeque<>();
        for (AttemptOutcome outcome : outcomes) {
            queue.add(outcome);
        }
        bookingByType.put(type, queue);
    }

    public void scriptReprice(String quoteRef, SupplierAdapter.QuoteCheck... checks) {
        Deque<SupplierAdapter.QuoteCheck> queue = new ArrayDeque<>();
        for (SupplierAdapter.QuoteCheck check : checks) {
            queue.add(check);
        }
        reprice.put(quoteRef, queue);
    }

    AttemptOutcome next(
            OperationType operation, String orderId, com.roam.domain.workflow.IntentType type) {
        Map<String, Deque<AttemptOutcome>> source =
                operation == OperationType.BOOK ? booking : cancellation;
        Deque<AttemptOutcome> queue = source.get(orderId);
        if (queue != null) {
            synchronized (queue) {
                AttemptOutcome outcome = queue.poll();
                if (outcome != null) {
                    return outcome;
                }
            }
        }
        Deque<AttemptOutcome> typeQueue =
                operation == OperationType.BOOK ? bookingByType.get(type) : null;
        if (typeQueue == null) {
            return null;
        }
        synchronized (typeQueue) {
            return typeQueue.poll();
        }
    }

    SupplierAdapter.QuoteCheck nextReprice(String quoteRef) {
        Deque<SupplierAdapter.QuoteCheck> queue = reprice.get(quoteRef);
        if (queue == null) {
            return null;
        }
        synchronized (queue) {
            return queue.poll();
        }
    }

    public void reset() {
        booking.clear();
        cancellation.clear();
        reprice.clear();
        bookingByType.clear();
    }
}
