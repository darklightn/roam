# ADR 0002: Package layering and executable boundaries

## Status

Accepted, 2026-11-09.

## Context

The MVP started with four top-level packages: `api`, `domain`, `service`, and `supplier`. That was
enough for the first vertical slice, but it produced three problems:

- `domain` became a catch-all for entities, value objects, commands, results, audit records, and
  enums. Those types change for different reasons.
- `service` became a catch-all for Agents, use-case services, HITL strategies, audit/observability
  components, policies, persistence, and Spring configuration.
- `ApiController` was a 530-line class with 14 nested request/response records.

ADR 0001 already named the important seams: `MasterAgent`, `BookingAgent`, `InMemoryStore`,
`SupplierAdapter`, and the bounded executor. It did not define package boundaries, so the seams
were not visible in the directory layout and could erode silently.

The goal is not to make the project look like a generic CRUD backend. The goal is to keep the
familiar backend layering (inbound adapter → application services → domain) while giving Agents a
first-class place and keeping the domain model independent of Spring, IO, and transport details.

## Decision

Use six top-level packages:

```text
com.roam
├── api/                    inbound HTTP adapter
│   ├── ApiController
│   └── dto/                request/response records
├── agent/                  orchestration layer
│   ├── MasterAgent
│   ├── BookingAgent
│   ├── TaskContext
│   └── contract/           WorkflowResult, WorkflowPreparation, IntentResultView
├── service/                use-case services
│   ├── BookingService, PlanningService, TripRequestService, HitlService, RecoveryService
│   ├── IntentPackageValidator
│   ├── audit/
│   ├── dto/                BookingResult, SubmissionResult
│   ├── observability/      MetricsService, AlertService, TraceService, TravelAlert
│   ├── policy/
│   ├── review/
│   └── security/
├── domain/                 pure model; no Spring, no IO
│   ├── trip/
│   ├── plan/
│   ├── booking/
│   ├── review/
│   ├── workflow/
│   ├── audit/
│   ├── shared/             Actor, Role, Money, FailureReason, FailureType
│   └── port/               SupplierAdapter
├── infrastructure/         outbound adapters and wiring
│   ├── persistence/        InMemoryStore
│   ├── supplier/           TuniuFixtureAdapter, FixtureScript, SupplierConfiguration,
│   │                       CallbackSignatures
│   └── config/             WorkflowConfiguration
└── shared/                 Ids, Redaction, RoamException
```

`agent` and `service` are sibling application-layer roles, not stacked layers. Agent code may call
services; services must not call agents.

### Dependency direction

| Source | May depend on | Must not depend on |
| --- | --- | --- |
| `api` | `agent`, `service`, `domain`, `shared` | `infrastructure` |
| `agent` | `service`, `domain`, `infrastructure`, `shared` | `api` |
| `service` | `domain`, `infrastructure`, `shared` | `agent`, `api` |
| `domain` | `shared` (and domain-internal packages) | `api`, `agent`, `service`, `infrastructure` |
| `infrastructure` | `domain`, `shared` | `api`, `agent`, `service` |
| `shared` | nothing business-specific | all business packages |

Additional rules:

- `domain` must not import Spring, Jakarta, HTTP, JDBC, or any outer `com.roam` layer.
- `api/dto` may only depend on `domain`; it must not depend on `service`, `agent`, or
  `infrastructure`.
- `service/dto` must not depend on `agent` or `api`.
- `SupplierAdapter` is a port owned by `domain/port`; concrete supplier adapters live in
  `infrastructure/supplier`.
- `InMemoryStore` is a persistence adapter, not a business service. Audit appends go through its
  atomic `appendAudit(...)` method; callers cannot mutate the audit list.
- No cycles between top-level layers or between `domain` subpackages.

### Enforcement

`src/test/java/com/roam/architecture/ArchitectureTest.java` uses ArchUnit to enforce:

- the six-layer dependency direction;
- domain purity and domain-internal acyclicity;
- `api → infrastructure`, `service → agent`, and `infrastructure → service` prohibitions;
- `api/dto` and `service/dto` dependency limits;
- `shared` independence;
- top-level package acyclicity.

The full rationale and migration history are in `docs/research/package-layering.md`.

## Migration

The migration was behavior-preserving and done in six phases:

1. Move supplier, `InMemoryStore`, `Ids`, `RoamException`, and `WorkflowConfiguration` to
   `infrastructure`/`shared`.
2. Split `service` into `audit`, `observability`, `security`, `policy`, and `review`, and move
   `Redaction`/`CallbackSignatures` to their proper homes.
3. Create `agent`, rename `WorkflowService` to `MasterAgent`, and move Agent contracts to
   `agent/contract`.
4. Split `domain` by aggregate: `trip`, `plan`, `booking`, `review`, `workflow`, `audit`, `shared`,
   `port`; move `BookingResult`/`SubmissionResult` to `service/dto` and `TravelAlert` to
   `service/observability`.
5. Extract the 14 nested `ApiController` records into `api/dto`; rename the takeover response to
   `WorkflowTakeoverView`.
6. Enable the full ArchUnit rule set.

After every phase, `./mvnw test` passed: 63 tests, including the architecture tests. HTTP JSON
field names were unchanged because record component names were preserved.

## Consequences

- The directory now expresses the architecture: inbound adapter, Agents, application services,
  pure domain, outbound adapters, shared utilities.
- `MasterAgent` is discoverable by the glossary name, and `BookingAgent`/`TaskContext` have a clear
  home for the future AgentScope runtime.
- Domain subpackages are acyclic, so aggregate boundaries are visible and safe to deepen.
- ArchUnit prevents the most common regressions without a runtime cost.
- There is more package ceremony and one test-only dependency. The rules need occasional
  maintenance as new packages appear.
- `InMemoryStore` still exposes typed maps to application services; repository ports are a
  follow-up, not part of this migration.
- `MasterAgent`, `BookingService`, and `ApiController` are still large. Directory layering makes
  their responsibilities visible but does not split them; behavior-preserving decomposition is a
  separate follow-up.

## References

- `docs/research/package-layering.md` — research, alternatives, and migration notes.
- `docs/adr/0001-runtime-seams.md` — runtime seams for the MVP.
- `src/test/java/com/roam/architecture/ArchitectureTest.java` — executable rules.
