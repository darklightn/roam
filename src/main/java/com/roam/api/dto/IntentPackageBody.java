package com.roam.api.dto;

import com.roam.domain.trip.TripRequestData;
import com.roam.domain.workflow.IntentRequest;
import java.util.List;

public record IntentPackageBody(
        String packageId,
        String sessionId,
        String contractVersion,
        String tripRequestId,
        TripRequestData tripRequest,
        List<IntentRequest> intents) {}
