package com.roam.api.dto;

import com.roam.domain.review.Decision;

public record DecisionBody(Decision decision, String reason) {}
