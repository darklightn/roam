package com.roam.domain.trip;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The employee-supplied content of one Trip Request. This is normalized data, not a conversation.
 * {@code departureDate} for approval purposes is the first stop arrival; a return date is the last
 * stop departure, unless the employee explicitly selected one-way travel.
 */
public record TripRequestData(
        String travelerName,
        String travelerIdNumber,
        String origin,
        List<TripStop> stops,
        boolean oneWay,
        String tripReason) {

    public TripRequestData {
        stops = stops == null ? List.of() : List.copyOf(stops);
    }

    public static TripRequestData empty() {
        return new TripRequestData(null, null, null, List.of(), false, null);
    }

    public TripRequestData withReason(String reason) {
        return new TripRequestData(travelerName, travelerIdNumber, origin, stops, oneWay, reason);
    }

    public boolean hasTripReason() {
        return tripReason != null && !tripReason.isBlank();
    }

    public String firstDestination() {
        return stops.isEmpty() ? null : stops.get(0).city();
    }

    public LocalDate departureDate() {
        return stops.isEmpty() ? null : stops.get(0).arrivalDate();
    }

    public LocalDate returnDate() {
        if (stops.isEmpty()) {
            return null;
        }
        return stops.get(stops.size() - 1).departureDate();
    }

    /** Returns the stable field names that still need employee input. */
    public List<String> missingFields() {
        java.util.ArrayList<String> missing = new java.util.ArrayList<>();
        if (travelerName == null || travelerName.isBlank()) {
            missing.add("traveler");
        }
        if (origin == null || origin.isBlank()) {
            missing.add("origin");
        }
        if (stops.isEmpty()) {
            missing.add("destination");
        } else {
            for (int i = 0; i < stops.size(); i++) {
                TripStop stop = stops.get(i);
                boolean last = i == stops.size() - 1;
                if (stop.arrivalDate() == null) {
                    missing.add("stops[" + i + "].arrivalDate");
                }
                if (!last && stop.departureDate() == null) {
                    missing.add("stops[" + i + "].departureDate");
                }
            }
            if (!oneWay && returnDate() == null) {
                missing.add("returnDate");
            }
        }
        if (!hasTripReason()) {
            missing.add("tripReason");
        }
        return List.copyOf(missing);
    }

    public boolean isComplete() {
        return missingFields().isEmpty();
    }

    public boolean coreItineraryEquals(TripRequestData other) {
        Objects.requireNonNull(other, "other");
        return Objects.equals(travelerName, other.travelerName)
                && Objects.equals(origin, other.origin)
                && stops.equals(other.stops)
                && oneWay == other.oneWay;
    }
}
