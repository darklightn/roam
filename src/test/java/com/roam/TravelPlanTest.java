package com.roam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.roam.agent.contract.WorkflowPreparation;
import com.roam.domain.plan.ItineraryOption;
import com.roam.domain.plan.PlanViewType;
import com.roam.domain.plan.QuoteStatus;
import com.roam.domain.plan.TravelPlan;
import com.roam.domain.port.SupplierAdapter;
import com.roam.domain.trip.TripRequest;
import com.roam.domain.trip.TripRequestStatus;
import com.roam.domain.workflow.IntentType;
import com.roam.shared.RoamException;
import org.junit.jupiter.api.Test;

/** Ticket #13: generate and select a four-view Travel Plan. */
class TravelPlanTest extends AcceptanceTestBase {

    @Test
    void planContainsAllFourViewsWithConcreteOptionsAndOneQuotePerOption() {
        WorkflowPreparation preparation =
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
        WorkflowPreparation preparation =
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

        var result = masterAgent.execute(preparation.workflowId(), employee);
        assertThat(result.workflowStatus().name()).isEqualTo("COMPLETED");
        assertThat(result.intents()).allMatch(intent -> intent.status().name().equals("COMPLETED"));
    }

    @Test
    void executionRequiresExplicitEmployeeSelectionAndNeverBooksAutomatically() {
        WorkflowPreparation preparation =
                prepareAndAssertApproved("session-no-selection", sampleTrip(), flight("flight-1"));

        assertThatThrownBy(() -> masterAgent.execute(preparation.workflowId(), employee))
                .isInstanceOf(RoamException.class)
                .hasMessageContaining("select");
        assertThat(store.orders()).isEmpty();

        var convenience = masterAgent.handle(
                pkg("session-convenience", sampleTrip(), flight("flight-1")), employee);
        assertThat(convenience.workflowStatus().name()).isEqualTo("IN_PROGRESS");
        assertThat(convenience.summary()).contains("Select");
        assertThat(store.orders()).isEmpty();
    }

    @Test
    void selectedPlanIsReadOnlyAndNewPlanningInputCreatesANewVersion() {
        WorkflowPreparation preparation =
                prepareAndAssertApproved("session-version", sampleTrip(), flight("flight-1"));
        TravelPlan original = planning.get(preparation.planId(), employee);
        ItineraryOption option = original.options().get(0);
        planning.selectOption(original.id(), option.id(), option.quote().id(), employee);
        String originalPrice = option.quote().price().toString();

        TripRequest trip = tripRequests.get(preparation.tripRequestId(), employee);
        TravelPlan next = planning.plan(
                trip, masterAgent.workflow(preparation.workflowId()).requests(), employee);

        assertThat(next.version()).isEqualTo(2);
        assertThat(original.isSuperseded()).isTrue();
        assertThat(original.selectedOptionId()).isEqualTo(option.id());
        assertThat(original.option(option.id()).quote().price().toString()).isEqualTo(originalPrice);
        assertThat(next.isSelected()).isFalse();
    }

    @Test
    void expiredQuoteIsRevalidatedAndBlocksBooking() {
        WorkflowPreparation preparation =
                prepareAndAssertApproved("session-expired", sampleTrip(), flight("flight-1"));
        TravelPlan plan = planning.get(preparation.planId(), employee);
        ItineraryOption option = plan.options().get(0);
        planning.selectOption(plan.id(), option.id(), option.quote().id(), employee);
        fixtures.scriptReprice(option.quote().supplierRef(), new SupplierAdapter.QuoteCheck(
                SupplierAdapter.QuoteCheck.Status.EXPIRED, null, null, "fixture expired"));

        var result = masterAgent.execute(preparation.workflowId(), employee);

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
        WorkflowPreparation preparation =
                prepareAndAssertApproved("session-changed", sampleTrip(), flight("flight-1"));
        TravelPlan plan = planning.get(preparation.planId(), employee);
        ItineraryOption option = plan.options().get(0);
        planning.selectOption(plan.id(), option.id(), option.quote().id(), employee);
        fixtures.scriptReprice(option.quote().supplierRef(), new SupplierAdapter.QuoteCheck(
                SupplierAdapter.QuoteCheck.Status.CHANGED,
                com.roam.domain.shared.Money.cny("1500"),
                clock.instant().plusSeconds(1800),
                "fixture price changed"));

        var result = masterAgent.execute(preparation.workflowId(), employee);

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
        WorkflowPreparation preparation =
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
