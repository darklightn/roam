package com.roam.agent;

import com.roam.domain.plan.ItineraryOption;
import com.roam.domain.plan.TravelPlan;
import com.roam.domain.shared.Actor;
import com.roam.service.BookingService;
import com.roam.service.dto.BookingResult;
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
