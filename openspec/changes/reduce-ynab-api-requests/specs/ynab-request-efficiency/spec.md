## ADDED Requirements

### Requirement: Shared YNAB reads and request accounting

The updater SHALL count every YNAB HTTP attempt across sync, dry run, learning, recovery and onboarding without logging credentials or raw responses. It SHALL reuse equivalent plan-scoped reads only when their coverage and freshness satisfy the consumer. It SHALL preserve deliberate fresh validation for guarded writes and interactive setup.

#### Scenario: Retailers need the same plan metadata

- **WHEN** Amazon, Walmart and Sheetz request the same plan currency, account or category data during one run
- **THEN** the updater fetches each needed metadata resource once unless fresh validation is required

### Requirement: Bounded guarded batch writes

The updater SHALL use bounded collection PATCH requests for compatible writes with verified semantics when they reduce total calls. It SHALL freshly validate each transaction, persist guarded intents before dispatch, and confirm outcomes per transaction. It SHALL preserve approval, user-owned data, exact split amounts and explicit category clearing where authorized by the retailer policy.

#### Scenario: A chunk has an unconfirmed result

- **WHEN** a timeout or partial response leaves one or more writes uncertain
- **THEN** the updater reconciles each uncertain transaction with fresh evidence before retrying and does not replay the whole chunk

### Requirement: Safe rate-limit deferral

The updater SHALL stop further YNAB requests on 429 and report unfinished work. It SHALL retain pending intents for recovery and honor known remaining quota before starting new work that requires verification.

#### Scenario: A confirmation reaches the rate limit

- **WHEN** YNAB returns 429 after a write attempt
- **THEN** no more YNAB requests are sent in the run and the unconfirmed intent remains recoverable

### Requirement: Measured request reductions after Sheetz

The app SHALL establish the request-count baseline after Sheetz enrichment is implemented. Deterministic fixtures SHALL show lower total attempts for representative multi-write runs, no increase in normal no-op or single-write runs, and equivalent transaction changes and recovery states.

#### Scenario: A representative three-retailer run is measured

- **WHEN** both versions process identical eligible transactions on the same fake YNAB service
- **THEN** counts include discovery, metadata, learning, preflight, dispatch and confirmation, and the optimized run makes fewer total requests with the same final state
