package com.roam.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record OptionView(
        String id, String type, String description, String origin, String destination,
        LocalDate startDate, LocalDate endDate, int durationMinutes, int preferenceScore,
        String quoteId, BigDecimal price, String currency, Instant validUntil,
        String quoteStatus) {}
