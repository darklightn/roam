package com.roam.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One of the four required Travel Plan views. */
public record PlanView(
        PlanViewType type,
        List<String> optionIds,
        String explanation,
        Map<String, BigDecimal> scoreDimensions,
        Map<String, BigDecimal> weights) {

    public PlanView {
        Objects.requireNonNull(type, "type");
        optionIds = optionIds == null ? List.of() : List.copyOf(optionIds);
        Objects.requireNonNull(explanation, "explanation");
        scoreDimensions = scoreDimensions == null ? Map.of() : Map.copyOf(scoreDimensions);
        weights = weights == null ? Map.of() : Map.copyOf(weights);
        if (optionIds.isEmpty()) {
            throw new IllegalArgumentException("a plan view must reference at least one option");
        }
        if (type == PlanViewType.OVERALL_BEST
                && (scoreDimensions.isEmpty() || weights.isEmpty())) {
            throw new IllegalArgumentException(
                    "the overall-best view must explain score dimensions and weights");
        }
    }
}
