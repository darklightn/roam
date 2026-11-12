# Domain Docs

How the engineering skills should consume this repo's domain documentation.

## Before exploring, read these

- **`GLOSSARY.md`** at the repo root.
- **`docs/adr/`**: read ADRs that touch the area you're about to work in.

If these files don't exist, proceed silently. The `/domain-modeling` skill creates them lazily when terms or decisions are resolved.

## File structure

This is a single-context repo:

```
/
├── GLOSSARY.md
├── docs/adr/
└── src/main/java/com/roam/
    ├── api/             HTTP adapter + dto/
    ├── agent/           MasterAgent, BookingAgent, TaskContext, contract/
    ├── service/         use-case services + audit/observability/policy/review/security/dto
    ├── domain/          pure model: trip/plan/booking/review/workflow/audit/shared/port
    ├── infrastructure/  persistence/supplier/config
    └── shared/          Ids, Redaction, RoamException
```

Package dependency rules are recorded in `docs/adr/0002-package-layering.md` and enforced by
`src/test/java/com/roam/architecture/ArchitectureTest.java`.

## Use the glossary's vocabulary

When output names a domain concept, use the term as defined in `GLOSSARY.md`. If the concept is not defined yet, note the gap for `/domain-modeling`.

## Flag ADR conflicts

If output contradicts an existing ADR, surface it explicitly rather than silently overriding it.
