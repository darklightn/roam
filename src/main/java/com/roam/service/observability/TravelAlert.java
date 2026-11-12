package com.roam.service.observability;

import java.util.Objects;

public record TravelAlert(
        String code,
        String title,
        String trigger,
        String owner,
        String runbookPointer,
        long observedValue) {

    public TravelAlert {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(runbookPointer, "runbookPointer");
    }
}
