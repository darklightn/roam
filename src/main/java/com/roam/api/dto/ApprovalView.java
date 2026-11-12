package com.roam.api.dto;

import java.time.Instant;

public record ApprovalView(
        String id, int version, Instant decidedAt, String decidedBy,
        String role, boolean automatic, String basis) {}
