package com.roam.domain.booking;

public enum AttemptOutcome {
    SUCCESS,
    RETRYABLE_FAILURE,
    TERMINAL_FAILURE,
    UNKNOWN,
    ALREADY_CANCELLED,
    UNSUPPORTED
}
