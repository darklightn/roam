package com.roam.domain.trip;

import java.util.Objects;

/** A candidate value recovered from Long-term Preferences or Historical Trip Information. */
public record Suggestion(String field, String value, String source) {

    public Suggestion {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(source, "source");
    }
}
