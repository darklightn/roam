package com.roam.service;

import com.roam.domain.Actor;
import com.roam.domain.AttemptOutcome;
import com.roam.domain.BookingAttempt;
import com.roam.domain.BookingOrder;
import com.roam.domain.BookingOrderState;
import com.roam.domain.BookingResult;
import com.roam.domain.CallbackObservation;
import com.roam.domain.CallbackRequest;
import com.roam.domain.CallbackStatus;
import com.roam.domain.FailureReason;
import com.roam.domain.FailureType;
import com.roam.domain.IntentType;
import com.roam.domain.ItineraryOption;
import com.roam.domain.Money;
import com.roam.domain.OperationType;
import com.roam.domain.Quote;
import com.roam.domain.TravelPlan;
import com.roam.domain.TripRequest;
import com.roam.domain.TripRequestStatus;
import com.roam.supplier.SupplierAdapter;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class BookingService {

    public static final int MAX_SUPPLIER_CALLS = 3;

    private final InMemoryStore store;
    private final Ids ids;
    private final AuditService audit;
    private final AccessControl access;
    private final SupplierAdapter supplier;
    private final PlanningService planning;
    private final BudgetPolicy budgetPolicy;
    private final MetricsService metrics;
    private final Redaction redaction;
    private final Clock clock;

    public BookingService(
            InMemoryStore store,
            Ids ids,
            AuditService audit,
            AccessControl access,
            SupplierAdapter supplier,
            PlanningService planning,
            BudgetPolicy budgetPolicy,
            MetricsService metrics,
            Redaction redaction,
            Clock clock) {
        this.store = store;
        this.ids = ids;
        this.audit = audit;
        this.access = access;
        this.supplier = supplier;
        this.planning = planning;
        this.budgetPolicy = budgetPolicy;
        this.metrics = metrics;
        this.redaction = redaction;
        this.clock = clock;
    }

    public BookingOrder prepareOrder(
            String tripRequestId,
            String planId,
            String intentId,
            String optionId,
            String quoteId,
            Actor actor) {
        TripRequest trip = requireTrip(tripRequestId);
        access.requireEmployeeOwnsTripForMutation(actor, trip);
        if (!trip.isApprovedForCurrentVersion()) {
            throw RoamException.conflict("booking requires an approved Trip Request version");
        }
        TravelPlan plan = requirePlan(planId, tripRequestId);
        ItineraryOption option = plan.option(optionId);
        if (!option.quote().id().equals(quoteId)) {
            throw RoamException.invalid("quote does not belong to the selected option");
        }
        String selectedForIntent = plan.selectedOptionForIntent(intentId);
        boolean selected = optionId.equals(selectedForIntent)
                || plan.matchesSelected(optionId, quoteId);
        if (!selected) {
            throw RoamException.conflict("the employee must select the option and quote before booking");
        }
        BookingOrder order = new BookingOrder(
                ids.next("order"),
                tripRequestId,
                intentId,
                option.type(),
                planId,
                optionId,
                quoteId,
                option.quote().price(),
                clock.instant());
        store.orders().put(order.id(), order);
        audit.append(actor, "BOOKING_ORDER_PERSISTED", "BookingOrder", order.id(),
                "state=PENDING;intent=" + intentId + ";option=" + optionId
                        + ";price=" + order.price(),
                "persisted before the first supplier call");
        return order;
    }

    public BookingResult prepareAndSubmit(
            String tripRequestId,
            String planId,
            String intentId,
            String optionId,
            String quoteId,
            Actor actor) {
        BookingOrder order = prepareOrder(
                tripRequestId, planId, intentId, optionId, quoteId, actor);
        return submit(order.id(), actor, false);
    }

    public BookingResult submit(String orderId, Actor actor) {
        return submit(orderId, actor, false);
    }

    private BookingResult submit(String orderId, Actor actor, boolean budgetExceptionApproved) {
        BookingOrder order = requireOrder(orderId);
        TripRequest trip = requireTrip(order.tripRequestId());
        access.requireEmployeeOwnsTripForMutation(actor, trip);
        if (order.state() != BookingOrderState.PENDING) {
            throw RoamException.conflict("only a Pending Booking Order can be submitted");
        }
        if (!trip.isApprovedForCurrentVersion()) {
            return failure(order, new FailureReason(
                    FailureType.INVALID_REQUEST,
                    "Trip Request is not approved for its current version",
                    null, clock.instant(), null), false);
        }

        Money ceiling = budgetPolicy.ceilingFor(trip.employeeId());
        if (!budgetExceptionApproved && order.price().isGreaterThan(ceiling)) {
            FailureReason failure = new FailureReason(
                    FailureType.OVER_BUDGET,
                    "order price " + order.price() + " exceeds the Budget Ceiling " + ceiling,
                    "BUDGET_CEILING",
                    clock.instant(),
                    null);
            order.recordFailure(failure, clock.instant());
            audit.append(actor, "BOOKING_BLOCKED_OVER_BUDGET", "BookingOrder", order.id(),
                    "price=" + order.price() + ";ceiling=" + ceiling + ";supplierCalls=0",
                    failure.reason());
            return new BookingResult(order.id(), order.state(), null, null, null,
                    Map.of(), failure, false, 0);
        }

        TravelPlan plan = requirePlan(order.planId(), order.tripRequestId());
        ItineraryOption option = plan.option(order.optionId());
        Quote quote = option.quote();
        PlanningService.Revalidation revalidation =
                planning.revalidateForBooking(plan, option.id(), quote, actor);
        if (!revalidation.canBook()) {
            FailureReason failure = new FailureReason(
                    revalidation.check().status() == SupplierAdapter.QuoteCheck.Status.EXPIRED
                            ? FailureType.QUOTE_EXPIRED : FailureType.QUOTE_CHANGED,
                    revalidation.message(),
                    "TUNIU_QUOTE_" + revalidation.check().status(),
                    clock.instant(),
                    null);
            order.recordFailure(failure, clock.instant());
            audit.append(actor, "BOOKING_BLOCKED_QUOTE_REVALIDATION", "BookingOrder", order.id(),
                    "result=" + revalidation.check().status() + ";plan=" + revalidation.plan().id(),
                    failure.reason());
            return new BookingResult(order.id(), order.state(), null, null, null,
                    Map.of(), failure, false, 0);
        }

        SupplierAdapter.SupplierOrderCommand command = new SupplierAdapter.SupplierOrderCommand(
                order.id(),
                order.type(),
                quote.supplierRef(),
                order.price(),
                quote.supplierTerms(),
                null,
                order.intentId());

        boolean duplicateRisk = false;
        FailureReason lastFailure = null;
        for (int call = 1; call <= MAX_SUPPLIER_CALLS; call++) {
            String attemptId = ids.next("attempt");
            metrics.recordOrderAttempt(call);
            try {
                SupplierAdapter.SupplierOrderResult result = supplier.createOrder(command);
                if (result.success() && result.supplierOrderId() != null
                        && !result.supplierOrderId().isBlank()) {
                    Map<String, String> references = redactReferences(result.rawReferences());
                    String paymentLink = references.entrySet().stream()
                            .filter(entry -> entry.getKey().toLowerCase().contains("pay")
                                    || entry.getKey().toLowerCase().contains("url"))
                            .map(Map.Entry::getValue)
                            .findFirst()
                            .orElse(null);
                    String confirmation = references.get("confirmationNumber");
                    order.markSubmitted(
                            result.supplierOrderId(), references, paymentLink, confirmation,
                            clock.instant());
                    order.addAttempt(new BookingAttempt(
                            attemptId, OperationType.BOOK, AttemptOutcome.SUCCESS,
                            result.supplierOrderId(), clock.instant(),
                            "supplier returned order id"), clock.instant());
                    metrics.recordOrderSuccess();
                    audit.append(actor, "SUPPLIER_BOOKING_RESPONSE", "BookingOrder", order.id(),
                            "attempt=" + attemptId + ";call=" + call
                                    + ";supplierOrderId=" + result.supplierOrderId(),
                            null);
                    audit.append(actor, "BOOKING_ORDER_SUBMITTED", "BookingOrder", order.id(),
                            "state=SUBMITTED;supplierOrderId=" + result.supplierOrderId()
                                    + ";paymentRef=" + paymentLink,
                            "supplier order id received; not paid or fulfilled");
                    if (duplicateRisk) {
                        return new BookingResult(order.id(), order.state(),
                                order.supplierOrderId(), order.paymentLinkReference(),
                                order.confirmationReference(), order.supplierReferences(),
                                null, true, call);
                    }
                    return new BookingResult(order.id(), order.state(),
                            order.supplierOrderId(), order.paymentLinkReference(),
                            order.confirmationReference(), order.supplierReferences(),
                            null, false, call);
                }
                lastFailure = result.failure();
                FailureType type = lastFailure == null ? FailureType.TERMINAL : lastFailure.type();
                AttemptOutcome outcome = switch (type) {
                    case RETRYABLE -> AttemptOutcome.RETRYABLE_FAILURE;
                    case UNKNOWN_EXTERNAL_OUTCOME -> AttemptOutcome.UNKNOWN;
                    case UNSUPPORTED -> AttemptOutcome.UNSUPPORTED;
                    default -> AttemptOutcome.TERMINAL_FAILURE;
                };
                order.addAttempt(new BookingAttempt(
                        attemptId, OperationType.BOOK, outcome,
                        lastFailure == null ? null : lastFailure.supplierCode(), clock.instant(),
                        lastFailure == null ? "supplier failure" : lastFailure.reason()),
                        clock.instant());
                if (lastFailure != null) {
                    order.recordFailure(lastFailure, clock.instant());
                    metrics.recordSupplierError();
                }
                audit.append(actor, "SUPPLIER_BOOKING_RESPONSE", "BookingOrder", order.id(),
                        "attempt=" + attemptId + ";call=" + call + ";result=" + outcome
                                + ";supplierCode="
                                + (lastFailure == null ? "" : lastFailure.supplierCode()),
                        lastFailure == null ? null : lastFailure.reason());
                if (type == FailureType.RETRYABLE && call < MAX_SUPPLIER_CALLS) {
                    continue;
                }
                if (type == FailureType.UNKNOWN_EXTERNAL_OUTCOME) {
                    duplicateRisk = true;
                    order.warnDuplicateRisk();
                    metrics.recordUnknownOutcome();
                    audit.append(actor, "DUPLICATE_RISK_WARNING", "BookingOrder", order.id(),
                            "call=" + call, "supplier may have created an undiscoverable duplicate");
                    return new BookingResult(order.id(), order.state(), null, null, null,
                            Map.of(), lastFailure, true, call);
                }
                return new BookingResult(order.id(), order.state(), null, null, null,
                        Map.of(), lastFailure, duplicateRisk, call);
            } catch (SupplierAdapter.SupplierTimeoutException timeout) {
                duplicateRisk = true;
                order.warnDuplicateRisk();
                metrics.recordUnknownOutcome();
                metrics.recordSupplierError();
                lastFailure = new FailureReason(
                        FailureType.UNKNOWN_EXTERNAL_OUTCOME,
                        "supplier response was lost; the order may exist supplier-side",
                        "TUNIU_TIMEOUT",
                        clock.instant(),
                        attemptId);
                order.recordFailure(lastFailure, clock.instant());
                order.addAttempt(new BookingAttempt(
                        attemptId, OperationType.BOOK, AttemptOutcome.UNKNOWN,
                        "TUNIU_TIMEOUT", clock.instant(), timeout.getMessage()), clock.instant());
                audit.append(actor, "SUPPLIER_BOOKING_RESPONSE", "BookingOrder", order.id(),
                        "attempt=" + attemptId + ";call=" + call + ";result=UNKNOWN;supplierCode=TUNIU_TIMEOUT",
                        "supplier response lost; duplicate risk");
                audit.append(actor, "DUPLICATE_RISK_WARNING", "BookingOrder", order.id(),
                        "call=" + call, "supplier may have created an undiscoverable duplicate");
                if (call < MAX_SUPPLIER_CALLS) {
                    continue;
                }
                return new BookingResult(order.id(), order.state(), null, null, null,
                        Map.of(), lastFailure, true, call);
            }
        }
        FailureReason exhausted = new FailureReason(
                FailureType.RETRYABLE,
                "bounded supplier call limit reached without a confirmed result",
                "TUNIU_RETRY_EXHAUSTED",
                clock.instant(),
                null);
        order.recordFailure(exhausted, clock.instant());
        audit.append(actor, "BOOKING_RETRY_EXHAUSTED", "BookingOrder", order.id(),
                "supplierCalls=" + MAX_SUPPLIER_CALLS,
                "order remains Pending");
        return new BookingResult(order.id(), order.state(), null, null, null,
                Map.of(), exhausted, duplicateRisk, MAX_SUPPLIER_CALLS);
    }

    /** Resumes only the reviewed order after an approved budget exception. */
    public BookingResult resumeAfterBudgetApproval(String orderId, Actor administrator) {
        access.requireAdministrator(administrator);
        BookingOrder order = requireOrder(orderId);
        boolean approved = store.reviews().values().stream()
                .anyMatch(review -> review.type() == com.roam.domain.ReviewType.BUDGET_EXCEPTION
                        && review.subjectId().equals(orderId)
                        && review.decision() == com.roam.domain.Decision.APPROVED);
        if (!approved) {
            throw RoamException.conflict("no approved budget exception for this order");
        }
        audit.append(administrator, "BUDGET_EXCEPTION_RESUME", "BookingOrder", order.id(),
                "state=" + order.state(), "approved exception applies only to this order");
        TripRequest trip = requireTrip(order.tripRequestId());
        return submit(orderId, Actor.employee(trip.employeeId()), true);
    }

    public BookingResult cancel(String orderId, Actor actor) {
        BookingOrder order = requireOrder(orderId);
        TripRequest trip = requireTrip(order.tripRequestId());
        access.requireEmployeeOwnsTripForMutation(actor, trip);
        if (order.state() != BookingOrderState.SUBMITTED) {
            throw RoamException.conflict("only a Submitted order can be cancelled");
        }
        if (order.type() == IntentType.HOTEL) {
            FailureReason failure = new FailureReason(
                    FailureType.UNSUPPORTED,
                    "Tuniu does not support hotel cancellation; handle it manually or contact HR",
                    "TUNIU_UNSUPPORTED",
                    clock.instant(),
                    null);
            order.recordFailure(failure, clock.instant());
            audit.append(actor, "CANCELLATION_UNSUPPORTED", "BookingOrder", order.id(),
                    "type=HOTEL;state=" + order.state(), failure.reason());
            return new BookingResult(order.id(), order.state(), order.supplierOrderId(),
                    order.paymentLinkReference(), order.confirmationReference(),
                    order.supplierReferences(), failure, false, 0);
        }
        if (order.paidObserved()) {
            FailureReason failure = new FailureReason(
                    FailureType.UNSUPPORTED,
                    "paid orders cannot be cancelled in the MVP",
                    "TUNIU_PAID_ORDER",
                    clock.instant(),
                    null);
            order.recordFailure(failure, clock.instant());
            audit.append(actor, "CANCELLATION_UNSUPPORTED", "BookingOrder", order.id(),
                    "paidObserved=true;state=" + order.state(), failure.reason());
            return new BookingResult(order.id(), order.state(), order.supplierOrderId(),
                    order.paymentLinkReference(), order.confirmationReference(),
                    order.supplierReferences(), failure, false, 0);
        }

        SupplierAdapter.SupplierCancelCommand command = new SupplierAdapter.SupplierCancelCommand(
                order.id(), order.type(), order.supplierOrderId(), order.paidObserved());
        FailureReason lastFailure = null;
        for (int call = 1; call <= MAX_SUPPLIER_CALLS; call++) {
            String attemptId = ids.next("attempt");
            try {
                SupplierAdapter.SupplierCancelResult result = supplier.cancelOrder(command);
                if (result.success()) {
                    order.markCancelled(clock.instant());
                    order.addAttempt(new BookingAttempt(
                            attemptId, OperationType.CANCEL,
                            result.alreadyCancelled()
                                    ? AttemptOutcome.ALREADY_CANCELLED : AttemptOutcome.SUCCESS,
                            result.alreadyCancelled() ? "ALREADY_CANCELLED" : "CANCELLED",
                            clock.instant(),
                            result.alreadyCancelled()
                                    ? "supplier confirmed already cancelled"
                                    : "supplier confirmed cancellation"),
                            clock.instant());
                    audit.append(actor, "BOOKING_ORDER_CANCELLED", "BookingOrder", order.id(),
                            "state=CANCELLED;alreadyCancelled=" + result.alreadyCancelled()
                                    + ";call=" + call,
                            "explicit supplier confirmation");
                    refreshAfterCancellation(order, actor);
                    return new BookingResult(order.id(), order.state(), order.supplierOrderId(),
                            order.paymentLinkReference(), order.confirmationReference(),
                            order.supplierReferences(), null, false, call);
                }
                lastFailure = result.failure();
                FailureType type = lastFailure == null ? FailureType.TERMINAL : lastFailure.type();
                order.addAttempt(new BookingAttempt(
                        attemptId, OperationType.CANCEL,
                        type == FailureType.RETRYABLE
                                ? AttemptOutcome.RETRYABLE_FAILURE
                                : type == FailureType.UNSUPPORTED
                                        ? AttemptOutcome.UNSUPPORTED
                                        : AttemptOutcome.TERMINAL_FAILURE,
                        lastFailure == null ? null : lastFailure.supplierCode(),
                        clock.instant(),
                        lastFailure == null ? "cancellation failure" : lastFailure.reason()),
                        clock.instant());
                order.recordFailure(lastFailure, clock.instant());
                metrics.recordSupplierError();
                audit.append(actor, "SUPPLIER_CANCELLATION_RESPONSE", "BookingOrder", order.id(),
                        "attempt=" + attemptId + ";call=" + call + ";result=" + type,
                        lastFailure == null ? null : lastFailure.reason());
                if (type == FailureType.RETRYABLE && call < MAX_SUPPLIER_CALLS) {
                    continue;
                }
                return new BookingResult(order.id(), order.state(), order.supplierOrderId(),
                        order.paymentLinkReference(), order.confirmationReference(),
                        order.supplierReferences(), lastFailure, false, call);
            } catch (SupplierAdapter.SupplierTimeoutException timeout) {
                order.addAttempt(new BookingAttempt(
                        attemptId, OperationType.CANCEL, AttemptOutcome.UNKNOWN,
                        "TUNIU_TIMEOUT", clock.instant(), timeout.getMessage()), clock.instant());
                lastFailure = new FailureReason(
                        FailureType.UNKNOWN_EXTERNAL_OUTCOME,
                        "cancellation outcome is uncertain; the order remains in its last confirmed state",
                        "TUNIU_TIMEOUT", clock.instant(), attemptId);
                order.recordFailure(lastFailure, clock.instant());
                metrics.recordUnknownOutcome();
                audit.append(actor, "SUPPLIER_CANCELLATION_RESPONSE", "BookingOrder", order.id(),
                        "attempt=" + attemptId + ";call=" + call + ";result=UNKNOWN",
                        "supplier response lost");
                if (call == MAX_SUPPLIER_CALLS) {
                    return new BookingResult(order.id(), order.state(), order.supplierOrderId(),
                            order.paymentLinkReference(), order.confirmationReference(),
                            order.supplierReferences(), lastFailure, false, call);
                }
            }
        }
        return new BookingResult(order.id(), order.state(), order.supplierOrderId(),
                order.paymentLinkReference(), order.confirmationReference(),
                order.supplierReferences(), lastFailure, false, MAX_SUPPLIER_CALLS);
    }

    /**
     * Validates and records a supplier payment-status callback as an observation. It never executes
     * payment, cancels an order, or changes Booking Order state.
     */
    public CallbackObservation recordPaymentCallback(CallbackRequest request) {
        String expectedSignature = CallbackSignatures.sign(request.callbackId());
        boolean valid = request.callbackId() != null && !request.callbackId().isBlank()
                && "TUNIU".equalsIgnoreCase(request.supplier())
                && request.supplierOrderId() != null && !request.supplierOrderId().isBlank()
                && request.paymentStatus() != null && !request.paymentStatus().isBlank()
                && expectedSignature.equals(request.signature());
        BookingOrder order = findOrderBySupplierId(request.supplierOrderId());
        if (!valid || order == null) {
            metrics.recordCallbackInvalid();
            CallbackObservation observation = new CallbackObservation(
                    ids.next("callback"),
                    request.callbackId(),
                    request.supplier(),
                    request.supplierOrderId(),
                    CallbackStatus.INVALID,
                    request.paymentStatus(),
                    clock.instant(),
                    "invalid callback rejected");
            store.callbacks().put(observation.id(), observation);
            audit.append(Actor.system("supplier-callback"), "PAYMENT_CALLBACK_INVALID",
                    "BookingOrder", request.supplierOrderId() == null ? "unknown" : request.supplierOrderId(),
                    "callback=" + request.callbackId(), "signature or reference validation failed");
            return observation;
        }
        if (store.callbacks().values().stream()
                .anyMatch(existing -> existing.callbackId().equals(request.callbackId())
                        && existing.status() == CallbackStatus.RECEIVED)) {
            metrics.recordCallbackDeduplicated();
            CallbackObservation duplicate = new CallbackObservation(
                    ids.next("callback"),
                    request.callbackId(),
                    request.supplier(),
                    request.supplierOrderId(),
                    CallbackStatus.DUPLICATE,
                    request.paymentStatus(),
                    clock.instant(),
                    "duplicate callback ignored");
            store.callbacks().put(duplicate.id(), duplicate);
            audit.append(Actor.system("supplier-callback"), "PAYMENT_CALLBACK_DUPLICATE",
                    "BookingOrder", order.id(), "callback=" + request.callbackId(),
                    "deduplicated; no state change");
            return duplicate;
        }

        String detail = redaction.redactText(
                "status=" + request.paymentStatus()
                        + ";amount=" + request.amount() + " " + request.currency());
        CallbackObservation observation = new CallbackObservation(
                ids.next("callback"),
                request.callbackId(),
                request.supplier(),
                request.supplierOrderId(),
                CallbackStatus.RECEIVED,
                request.paymentStatus(),
                clock.instant(),
                detail);
        store.callbacks().put(observation.id(), observation);
        metrics.recordCallbackReceived();
        if ("PAID".equalsIgnoreCase(request.paymentStatus())) {
            order.markPaidObserved();
        }
        audit.append(Actor.system("supplier-callback"), "PAYMENT_CALLBACK_OBSERVED",
                "BookingOrder", order.id(),
                "callback=" + request.callbackId() + ";" + detail,
                "observation only; Booking Order state unchanged");
        return observation;
    }

    /** Change and refund execution are outside the MVP and never change order state. */
    public BookingResult requestUnsupportedOperation(String orderId, String operation, Actor actor) {
        if (!"CHANGE".equals(operation) && !"REFUND".equals(operation)) {
            throw RoamException.invalid("unsupported operation type " + operation);
        }
        BookingOrder order = requireOrder(orderId);
        TripRequest trip = requireTrip(order.tripRequestId());
        access.requireEmployeeOwnsTripForMutation(actor, trip);
        FailureReason failure = new FailureReason(
                FailureType.UNSUPPORTED,
                operation.toLowerCase() + " execution is not supported in the MVP; handle it manually or contact HR",
                "TUNIU_UNSUPPORTED",
                clock.instant(),
                null);
        order.recordFailure(failure, clock.instant());
        audit.append(actor, "UNSUPPORTED_OPERATION_REQUESTED", "BookingOrder", order.id(),
                "operation=" + operation + ";state=" + order.state() + ";supplierCalls=0",
                failure.reason());
        return new BookingResult(order.id(), order.state(), order.supplierOrderId(),
                order.paymentLinkReference(), order.confirmationReference(),
                order.supplierReferences(), failure, false, 0);
    }

    public BookingOrder order(String orderId, Actor actor) {
        BookingOrder order = requireOrder(orderId);
        TripRequest trip = requireTrip(order.tripRequestId());
        access.requireEmployeeOwnsOrder(actor, order, trip);
        return order;
    }

    public Map<String, String> redactReferences(Map<String, String> raw) {
        Map<String, String> references = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            String lower = key.toLowerCase();
            if (lower.contains("pay") || lower.contains("url") || lower.contains("token")) {
                references.put(key, redaction.opaqueRef("payment-link", value));
            } else {
                references.put(key, redaction.redactText(value));
            }
        });
        return Map.copyOf(references);
    }

    private void refreshAfterCancellation(BookingOrder order, Actor actor) {
        if (order.claimedByWorkflow() == null) {
            return;
        }
        com.roam.domain.WorkflowInstance workflow =
                store.workflows().get(order.claimedByWorkflow());
        if (workflow == null) {
            return;
        }
        com.roam.domain.IntentRecord intent = workflow.intent(order.intentId());
        intent.markStatus(com.roam.domain.IntentStatus.CANCELLED, "order cancelled");
        TripRequest trip = requireTrip(order.tripRequestId());
        boolean allCancelled = workflow.intents().stream()
                .allMatch(item -> item.status() == com.roam.domain.IntentStatus.CANCELLED);
        boolean anyCompleted = workflow.anyCompleted();
        if (allCancelled) {
            workflow.setStatus(com.roam.domain.WorkflowStatus.CANCELLED);
            trip.markExecution(TripRequestStatus.CANCELLED);
        } else if (anyCompleted) {
            workflow.setStatus(com.roam.domain.WorkflowStatus.PARTIALLY_COMPLETED);
            trip.markExecution(TripRequestStatus.PARTIALLY_FULFILLED);
        } else {
            workflow.setStatus(com.roam.domain.WorkflowStatus.FAILED);
            trip.markExecution(TripRequestStatus.FAILED);
        }
        workflow.setSummary("cancellation recorded for order " + order.id());
        audit.append(actor, "WORKFLOW_STATUS", "Workflow", workflow.id(),
                "status=" + workflow.status() + ";tripStatus=" + trip.status(),
                "cancellation projected without compensating sibling orders");
    }

    private BookingOrder findOrderBySupplierId(String supplierOrderId) {
        if (supplierOrderId == null) {
            return null;
        }
        return store.orders().values().stream()
                .filter(order -> supplierOrderId.equals(order.supplierOrderId()))
                .findFirst()
                .orElse(null);
    }

    private BookingOrder requireOrder(String orderId) {
        BookingOrder order = store.orders().get(orderId);
        if (order == null) {
            throw RoamException.notFound("BookingOrder");
        }
        return order;
    }

    private TripRequest requireTrip(String tripRequestId) {
        TripRequest trip = store.trips().get(tripRequestId);
        if (trip == null) {
            throw RoamException.notFound("TripRequest");
        }
        return trip;
    }

    private TravelPlan requirePlan(String planId, String tripRequestId) {
        TravelPlan plan = store.plans().get(planId);
        if (plan == null || !plan.tripRequestId().equals(tripRequestId)) {
            throw RoamException.notFound("TravelPlan");
        }
        return plan;
    }

    private BookingResult failure(BookingOrder order, FailureReason failure, boolean duplicateRisk) {
        order.recordFailure(failure, clock.instant());
        audit.append(Actor.system("booking"), "BOOKING_REJECTED", "BookingOrder", order.id(),
                "failureType=" + failure.type(), failure.reason());
        return new BookingResult(order.id(), order.state(), null, null, null,
                Map.of(), failure, duplicateRisk, 0);
    }
}
