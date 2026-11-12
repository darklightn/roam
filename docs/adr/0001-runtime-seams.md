# ADR 0001: Runtime seams for the MVP vertical slice

## Status

Accepted for the MVP implementation.

## Context

The Wayfinder decisions name Spring Boot as the service foundation, AgentScope 2.0 as the Agent
runtime, MySQL as durable storage, and Redis for queues, locks, and idempotency. The target
environment for this slice has Java 21 and Maven available but no MySQL, Redis, AgentScope runtime, or
Tuniu credentials. The acceptance contract is behavioural: an employee submits a validated
IntentPackage and observes durable workflow outcomes, supplier identifiers, audit evidence,
permissions, redaction, and operational signals.

## Decision

- Keep Spring Boot as the service foundation and expose a small HTTP surface.
- Put durable business and workflow state behind an in-memory store with the same append-only audit
  semantics and guarded transitions a MySQL adapter must preserve. `InMemoryStore` is the seam.
- Implement `MasterAgent` coordination in `agent/MasterAgent`, child-Agent execution in
  `agent/BookingAgent`, and bounded, isolated `agent/TaskContext` records. The AgentScope runtime can replace the executor and
  model invocation without changing domain rules.
- Model the supplier boundary as `SupplierAdapter`; `TuniuFixtureAdapter` implements deterministic
  flight, hotel, and train behaviour, including uncertain outcomes and bounded calls.
- Use a bounded executor in place of Redis queues and locks. Review claims are synchronized, and all
  recovery state can be rebuilt from the durable store.
- Keep payment execution out of scope. Payment links are returned only as opaque references, and
  callbacks are observations.

## Consequences

- The acceptance tests run deterministically without external infrastructure.
- MySQL/Redis/AgentScope/Tuniu can be introduced behind the existing seams without changing the
  externally visible workflow invariants.
- Some implementation-level concerns (SQL schema, MCP transport, model prompts) remain follow-on
  work, as the specification allows.
