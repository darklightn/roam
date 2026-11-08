package com.roam.service;

import com.roam.domain.Actor;
import com.roam.domain.BookingOrder;
import com.roam.domain.HitlReview;
import com.roam.domain.TravelPlan;
import com.roam.domain.TripRequest;
import org.springframework.stereotype.Component;

/** Employee ownership and Administrator boundaries, enforced in code rather than prompts. */
@Component
public class AccessControl {

    public void requireAdministrator(Actor actor) {
        if (!actor.isAdministrator()) {
            throw RoamException.forbidden("Administrator role required");
        }
    }

    public void requireEmployeeOwnsTrip(Actor actor, TripRequest trip) {
        if (actor.isAdministrator()) {
            return;
        }
        if (!trip.employeeId().equals(actor.userId())) {
            throw RoamException.forbidden("Employees may only access their own Trip Requests");
        }
    }

    public void requireEmployeeOwnsTripForMutation(Actor actor, TripRequest trip) {
        if (actor.isAdministrator()) {
            throw RoamException.forbidden("Administrators cannot rewrite employee business records");
        }
        if (!trip.employeeId().equals(actor.userId())) {
            throw RoamException.forbidden("Employees may only mutate their own Trip Requests");
        }
    }

    public void requireEmployeeOwnsPlan(Actor actor, TravelPlan plan, TripRequest trip) {
        requireEmployeeOwnsTrip(actor, trip);
        if (!plan.tripRequestId().equals(trip.id())) {
            throw RoamException.notFound("Travel Plan");
        }
    }

    public void requireEmployeeOwnsOrder(Actor actor, BookingOrder order, TripRequest trip) {
        requireEmployeeOwnsTrip(actor, trip);
        if (!order.tripRequestId().equals(trip.id())) {
            throw RoamException.notFound("Booking Order");
        }
    }

    public void requireReviewer(Actor actor, HitlReview review) {
        requireAdministrator(actor);
        if (review.claimedBy() != null && !review.claimedBy().equals(actor.userId())) {
            throw RoamException.forbidden("another Administrator owns this review");
        }
    }
}
