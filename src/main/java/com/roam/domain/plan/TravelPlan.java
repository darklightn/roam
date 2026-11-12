package com.roam.domain.plan;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A complete, versioned planning result. Once an option is selected the plan is read-only; new
 * planning input or quote results produce a new version instead of overwriting history.
 */
public final class TravelPlan {

    private final String id;
    private final String tripRequestId;
    private final int version;
    private final Instant createdAt;
    private final List<ItineraryOption> options;
    private final Map<PlanViewType, PlanView> views;
    private final Map<String, String> selectedOptionsByIntent = new LinkedHashMap<>();
    private String selectedOptionId;
    private String selectedQuoteId;
    private boolean superseded;

    public TravelPlan(
            String id,
            String tripRequestId,
            int version,
            Instant createdAt,
            List<ItineraryOption> options,
            Map<PlanViewType, PlanView> views) {
        this.id = Objects.requireNonNull(id, "id");
        this.tripRequestId = Objects.requireNonNull(tripRequestId, "tripRequestId");
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.options = List.copyOf(options);
        this.views = new LinkedHashMap<>(views);
        validateViews();
    }

    private void validateViews() {
        for (PlanViewType type : PlanViewType.values()) {
            if (!views.containsKey(type)) {
                throw new IllegalArgumentException("Travel Plan is missing view " + type);
            }
        }
        Map<String, ItineraryOption> byId = new LinkedHashMap<>();
        for (ItineraryOption option : options) {
            if (byId.put(option.id(), option) != null) {
                throw new IllegalArgumentException("duplicate option " + option.id());
            }
        }
        for (PlanView view : views.values()) {
            for (String optionId : view.optionIds()) {
                ItineraryOption option = byId.get(optionId);
                if (option == null) {
                    throw new IllegalArgumentException(
                            "view " + view.type() + " references unknown option " + optionId);
                }
                if (option.quote() == null) {
                    throw new IllegalArgumentException("option " + optionId + " has no quote");
                }
            }
        }
    }

    public String id() {
        return id;
    }

    public String tripRequestId() {
        return tripRequestId;
    }

    public int version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public List<ItineraryOption> options() {
        return options;
    }

    public Map<PlanViewType, PlanView> views() {
        return Map.copyOf(views);
    }

    public PlanView view(PlanViewType type) {
        return views.get(type);
    }

    public synchronized String selectedOptionId() {
        return selectedOptionId;
    }

    public synchronized String selectedQuoteId() {
        return selectedQuoteId;
    }

    public synchronized boolean isSelected() {
        return selectedOptionId != null || !selectedOptionsByIntent.isEmpty();
    }

    public synchronized String selectedOptionForIntent(String intentId) {
        return selectedOptionsByIntent.get(intentId);
    }

    public synchronized boolean isSuperseded() {
        return superseded;
    }

    public synchronized boolean isReadOnly() {
        return isSelected() || superseded;
    }

    public ItineraryOption option(String optionId) {
        return options.stream()
                .filter(option -> option.id().equals(optionId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown option " + optionId));
    }

    /** Explicit employee selection. The option and its quote must belong to this plan. */
    public synchronized void select(String optionId, String quoteId) {
        if (superseded) {
            throw new IllegalStateException("a superseded Travel Plan cannot be selected");
        }
        if (isSelected()) {
            throw new IllegalStateException("the Travel Plan is already selected and read-only");
        }
        ItineraryOption option = option(optionId);
        if (!option.quote().id().equals(quoteId)) {
            throw new IllegalArgumentException("quote does not belong to the selected option");
        }
        if (option.quote().status() == QuoteStatus.EXPIRED) {
            throw new IllegalStateException("an expired quote cannot be selected");
        }
        this.selectedOptionId = optionId;
        this.selectedQuoteId = quoteId;
    }

    /** Selects the option for one intent. Multiple intents keep independent selections. */
    public synchronized void selectForIntent(String intentId, String optionId, String quoteId) {
        if (superseded) {
            throw new IllegalStateException("a superseded Travel Plan cannot be selected");
        }
        if (selectedOptionsByIntent.containsKey(intentId)) {
            throw new IllegalStateException("intent " + intentId + " already has a selection");
        }
        ItineraryOption option = option(optionId);
        if (!option.quote().id().equals(quoteId)) {
            throw new IllegalArgumentException("quote does not belong to the selected option");
        }
        if (option.quote().status() == QuoteStatus.EXPIRED) {
            throw new IllegalStateException("an expired quote cannot be selected");
        }
        selectedOptionsByIntent.put(intentId, optionId);
        if (selectedOptionId == null) {
            selectedOptionId = optionId;
            selectedQuoteId = quoteId;
        }
    }

    public synchronized void markSuperseded() {
        this.superseded = true;
    }

    public synchronized boolean matchesSelected(String optionId, String quoteId) {
        return isSelected()
                && selectedOptionId.equals(optionId)
                && selectedQuoteId.equals(quoteId);
    }

    /** Creates the next version with a refreshed quote, preserving this version as history. */
    public TravelPlan nextVersion(
            String newId, Instant now, String optionId, Quote refreshedQuote) {
        List<ItineraryOption> nextOptions = options.stream()
                .map(option -> option.id().equals(optionId) ? option.withQuote(refreshedQuote) : option)
                .toList();
        TravelPlan next = new TravelPlan(newId, tripRequestId, version + 1, now, nextOptions, views);
        markSuperseded();
        return next;
    }
}
