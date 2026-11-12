package com.roam.api.dto;

public record WorkflowTakeoverView(
        String workflowId, String status, String ownerAdministrator, String summary) {}
