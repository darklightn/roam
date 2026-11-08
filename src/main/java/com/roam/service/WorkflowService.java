package com.roam.service;

import com.roam.domain.Actor;
import com.roam.domain.BookingOrder;
import com.roam.domain.BookingResult;
import com.roam.domain.BookingOrderState;
import com.roam.domain.FailureReason;
import com.roam.domain.FailureType;
import com.roam.domain.IntentPackage;
import com.roam.domain.IntentRecord;
import com.roam.domain.IntentRequest;
import com.roam.domain.IntentResultView;
import com.roam.domain.IntentStatus;
import com.roam.domain.ItineraryOption;
import com.roam.domain.PlanViewType;
import com.roam.domain.SubmissionResult;
import com.roam.domain.TravelPlan;
import com.roam.domain.TripRequest;
import com.roam.domain.TripRequestStatus;
import com.roam.domain.WorkflowInstance;
import com.roam.domain.WorkflowResult;
import com.roam.domain.WorkflowStatus;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * MasterAgent: the single entry and cross-Agent coordinator. It routes intent work, coordinates
 * independent and dependent tasks, pauses for HITL, and projects accepted results into durable
 * workflow state. It never writes Trip Request approval state directly.
 */
@Service
public class WorkflowService {

    private final InMemoryStore store;
    private final Ids ids;
    private final AuditService audit;
    private final AccessControl access;
    private final IntentPackageValidator validator;
    private final TripRequestService tripRequests;
    private final PlanningService planning;
    private final BookingAgent bookingAgent;
    private final HitlService hitl;
    private final ExecutorService executor;
    private final Clock clock;

    public WorkflowService(
            InMemoryStore store,
            Ids ids,
            AuditService audit,
            AccessControl access,
            IntentPackageValidator validator,
            TripRequestService tripRequests,
            PlanningService planning,
            BookingAgent bookingAgent,
            HitlService hitl,
            ExecutorService executor,
            Clock clock) {
        this.store = store;
        this.ids = ids;
        this.audit = audit;
        this.access = access;
        this.validator = validator;
        this.tripRequests = tripRequests;
        this.planning = planning;
        this.bookingAgent = bookingAgent;
        this.hitl = hitl;
        this.executor = executor;
        this.clock = clock;
    }

    public WorkflowResult handle(IntentPackage pkg, Actor actor) {
        WorkflowPreparation preparation = prepare(pkg, actor);
        if (preparation.planId() == null) {
            return preparation.result();
        }
        TravelPlan plan = planning.latest(preparation.tripRequestId(), actor);
        WorkflowInstance workflow = requireWorkflow(preparation.workflowId());
        if (!allIntentsSelected(plan, workflow)) {
            workflow.setSummary(
                    "Select an Itinerary Option and its Quote for every intent before execution.");
            return result(workflow);
        }
        return execute(preparation.workflowId(), actor);
    }

    /**
     * Validates the package, creates/updates the Trip Request, and runs approval and planning in
     * parallel. The employee can inspect and select an option before execution.
     */
    public WorkflowPreparation prepare(IntentPackage pkg, Actor actor) {
        validator.validate(pkg);
        TripRequest trip = tripRequests.createOrUpdate(pkg, actor);
        WorkflowInstance workflow = new WorkflowInstance(
                ids.next("workflow"), pkg.sessionId(), trip.id(), clock.instant());
        for (IntentRequest intent : pkg.intents()) {
            String dependency = intent.dependsOn().isEmpty() ? null : intent.dependsOn().get(0);
            IntentRecord record = new IntentRecord(
                    intent.id(), intent.type(), ids.next("task"), dependency);
            workflow.addIntent(record, intent);
        }
        store.workflows().put(workflow.id(), workflow);
        audit.append(actor, "WORKFLOW_STARTED", "Workflow", workflow.id(),
                "session=" + pkg.sessionId() + ";tripRequest=" + trip.id()
                        + ";intents=" + pkg.intents().size(),
                null);

        CompletableFuture<TravelPlan> planFuture = CompletableFuture.supplyAsync(
                () -> planning.plan(trip, pkg.intents(), actor), executor);
        SubmissionResult submission = tripRequests.submit(trip.id(), actor);
        if (submission.status() != TripRequestStatus.APPROVED) {
            planFuture.cancel(false);
            audit.append(actor, "WORKFLOW_WAITING_FOR_TRIP_REQUEST", "Workflow", workflow.id(),
                    "tripStatus=" + submission.status(), submission.nextStep());
            return preparation(workflow, trip, submission, null,
                    "Trip Request is " + submission.status() + ". " + submission.nextStep());
        }
        TravelPlan plan = planFuture.join();
        audit.append(actor, "WORKFLOW_PLANNED", "Workflow", workflow.id(),
                "plan=" + plan.id() + ";version=" + plan.version(),
                "approval and planning completed in parallel");
        return preparation(workflow, trip, submission, plan,
                "Trip Request approved. Travel Plan version " + plan.version() + " is ready.");
    }

    public WorkflowResult execute(String workflowId, Actor actor) {
        WorkflowInstance workflow = requireWorkflow(workflowId);
        TripRequest trip = store.trips().get(workflow.tripRequestId());
        access.requireEmployeeOwnsTrip(actor, trip);
        if (!trip.isApprovedForCurrentVersion()) {
            throw RoamException.conflict("workflow execution requires an approved Trip Request");
        }
        TravelPlan plan = planning.latest(trip.id(), actor);
        for (IntentRecord intent : workflow.intents()) {
            if (selectedOptionFor(plan, workflow.request(intent.id())) == null) {
                throw RoamException.conflict(
                        "select an option and quote for intent " + intent.id() + " before execution");
            }
        }
        if (workflow.status() == WorkflowStatus.COMPLETED) {
            return result(workflow);
        }

        while (true) {
            List<IntentRecord> runnable = workflow.intents().stream()
                    .filter(intent -> intent.status() == IntentStatus.PENDING
                            || intent.status() == IntentStatus.WAITING_DEPENDENCY)
                    .filter(intent -> dependencyCompleted(workflow, intent))
                    .toList();
            if (runnable.isEmpty()) {
                break;
            }
            List<CompletableFuture<Void>> futures = runnable.stream()
                    .map(intent -> CompletableFuture.runAsync(
                            () -> executeIntent(workflow, plan, intent, actor), executor))
                    .toList();
            futures.forEach(CompletableFuture::join);
        }

        // Any intent still WaitingDependency because its prerequisite never completed is not a
        // silent success: record the blocked reason without rewriting the prerequisite.
        workflow.intents().stream()
                .filter(intent -> intent.status() == IntentStatus.WAITING_DEPENDENCY)
                .filter(intent -> workflow.intent(intent.dependsOn()).status() != IntentStatus.PENDING
                        && workflow.intent(intent.dependsOn()).status() != IntentStatus.WAITING_HITL)
                .forEach(intent -> intent.markFailure(
                        IntentStatus.FAILED,
                        new FailureReason(
                                FailureType.TERMINAL,
                                "dependency " + intent.dependsOn() + " did not complete",
                                "DEPENDENCY_FAILED",
                                clock.instant(),
                                intent.taskId())));

        finalizeWorkflow(workflow, trip, actor);
        return result(workflow);
    }

    public WorkflowResult result(String workflowId, Actor actor) {
        WorkflowInstance workflow = requireWorkflow(workflowId);
        TripRequest trip = store.trips().get(workflow.tripRequestId());
        access.requireEmployeeOwnsTrip(actor, trip);
        return result(workflow);
    }

    public WorkflowInstance workflow(String workflowId) {
        return requireWorkflow(workflowId);
    }

    private void executeIntent(
            WorkflowInstance workflow, TravelPlan plan, IntentRecord intent, Actor actor) {
        IntentRequest request = workflow.request(intent.id());
        ItineraryOption option = selectedOptionFor(plan, request);
        if (option == null) {
            throw RoamException.conflict(
                    "select an option and quote for intent " + request.id());
        }
        audit.append(actor, "TASK_STARTED", "IntentTask", intent.taskId(),
                "intent=" + intent.id() + ";orderOption=" + option.id()
                        + ";session=" + workflow.sessionId(),
                null);
        TaskContext context = TaskContext.forIntent(
                intent.taskId(), workflow.id(), workflow.sessionId(), workflow.tripRequestId(), request);
        BookingResult bookingResult;
        try {
            bookingResult = bookingAgent.execute(context, plan, option, actor);
        } catch (RuntimeException e) {
            FailureReason failure = new FailureReason(
                    FailureType.TERMINAL, e.getMessage(), "BOOKING_EXCEPTION",
                    clock.instant(), intent.taskId());
            intent.markFailure(IntentStatus.FAILED, failure);
            audit.append(actor, "TASK_FAILED", "IntentTask", intent.taskId(),
                    "intent=" + intent.id() + ";failureType=TERMINAL", e.getMessage());
            return;
        }
        intent.markOrder(bookingResult.orderId());
        BookingOrder order = store.orders().get(bookingResult.orderId());
        if (order != null) {
            order.claimByWorkflow(workflow.id());
        }
        if (bookingResult.state() == BookingOrderState.SUBMITTED) {
            intent.markCompleted(bookingResult.supplierOrderId());
            audit.append(actor, "TASK_COMPLETED", "IntentTask", intent.taskId(),
                    correlation(workflow, intent, bookingResult),
                    "supplier order id received; not paid or fulfilled");
            return;
        }
        FailureReason failure = bookingResult.failure() == null
                ? new FailureReason(FailureType.TERMINAL, "booking did not complete",
                        "BOOKING_INCOMPLETE", clock.instant(), intent.taskId())
                : bookingResult.failure();
        if (failure.type() == FailureType.OVER_BUDGET) {
            hitl.requestBudgetException(
                    store.orders().get(bookingResult.orderId()), workflow.id(), workflowEmployee(workflow));
            intent.markStatus(IntentStatus.WAITING_HITL, failure.reason());
            workflow.setStatus(WorkflowStatus.WAITING_HITL);
            audit.append(actor, "TASK_BLOCKED_BUDGET_EXCEPTION", "IntentTask", intent.taskId(),
                    correlation(workflow, intent, bookingResult),
                    failure.reason());
        } else if (failure.type() == FailureType.UNKNOWN_EXTERNAL_OUTCOME) {
            intent.markFailure(IntentStatus.UNKNOWN_EXTERNAL_OUTCOME, failure);
            audit.append(actor, "TASK_UNKNOWN_OUTCOME", "IntentTask", intent.taskId(),
                    correlation(workflow, intent, bookingResult),
                    "supplier outcome uncertain; employee warned about duplicate risk");
        } else {
            intent.markFailure(IntentStatus.FAILED, failure);
            audit.append(actor, "TASK_FAILED", "IntentTask", intent.taskId(),
                    correlation(workflow, intent, bookingResult),
                    failure.reason());
        }
    }

    private ItineraryOption selectedOptionFor(TravelPlan plan, IntentRequest request) {
        String selectedForIntent = plan.selectedOptionForIntent(request.id());
        if (selectedForIntent != null) {
            return plan.option(selectedForIntent);
        }
        if (plan.selectedOptionId() != null) {
            ItineraryOption selected = plan.option(plan.selectedOptionId());
            if (selected.type() == request.type()) {
                return selected;
            }
        }
        return null;
    }

    private boolean allIntentsSelected(TravelPlan plan, WorkflowInstance workflow) {
        return workflow.requests().stream()
                .allMatch(request -> selectedOptionFor(plan, request) != null);
    }

    private boolean dependencyCompleted(WorkflowInstance workflow, IntentRecord intent) {
        return intent.dependsOn() == null
                || workflow.intent(intent.dependsOn()).status() == IntentStatus.COMPLETED;
    }

    private void finalizeWorkflow(WorkflowInstance workflow, TripRequest trip, Actor actor) {
        boolean allCompleted = workflow.allCompleted();
        boolean anyWaiting = workflow.anyWaiting();
        boolean anyCompleted = workflow.anyCompleted();
        if (allCompleted) {
            workflow.setStatus(WorkflowStatus.COMPLETED);
        } else if (anyWaiting) {
            workflow.setStatus(workflow.intents().stream()
                            .anyMatch(intent -> intent.status() == IntentStatus.WAITING_HITL)
                    ? WorkflowStatus.WAITING_HITL : WorkflowStatus.IN_PROGRESS);
        } else if (anyCompleted) {
            workflow.setStatus(WorkflowStatus.PARTIALLY_FAILED);
        } else {
            workflow.setStatus(WorkflowStatus.FAILED);
        }

        TripRequestStatus tripStatus;
        if (allCompleted) {
            tripStatus = TripRequestStatus.FULFILLED;
        } else if (anyCompleted) {
            tripStatus = TripRequestStatus.PARTIALLY_FULFILLED;
        } else if (anyWaiting) {
            tripStatus = TripRequestStatus.EXECUTION_IN_PROGRESS;
        } else {
            tripStatus = TripRequestStatus.FAILED;
        }
        workflow.setSummary(buildSummary(workflow));
        tripRequests.recordExecutionStatus(trip, tripStatus, actor);
        audit.append(actor, "WORKFLOW_STATUS", "Workflow", workflow.id(),
                "status=" + workflow.status() + ";tripStatus=" + tripStatus,
                workflow.summary());
    }

    private WorkflowResult result(WorkflowInstance workflow) {
        TripRequest trip = store.trips().get(workflow.tripRequestId());
        TravelPlan plan = store.plans().values().stream()
                .filter(candidate -> candidate.tripRequestId().equals(trip.id()))
                .max(Comparator.comparingInt(TravelPlan::version))
                .orElse(null);
        List<IntentResultView> views = workflow.intents().stream()
                .map(intent -> {
                    BookingOrder order = intent.orderId() == null
                            ? null : store.orders().get(intent.orderId());
                    return new IntentResultView(
                            intent.id(), intent.taskId(), intent.type(), intent.status(),
                            intent.orderId(), intent.supplierOrderId(), intent.failure(),
                            order != null && order.duplicateRiskWarned());
                })
                .toList();
        return new WorkflowResult(
                workflow.id(),
                workflow.sessionId(),
                trip.id(),
                trip.status(),
                workflow.status(),
                plan == null ? 0 : plan.version(),
                plan == null ? null : plan.id(),
                plan == null ? null : plan.selectedOptionId(),
                views,
                workflow.summary());
    }

    private WorkflowPreparation preparation(
            WorkflowInstance workflow,
            TripRequest trip,
            SubmissionResult submission,
            TravelPlan plan,
            String summary) {
        workflow.setSummary(summary);
        WorkflowResult result = result(workflow);
        return new WorkflowPreparation(
                workflow.id(), trip.id(), submission.status(),
                plan == null ? null : plan.id(), plan == null ? 0 : plan.version(),
                submission.nextStep(), summary, result);
    }

    private String correlation(
            WorkflowInstance workflow, IntentRecord intent, BookingResult bookingResult) {
        return "session=" + workflow.sessionId()
                + ";tripRequest=" + workflow.tripRequestId()
                + ";intent=" + intent.id()
                + ";task=" + intent.taskId()
                + ";order=" + bookingResult.orderId()
                + ";supplierOrder=" + (bookingResult.supplierOrderId() == null
                        ? "not-received" : bookingResult.supplierOrderId());
    }

    private String workflowEmployee(WorkflowInstance workflow) {
        return store.trips().get(workflow.tripRequestId()).employeeId();
    }

    private String buildSummary(WorkflowInstance workflow) {
        long completed = workflow.intents().stream()
                .filter(intent -> intent.status() == IntentStatus.COMPLETED).count();
        long waiting = workflow.intents().stream()
                .filter(intent -> intent.status() == IntentStatus.WAITING_HITL
                        || intent.status() == IntentStatus.WAITING_DEPENDENCY)
                .count();
        long failed = workflow.intents().stream()
                .filter(intent -> intent.status() == IntentStatus.FAILED).count();
        long unknown = workflow.intents().stream()
                .filter(intent -> intent.status() == IntentStatus.UNKNOWN_EXTERNAL_OUTCOME).count();
        String items = workflow.intents().stream()
                .map(intent -> intent.id() + " (" + intent.type() + "): " + intent.status()
                        + (intent.supplierOrderId() == null ? "" : " " + intent.supplierOrderId())
                        + (intent.failure() == null ? "" : " - " + intent.failure().reason()))
                .collect(Collectors.joining("; "));
        boolean duplicateRisk = workflow.intents().stream()
                .map(IntentRecord::orderId)
                .filter(java.util.Objects::nonNull)
                .map(id -> store.orders().get(id))
                .anyMatch(order -> order != null && order.duplicateRiskWarned());
        String completion = workflow.allCompleted()
                ? "All required intents completed. Orders are Submitted, not paid or fulfilled."
                : "Trip is not complete until every required intent completes.";
        if (duplicateRisk) {
            completion += " Duplicate-risk warning: a lost supplier response may have created an"
                    + " undiscoverable supplier-side order.";
        }
        return completed + " completed, " + waiting + " waiting, " + failed + " failed, "
                + unknown + " uncertain. " + completion + " " + items;
    }

    private WorkflowInstance requireWorkflow(String workflowId) {
        WorkflowInstance workflow = store.workflows().get(workflowId);
        if (workflow == null) {
            throw RoamException.notFound("Workflow");
        }
        return workflow;
    }

    public record WorkflowPreparation(
            String workflowId,
            String tripRequestId,
            TripRequestStatus submissionStatus,
            String planId,
            int planVersion,
            String nextStep,
            String summary,
            WorkflowResult result) {}
}
