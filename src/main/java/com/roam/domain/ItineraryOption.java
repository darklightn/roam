package com.roam.domain;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/** A concrete candidate itinerary. Exactly one Quote is associated with each option. */
public record ItineraryOption(
        String id,
        IntentType type,
        String description,
        String origin,
        String destination,
        LocalDate startDate,
        LocalDate endDate,
        int durationMinutes,
        int preferenceScore,
        Quote quote,
        Map<String, String> supplierRefs) {

    public ItineraryOption {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(quote, "quote");
        supplierRefs = supplierRefs == null ? Map.of() : Map.copyOf(supplierRefs);
    }

    public ItineraryOption withQuote(Quote nextQuote) {
        return new ItineraryOption(
                id, type, description, origin, destination, startDate, endDate,
                durationMinutes, preferenceScore, nextQuote, supplierRefs);
    }
}
