# Roam

Intelligent business-travel MVP vertical slice: an Employee submits a validated `IntentPackage`,
`MasterAgent` coordinates approval, planning, and supplier booking work, and an Administrator handles
human decisions, recovery, and audit review.

## What is implemented

- Trip Request completeness, suggestions (never applied silently), automatic approval, rejected
  resubmission, Administrator review, version locking, and immutable history (#12).
- Versioned four-view Travel Plan, explainable overall-best scoring, explicit option/quote selection,
  and quote revalidation before booking (#13).
- Flight, hotel, and train Booking Orders through deterministic Tuniu fixtures; local `Pending →
  Submitted` transitions, a hard Budget Ceiling check, and at most three supplier calls per operation
  (#14).
- Over-budget pause, shared Administrator queue, first-claim wins, reason-required decisions, and
  order-scoped exception resume (#15).
- Multi-city intents with dependencies, independent outcomes, partial success, and no automatic
  compensation (#16).
- Supported unpaid flight/train cancellation, unsupported hotel/paid/change/refund handling, and
  validated, deduplicated payment-status callbacks as observations only (#17).
- Correlation from session to supplier order, P0 metrics, alerts mapped to a runbook, a manual
  recovery drill, append-only hash-chained audit, and uniform redaction (#18).

## Build and test

```bash
./mvnw test
```

Requirements: Java 21. The tests use Spring Boot and run fully in-memory with deterministic fixtures.

## Run

```bash
./mvnw spring-boot:run
```

The HTTP API uses trusted headers `X-User-Id` and `X-Role` (`EMPLOYEE` or `ADMINISTRATOR`) in this
slice. Key endpoints:

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/intent-packages` | Validate, approve/plan, and execute a package |
| `POST` | `/api/intent-packages/prepare` | Approve and plan without booking |
| `POST` | `/api/workflows/{id}/execute` | Execute a prepared workflow |
| `GET` | `/api/trip-requests/{id}` | Redacted Trip Request view |
| `GET` | `/api/trip-requests/{id}/plan` | Versioned Travel Plan |
| `POST` | `/api/plans/{id}/selection` | Select an option and its quote |
| `POST` | `/api/orders/{id}/cancel` | Supported cancellation |
| `POST` | `/api/trip-requests/{id}/reviews` | Request Administrator review |
| `GET` | `/api/admin/reviews` | Shared review queue |
| `POST` | `/api/admin/reviews/{id}/claim` | Claim a review |
| `POST` | `/api/admin/reviews/{id}/decision` | Approve/reject with a reason |
| `POST` | `/api/supplier/payment-callbacks` | Payment-status observation |
| `GET` | `/api/admin/metrics` | P0 metrics |
| `GET` | `/api/admin/alerts` | Alert evaluation |
| `GET` | `/api/admin/audit/{subjectId}` | Append-only audit evidence |
| `GET` | `/api/admin/trace/{sessionId}` | Correlation chain |
| `POST` | `/api/admin/workflows/{id}/takeover` | Manual recovery takeover |
| `POST` | `/api/admin/recovery-drills` | P0 recovery drill |

## Documentation

- Domain vocabulary: `GLOSSARY.md`.
- Runtime seams and substitutions: `docs/adr/0001-runtime-seams.md`.
- Operations runbook: `docs/runbook/operations.md`.
- P0 scenario-to-test map: `docs/acceptance/p0-scenarios.md`.
- Tuniu facts: `docs/research/tuniu-openplatform-mcp.md`.

## Known MVP limits

- The store is in-memory behind the persistence seam; MySQL and Redis are not wired in this slice.
- AgentScope model invocation is represented by the `MasterAgent`/`BookingAgent` boundaries and a
  bounded executor; no live LLM prompt is executed.
- Payment execution, settlement, invoicing, reimbursement, change/refund execution, hotel
  cancellation, and automatic supplier reconciliation are out of scope.
- Submitted means a supplier order identifier was received; it does not mean paid or fulfilled.
