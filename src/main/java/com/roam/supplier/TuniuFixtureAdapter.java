package com.roam.supplier;

import com.roam.domain.AttemptOutcome;
import com.roam.domain.FailureReason;
import com.roam.domain.FailureType;
import com.roam.domain.IntentType;
import com.roam.domain.Money;
import com.roam.domain.OperationType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Deterministic example adapter with the same externally visible behavior as Tuniu's flight, hotel,
 * and train MCP tools. Fixtures can force retryable, terminal, unsupported, and uncertain outcomes.
 */
@Component
public class TuniuFixtureAdapter implements SupplierAdapter {

    private final FixtureScript script;
    private final Clock clock;
    private final AtomicLong flightOrders = new AtomicLong(1000);
    private final AtomicLong hotelOrders = new AtomicLong(2000);
    private final AtomicLong trainOrders = new AtomicLong(3000);
    private final Map<String, Integer> calls = new ConcurrentHashMap<>();
    private Runnable beforeSupplierCall = () -> {};

    public TuniuFixtureAdapter(FixtureScript script, Clock clock) {
        this.script = script;
        this.clock = clock;
    }

    public void setBeforeSupplierCall(Runnable hook) {
        this.beforeSupplierCall = hook == null ? () -> {} : hook;
    }

    public int callCount(String orderId, OperationType operation) {
        return calls.getOrDefault(orderId + ":" + operation, 0);
    }

    public void reset() {
        script.reset();
        calls.clear();
        beforeSupplierCall = () -> {};
        flightOrders.set(1000);
        hotelOrders.set(2000);
        trainOrders.set(3000);
    }

    @Override
    public List<SupplierOffer> search(SearchRequest request) {
        String city = request.destination() == null ? "UNKNOWN" : request.destination();
        String origin = request.origin() == null ? "UNKNOWN" : request.origin();
        Instant validUntil = clock.instant().plusSeconds(1800);
        List<SupplierOffer> offers = new ArrayList<>();
        switch (request.type()) {
            case FLIGHT -> {
                offers.add(new SupplierOffer(
                        "FL-" + origin + "-" + city + "-FAST",
                        "CABIN-FAST-" + origin + "-" + city,
                        "Nonstop " + origin + " to " + city,
                        130, 90, Money.cny("1200"), validUntil,
                        Map.of("cabinPriceId", "CABIN-FAST-" + origin + "-" + city)));
                offers.add(new SupplierOffer(
                        "FL-" + origin + "-" + city + "-CHEAP",
                        "CABIN-CHEAP-" + origin + "-" + city,
                        "Connecting " + origin + " to " + city,
                        175, 70, Money.cny("880"), validUntil,
                        Map.of("cabinPriceId", "CABIN-CHEAP-" + origin + "-" + city)));
            }
            case HOTEL -> {
                offers.add(new SupplierOffer(
                        "HT-" + city + "-CENTRAL",
                        "PREBOOK-CENTRAL-" + city,
                        "Central hotel in " + city,
                        0, 95, Money.cny("620"), validUntil,
                        Map.of("preBookParam", "PREBOOK-CENTRAL-" + city)));
                offers.add(new SupplierOffer(
                        "HT-" + city + "-BUDGET",
                        "PREBOOK-BUDGET-" + city,
                        "Budget hotel in " + city,
                        0, 65, Money.cny("430"), validUntil,
                        Map.of("preBookParam", "PREBOOK-BUDGET-" + city)));
            }
            case TRAIN -> {
                offers.add(new SupplierOffer(
                        "TR-" + origin + "-" + city + "-G",
                        "RES-G-" + origin + "-" + city,
                        "High-speed " + origin + " to " + city,
                        290, 85, Money.cny("320"), validUntil,
                        Map.of("resId", "RES-G-" + origin + "-" + city)));
                offers.add(new SupplierOffer(
                        "TR-" + origin + "-" + city + "-D",
                        "RES-D-" + origin + "-" + city,
                        "Standard " + origin + " to " + city,
                        330, 70, Money.cny("250"), validUntil,
                        Map.of("resId", "RES-D-" + origin + "-" + city)));
            }
            default -> throw new IllegalStateException("unsupported intent type");
        }
        return offers;
    }

    @Override
    public QuoteCheck reprice(String supplierQuoteRef) {
        QuoteCheck scripted = script.nextReprice(supplierQuoteRef);
        if (scripted != null) {
            return scripted;
        }
        return new QuoteCheck(QuoteCheck.Status.STILL_VALID, null, null, "supplier revalidated");
    }

    @Override
    public SupplierOrderResult createOrder(SupplierOrderCommand command) {
        int count = calls.merge(command.orderId() + ":" + OperationType.BOOK, 1, Integer::sum);
        beforeSupplierCall.run();
        AttemptOutcome outcome = script.next(OperationType.BOOK, command.orderId(), command.type());
        if (outcome == null || outcome == AttemptOutcome.SUCCESS) {
            String supplierOrderId = nextSupplierOrderId(command.type());
            return SupplierOrderResult.success(supplierOrderId, rawReferences(command.type(), supplierOrderId));
        }
        if (outcome == AttemptOutcome.RETRYABLE_FAILURE) {
            return failure(FailureType.RETRYABLE, "Tuniu temporarily unavailable", "TUNIU_TEMP");
        }
        if (outcome == AttemptOutcome.TERMINAL_FAILURE) {
            return failure(FailureType.TERMINAL, "Tuniu rejected the order", "TUNIU_REJECTED");
        }
        if (outcome == AttemptOutcome.UNSUPPORTED) {
            return failure(FailureType.UNSUPPORTED, "Tuniu does not support this operation", "TUNIU_UNSUPPORTED");
        }
        throw new SupplierTimeoutException(
                "Tuniu response was lost after attempt " + count + "; supplier may have created an order",
                true);
    }

    @Override
    public SupplierCancelResult cancelOrder(SupplierCancelCommand command) {
        calls.merge(command.orderId() + ":" + OperationType.CANCEL, 1, Integer::sum);
        if (command.type() == IntentType.HOTEL) {
            return cancelFailure(FailureType.UNSUPPORTED, "Tuniu has no hotel cancellation tool", "TUNIU_UNSUPPORTED");
        }
        if (command.paid()) {
            return cancelFailure(FailureType.UNSUPPORTED, "Paid orders cannot be cancelled in the MVP", "TUNIU_PAID_ORDER");
        }
        AttemptOutcome outcome = script.next(OperationType.CANCEL, command.orderId(), command.type());
        if (outcome == null || outcome == AttemptOutcome.SUCCESS) {
            return SupplierCancelResult.cancelled(false);
        }
        if (outcome == AttemptOutcome.ALREADY_CANCELLED) {
            return SupplierCancelResult.cancelled(true);
        }
        if (outcome == AttemptOutcome.RETRYABLE_FAILURE) {
            return cancelFailure(FailureType.RETRYABLE, "Tuniu cancellation temporarily unavailable", "TUNIU_TEMP");
        }
        if (outcome == AttemptOutcome.UNSUPPORTED) {
            return cancelFailure(FailureType.UNSUPPORTED, "Tuniu does not support cancellation", "TUNIU_UNSUPPORTED");
        }
        throw new SupplierTimeoutException("Tuniu cancellation response was lost", true);
    }

    @Override
    public SupplierOrderDetail orderDetail(String supplierOrderId, IntentType type) {
        return new SupplierOrderDetail(
                supplierOrderId,
                "CONFIRMED",
                "UNPAID",
                clock.instant());
    }

    private SupplierOrderResult failure(FailureType type, String reason, String code) {
        return new SupplierOrderResult(
                false, null, Map.of(),
                new FailureReason(type, reason, code, clock.instant(), null), false);
    }

    private SupplierCancelResult cancelFailure(FailureType type, String reason, String code) {
        return new SupplierCancelResult(
                false, false, new FailureReason(type, reason, code, clock.instant(), null));
    }

    private String nextSupplierOrderId(IntentType type) {
        return switch (type) {
            case FLIGHT -> "TN-F-" + flightOrders.incrementAndGet();
            case HOTEL -> "TN-H-" + hotelOrders.incrementAndGet();
            case TRAIN -> "TN-T-" + trainOrders.incrementAndGet();
        };
    }

    private Map<String, String> rawReferences(IntentType type, String supplierOrderId) {
        Map<String, String> references = new LinkedHashMap<>();
        switch (type) {
            case FLIGHT -> {
                references.put("supplierOrderId", supplierOrderId);
                references.put("payUrl", "https://pay.tuniu.com/flight/" + supplierOrderId + "?token=secret");
            }
            case HOTEL -> {
                references.put("supplierOrderId", supplierOrderId);
                references.put("confirmationNumber", "CN-" + supplierOrderId);
                references.put("paymentUrl", "https://pay.tuniu.com/hotel/" + supplierOrderId + "?token=secret");
            }
            case TRAIN -> {
                references.put("supplierOrderId", supplierOrderId);
                references.put("orderDetailUrl", "https://pay.tuniu.com/train/" + supplierOrderId + "?token=secret");
            }
        }
        return references;
    }
}
