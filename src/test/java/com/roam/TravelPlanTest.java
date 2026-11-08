package com.roam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.roam.domain.IntentType;
import com.roam.domain.ItineraryOption;
import com.roam.domain.PlanViewType;
import com.roam.domain.QuoteStatus;
import com.roam.domain.TravelPlan;
import com.roam.domain.TripRequest;
import com.roam.domain.TripRequestStatus;
import com.roam.service.RoamException;
import com.roam.service.WorkflowService;
import com.roam.supplier.SupplierAdapter;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Ticket #13: generate and select a four-view Travel Plan. */
class TravelPlanTest extends AcceptanceTestBase {

    @Test
    void planContainsAllFourViewsWithConcreteOptionsAndOneQuotePerOption() {
        WorkflowService.WorkflowPreparation preparation =
                prepareAndAssertApproved("session-plan", sampleTrip(), flight("flight-1"), hotel("hotel-1"));
        TravelPlan plan = planning.get(preparation.planId(), employee);

        assertThat(plan.views()).containsKeys(
                PlanViewType.OVERALL_BEST,
                PlanViewType.LOWEST_COST,
                PlanViewType.SHORTEST_DURATION,
                PlanViewType.CLOSEST_TO_PREFERENCES);
        for (PlanViewType type : PlanViewType.values()) {
            var view = plan.view(type);
            assertThat(view.optionIds()).isNotEmpty();
            for (String optionId : view.optionIds()) {
                ItineraryOption option = plan.option(optionId);
                assertThat(option.quote()).isNotNull();
                assertThat(option.quote().id()).isNotBlank();
            }
        }
        assertThat(plan.view(PlanViewType.OVERALL_BEST).scoreDimensions())
                .containsKeys("cost", "duration", "preferences");
        assertThat(plan.view(PlanViewType.OVERALL_BEST).weights())
                .containsKeys("cost", "duration", "preferences");
        assertThat(plan.view(PlanViewType.OVERALL_BEST).explanation())
                .contains("Recommendation only");
        assertThat(plan.isSelected()).isFalse();
        assertThat(audit.verifyIntegrity()).isTrue();
    }

    @Test
    void employeeExplicitlySelectsAnOptionAndItsQuote() {
        WorkflowService.WorkflowPreparation preparation =
                prepareAndAssertApproved("session-select", sampleTrip(), flight("flight-1"));
        TravelPlan plan = planning.get(preparation.planId(), employee);
        ItineraryOption option = plan.view(PlanViewType.OVERALL_BEST).optionIds().stream()
                .map(plan::option)
                .filter(candidate -> candidate.type() == IntentType.FLIGHT)
                .findFirst()
                .orElseThrow();

        planning.selectOption(plan.id(), option.id(), option.quote().id(), employee);

        assertThat(plan.isSelected()).isTrue();
        assertThat(plan.isReadOnly()).isTrue();
        assertThat(plan.matchesSelected(option.id(), option.quote().id())).isTrue();
        assertThatThrownBy(() -> planning.selectOption(
                plan.id(), plan.options().get(1).id(), plan.options().get(1).quote().id(), employee))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("read-only");

        var result = workflow.execute(preparation.workflowId(), employee);
        assertThat(result.workflowStatus().name()).isEqualTo("COMPLETED");
        assertThat(result.intents()).allMatch(intent -> intent.status().name().equals("COMPLETED"));
    }

    @Test
    void selectedPlanIsReadOnlyAndNewPlanningInputCreatesANewVersion() {
        WorkflowService.WorkflowPreparation preparation =
                prepareAndAssertApproved("session-version", sampleTrip(), flight("flight-1"));
        TravelPlan original = planning.get(preparation.planId(), employee);
        ItineraryOption option = original.options().get(0);
        planning.selectOption(original.id(), option.id(), option.quote().id(), employee);
        String originalPrice = option.quote().price().toString();

        TripRequest trip = tripRequests.get(preparation.tripRequestId(), employee);
        TravelPlan next = planning.plan(
                trip, workflow.workflow(preparation.workflowId()).requests(), employee);

        assertThat(next.version()).isEqualTo(2);
        assertThat(original.isSuperseded()).isTrue();
        assertThat(original.selectedOptionId()).isEqualTo(option.id());
        assertThat(original.option(option.id()).quote().price().toString()).isEqualTo(originalPrice);
        assertThat(next.isSelected()).isFalse();
    }

    @Test
    void expiredQuoteIsRevalidatedAndBlocksBooking() {
        WorkflowService.WorkflowPreparation preparation =
                prepareAndAssertApproved("session-expired", sampleTrip(), flight("flight-1"));
        TravelPlan plan = planning.get(preparation.planId(), employee);
        ItineraryOption option = plan.options().get(0);
        planning.selectOption(plan.id(), option.id(), option.quote().id(), employee);
        fixtures.scriptReprice(option.quote().supplierRef(), new SupplierAdapter.QuoteCheck(
                SupplierAdapter.QuoteCheck.Status.EXPIRED, null, null, "fixture expired"));

        var result = workflow.execute(preparation.workflowId(), employee);

        assertThat(result.workflowStatus().name()).isNotEqualTo("COMPLETED");
        assertThat(result.intents().get(0).status().name()).isEqualTo("FAILED");
        var order = store.orders().values().iterator().next();
        assertThat(order.state().name()).isEqualTo("PENDING");
        assertThat(order.lastFailure().type().name()).isEqualTo("QUOTE_EXPIRED");
        TravelPlan latest = planning.latest(result.tripRequestId(), employee);
        assertThat(latest.version()).isEqualTo(2);
        assertThat(latest.option(option.id()).quote().status()).isEqualTo(QuoteStatus.EXPIRED);
        assertThat(tripRequests.get(result.tripRequestId(), employee).status())
                .isEqualTo(TripRequestStatus.FAILED);
    }

    @Test
    void changedQuoteCreatesANewVersionAndRequiresReselection() {
        WorkflowService.WorkflowPreparation preparation =
                prepareAndAssertApproved("session-changed", sampleTrip(), flight("flight-1"));
        TravelPlan plan = planning.get(preparation.planId(), employee);
        ItineraryOption option = plan.options().get(0);
        planning.selectOption(plan.id(), option.id(), option.quote().id(), employee);
        fixtures.scriptReprice(option.quote().supplierRef(), new SupplierAdapter.QuoteCheck(
                SupplierAdapter.QuoteCheck.Status.CHANGED,
                com.roam.domain.Money.cny("1500"),
                clock.instant().plusSeconds(1800),
                "fixture price changed"));

        var result = workflow.execute(preparation.workflowId(), employee);

        var order = store.orders().values().iterator().next();
        assertThat(order.state().name()).isEqualTo("PENDING");
        assertThat(order.lastFailure().type().name()).isEqualTo("QUOTE_CHANGED");
        TravelPlan latest = planning.latest(result.tripRequestId(), employee);
        assertThat(latest.option(option.id()).quote().status()).isEqualTo(QuoteStatus.CHANGED);
        assertThat(latest.option(option.id()).quote().price().amount())
                .isEqualByComparingTo("1500");
        planning.selectOption(
                latest.id(), latest.options().get(0).id(), latest.options().get(0).quote().id(), employee);
        assertThat(latest.isSelected()).isTrue();
    }

    @Test
    void planIsVisibleOnlyToItsOwnerAndAdministrators() {
        WorkflowService.WorkflowPreparation preparation =
                prepareAndAssertApproved("session-access", sampleTrip(), flight("flight-1"));

        assertThatThrownBy(() -> planning.get(preparation.planId(), otherEmployee))
                .isInstanceOf(RoamException.class)
                .hasMessageContaining("own");
        assertThat(planning.get(preparation.planId(), admin)).isNotNull();
        assertThatThrownBy(() -> planning.selectOption(
                preparation.planId(),
                planning.get(preparation.planId(), employee).options().get(0).id(),
                planning.get(preparation.planId(), employee).options().get(0).quote().id(),
                admin))
                .isInstanceOf(RoamException.class);
    }
}
