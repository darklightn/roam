package com.roam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.roam.domain.EmployeeProfile;
import com.roam.domain.IntentPackage;
import com.roam.domain.SubmissionResult;
import com.roam.domain.TripRequest;
import com.roam.domain.TripRequestData;
import com.roam.domain.TripRequestStatus;
import com.roam.domain.TripStop;
import com.roam.service.RoamException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Ticket #12: submit, approve, and review a Trip Request. */
class TripRequestApprovalTest extends AcceptanceTestBase {

    private TripRequest createAndSubmit(TripRequestData data) {
        IntentPackage pkg = new IntentPackage(
                "pkg-" + Math.random(), "session-" + Math.random(), "1.0", null, data,
                List.of(flight("flight-1")));
        TripRequest trip = tripRequests.createOrUpdate(pkg, employee);
        tripRequests.submit(trip.id(), employee);
        return trip;
    }

    @Test
    void completeRequestIsAutomaticallyApprovedAndProducesAnApprovalRecord() {
        TripRequest trip = createAndSubmit(sampleTrip());

        assertThat(trip.status()).isEqualTo(TripRequestStatus.APPROVED);
        assertThat(trip.approvalRecords()).hasSize(1);
        assertThat(trip.approvalForCurrentVersion()).isNotNull();
        assertThat(trip.approvalForCurrentVersion().automatic()).isTrue();
        assertThat(trip.approvalForCurrentVersion().version()).isEqualTo(1);
        assertThat(audit.verifyIntegrity()).isTrue();
        assertThat(store.auditEvents())
                .anyMatch(event -> event.action().equals("TRIP_REQUEST_APPROVED"));
    }

    @Test
    void incompleteRequestWaitsAndPresentsSuggestionsWithoutApplyingThem() {
        tripRequests.registerProfile(new EmployeeProfile(
                "emp-1", "Li Wei", "110101199001011234", "Beijing",
                List.of(new EmployeeProfile.HistoricalTrip("Beijing", "Shanghai", DEPART))));
        TripRequestData incomplete =
                new TripRequestData(null, null, null, List.of(), false, "Client meeting");
        IntentPackage pkg = new IntentPackage(
                "pkg-incomplete", "session-incomplete", "1.0", null, incomplete,
                List.of(flight("flight-1")));
        TripRequest trip = tripRequests.createOrUpdate(pkg, employee);

        SubmissionResult result = tripRequests.submit(trip.id(), employee);

        assertThat(result.status()).isEqualTo(TripRequestStatus.AWAITING_INFO);
        assertThat(result.missingFields()).contains("traveler", "origin", "destination");
        assertThat(result.suggestions())
                .extracting(com.roam.domain.Suggestion::field)
                .contains("traveler", "origin", "destination");
        assertThat(trip.currentData().origin()).isNull();
        assertThat(trip.status()).isEqualTo(TripRequestStatus.AWAITING_INFO);
        assertThat(trip.approvalRecords()).isEmpty();

        tripRequests.applyInformation(trip.id(), employee, new TripRequestData(
                "Li Wei",
                "110101199001011234",
                "Beijing",
                List.of(new TripStop("Shanghai", DEPART, RETURN)),
                false,
                null));
        SubmissionResult approved = tripRequests.submit(trip.id(), employee);

        assertThat(approved.status()).isEqualTo(TripRequestStatus.APPROVED);
        assertThat(trip.currentVersionNumber()).isEqualTo(1);
        assertThat(trip.approvalRecords()).hasSize(1);
    }

    @Test
    void tripReasonOnlyChangeDoesNotCreateANewVersionOrApproval() {
        TripRequest trip = createAndSubmit(sampleTrip());
        TripRequestData before = trip.currentData();

        tripRequests.updateReason(trip.id(), employee, "Updated client meeting purpose");

        assertThat(trip.status()).isEqualTo(TripRequestStatus.APPROVED);
        assertThat(trip.currentVersionNumber()).isEqualTo(1);
        assertThat(trip.versions()).hasSize(1);
        assertThat(trip.approvalRecords()).hasSize(1);
        assertThat(trip.currentData().tripReason()).isEqualTo("Updated client meeting purpose");
        assertThat(trip.currentData().coreItineraryEquals(before)).isTrue();
    }

    @Test
    void coreItineraryChangeCreatesANewVersionAndRequiresReapproval() {
        TripRequest trip = createAndSubmit(sampleTrip());

        tripRequests.applyInformation(trip.id(), employee, new TripRequestData(
                "Li Wei",
                "110101199001011234",
                "Beijing",
                List.of(
                        new TripStop("Shanghai", DEPART, LocalDate.parse("2026-12-02")),
                        new TripStop("Hangzhou", LocalDate.parse("2026-12-02"), RETURN)),
                false,
                "Client meeting"));

        assertThat(trip.status()).isEqualTo(TripRequestStatus.DRAFT);
        assertThatThrownBy(() -> tripRequests.requireBookable(trip.id(), employee))
                .isInstanceOf(RoamException.class)
                .hasMessageContaining("approved");
        assertThat(trip.versions()).hasSize(1);

        SubmissionResult resubmitted = tripRequests.submit(trip.id(), employee);

        assertThat(resubmitted.status()).isEqualTo(TripRequestStatus.APPROVED);
        assertThat(trip.currentVersionNumber()).isEqualTo(2);
        assertThat(trip.approvalRecords()).hasSize(2);
        assertThat(trip.approvalRecords().get(1).version()).isEqualTo(2);
        assertThat(trip.approvalRecords().get(0).version()).isEqualTo(1);
    }

    @Test
    void rejectedRequestCanRequestAdministratorReviewAndIsLockedWhilePending() {
        TripRequest trip = createAndSubmit(new TripRequestData(
                "Li Wei",
                "110101199001011234",
                "Beijing",
                List.of(new TripStop("Shanghai", LocalDate.parse("2026-10-01"), RETURN)),
                false,
                "Client meeting"));
        assertThat(trip.status()).isEqualTo(TripRequestStatus.REJECTED);

        hitl.requestTripReview(trip, employee);

        assertThat(trip.status()).isEqualTo(TripRequestStatus.AWAITING_REVIEW);
        assertThat(trip.versionLocked()).isTrue();
        assertThatThrownBy(() -> tripRequests.applyInformation(trip.id(), employee, sampleTrip()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("locked");
        assertThat(hitl.queue(admin)).hasSize(1);
        assertThat(hitl.queue(secondAdmin)).hasSize(1);

        String reviewId = trip.pendingReviewId();
        hitl.claim(reviewId, admin);
        assertThatThrownBy(() -> hitl.claim(reviewId, secondAdmin))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> hitl.decide(reviewId, admin, com.roam.domain.Decision.APPROVED, " "))
                .isInstanceOf(RoamException.class)
                .hasMessageContaining("reason");

        hitl.decide(reviewId, admin, com.roam.domain.Decision.APPROVED,
                "Exception approved for the client meeting");

        assertThat(trip.status()).isEqualTo(TripRequestStatus.APPROVED);
        assertThat(trip.versionLocked()).isFalse();
        assertThat(trip.approvalRecords()).hasSize(1);
        assertThat(trip.approvalForCurrentVersion().automatic()).isFalse();
        assertThat(trip.approvalForCurrentVersion().decidedBy()).isEqualTo("admin-1");
        assertThat(store.auditEvents())
                .anyMatch(event -> event.action().equals("HITL_DECISION")
                        && event.reason().contains("Exception approved"));
    }

    @Test
    void unclaimedReviewIsRemindedAndNeverAutoDecided() {
        TripRequest trip = createAndSubmit(new TripRequestData(
                "Li Wei",
                "101010199001011234",
                "Beijing",
                List.of(new TripStop("Shanghai", LocalDate.parse("2026-10-01"), RETURN)),
                false,
                "Client meeting"));
        hitl.requestTripReview(trip, employee);
        String reviewId = trip.pendingReviewId();

        assertThat(hitl.remindPending()).isEqualTo(1);
        assertThat(hitl.find(reviewId).reminderCount()).isEqualTo(1);
        assertThat(hitl.find(reviewId).status()).isEqualTo(com.roam.domain.ReviewStatus.UNCLAIMED);
        assertThat(trip.status()).isEqualTo(TripRequestStatus.AWAITING_REVIEW);

        clock.advanceSeconds(120);
        assertThat(alerts.evaluate())
                .anyMatch(alert -> alert.code().equals("HITL_AGING"));
    }

    @Test
    void cancelReviewUnlocksEditingAndAllowsANewVersion() {
        TripRequest trip = createAndSubmit(new TripRequestData(
                "Li Wei",
                "110101199001011234",
                "Beijing",
                List.of(new TripStop("Shanghai", LocalDate.parse("2026-10-01"), RETURN)),
                false,
                "Client meeting"));
        hitl.requestTripReview(trip, employee);

        tripRequests.cancelReview(trip.id(), employee);

        assertThat(trip.versionLocked()).isFalse();
        assertThat(trip.status()).isEqualTo(TripRequestStatus.REJECTED);

        tripRequests.applyInformation(trip.id(), employee, sampleTrip());
        SubmissionResult result = tripRequests.submit(trip.id(), employee);

        assertThat(result.status()).isEqualTo(TripRequestStatus.APPROVED);
        assertThat(trip.currentVersionNumber()).isEqualTo(2);
        assertThat(trip.approvalRecords()).hasSize(1);
    }

    @Test
    void employeeOwnershipAndAdministratorBoundariesAreEnforced() {
        TripRequest trip = createAndSubmit(sampleTrip());

        assertThatThrownBy(() -> tripRequests.get(trip.id(), otherEmployee))
                .isInstanceOf(RoamException.class)
                .hasMessageContaining("own");
        assertThatThrownBy(() -> tripRequests.applyInformation(trip.id(), admin, sampleTrip()))
                .isInstanceOf(RoamException.class);
        assertThatThrownBy(() -> audit.all(employee))
                .isInstanceOf(RoamException.class)
                .hasMessageContaining("Administrators");
        assertThat(audit.all(admin)).isNotEmpty();
    }
}
