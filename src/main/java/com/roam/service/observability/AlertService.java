package com.roam.service.observability;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * P0 alert rules. Every alert has a trigger, an owner, and a runbook pointer; the runbook lives at
 * docs/runbook/operations.md.
 */
@Component
public class AlertService {

    public static final String RUNBOOK = "docs/runbook/operations.md";

    private final MetricsService metrics;
    private final long hitlAgeSeconds;
    private final long supplierErrorThreshold;

    public AlertService(
            MetricsService metrics,
            @Value("${roam.alerts.hitl-age-seconds:60}") long hitlAgeSeconds,
            @Value("${roam.alerts.supplier-error-threshold:3}") long supplierErrorThreshold) {
        this.metrics = metrics;
        this.hitlAgeSeconds = hitlAgeSeconds;
        this.supplierErrorThreshold = supplierErrorThreshold;
    }

    public List<TravelAlert> evaluate() {
        MetricsService.MetricsSnapshot snapshot = metrics.snapshot();
        List<TravelAlert> alerts = new ArrayList<>();
        if (snapshot.unknownExternalOutcomes() > 0) {
            alerts.add(new TravelAlert(
                    "UNKNOWN_OUTCOME_BACKLOG",
                    "Unknown external outcomes require recovery",
                    "unknown_external_outcome count > 0",
                    "Administrator on-call",
                    RUNBOOK + "#unknown-external-outcomes",
                    snapshot.unknownExternalOutcomes()));
        }
        if (snapshot.hitlOldestAgeSeconds() >= hitlAgeSeconds && snapshot.hitlQueueLength() > 0) {
            alerts.add(new TravelAlert(
                    "HITL_AGING",
                    "Human review queue is aging",
                    "oldest open HITL review age >= " + hitlAgeSeconds + " seconds",
                    "Administrator on-call",
                    RUNBOOK + "#aging-hitl-queue",
                    snapshot.hitlOldestAgeSeconds()));
        }
        if (snapshot.supplierErrors() >= supplierErrorThreshold) {
            alerts.add(new TravelAlert(
                    "SUPPLIER_ERROR_SPIKE",
                    "Supplier error rate spike",
                    "supplier errors >= " + supplierErrorThreshold,
                    "Supplier integration owner",
                    RUNBOOK + "#supplier-error-spike",
                    snapshot.supplierErrors()));
        }
        if (snapshot.callbacksInvalid() > 0 || snapshot.callbacksDeduplicated() > 0) {
            alerts.add(new TravelAlert(
                    "INVALID_OR_DUPLICATE_CALLBACK",
                    "Invalid or duplicate supplier callback",
                    "invalid callback count > 0 or deduplicated callback count > 0",
                    "Supplier integration owner",
                    RUNBOOK + "#callbacks",
                    snapshot.callbacksInvalid() + snapshot.callbacksDeduplicated()));
        }
        return alerts;
    }
}
