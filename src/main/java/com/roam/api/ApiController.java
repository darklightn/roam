package com.roam.api;

import com.roam.domain.Actor;
import com.roam.domain.BookingOrder;
import com.roam.domain.BookingResult;
import com.roam.domain.CallbackObservation;
import com.roam.domain.CallbackRequest;
import com.roam.domain.Decision;
import com.roam.domain.HitlReview;
import com.roam.domain.IntentPackage;
import com.roam.domain.IntentRequest;
import com.roam.domain.ItineraryOption;
import com.roam.domain.PlanView;
import com.roam.domain.PlanViewType;
import com.roam.domain.Role;
import com.roam.domain.TravelPlan;
import com.roam.domain.TripRequest;
import com.roam.domain.TripRequestData;
import com.roam.domain.WorkflowResult;
import com.roam.service.AlertService;
import com.roam.service.BookingService;
import com.roam.service.HitlService;
import com.roam.service.MetricsService;
import com.roam.service.PlanningService;
import com.roam.service.RecoveryService;
import com.roam.service.Redaction;
import com.roam.service.RoamException;
import com.roam.service.TraceService;
import com.roam.service.TripRequestService;
import com.roam.service.WorkflowService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final WorkflowService workflow;
    private final TripRequestService tripRequests;
    private final PlanningService planning;
    private final BookingService booking;
    private final HitlService hitl;
    private final MetricsService metrics;
    private final AlertService alerts;
    private final Redaction redaction;
    private final RecoveryService recovery;
    private final TraceService trace;

    public ApiController(
            WorkflowService workflow,
            TripRequestService tripRequests,
            PlanningService planning,
            BookingService booking,
            HitlService hitl,
            MetricsService metrics,
            AlertService alerts,
            Redaction redaction,
            RecoveryService recovery,
            TraceService trace) {
        this.workflow = workflow;
        this.tripRequests = tripRequests;
        this.planning = planning;
        this.booking = booking;
        this.hitl = hitl;
        this.metrics = metrics;
        this.alerts = alerts;
        this.redaction = redaction;
        this.recovery = recovery;
        this.trace = trace;
    }

    @PostMapping("/intent-packages")
    public WorkflowResult submitIntentPackage(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Role") String role,
            @RequestBody IntentPackageBody body) {
        Actor actor = actor(userId, role);
        return workflow.handle(toPackage(body), actor);
    }

    @PostMapping("/intent-packages/prepare")
    public WorkflowService.WorkflowPreparation prepareIntentPackage(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Role") String role,
            @RequestBody IntentPackageBody body) {
        Actor actor = actor(userId, role);
        return workflow.prepare(toPackage(body), actor);
    }

    @PostMapping("/workflows/{workflowId}/execute")
    public WorkflowResult executeWorkflow(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Role") String role,
            @PathVariable String workflowId) {
        return workflow.execute(workflowId, actor(userId, role));
    }

    @GetMapping("/workflows/{workflowId}")
    public WorkflowResult workflowResult(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Role") String role,
            @PathVariable String workflowId) {
        return workflow.result(workflowId, actor(userId, role));
    }

    @GetMapping("/trip-requests/{tripRequestId}")
    public TripRequestView tripRequest(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Role") String role,
            @PathVariable String tripRequestId) {
        TripRequest trip = tripRequests.get(tripRequestId, actor(userId, role));
        return tripView(trip);
    }

    @GetMapping("/trip-requests/{tripRequestId}/plan")
    public PlanResponse plan(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Role") String role,
            @PathVariable String tripRequestId) {
        TravelPlan plan = planning.latest(tripRequestId, actor(userId, role));
        return planView(plan);
    }

    @PostMapping("/plans/{planId}/selection")
    public PlanResponse selectOption(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Role") String role,
            @PathVariable String planId,
            @RequestBody SelectionBody body) {
        Actor actor = actor(userId, role);
        TravelPlan plan = body.intentId() == null
                ? planning.selectOption(planId, body.optionId(), body.quoteId(), actor)
                : planning.selectForIntent(
                        planId, body.intentId(), body.optionId(), body.quoteId(), actor);
        return planView(plan);
    }

    @GetMapping("/orders/{orderId}")
    public OrderView order(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Role") String role,
            @PathVariable String orderId) {
        BookingOrder order = booking.order(orderId, actor(userId, role));
        return new OrderView(
                order.id(),
                order.tripRequestId(),
                order.intentId(),
                order.type().name(),
                order.state().name(),
                order.supplierOrderId(),
                order.paymentLinkReference(),
                order.confirmationReference(),
                order.supplierReferences(),
                order.lastFailure() == null ? null : order.lastFailure().type().name(),
                order.duplicateRiskWarned(),
                order.paidObserved(),
                order.attempts().size());
    }

    @PostMapping("/orders/{orderId}/cancel")
    public BookingResult cancel(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Role") String role,
            @PathVariable String orderId) {
        return booking.cancel(orderId, actor(userId, role));
    }

    @PostMapping("/trip-requests/{tripRequestId}/reviews")
    public ReviewView requestReview(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Role") String role,
            @PathVariable String tripRequestId) {
        return reviewView(tripRequests.requestReview(tripRequestId, actor(userId, role)));
    }

    @DeleteMapping("/trip-requests/{tripRequestId}/reviews/current")
    public TripRequestView cancelReview(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Role") String role,
            @PathVariable String tripRequestId) {
        return tripView(tripRequests.cancelReview(tripRequestId, actor(userId, role)));
    }

    @GetMapping("/admin/reviews")
    public List<ReviewView> reviewQueue(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Role") String role) {
        return hitl.queue(actor(userId, role)).stream().map(this::reviewView).toList();
    }

    @PostMapping("/admin/reviews/{reviewId}/claim")
    public ReviewView claim(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Role") String role,
            @PathVariable String reviewId) {
        return reviewView(hitl.claim(reviewId, actor(userId, role)));
    }

    @PostMapping("/admin/reviews/{reviewId}/decision")
    public ReviewView decide(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Role") String role,
            @PathVariable String reviewId,
            @RequestBody DecisionBody body) {
        return reviewView(hitl.decide(
                reviewId, actor(userId, role), body.decision(), body.reason()));
    }

    @PostMapping("/supplier/payment-callbacks")
    public CallbackObservation callback(@RequestBody CallbackRequest request) {
        return booking.recordPaymentCallback(request);
    }

    @GetMapping("/admin/metrics")
    public MetricsService.MetricsSnapshot metrics(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Role") String role) {
        requireAdmin(actor(userId, role));
        return metrics.snapshot();
    }

    @GetMapping("/admin/alerts")
    public List<?> alerts(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Role") String role) {
        requireAdmin(actor(userId, role));
        return alerts.evaluate();
    }

    @GetMapping("/admin/trace/{sessionId}")
    public TraceService.TraceChain trace(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Role") String role,
            @PathVariable String sessionId) {
        return trace.trace(sessionId, actor(userId, role));
    }

    @PostMapping("/admin/workflows/{workflowId}/takeover")
    public WorkflowServiceTakeover takeover(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Role") String role,
            @PathVariable String workflowId,
            @RequestBody ReasonBody body) {
        var instance = recovery.takeOver(workflowId, actor(userId, role), body.reason());
        return new WorkflowServiceTakeover(
                instance.id(), instance.status().name(), instance.ownerAdministrator(),
                instance.summary());
    }

    @PostMapping("/admin/recovery-drills")
    public RecoveryService.RecoveryDrillReport drill(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Role") String role,
            @RequestBody DrillBody body) {
        return recovery.runRecoveryDrill(
                body.workflowId(), body.orderId(), actor(userId, role), body.reason());
    }

    @PostMapping("/admin/recovery/orders/{orderId}")
    public OrderView resolveUnknown(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Role") String role,
            @PathVariable String orderId,
            @RequestBody ResolveBody body) {
        BookingOrder order = recovery.resolveUncertainOutcome(
                orderId, actor(userId, role), body.reason(), body.confirmedSupplierOrderId());
        return new OrderView(
                order.id(), order.tripRequestId(), order.intentId(), order.type().name(),
                order.state().name(), order.supplierOrderId(), order.paymentLinkReference(),
                order.confirmationReference(), order.supplierReferences(),
                order.lastFailure() == null ? null : order.lastFailure().type().name(),
                order.duplicateRiskWarned(), order.paidObserved(), order.attempts().size());
    }

    @ExceptionHandler(RoamException.class)
    public ProblemDetail handleRoam(RoamException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(exception.status());
        problem.setTitle(exception.code());
        problem.setDetail(exception.getMessage());
        problem.setProperty("code", exception.code());
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegal(IllegalArgumentException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("invalid_request");
        problem.setDetail(exception.getMessage());
        problem.setProperty("code", "invalid_request");
        return problem;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleState(IllegalStateException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("conflict");
        problem.setDetail(exception.getMessage());
        problem.setProperty("code", "conflict");
        return problem;
    }

    private Actor actor(String userId, String role) {
        if (userId == null || userId.isBlank()) {
            throw new RoamException("unauthenticated", "X-User-Id is required", 401);
        }
        Role parsed;
        try {
            parsed = Role.valueOf(role);
        } catch (RuntimeException e) {
            throw new RoamException("unauthenticated", "unknown role " + role, 401);
        }
        if (parsed == Role.SYSTEM) {
            throw new RoamException("forbidden", "SYSTEM is not a human role", 403);
        }
        return new Actor(userId, parsed);
    }

    private void requireAdmin(Actor actor) {
        if (!actor.isAdministrator()) {
            throw RoamException.forbidden("Administrator role required");
        }
    }

    private IntentPackage toPackage(IntentPackageBody body) {
        return new IntentPackage(
                body.packageId(),
                body.sessionId(),
                body.contractVersion() == null ? "1.0" : body.contractVersion(),
                body.tripRequestId(),
                body.tripRequest(),
                body.intents());
    }

    private TripRequestView tripView(TripRequest trip) {
        TripRequestData data = trip.currentData();
        return new TripRequestView(
                trip.id(),
                trip.status().name(),
                trip.currentVersionNumber(),
                trip.versionLocked(),
                trip.pendingReviewId(),
                data.travelerName(),
                redaction.maskIdNumber(data.travelerIdNumber()),
                data.origin(),
                data.stops(),
                data.oneWay(),
                data.tripReason(),
                trip.approvalRecords().stream()
                        .map(record -> new ApprovalView(
                                record.id(), record.version(), record.decidedAt(),
                                record.decidedBy(), record.decidedByRole().name(),
                                record.automatic(), record.basis()))
                        .toList());
    }

    private PlanResponse planView(TravelPlan plan) {
        List<OptionView> options = plan.options().stream()
                .map(option -> new OptionView(
                        option.id(),
                        option.type().name(),
                        option.description(),
                        option.origin(),
                        option.destination(),
                        option.startDate(),
                        option.endDate(),
                        option.durationMinutes(),
                        option.preferenceScore(),
                        option.quote().id(),
                        option.quote().price().amount(),
                        option.quote().price().currency(),
                        option.quote().validUntil(),
                        option.quote().status().name()))
                .toList();
        Map<PlanViewType, ViewResponse> views = plan.views().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> new ViewResponse(
                                entry.getValue().optionIds(),
                                entry.getValue().explanation(),
                                entry.getValue().scoreDimensions(),
                                entry.getValue().weights())));
        return new PlanResponse(
                plan.id(), plan.tripRequestId(), plan.version(), plan.createdAt(),
                plan.isSelected(), plan.isReadOnly(), plan.isSuperseded(),
                plan.selectedOptionId(), plan.selectedQuoteId(), options, views);
    }

    private ReviewView reviewView(HitlReview review) {
        return new ReviewView(
                review.id(), review.type().name(), review.status().name(),
                review.subjectId(), review.tripRequestId(), review.tripRequestVersion(),
                review.requestedBy(), review.requestedAt(), review.claimedBy(), review.claimedAt(),
                review.decision() == null ? null : review.decision().name(),
                review.decisionReason(), review.reminderCount());
    }

    public record IntentPackageBody(
            String packageId,
            String sessionId,
            String contractVersion,
            String tripRequestId,
            TripRequestData tripRequest,
            List<IntentRequest> intents) {}

    public record SelectionBody(String intentId, String optionId, String quoteId) {}

    public record DecisionBody(Decision decision, String reason) {}

    public record ReasonBody(String reason) {}

    public record DrillBody(String workflowId, String orderId, String reason) {}

    public record ResolveBody(String reason, String confirmedSupplierOrderId) {}

    public record WorkflowServiceTakeover(
            String workflowId, String status, String ownerAdministrator, String summary) {}

    public record TripRequestView(
            String id,
            String status,
            int currentVersion,
            boolean versionLocked,
            String pendingReviewId,
            String travelerName,
            String travelerIdNumber,
            String origin,
            List<com.roam.domain.TripStop> stops,
            boolean oneWay,
            String tripReason,
            List<ApprovalView> approvals) {}

    public record ApprovalView(
            String id, int version, Instant decidedAt, String decidedBy,
            String role, boolean automatic, String basis) {}

    public record OrderView(
            String id,
            String tripRequestId,
            String intentId,
            String type,
            String state,
            String supplierOrderId,
            String paymentLinkReference,
            String confirmationReference,
            Map<String, String> supplierReferences,
            String failureType,
            boolean duplicateRiskWarning,
            boolean paidObserved,
            int attempts) {}

    public record ReviewView(
            String id, String type, String status, String subjectId, String tripRequestId,
            int tripRequestVersion, String requestedBy, Instant requestedAt, String claimedBy,
            Instant claimedAt, String decision, String decisionReason, int reminderCount) {}

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

    public record OptionView(
            String id, String type, String description, String origin, String destination,
            LocalDate startDate, LocalDate endDate, int durationMinutes, int preferenceScore,
            String quoteId, BigDecimal price, String currency, Instant validUntil,
            String quoteStatus) {}

    public record ViewResponse(
            List<String> optionIds,
            String explanation,
            Map<String, BigDecimal> scoreDimensions,
            Map<String, BigDecimal> weights) {}
}
