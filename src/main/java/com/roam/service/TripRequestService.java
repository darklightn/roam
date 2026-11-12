package com.roam.service;

import com.roam.domain.plan.Quote;
import com.roam.domain.shared.Actor;
import com.roam.domain.shared.Role;
import com.roam.domain.trip.ApprovalRecord;
import com.roam.domain.trip.EmployeeProfile;
import com.roam.domain.trip.Suggestion;
import com.roam.domain.trip.TripRequest;
import com.roam.domain.trip.TripRequestData;
import com.roam.domain.trip.TripRequestStatus;
import com.roam.domain.trip.TripStop;
import com.roam.domain.workflow.IntentPackage;
import com.roam.infrastructure.persistence.InMemoryStore;
import com.roam.service.audit.AuditService;
import com.roam.service.dto.SubmissionResult;
import com.roam.service.security.AccessControl;
import com.roam.shared.Ids;
import com.roam.shared.Redaction;
import com.roam.shared.RoamException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class TripRequestService {

    private final InMemoryStore store;
    private final Ids ids;
    private final AuditService audit;
    private final AccessControl access;
    private final HitlService hitl;
    private final Redaction redaction;
    private final Clock clock;

    public TripRequestService(
            InMemoryStore store,
            Ids ids,
            AuditService audit,
            AccessControl access,
            HitlService hitl,
            Redaction redaction,
            Clock clock) {
        this.store = store;
        this.ids = ids;
        this.audit = audit;
        this.access = access;
        this.hitl = hitl;
        this.redaction = redaction;
        this.clock = clock;
    }

    public void registerProfile(EmployeeProfile profile) {
        store.profiles().put(profile.employeeId(), profile);
    }

    public TripRequest createOrUpdate(IntentPackage pkg, Actor actor) {
        if (actor.role() != Role.EMPLOYEE) {
            throw RoamException.forbidden("an Employee must submit the Trip Request");
        }
        TripRequest trip;
        if (pkg.tripRequestId() != null) {
            trip = get(pkg.tripRequestId(), actor);
            access.requireEmployeeOwnsTripForMutation(actor, trip);
            trip.edit(pkg.tripRequest());
            audit.append(actor, "TRIP_REQUEST_UPDATED", "TripRequest", trip.id(),
                    "version=" + trip.currentVersionNumber(), null);
        } else {
            trip = new TripRequest(ids.next("trip"), actor.userId(), pkg.tripRequest(), clock.instant());
            store.trips().put(trip.id(), trip);
            audit.append(actor, "TRIP_REQUEST_CREATED", "TripRequest", trip.id(),
                    "origin=" + pkg.tripRequest().origin(), null);
        }
        return trip;
    }

    public SubmissionResult submit(String tripRequestId, Actor actor) {
        TripRequest trip = get(tripRequestId, actor);
        access.requireEmployeeOwnsTripForMutation(actor, trip);
        if (trip.versionLocked()) {
            throw RoamException.conflict("cancel the pending review before editing or resubmitting");
        }
        var version = trip.submit(actor.userId(), clock.instant(), false);
        audit.append(actor, "TRIP_REQUEST_SUBMITTED", "TripRequest", trip.id(),
                "version=" + version.version()
                        + ";stops=" + version.data().stops().size()
                        + ";travelerId=" + redaction.maskIdNumber(version.data().travelerIdNumber()),
                null);

        TripRequestData data = version.data();
        List<String> missing = data.missingFields();
        if (!missing.isEmpty()) {
            trip.markAwaitingInfo();
            List<Suggestion> suggestions = suggestions(trip.id());
            suggestions.forEach(suggestion -> audit.append(actor, "SUGGESTION_PRESENTED",
                    "TripRequest", trip.id(),
                    "field=" + suggestion.field() + ";source=" + suggestion.source(),
                    "employee must confirm before use"));
            audit.append(actor, "TRIP_REQUEST_AWAITING_INFO", "TripRequest", trip.id(),
                    "missing=" + String.join(",", missing), null);
            return new SubmissionResult(
                    trip.id(), version.version(), TripRequestStatus.AWAITING_INFO,
                    missing, suggestions,
                    "Provide the missing information or confirm a suggested value.", null);
        }

        LocalDate today = LocalDate.now(clock);
        LocalDate departure = data.departureDate();
        if (departure != null && departure.isBefore(today)) {
            trip.markRejected();
            audit.append(actor, "TRIP_REQUEST_REJECTED_AUTOMATICALLY", "TripRequest", trip.id(),
                    "version=" + version.version() + ";reason=departureDateInPast",
                    "Trip Request policy requires a current or future departure date");
            return new SubmissionResult(
                    trip.id(), version.version(), TripRequestStatus.REJECTED,
                    List.of(), List.of(),
                    "The departure date is in the past. You may request Administrator review or edit the request.",
                    null);
        }

        ApprovalRecord approval = new ApprovalRecord(
                ids.next("approval"),
                trip.id(),
                version.version(),
                clock.instant(),
                "system:trip-request-policy",
                Role.SYSTEM,
                true,
                "complete required information; Trip Reason present; departure date valid");
        trip.markApproved(approval);
        audit.append(Actor.system("trip-request-policy"), "TRIP_REQUEST_APPROVED", "TripRequest",
                trip.id(), "version=" + version.version() + ";automatic=true",
                "complete required information and non-empty Trip Reason");
        return new SubmissionResult(
                trip.id(), version.version(), TripRequestStatus.APPROVED,
                List.of(), List.of(),
                "Approved. Select an Itinerary Option and Quote to continue.", approval);
    }

    public List<Suggestion> suggestions(String tripRequestId) {
        TripRequest trip = store.trips().get(tripRequestId);
        if (trip == null) {
            return List.of();
        }
        EmployeeProfile profile = store.profiles().get(trip.employeeId());
        if (profile == null) {
            return List.of();
        }
        List<Suggestion> suggestions = new ArrayList<>();
        List<String> missing = trip.currentData().missingFields();
        if (missing.contains("traveler") && profile.travelerName() != null) {
            suggestions.add(new Suggestion("traveler", profile.travelerName(), "Long-term Preference"));
        }
        if (missing.contains("origin") && profile.usualOrigin() != null) {
            suggestions.add(new Suggestion("origin", profile.usualOrigin(), "Long-term Preference"));
        }
        if (missing.contains("destination") && !profile.history().isEmpty()) {
            EmployeeProfile.HistoricalTrip last =
                    profile.history().get(profile.history().size() - 1);
            suggestions.add(new Suggestion(
                    "destination", last.destination(), "Historical Trip Information"));
        }
        return List.copyOf(suggestions);
    }

    /** Applies values the employee explicitly confirmed or entered. Suggestions are never applied automatically. */
    public TripRequest applyInformation(String tripRequestId, Actor actor, TripRequestData updated) {
        TripRequest trip = get(tripRequestId, actor);
        access.requireEmployeeOwnsTripForMutation(actor, trip);
        TripRequestData merged = merge(trip.currentData(), updated);
        trip.edit(merged);
        audit.append(actor, "TRIP_REQUEST_INFORMATION_RECORDED", "TripRequest", trip.id(),
                "missing=" + String.join(",", merged.missingFields()),
                null);
        return trip;
    }

    public TripRequest updateReason(String tripRequestId, Actor actor, String reason) {
        TripRequest trip = get(tripRequestId, actor);
        access.requireEmployeeOwnsTripForMutation(actor, trip);
        trip.edit(trip.currentData().withReason(reason));
        audit.append(actor, "TRIP_REQUEST_REASON_UPDATED", "TripRequest", trip.id(),
                "version=" + trip.currentVersionNumber() + ";reapprovalRequired=false", null);
        return trip;
    }

    public com.roam.domain.review.HitlReview requestReview(String tripRequestId, Actor actor) {
        TripRequest trip = get(tripRequestId, actor);
        access.requireEmployeeOwnsTripForMutation(actor, trip);
        return hitl.requestTripReview(trip, actor);
    }

    public TripRequest cancelReview(String tripRequestId, Actor actor) {
        TripRequest trip = get(tripRequestId, actor);
        access.requireEmployeeOwnsTripForMutation(actor, trip);
        if (trip.pendingReviewId() == null) {
            throw RoamException.conflict("there is no pending review");
        }
        hitl.cancel(trip.pendingReviewId(), actor);
        trip.clearReview();
        trip.markRejected();
        return trip;
    }

    public TripRequest get(String tripRequestId, Actor actor) {
        TripRequest trip = store.trips().get(tripRequestId);
        if (trip == null) {
            throw RoamException.notFound("TripRequest");
        }
        access.requireEmployeeOwnsTrip(actor, trip);
        return trip;
    }

    public TripRequest requireBookable(String tripRequestId, Actor actor) {
        TripRequest trip = get(tripRequestId, actor);
        if (!trip.isApprovedForCurrentVersion()) {
            throw RoamException.conflict("booking requires an approved Trip Request version");
        }
        return trip;
    }

    public void recordExecutionStatus(TripRequest trip, TripRequestStatus status, Actor actor) {
        trip.markExecution(status);
        audit.append(actor, "TRIP_REQUEST_EXECUTION_STATUS", "TripRequest", trip.id(),
                "status=" + status, null);
    }

    private static TripRequestData merge(TripRequestData current, TripRequestData patch) {
        Objects.requireNonNull(patch, "patch");
        String travelerName = patch.travelerName() == null
                ? current.travelerName() : patch.travelerName();
        String travelerId = patch.travelerIdNumber() == null
                ? current.travelerIdNumber() : patch.travelerIdNumber();
        String origin = patch.origin() == null ? current.origin() : patch.origin();
        List<TripStop> stops = patch.stops().isEmpty() ? current.stops() : patch.stops();
        boolean oneWay = patch.oneWay() || current.oneWay();
        String reason = patch.tripReason() == null ? current.tripReason() : patch.tripReason();
        return new TripRequestData(travelerName, travelerId, origin, stops, oneWay, reason);
    }
}
