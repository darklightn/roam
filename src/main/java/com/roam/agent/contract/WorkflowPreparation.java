package com.roam.agent.contract;

import com.roam.domain.trip.TripRequestStatus;

/** Result of preparing a workflow without executing supplier bookings. */
public record WorkflowPreparation(
        String workflowId,
        String tripRequestId,
        TripRequestStatus submissionStatus,
        String planId,
        int planVersion,
        String nextStep,
        String summary,
        WorkflowResult result) {}
