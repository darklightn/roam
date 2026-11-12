package com.roam.api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record ViewResponse(
        List<String> optionIds,
        String explanation,
        Map<String, BigDecimal> scoreDimensions,
        Map<String, BigDecimal> weights) {}
