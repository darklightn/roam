# Supplier-neutral travel integration contracts

Research resolution for [issue #5](https://github.com/darklightn/roam/issues/5), “Research supplier-neutral travel integration contracts”. The recommendation targets the MVP and keeps the example adapter replaceable.

## Findings from primary sources

- **Air uses an offer/order boundary.** IATA describes NDC as a data-exchange format for airlines to distribute offers and orders across channels. An offer is selected before an order is created; the airline owns the actual product, price, and fulfillment rules. Source: [IATA, Distribution with Offers & Orders (NDC)](https://www.iata.org/en/programs/airline-distribution/retailing/ndc/).
- **Rail separates offer parts/products/fares and needs synchronization.** The UIC Open Sales and Distribution Model (OSDM) specification documents shared offer/product/fare structures, online processes, errors, and synchronization. Its example booking response distinguishes `provisionalPrice` from `confirmedPrice`, returns a `ticketTimeLimit`, and models refund/exchange offers with fees, refundable amount, and a validity window. OSDM's non-functional requirements call for retry/retrieve handling when a booking outcome is uncertain. Sources: [OSDM specification](https://osdm.io/spec/), [getting started](https://osdm.io/spec/getting-started/), [after-sales processes](https://osdm.io/spec/after-sales-processes/), and [non-functional requirements](https://osdm.io/spec/non-functional-requirements/).
- **Hotel supply is also time-sensitive and message-oriented.** Booking.com Connectivity documents reservation messages for new booking confirmations, modifications, and cancellations; consumers retrieve and acknowledge those messages, with fallback notification when they are not acknowledged. Its Request-to-Book flow separates a booking request from a reservation id, permits expiry, and warns that price/availability can change before approval. Sources: [Reservations API overview](https://developers.booking.com/connectivity/docs/reservations-api/reservations-overview) and [Request to Book overview](https://developers.booking.com/connectivity/docs/request-to-book/overview).
- **Payment confirmation is a separate state machine.** Stripe’s PaymentIntent confirmation API allows outcomes such as `requires_action`, `succeeded`, and failure; its idempotency guidance requires a stable key for safely retrying mutating requests, and webhook guidance treats events as the asynchronous source for later state changes. These are payment-system examples, not a recommendation to couple Wayfinder to Stripe. Sources: [Confirm a PaymentIntent](https://docs.stripe.com/api/payment_intents/confirm), [Idempotent requests](https://docs.stripe.com/api/idempotent_requests), and [Webhooks](https://docs.stripe.com/webhooks).

These sources do not define one universal supplier API. They support a common lifecycle and the need to preserve supplier-specific terms and identifiers behind an adapter. For machine-readable API failures, [RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) provides a stable `type`/`title`/`status`/`detail`/`instance` shape that can wrap adapter errors.

## Recommended common contract

### 1. Search

`search(request) -> SearchResult` is read-only and may return zero or many heterogeneous offers.

The normalized request should carry:

- travel mode (`flight`, `rail`, or `hotel`);
- origin/destination or property, local dates/times, and timezone where relevant;
- travelers/guests and occupancy;
- requested currency, locale, and policy constraints; and
- a correlation/request id for tracing.

Each normalized **Quote**/offer should include an opaque adapter-scoped id, the complete selected product (all flight segments, rail offer parts, or hotel room/rate details), passenger/guest applicability, total and itemized price in an ISO currency, included taxes/fees, terms, and a `valid_until`/last-ticketing or booking deadline when supplied. Preserve the original supplier payload and supplier id as opaque metadata. Search results are snapshots with `observed_at`; they are never promises of inventory.

### 2. Quote/reprice

`reprice(quote_id) -> QuoteCheck` is required immediately before a booking mutation when the supplier supports it. The result is one of `still_valid`, `expired`, or `changed`, with the current price/terms when changed. A changed or expired quote requires a new user decision; the adapter must not silently book a different price or rule.

The contract must distinguish a quote from a confirmed **Booking Order**. A quote can disappear, reprice, or lose availability without being a booking failure.

### 3. Booking order

`create_order(command) -> OrderResult` accepts the selected quote, traveler/guest data, a stable idempotency key, and a **Payment Confirmation** reference (or an explicitly supported supplier hold mode). It returns:

- `confirmed`, with supplier order/PNR/confirmation references and issued-ticket or hotel confirmation details;
- `pending`, when the supplier will complete asynchronously;
- `failed`, with a typed reason that is safe to show or map; or
- `unknown`, when the request may have reached the supplier but the response was lost.

`unknown` is essential: a network timeout must not be retried as a new order. The orchestrator should retrieve/reconcile by idempotency key and supplier reference before deciding whether another attempt is safe. Every mutation is retryable only with the same key, and duplicate supplier orders must be detectable.

The adapter does not collect card data or settle funds in this MVP. It accepts a payment-confirmation token/reference and reports whether the supplier requires payment, authorization, or a later settlement step. A booking may be placed on a supplier-supported hold, but the hold deadline and unpaid state must be explicit.

### 4. Payment confirmation boundary

Payment confirmation is a user authorization record attached to the exact quote/order total, currency, and expiry it approved. It is not equivalent to “booking submitted”. Minimum states are `required`, `pending`, `requires_action`, `confirmed`, `failed`, and `canceled/expired`. The orchestrator may submit a charge/booking command only after the required confirmation state is reached, and must preserve the payment provider reference without storing payment credentials. A later payment webhook or provider lookup can move the record from pending to confirmed/failed; reconciliation must be idempotent.

### 5. Change and cancellation

Expose capability discovery per order (`can_change`, `can_cancel`, supported scope, deadline, and supplier policy). For both operations provide a preview before execution:

- requested affected items (flight segment/ticket, rail product/offer part, or hotel room/stay);
- supplier-calculated additional charge or refund, currency, and non-refundable amount;
- resulting itinerary/stay and terms; and
- the last time the action is allowed.

`change` and `cancel` execute an accepted preview with a stable idempotency key and return `confirmed`, `pending`, `failed`, or `unknown`. Preserve the supplier’s penalty/rule text and references. If the supplier cannot preview, return an explicit `preview_unavailable` capability; do not invent a fee calculation. Partial success (for example, one ticket in a multi-ticket order) must be represented per item and leave the parent order in a reconciliation-required state.

### 6. Reconciliation

`get_order(order_ref)` and an optional `list_events(cursor)` form the read-after-write safety net. The adapter should support supplier webhooks/events where available and periodic polling otherwise. Store supplier event ids (or a deterministic payload hash when no id exists), received time, source, acknowledgement time, and raw payload; deduplicate events and apply only valid state transitions. Reconciliation must handle delayed confirmations, supplier-side changes/cancellations, refunds, and an order whose local state is `unknown`. Never infer a successful booking only from a successful HTTP response to a create request. For uncertain writes, retry/retrieve with the same idempotency key and reconcile before issuing a new mutation.

## Mode-specific normalization

The common contract should not erase material differences:

- **Flight:** retain every segment, marketing/operating carrier, passenger-to-ticket mapping, fare family, baggage/ancillary terms, ticketing deadline, and ticket/PNR references. Changes and cancellations can be per passenger or segment and may require repricing.
- **Rail:** retain offer parts/products/fare conditions, train/service identifiers, seat/reservation data, and rail-specific fulfillment (ticket or barcode). A journey can contain multiple offer parts with different change/refund rules.
- **Hotel:** retain property, room/rate plan, occupancy, stay dates/timezone, cancellation/no-show windows, taxes/fees, payment-at-property versus prepaid terms, and confirmation number. Cancellation terms are deadline-dependent and can differ by room/rate within one order. Keep provider reservation/message ids and acknowledgement state because modifications and cancellations may arrive asynchronously.

## MVP adapter scope

The replaceable example adapter should implement deterministic fixtures for all three modes with the same lifecycle:

1. search returns at least one valid offer and one expiring/unavailable offer;
2. reprice can return valid, expired, and changed-price outcomes;
3. create order honors idempotency and exercises confirmed, pending, failed, and unknown/retrieve paths;
4. payment confirmation is a required input/state transition, with no card or settlement implementation;
5. change/cancel expose capability, preview penalties, and idempotent execution, including one partial-success fixture; and
6. get/reconcile applies delayed supplier events and duplicate-event delivery safely.

The adapter contract should use typed, provider-neutral errors (`invalid_request`, `quote_expired`, `price_changed`, `sold_out`, `payment_required`, `supplier_rejected`, `timeout_unknown`, `unsupported`, and `reconciliation_required`) while retaining the raw supplier error for diagnostics. Supplier-specific fields belong in an extension/opaque metadata object, so replacing the adapter does not change the orchestrator or Trip Request model.

## Uncertainties and deliberate MVP limits

- Airline, rail, and hotel suppliers differ on whether a hold can exist without payment, when a price is guaranteed, and whether changes are synchronous. The contract therefore models capabilities and pending/unknown states rather than promising one timing model. OSDM also documents a residual race between pre-confirmation checks and final commit; previews are advisory until the supplier confirms the mutation.
- Refund settlement, charge capture, and accounting are outside this ticket. The boundary is the Payment Confirmation record plus provider reference and asynchronous status/reconciliation.
- No universal cancellation or change-fee vocabulary exists across suppliers. Store supplier policy text and structured amounts when available; policy evaluation can consume normalized flags but must retain the source terms.
- Webhooks are not universally available or reliable. Polling/retrieve is a required fallback, with bounded retries and an operator-visible reconciliation queue.

