package com.roam.domain.workflow;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One requested business outcome extracted during request preparation. */
public record IntentRequest(
        String id,
        IntentType type,
        String origin,
        String destination,
        LocalDate startDate,
        LocalDate endDate,
        List<String> dependsOn,
        Map<String, String> attributes) {

    public IntentRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        if (id.isBlank()) {
            throw new IllegalArgumentException("intent id must not be blank");
        }
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
