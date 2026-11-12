package com.roam.domain.shared;

public enum FailureType {
    RETRYABLE,
    TERMINAL,
    CONTRACT_ERROR,
    CANCELLED,
    UNKNOWN_EXTERNAL_OUTCOME,
    UNSUPPORTED,
    OVER_BUDGET,
    INVALID_REQUEST,
    QUOTE_EXPIRED,
    QUOTE_CHANGED
}
