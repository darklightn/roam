package com.roam.api.dto;

import com.roam.domain.trip.TripStop;
import java.util.List;

public record TripRequestView(
        String id,
        String status,
        int currentVersion,
        boolean versionLocked,
        String pendingReviewId,
        String travelerName,
        String travelerIdNumber,
        String origin,
        List<TripStop> stops,
        boolean oneWay,
        String tripReason,
        List<ApprovalView> approvals) {}
