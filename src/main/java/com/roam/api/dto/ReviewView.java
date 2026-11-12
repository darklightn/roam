package com.roam.api.dto;

import java.time.Instant;

public record ReviewView(
        String id, String type, String status, String subjectId, String tripRequestId,
        int tripRequestVersion, String requestedBy, Instant requestedAt, String claimedBy,
        Instant claimedAt, String decision, String decisionReason, int reminderCount) {}
