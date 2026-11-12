package com.roam.api.dto;

import com.roam.domain.plan.PlanViewType;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record PlanResponse(
        String id,
        String tripRequestId,
        int version,
        Instant createdAt,
        boolean selected,
        boolean readOnly,
        boolean superseded,
        String selectedOptionId,
        String selectedQuoteId,
        List<OptionView> options,
        Map<PlanViewType, ViewResponse> views) {}
