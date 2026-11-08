package com.roam;

import static org.assertj.core.api.Assertions.assertThat;

import com.roam.domain.Actor;
import com.roam.domain.IntentPackage;
import com.roam.domain.ItineraryOption;
import com.roam.domain.TravelPlan;
import com.roam.domain.WorkflowResult;
import com.roam.domain.IntentRequest;
import com.roam.domain.IntentType;
import com.roam.domain.TripRequestData;
import com.roam.domain.TripStop;
import com.roam.service.AlertService;
import com.roam.service.AuditService;
import com.roam.service.BookingService;
import com.roam.service.BudgetPolicy;
import com.roam.service.HitlService;
import com.roam.service.Ids;
import com.roam.service.InMemoryStore;
import com.roam.service.MetricsService;
import com.roam.service.PlanningService;
import com.roam.service.RecoveryService;
import com.roam.service.Redaction;
import com.roam.service.TraceService;
import com.roam.service.TripRequestService;
import com.roam.service.WorkflowService;
import com.roam.supplier.FixtureScript;
import com.roam.supplier.SupplierAdapter;
import com.roam.supplier.TuniuFixtureAdapter;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestClockConfiguration.class)
public abstract class AcceptanceTestBase {

    protected static final Instant NOW = Instant.parse("2026-11-10T09:00:00Z");
    protected static final LocalDate DEPART = LocalDate.parse("2026-12-01");
    protected static final LocalDate RETURN = LocalDate.parse("2026-12-03");

    @Autowired protected InMemoryStore store;
    @Autowired protected Ids ids;
    @Autowired protected MutableClock clock;
    @Autowired protected FixtureScript fixtures;
    @Autowired protected TuniuFixtureAdapter adapter;
    @Autowired protected SupplierAdapter supplier;
    @Autowired protected AuditService audit;
    @Autowired protected Redaction redaction;
    @Autowired protected MetricsService metrics;
    @Autowired protected AlertService alerts;
    @Autowired protected BudgetPolicy budget;
    @Autowired protected TripRequestService tripRequests;
    @Autowired protected PlanningService planning;
    @Autowired protected BookingService booking;
    @Autowired protected HitlService hitl;
    @Autowired protected WorkflowService workflow;
    @Autowired protected RecoveryService recovery;
    @Autowired protected TraceService trace;

    protected final Actor employee = Actor.employee("emp-1");
    protected final Actor otherEmployee = Actor.employee("emp-2");
    protected final Actor admin = Actor.administrator("admin-1");
    protected final Actor secondAdmin = Actor.administrator("admin-2");

    @BeforeEach
    void resetWorld() {
        store.reset();
        ids.reset();
        clock.set(NOW);
        adapter.reset();
        metrics.reset();
        budget.reset();
    }

    protected TripRequestData sampleTrip() {
        return new TripRequestData(
                "Li Wei",
                "110101199001011234",
                "Beijing",
                List.of(new TripStop("Shanghai", DEPART, RETURN)),
                false,
                "Client meeting");
    }

    protected IntentRequest flight(String id) {
        return new IntentRequest(
                id, IntentType.FLIGHT, "Beijing", "Shanghai", DEPART, RETURN, List.of(), java.util.Map.of());
    }

    protected IntentRequest hotel(String id) {
        return new IntentRequest(
                id, IntentType.HOTEL, "Shanghai", "Shanghai", DEPART, RETURN, List.of(), java.util.Map.of());
    }

    protected IntentRequest train(String id, String origin, String destination,
            LocalDate start, LocalDate end) {
        return new IntentRequest(
                id, IntentType.TRAIN, origin, destination, start, end, List.of(), java.util.Map.of());
    }

    protected IntentPackage pkg(String sessionId, TripRequestData data, IntentRequest... intents) {
        return new IntentPackage(
                "pkg-" + sessionId, sessionId, "1.0", null, data, List.of(intents));
    }

    protected WorkflowService.WorkflowPreparation prepareAndAssertApproved(
            String sessionId, TripRequestData data, IntentRequest... intents) {
        WorkflowService.WorkflowPreparation preparation =
                workflow.prepare(pkg(sessionId, data, intents), employee);
        assertThat(preparation.submissionStatus())
                .isEqualTo(com.roam.domain.TripRequestStatus.APPROVED);
        return preparation;
    }

    protected WorkflowResult run(
            String sessionId, TripRequestData data, IntentRequest... intents) {
        WorkflowService.WorkflowPreparation preparation =
                workflow.prepare(pkg(sessionId, data, intents), employee);
        if (preparation.planId() == null) {
            return preparation.result();
        }
        return selectAndExecute(preparation, employee);
    }

    /** Test helper that performs the explicit employee selection required before execution. */
    protected WorkflowResult selectAndExecute(
            WorkflowService.WorkflowPreparation preparation, Actor actor) {
        selectDefaults(preparation, actor);
        return workflow.execute(preparation.workflowId(), actor);
    }

    protected void selectDefaults(WorkflowService.WorkflowPreparation preparation, Actor actor) {
        TravelPlan plan = planning.get(preparation.planId(), actor);
        var workflowInstance = workflow.workflow(preparation.workflowId());
        for (IntentRequest request : workflowInstance.requests()) {
            String selectedForIntent = plan.selectedOptionForIntent(request.id());
            if (selectedForIntent != null) {
                continue;
            }
            if (plan.selectedOptionId() != null) {
                ItineraryOption selected = plan.option(plan.selectedOptionId());
                if (selected.type() == request.type()) {
                    continue;
                }
            }
            java.util.List<String> preferred = plan.view(
                    com.roam.domain.PlanViewType.OVERALL_BEST).optionIds();
            ItineraryOption option = plan.options().stream()
                    .filter(candidate -> candidate.id().startsWith(request.id() + ":"))
                    .sorted(java.util.Comparator.comparingInt(candidate -> {
                        int index = preferred.indexOf(candidate.id());
                        return index < 0 ? Integer.MAX_VALUE : index;
                    }))
                    .findFirst()
                    .orElseThrow();
            planning.selectForIntent(
                    plan.id(), request.id(), option.id(), option.quote().id(), actor);
        }
    }
}
