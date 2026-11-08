# Roam operations runbook (P0)

Owner: Administrator on-call for business workflows; supplier integration owner for supplier-facing alerts.

Every P0 alert has an explicit trigger, an owner, and a runbook action. Alerts are evaluated by
`com.roam.service.AlertService`; the alert code and runbook pointer are part of the alert payload.

## Alert-to-action map

| Alert code | Trigger | Owner | Runbook action |
| --- | --- | --- | --- |
| `UNKNOWN_OUTCOME_BACKLOG` | `unknown_external_outcome` count > 0 | Administrator on-call | [Unknown external outcomes](#unknown-external-outcomes) |
| `HITL_AGING` | Oldest open HITL review age ≥ 60 seconds | Administrator on-call | [Aging HITL queue](#aging-hitl-queue) |
| `SUPPLIER_ERROR_SPIKE` | Supplier error count ≥ 3 in the current window | Supplier integration owner | [Supplier error spike](#supplier-error-spike) |
| `INVALID_OR_DUPLICATE_CALLBACK` | Invalid callback count > 0 or deduplicated callback count > 0 | Supplier integration owner | [Supplier callbacks](#callbacks) |

Runbook pointer base: `docs/runbook/operations.md`.

## Unknown external outcomes

Anchor: `#unknown-external-outcomes`

1. Open the alert and trace the session: `GET /api/admin/trace/{sessionId}`.
2. Inspect the affected Booking Order. A `PENDING` order after a lost supplier response may already
   exist supplier-side; the local state is intentionally unchanged.
3. Do **not** retry the booking automatically after the three-call limit, and do not mark the order
   Submitted without supplier evidence.
4. If the supplier provides an order identifier, record it with
   `POST /api/admin/recovery/orders/{orderId}` and a reason. That appends a new business event; it
   does not rewrite history.
5. If no supplier identifier exists, record the observation and tell the employee about the
   duplicate risk. Let the supplier-side order expire or cancel manually.
6. Never execute payment from Roam.

## Aging HITL queue

Anchor: `#aging-hitl-queue`

1. Open `GET /api/admin/reviews`; unclaimed reviews are visible to every Administrator.
2. Claim exactly one review. The first claim wins; do not process another Administrator's claim.
3. Review the locked subject version and decide **approve** or **reject** with a reason.
4. If nobody claims a review, it stays pending and reminders continue. Never auto-approve or
   auto-reject because of non-response.
5. Self-review is permitted for the MVP but is fully audited.

## Supplier error spike

Anchor: `#supplier-error-spike`

1. Check `GET /api/admin/metrics` for supplier errors and retry distribution.
2. Inspect recent `SUPPLIER_*` audit events for supplier codes.
3. If Tuniu is unavailable, pause new order submissions and inform the employee; the existing order
   remains in its last confirmed state.
4. When the supplier recovers, a new employee-initiated booking flow creates a new local Booking
   Order. Do not silently reopen an exhausted operation.

## Supplier callbacks

Anchor: `#callbacks`

1. Verify the callback signature and supplier reference. Invalid callbacks are recorded as invalid
   observations and counted for the alert.
2. Duplicate callback ids are deduplicated; only the first observation is retained.
3. Treat payment status as an observation. It never executes payment, never cancels an order, and
   never changes Booking Order state.
4. If a paid order needs cancellation, use the supplier-side process below.

## Manual approval

Anchor: `#manual-approval`

1. Claim the shared review.
2. Confirm the exact subject: the Trip Request version or one Booking Order.
3. Decide with a mandatory reason. The decision records actor, role, time, subject, version, reason,
   and the data shown at decision time.
4. A budget-exception approval resumes only that Booking Order. It never changes Trip Request
   approval or authorises a sibling order.

## Stuck workflow takeover

Anchor: `#stuck-workflow-takeover`

1. Trace the session and identify the stuck intent, task, and Booking Order.
2. Use `POST /api/admin/workflows/{workflowId}/takeover` with a reason.
3. Takeover appends a `HUMAN_TAKEOVER` audit event and changes ownership/status only. It does not
   rewrite business events.
4. Continue with the relevant supplier or HITL procedure.

## Supplier-side manual cancellation

Anchor: `#supplier-side-manual-cancellation`

1. Hotel cancellation, paid-order cancellation, changes, and refunds are unsupported in the MVP.
2. Perform the action in the Tuniu portal or with Tuniu support.
3. Record the outcome as an observation. Do not change the local Booking Order state unless the
   supplier explicitly confirms cancellation; a timeout or inferred result is not confirmation.
4. Tell the employee that the local order remains in its last confirmed state and explain the manual
   action taken.

## Recovery drill

Anchor: `#recovery-drill`

1. Pick a stuck or uncertain workflow and a `PENDING` Booking Order.
2. Run `POST /api/admin/recovery-drills` with a reason.
3. Verify the drill report: an audit event was appended, audit integrity is verified, and the order's
   historical state was not rewritten.
4. Record the drill outcome in the operations log. Repeat at least once per MVP readiness cycle.
