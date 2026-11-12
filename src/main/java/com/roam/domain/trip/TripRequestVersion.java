package com.roam.domain.trip;

import java.time.Instant;
import java.util.Objects;

/**
 * An immutable snapshot of a submitted Trip Request version. Only the current version's Trip Reason
 * may be edited in place, because a Trip Reason-only change does not trigger re-approval; all prior
 * versions stay historical.
 */
public final class TripRequestVersion {

    private final int version;
    private final Instant submittedAt;
    private final String submittedBy;
    private final boolean coreItineraryChange;
    private TripRequestData data;

    public TripRequestVersion(
            int version,
            TripRequestData data,
            Instant submittedAt,
            String submittedBy,
            boolean coreItineraryChange) {
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
        this.version = version;
        this.data = Objects.requireNonNull(data, "data");
        this.submittedAt = Objects.requireNonNull(submittedAt, "submittedAt");
        this.submittedBy = Objects.requireNonNull(submittedBy, "submittedBy");
        this.coreItineraryChange = coreItineraryChange;
    }

    public int version() {
        return version;
    }

    public TripRequestData data() {
        return data;
    }

    public Instant submittedAt() {
        return submittedAt;
    }

    public String submittedBy() {
        return submittedBy;
    }

    public boolean coreItineraryChange() {
        return coreItineraryChange;
    }

    /** Internal transition for a Trip Reason-only edit on the current version. */
    public void replaceReason(String reason) {
        this.data = data.withReason(reason);
    }

    /** Internal transition used while completing information for the same in-flight version. */
    public void replaceData(TripRequestData data) {
        this.data = Objects.requireNonNull(data, "data");
    }
}
