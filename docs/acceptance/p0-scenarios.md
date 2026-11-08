# P0 acceptance scenario map

The MVP readiness gate is every P0 scenario green against deterministic Tuniu fixtures plus every P0
operational signal wired and exercised. This file maps each scenario to its executable coverage.

| Scenario | Coverage |
| --- | --- |
| Domestic single-city happy path | `BookingTest`, `CancellationCallbackTest` |
| Domestic multi-city happy path | `MultiCityTest` |
| Budget exception (approve and reject) | `BudgetExceptionTest` |
| Rejected resubmission | `TripRequestApprovalTest` |
| Failed booking retries | `BookingTest` |
| Partial/full cancellation | `CancellationCallbackTest` |
| Idempotency and duplicate callbacks | `BookingTest`, `CancellationCallbackTest` |
| Optional payment-status callback observation | `CancellationCallbackTest` |
| Correlation, metrics, alerts, runbook | `OperationalReadinessTest`, `ApiSurfaceTest` |
| Manual recovery drill and append-only audit | `OperationalReadinessTest` |
| Employee/Administrator access and redaction | `TripRequestApprovalTest`, `ApiSurfaceTest`, `OperationalReadinessTest` |

Booking Order completion semantics: `Fulfilled` means every original planned intent reached
`Submitted` (supplier order id received). It does not mean paid or fulfilled by the supplier.
