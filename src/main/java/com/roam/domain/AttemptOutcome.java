package com.roam.domain;

public enum AttemptOutcome {
    SUCCESS,
    RETRYABLE_FAILURE,
    TERMINAL_FAILURE,
    UNKNOWN,
    ALREADY_CANCELLED,
    UNSUPPORTED
}
