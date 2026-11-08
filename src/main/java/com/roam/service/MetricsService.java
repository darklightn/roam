package com.roam.service;

import com.roam.domain.BookingOrder;
import com.roam.domain.HitlReview;
import com.roam.domain.ReviewStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** Operational signals required by the P0 readiness gate. */
@Component
public class MetricsService {

    private final InMemoryStore store;
    private final Clock clock;
    private final AtomicLong orderAttempts = new AtomicLong();
    private final AtomicLong orderSuccesses = new AtomicLong();
    private final AtomicLong unknownOutcomes = new AtomicLong();
    private final AtomicLong supplierErrors = new AtomicLong();
    private final AtomicLong callbacksReceived = new AtomicLong();
    private final AtomicLong callbacksDeduplicated = new AtomicLong();
    private final AtomicLong callbacksInvalid = new AtomicLong();
    private final Map<Integer, AtomicLong> retryDistribution = new ConcurrentHashMap<>();

    public MetricsService(InMemoryStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public void recordOrderAttempt(int callNumber) {
        orderAttempts.incrementAndGet();
        retryDistribution
                .computeIfAbsent(callNumber, ignored -> new AtomicLong())
                .incrementAndGet();
    }

    public void recordOrderSuccess() {
        orderSuccesses.incrementAndGet();
    }

    public void recordUnknownOutcome() {
        unknownOutcomes.incrementAndGet();
    }

    public void recordSupplierError() {
        supplierErrors.incrementAndGet();
    }

    public void recordCallbackReceived() {
        callbacksReceived.incrementAndGet();
    }

    public void recordCallbackDeduplicated() {
        callbacksDeduplicated.incrementAndGet();
    }

    public void recordCallbackInvalid() {
        callbacksInvalid.incrementAndGet();
    }

    public long unknownOutcomes() {
        return unknownOutcomes.get();
    }

    public long supplierErrors() {
        return supplierErrors.get();
    }

    public long callbacksInvalid() {
        return callbacksInvalid.get();
    }

    public long callbacksDeduplicated() {
        return callbacksDeduplicated.get();
    }

    public MetricsSnapshot snapshot() {
        Instant now = clock.instant();
        long queueLength = store.reviews().values().stream()
                .filter(review -> review.status() == ReviewStatus.UNCLAIMED
                        || review.status() == ReviewStatus.CLAIMED)
                .count();
        long oldestAge = store.reviews().values().stream()
                .filter(review -> review.status() == ReviewStatus.UNCLAIMED
                        || review.status() == ReviewStatus.CLAIMED)
                .mapToLong(review -> review.ageSeconds(now))
                .max()
                .orElse(0);
        Map<Integer, Long> retries = new ConcurrentHashMap<>();
        retryDistribution.forEach((key, value) -> retries.put(key, value.get()));
        return new MetricsSnapshot(
                orderAttempts.get(),
                orderSuccesses.get(),
                queueLength,
                oldestAge,
                Map.copyOf(retries),
                unknownOutcomes.get(),
                callbacksReceived.get(),
                callbacksDeduplicated.get(),
                callbacksInvalid.get(),
                supplierErrors.get());
    }

    public void reset() {
        orderAttempts.set(0);
        orderSuccesses.set(0);
        unknownOutcomes.set(0);
        supplierErrors.set(0);
        callbacksReceived.set(0);
        callbacksDeduplicated.set(0);
        callbacksInvalid.set(0);
        retryDistribution.clear();
    }

    public record MetricsSnapshot(
            long orderSubmissionAttempts,
            long orderSubmissionSuccesses,
            long hitlQueueLength,
            long hitlOldestAgeSeconds,
            Map<Integer, Long> retryDistribution,
            long unknownExternalOutcomes,
            long callbacksReceived,
            long callbacksDeduplicated,
            long callbacksInvalid,
            long supplierErrors) {}
}
