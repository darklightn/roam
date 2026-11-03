# Supplier-neutral travel integration contracts

Research note for [Research supplier-neutral travel integration contracts](https://github.com/darklightn/roam/issues/5) (Wayfinder map: [Intelligent business travel multi-Agent MVP architecture](https://github.com/darklightn/roam/issues/1)).

## Purpose

Answer one question: **which capabilities and failure behaviors must the supplier-neutral search, quote, booking-order, payment, change, cancellation, and reconciliation contracts support for flights, trains, and hotels — and what must the replaceable example adapter implement for the MVP?**

"Supplier-neutral" is the operative word: the internal contracts must express concepts that every supplier in a vertical can map onto, so the example adapter is replaceable without touching the domain Agents.

## Sources

Primary sources consulted (fetched 2025-11-03):

- Stripe, [Idempotent Requests](https://docs.stripe.com/api/idempotent_requests) — idempotency-key semantics for supplier-facing mutations.
- Stripe, [Webhooks](https://docs.stripe.com/webhooks) — delivery-retry and duplicate-event semantics for payment-status callbacks.
- Adyen, [Webhooks](https://docs.adyen.com/development-resources/webhooks) — signed (HMAC) webhook delivery.
- IATA, [NDC program](https://www.iata.org/en/programs/airline-distribution/ndc/) — the airline distribution standard that names the offer/order message set.
- OpenTravel Alliance (OTA) XML schema family — the hotel distribution message vocabulary (`OTA_HotelAvail`, `OTA_HotelRes`, `OTA_HotelCancelNotif`, …).

Rail was assessed at the provider-neutral level; Trainline Partner API and Rail Europe developer portals were unreachable from this environment (connection reset), so no rail-specific primary source is cited below. Rail concepts below are stated only at the supplier-neutral level and are safe for the contract definition.

---

## 1. The six contract surfaces

Every supplier adapter, regardless of vertical, exposes the same six capabilities. The domain Agents talk only to these; the adapter translates to the supplier's native API.

| Surface | Purpose | Question it answers |
| --- | --- | --- |
| **Search** | Find candidate options for an origin/destination/dates/passengers query. | "What can I buy?" |
| **Quote** | Price/repricing of a specific candidate, with validity. | "What does this cost right now?" |
| **Booking order** | Turn a selected quote into a durable supplier reservation. | "Hold/reserve it." |
| **Retrieve/reconcile** | Read the supplier's current view of an order. | "What is the true state?" |
| **Change** | Modify a booked order (rebook, reprice, add/remove segments/stays). | "Alter it." |
| **Cancel** | Cancel a booked order, with the supplier's penalty/refund outcome. | "Undo it." |

**Payment** is not a seventh surface in the MVP: the system does not execute payment or require Payment Confirmation. It may only *observe* an optional supplier payment-status callback (section 6).

## 2. Flights — NDC-aligned concepts

IATA's NDC standard separates *shopping* from *order management*, which is the stable provider-independent split the contract must mirror.

- **Offer vs Order.** A *shopping* result is an **Offer** (a priced itinerary with an OfferID and fare rules). A *booked* result is an **Order** (a confirmed record with an OrderID). An Offer is never a reservation; an Order is the single durable supplier record after booking. ([NDC program](https://www.iata.org/en/programs/airline-distribution/ndc/))
- **Message roles** the adapter must cover, mapped onto the six surfaces:
  - Search → *AirShopping*-equivalent: query → list of Offers (segments, fare rules, total price).
  - Quote → *OfferPrice*-equivalent: reprice one OfferID → fresh price + validity.
  - Booking order → *OrderCreate*-equivalent: submit a selected OfferID → Order with OrderID and ticketing status.
  - Retrieve/reconcile → *OrderRetrieve/OrderView*-equivalent: read current Order state by OrderID.
  - Change → *OrderChange*-equivalent, preceded by *OrderRules* to fetch change penalties; voluntary changes create a new priced option.
  - Cancel → *OrderCancel*-equivalent, with refund/penalty outcome.
  - Ancillaries (seats, bags) → *ServiceList/ServicePrice*-equivalent; MVP can treat these as a change surface.
- **Key constraint for the contract:** after any uncertain submit, the adapter must *retrieve* the Order by OrderID to learn the truth. The OrderID is the reconciliation key; local state may never assume success from a request timeout (this is already an invariant in the lifecycle decision — [issue #2](https://github.com/darklightn/roam/issues/2)).

## 3. Hotels — OTA-aligned concepts

OpenTravel Alliance (OTA) hotel messages give the provider-neutral vocabulary:

- **Availability/search** → `OTA_HotelAvail`: property, check-in/out, room types, rate plans, cancellation policy.
- **Reservation** → `OTA_HotelRes`: create a booking → supplier **confirmation number / UniqueID** (the reconciliation key).
- **Modify** → `OTA_HotelResModifyNotif`: change dates/room type on an existing confirmation.
- **Cancel** → `OTA_HotelCancelNotif`: cancel by confirmation number, returning penalty/refund.
- **Content** → `OTA_HotelDescriptiveInfo`: property/room metadata; MVP example adapter may ship static content instead.

**Constraint for the contract:** hotels are rate-plan/room-type structured, and their cancellation semantics are *deadline + penalty* (not a per-segment state machine). The contract must carry the confirmation number on every post-booking call, and return a penalty/refund result from cancel.

## 4. Trains — provider-neutral concepts

Rail distribution (Trainline Partner API and peers) follows the same search → fare → booking → fulfilment → refund shape:

- **Search** → journeys: origin, destination, date, passenger count, class.
- **Quote** → fare options with conditions (flexibility, seat-reservation requirement).
- **Booking order** → create booking → **booking reference** (the reconciliation key).
- **Retrieve** → read fulfilment state (ticket issued / not issued).
- **Change/refund** → exchange or refund with fare-condition penalties.

**Constraint for the contract:** rail fulfilment is a distinct post-booking state (ticket issued vs. held). The MVP's Booking Order state must be able to record "reserved but not fulfilled" distinctly from "confirmed/fulfilled".

## 5. Cross-vertical reconciliation key

Every vertical yields exactly one **supplier confirmation reference** that the adapter returns on booking and that all later calls use:

- Flight → OrderID (NDC).
- Hotel → confirmation number / UniqueID (OTA).
- Train → booking reference.

This reference is the contract's reconciliation and idempotency anchor, and it is what the MVP persists on the Booking Order.

## 6. Payment-status observation (no execution)

Payment is **out of scope for execution** in the MVP, but the system may record an optional supplier payment-status callback. Primary-source facts that constrain how the adapter must expose it:

- Webhook deliveries are **at-least-once** with automatic retries; the same event **can arrive more than once**; processing must be **idempotent** and must not assume delivery order. ([Stripe Webhooks](https://docs.stripe.com/webhooks))
- Callbacks are **signed (HMAC)** so the receiver can authenticate the source. ([Adyen Webhooks](https://docs.adyen.com/development-resources/webhooks))
- A callback carries an **event id**; deduplicate by logging received event ids. ([Stripe Webhooks](https://docs.stripe.com/webhooks))

**Contract consequence:** the adapter exposes a *payment-status event* (`status`, `amount`, `currency`, `reference`, `eventId`) and the MVP treats it as an observed, append-only record with idempotent ingest — never as an action trigger for payment execution.

## 7. Failure behaviors the contracts must carry

These are contract-level requirements (the adapter must support them; the domain Agents enforce the policy — see [issue #3](https://github.com/darklightn/roam/issues/3) retry/result states).

1. **Idempotency keys on all mutations** (create/change/cancel). Retrying with the same key returns the saved result rather than duplicating the effect; the supplier compares parameters and rejects mismatched reuse. Keys are client-generated, ≤255 chars, non-sensitive. ([Stripe Idempotent Requests](https://docs.stripe.com/api/idempotent_requests))
2. **Timeout → reconcile, never assume.** After an uncertain timeout the adapter must query/retrieve the order before another mutation; if the truth is unreachable, surface `unknown_external_outcome` and wait for reconciliation or HITL.
3. **Partial success is first-class.** Multi-segment flights, multi-room/multi-night hotels, and multi-leg trains can partially confirm. The adapter returns per-item status; the Booking Order can be `PartiallyConfirmed`.
4. **Compensation is explicit.** If part of an order completes and the rest fails, the contract must expose what can be cancelled/refunded to unwind the completed part, and the penalty/refund result of that unwind.
5. **Structured failure.** Every supplier failure maps to: failure type, human-readable reason, supplier error code or receipt, occurrence time, and execution-attempt id (already an invariant in [issue #2](https://github.com/darklightn/roam/issues/2)).
6. **Cancellation failure is surfaced, not swallowed.** Cancel returns Applied / PartiallyApplied / Failed with penalty/refund; the adapter must never report success on a supplier timeout.

## 8. What the replaceable example adapter must implement for the MVP

One adapter interface, six operations, plus one callback handler:

```
search(query)        -> CandidateOption[]   (offer/room/journey, total price, fare/cancel conditions)
quote(candidateRef)  -> Quote                 (price, validity timestamp, snapshot)
book(quoteRef, idempotencyKey) -> BookingConfirmation (supplierRef, per-item status, fulfilment state)
retrieve(supplierRef) -> OrderState            (current supplier view, per-item status)
change(supplierRef, delta, idempotencyKey) -> ChangeResult (Applied | PartiallyApplied | Failed, new price, penalty)
cancel(supplierRef, idempotencyKey) -> CancelResult (Applied | PartiallyApplied | Failed, refund/penalty)
handlePaymentStatus(event) -> recorded, idempotent observation (no execution)
```

MVP scope for the example adapter: **one vertical end-to-end is enough to prove replaceability** — recommend a flight adapter (NDC-shaped) since it exercises offer/order separation, per-item partial status, and change/cancel penalties, which are the hardest contract behaviors. Hotel and train adapters can be stubs behind the same interface.

---

## Open questions handed to downstream tickets

- Exact idempotency/timeout/retry/reconciliation **policy** (how the domain Agents act on these contract facts) → [Define booking reliability and compensation behavior](https://github.com/darklightn/roam/issues/8).
- Which roles may read/mutate supplier references, payment-status records, and credentials → [Define permissions, sensitive data, and audit model](https://github.com/darklightn/roam/issues/6).
