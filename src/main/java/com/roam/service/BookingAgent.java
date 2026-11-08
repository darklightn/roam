package com.roam.service;

import com.roam.domain.Actor;
import com.roam.domain.BookingResult;
import com.roam.domain.ItineraryOption;
import com.roam.domain.TravelPlan;
import org.springframework.stereotype.Component;

/**
 * Child Agent for booking one intent. It is stateless: every invocation uses only the isolated
 * TaskContext and the selected option, so sibling tasks cannot share or mutate each other's context.
 */
@Component
public class BookingAgent {

    private final BookingService booking;

    public BookingAgent(BookingService booking) {
        this.booking = booking;
    }

    public BookingResult execute(
            TaskContext context,
            TravelPlan plan,
            ItineraryOption option,
            Actor actor) {
        return booking.prepareAndSubmit(
                context.tripRequestId(),
                plan.id(),
                context.intentId(),
                option.id(),
                option.quote().id(),
                actor);
    }
}
