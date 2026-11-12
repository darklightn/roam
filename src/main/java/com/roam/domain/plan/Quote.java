package com.roam.domain.plan;

import com.roam.domain.shared.Money;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** A time-bounded supplier offer belonging to one Itinerary Option. */
public record Quote(
        String id,
        String supplier,
        String supplierRef,
        Money price,
        Instant validUntil,
        QuoteStatus status,
        Map<String, String> supplierTerms) {

    public Quote {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(supplierRef, "supplierRef");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(validUntil, "validUntil");
        Objects.requireNonNull(status, "status");
        supplierTerms = supplierTerms == null ? Map.of() : Map.copyOf(supplierTerms);
    }

    public Quote withStatus(QuoteStatus next) {
        return new Quote(id, supplier, supplierRef, price, validUntil, next, supplierTerms);
    }

    public Quote withPrice(Money nextPrice, Instant nextValidUntil, QuoteStatus nextStatus) {
        return new Quote(id, supplier, supplierRef, nextPrice, nextValidUntil, nextStatus, supplierTerms);
    }
}
