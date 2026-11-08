package com.roam.domain;

import java.time.LocalDate;
import java.util.Objects;

/** One destination in a Trip Request, with its own dates for multi-destination travel. */
public record TripStop(String city, LocalDate arrivalDate, LocalDate departureDate) {

    public TripStop {
        Objects.requireNonNull(city, "city");
        if (city.isBlank()) {
            throw new IllegalArgumentException("stop city must not be blank");
        }
    }

    public boolean hasBothDates() {
        return arrivalDate != null && departureDate != null;
    }
}
