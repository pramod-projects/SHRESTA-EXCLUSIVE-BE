# AI Synchronization Policy

No backend change is complete unless its AI documentation is updated in the same change.

## Required Updates

Update `.ai/knowledge-base.md` when a change affects architecture, module responsibility, workflow, API behavior, deployment, test strategy, or production operations.

Update `.ai/mind-map.md` when a module, package, service, class, endpoint, table, event, workflow, dependency, or important function is added, renamed, moved, or removed.

Update `.ai/business-rules.json` when a business invariant, SLO, security rule, category rule, payment rule, inventory rule, or financial rule changes.

Update `.ai/architecture-map.json` when module ownership, data ownership, communication rules, or phase evolution changes.

Create or update an ADR in `.ai/adr/` when a change introduces a durable technical decision or rejects a meaningful alternative.

Add or update sibling `.ai-context.json` files for every significant Java class.

## Completion Checklist

Before a backend change is considered done:

- Source compiles under Java 21.
- Tests covering the changed behavior pass.
- Flyway migrations are forward-only and immutable.
- New or changed APIs are documented in OpenAPI and `.ai/knowledge-base.md`.
- New or changed tables are documented in `.ai/knowledge-base.md` and `.ai/mind-map.md`.
- New or changed events are documented in `.ai/architecture-map.json` and `.ai/mind-map.md`.
- Financial, payment, order, inventory, auth, and admin changes include negative tests.
- Observability expectations are updated for new critical paths.
