package com.roam.domain.trip;

public enum TripRequestStatus {
    DRAFT,
    SUBMITTED,
    AWAITING_INFO,
    AWAITING_REVIEW,
    APPROVED,
    REJECTED,
    EXECUTION_IN_PROGRESS,
    PARTIALLY_FULFILLED,
    FULFILLED,
    FAILED,
    CANCELLED
}
