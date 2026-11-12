package com.roam.service;

import com.roam.domain.plan.ItineraryOption;
import com.roam.domain.plan.PlanView;
import com.roam.domain.plan.PlanViewType;
import com.roam.domain.plan.Quote;
import com.roam.domain.plan.QuoteStatus;
import com.roam.domain.plan.TravelPlan;
import com.roam.domain.port.SupplierAdapter;
import com.roam.domain.shared.Actor;
import com.roam.domain.trip.TripRequest;
import com.roam.domain.workflow.IntentRequest;
import com.roam.infrastructure.persistence.InMemoryStore;
import com.roam.service.audit.AuditService;
import com.roam.service.security.AccessControl;
import com.roam.shared.Ids;
import com.roam.shared.RoamException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class PlanningService {

    private final InMemoryStore store;
    private final Ids ids;
    private final AuditService audit;
    private final AccessControl access;
    private final SupplierAdapter supplier;
    private final Clock clock;

    public PlanningService(
            InMemoryStore store,
            Ids ids,
            AuditService audit,
            AccessControl access,
            SupplierAdapter supplier,
            Clock clock) {
        this.store = store;
        this.ids = ids;
        this.audit = audit;
        this.access = access;
        this.supplier = supplier;
        this.clock = clock;
    }

    public TravelPlan plan(TripRequest trip, List<IntentRequest> intents, Actor actor) {
        access.requireEmployeeOwnsTrip(actor, trip);
        List<ItineraryOption> options = new ArrayList<>();
        for (IntentRequest intent : intents) {
            SupplierAdapter.SearchRequest request = new SupplierAdapter.SearchRequest(
                    intent.type(), intent.origin(), intent.destination(),
                    intent.startDate(), intent.endDate());
            List<SupplierAdapter.SupplierOffer> offers = supplier.search(request);
            for (SupplierAdapter.SupplierOffer offer : offers) {
                Map<String, String> refs = new LinkedHashMap<>(offer.supplierRefs());
                refs.put("intentId", intent.id());
                Quote quote = new Quote(
                        ids.next("quote"),
                        "TUNIU",
                        offer.supplierRef(),
                        offer.price(),
                        offer.validUntil(),
                        QuoteStatus.AVAILABLE,
                        Map.copyOf(refs));
                options.add(new ItineraryOption(
                        intent.id() + ":" + offer.offerId(),
                        intent.type(),
                        offer.description(),
                        intent.origin(),
                        intent.destination(),
                        intent.startDate(),
                        intent.endDate(),
                        offer.durationMinutes(),
                        offer.preferenceScore(),
                        quote,
                        Map.copyOf(refs)));
            }
        }
        if (options.isEmpty()) {
            throw new RoamException("no_offers", "the supplier returned no options", 409);
        }
        Map<PlanViewType, PlanView> views = buildViews(options);
        int version = store.plans().values().stream()
                        .filter(existing -> existing.tripRequestId().equals(trip.id()))
                        .mapToInt(TravelPlan::version)
                        .max()
                        .orElse(0)
                + 1;
        store.plans().values().stream()
                .filter(existing -> existing.tripRequestId().equals(trip.id()))
                .forEach(TravelPlan::markSuperseded);
        TravelPlan plan = new TravelPlan(
                ids.next("plan"), trip.id(), version, clock.instant(), options, views);
        store.plans().put(plan.id(), plan);
        audit.append(actor, "TRAVEL_PLAN_GENERATED", "TravelPlan", plan.id(),
                "tripRequest=" + trip.id() + ";version=" + version + ";options=" + options.size(),
                "four-view Travel Plan");
        return plan;
    }

    public TravelPlan get(String planId, Actor actor) {
        TravelPlan plan = store.plans().get(planId);
        if (plan == null) {
            throw RoamException.notFound("TravelPlan");
        }
        TripRequest trip = store.trips().get(plan.tripRequestId());
        if (trip == null) {
            throw RoamException.notFound("TripRequest");
        }
        access.requireEmployeeOwnsPlan(actor, plan, trip);
        return plan;
    }

    public TravelPlan latest(String tripRequestId, Actor actor) {
        TripRequest trip = store.trips().get(tripRequestId);
        if (trip == null) {
            throw RoamException.notFound("TripRequest");
        }
        access.requireEmployeeOwnsTrip(actor, trip);
        return store.plans().values().stream()
                .filter(plan -> plan.tripRequestId().equals(tripRequestId))
                .max(Comparator.comparingInt(TravelPlan::version))
                .orElseThrow(() -> RoamException.notFound("TravelPlan"));
    }

    public TravelPlan selectOption(
            String planId, String optionId, String quoteId, Actor actor) {
        TravelPlan plan = get(planId, actor);
        TripRequest trip = store.trips().get(plan.tripRequestId());
        access.requireEmployeeOwnsTripForMutation(actor, trip);
        plan.select(optionId, quoteId);
        audit.append(actor, "ITINERARY_OPTION_SELECTED", "TravelPlan", plan.id(),
                "option=" + optionId + ";quote=" + quoteId, null);
        return plan;
    }

    public TravelPlan selectForIntent(
            String planId, String intentId, String optionId, String quoteId, Actor actor) {
        TravelPlan plan = get(planId, actor);
        TripRequest trip = store.trips().get(plan.tripRequestId());
        access.requireEmployeeOwnsTripForMutation(actor, trip);
        plan.selectForIntent(intentId, optionId, quoteId);
        audit.append(actor, "ITINERARY_OPTION_SELECTED", "TravelPlan", plan.id(),
                "intent=" + intentId + ";option=" + optionId + ";quote=" + quoteId, null);
        return plan;
    }

    /**
     * Revalidates the selected quote immediately before a booking mutation. A changed or expired
     * quote creates a new Travel Plan version; the employee must reselect before booking.
     */
    public Revalidation revalidateForBooking(
            TravelPlan plan, String optionId, Quote quote, Actor actor) {
        SupplierAdapter.QuoteCheck check = supplier.reprice(quote.supplierRef());
        if (check.status() == SupplierAdapter.QuoteCheck.Status.STILL_VALID) {
            return new Revalidation(true, plan, check, "quote is still valid");
        }
        Quote refreshed = new Quote(
                quote.id(),
                quote.supplier(),
                quote.supplierRef(),
                check.currentPrice() == null ? quote.price() : check.currentPrice(),
                check.validUntil() == null ? clock.instant() : check.validUntil(),
                check.status() == SupplierAdapter.QuoteCheck.Status.EXPIRED
                        ? QuoteStatus.EXPIRED : QuoteStatus.CHANGED,
                quote.supplierTerms());
        TravelPlan next = plan.nextVersion(ids.next("plan"), clock.instant(), optionId, refreshed);
        store.plans().put(next.id(), next);
        audit.append(actor, "QUOTE_REVALIDATED", "TravelPlan", next.id(),
                "option=" + optionId + ";result=" + check.status(),
                "material change requires Employee confirmation or reselection");
        return new Revalidation(false, next, check,
                check.status() == SupplierAdapter.QuoteCheck.Status.EXPIRED
                        ? "the quote expired; reselect a current option"
                        : "the quote changed; confirm the new price by reselecting");
    }

    private Map<PlanViewType, PlanView> buildViews(List<ItineraryOption> options) {
        BigDecimal maxCost = options.stream()
                .map(option -> option.quote().price().amount())
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ONE);
        BigDecimal minCost = options.stream()
                .map(option -> option.quote().price().amount())
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
        int maxDuration = options.stream().mapToInt(ItineraryOption::durationMinutes).max().orElse(1);
        int minDuration = options.stream().mapToInt(ItineraryOption::durationMinutes).min().orElse(0);

        record Scored(ItineraryOption option, BigDecimal score) {}
        List<Scored> scored = options.stream().map(option -> {
            BigDecimal costScore = normalize(maxCost.subtract(option.quote().price().amount()),
                    maxCost.subtract(minCost));
            BigDecimal durationScore = normalize(
                    BigDecimal.valueOf(maxDuration - option.durationMinutes()),
                    BigDecimal.valueOf(Math.max(1, maxDuration - minDuration)));
            BigDecimal preference = BigDecimal.valueOf(option.preferenceScore());
            BigDecimal total = costScore.multiply(new BigDecimal("0.4"))
                    .add(durationScore.multiply(new BigDecimal("0.3")))
                    .add(preference.multiply(new BigDecimal("0.3")));
            return new Scored(option, total);
        }).sorted(Comparator.comparing(Scored::score).reversed()).toList();

        ItineraryOption best = scored.get(0).option();
        BigDecimal bestCostScore = normalize(
                maxCost.subtract(best.quote().price().amount()), maxCost.subtract(minCost));
        BigDecimal bestDurationScore = normalize(
                BigDecimal.valueOf(maxDuration - best.durationMinutes()),
                BigDecimal.valueOf(Math.max(1, maxDuration - minDuration)));
        Map<String, BigDecimal> dimensions = new LinkedHashMap<>();
        dimensions.put("cost", bestCostScore);
        dimensions.put("duration", bestDurationScore);
        dimensions.put("preferences", BigDecimal.valueOf(best.preferenceScore()));
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        weights.put("cost", new BigDecimal("0.4"));
        weights.put("duration", new BigDecimal("0.3"));
        weights.put("preferences", new BigDecimal("0.3"));

        ItineraryOption lowestCost = options.stream()
                .min(Comparator.comparing(option -> option.quote().price().amount()))
                .orElseThrow();
        ItineraryOption shortest = options.stream()
                .min(Comparator.comparingInt(ItineraryOption::durationMinutes))
                .orElseThrow();
        ItineraryOption closest = options.stream()
                .max(Comparator.comparingInt(ItineraryOption::preferenceScore))
                .orElseThrow();

        Map<PlanViewType, PlanView> views = new LinkedHashMap<>();
        views.put(PlanViewType.OVERALL_BEST, new PlanView(
                PlanViewType.OVERALL_BEST,
                scored.stream().limit(3).map(s -> s.option().id()).toList(),
                "Best balance of price (40%), duration (30%), and personal preferences (30%)."
                        + " Recommendation only; it is never selected or booked automatically.",
                dimensions,
                weights));
        views.put(PlanViewType.LOWEST_COST, new PlanView(
                PlanViewType.LOWEST_COST,
                List.of(lowestCost.id()),
                "Lowest supplier price: " + lowestCost.quote().price() + ".",
                Map.of(), Map.of()));
        views.put(PlanViewType.SHORTEST_DURATION, new PlanView(
                PlanViewType.SHORTEST_DURATION,
                List.of(shortest.id()),
                "Shortest travel duration: " + shortest.durationMinutes() + " minutes.",
                Map.of(), Map.of()));
        views.put(PlanViewType.CLOSEST_TO_PREFERENCES, new PlanView(
                PlanViewType.CLOSEST_TO_PREFERENCES,
                List.of(closest.id()),
                "Closest match to personal preferences (score " + closest.preferenceScore() + ").",
                Map.of(), Map.of()));
        return views;
    }

    private static BigDecimal normalize(BigDecimal numerator, BigDecimal denominator) {
        if (denominator.signum() == 0) {
            return BigDecimal.valueOf(100);
        }
        return numerator.multiply(BigDecimal.valueOf(100))
                .divide(denominator, 2, RoundingMode.HALF_UP);
    }

    public record Revalidation(
            boolean canBook, TravelPlan plan, SupplierAdapter.QuoteCheck check, String message) {}
}
