package com.roam.domain.trip;

import java.util.List;
import java.util.Objects;

/** Long-term Preferences and Historical Trip Information used only as suggestions. */
public record EmployeeProfile(
        String employeeId,
        String travelerName,
        String travelerIdNumber,
        String usualOrigin,
        List<HistoricalTrip> history) {

    public EmployeeProfile {
        Objects.requireNonNull(employeeId, "employeeId");
        history = history == null ? List.of() : List.copyOf(history);
    }

    public record HistoricalTrip(
            String origin, String destination, java.time.LocalDate departureDate) {
        public HistoricalTrip {
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(destination, "destination");
            Objects.requireNonNull(departureDate, "departureDate");
        }
    }
}
