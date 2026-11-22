## Purpose

Provide optional, secret-safe Walmart consumer order history, item costs and final payment charges for transaction linking and reconciled item allocation.

## ADDED Requirements

### Requirement: Sole opt-in Go ledger source
The application SHALL provide the session-backed Go ledger helper as the sole Walmart fetching source, gated by `walmart.enabled` defaulting to `false`, without changing Amazon behavior. It SHALL NOT restore removed browser fetchers, Playwright, source selectors, compatibility aliases or fallback sources. Fetching SHALL NOT require passwords or email credentials in application YAML.

#### Scenario: Operator enables the implemented Go integration
- **WHEN** Walmart is enabled after implementation with valid helper/session paths and positive time/output bounds
- **THEN** Walmart retrieval uses the ledger source without launching a login browser or connecting to IMAP

#### Scenario: Operator leaves Walmart disabled
- **WHEN** `walmart.enabled` is false or omitted
- **THEN** no Walmart helper or session is required and Amazon behavior and hardening remain unchanged

#### Scenario: Operator enables Walmart before helper integration exists
- **WHEN** `walmart.enabled` is true before the Go integration is implemented
- **THEN** an explicit unavailable error is reported without attempting any removed browser path

#### Scenario: Operator retains removed source settings
- **WHEN** an old configuration contains Walmart guest/login or browser settings
- **THEN** those settings do not select a source, become aliases or trigger a fallback, and documentation instructs the operator to remove them

### Requirement: Retrieved history and Groovy-owned charge normalization
Go SHALL return bounded versioned order/item/pricing records and repeated ledger entries preserving amount, currency, date, payment identity and status/type. Groovy SHALL normalize final charges and completeness without substituting bank metadata. Go SHALL NOT perform inference/financial/category decisions or receive OpenAI/YNAB credentials. Failed retrieval SHALL NOT masquerade as complete empty data.

#### Scenario: Successful history contains no orders
- **WHEN** retrieval succeeds with an empty history
- **THEN** the helper emits an explicit orders:[] array and Groovy accepts a successful empty result, while missing/null arrays remain schema failures

#### Scenario: One order has multiple final charges
- **WHEN** a fulfilled order has two final charges, a temporary hold and a refund
- **THEN** its two purchase charges remain distinct, including equal-valued occurrences, and neither the hold nor refund is emitted as a purchase charge

#### Scenario: Details or ledger cannot be trusted
- **WHEN** required pricing or ledger retrieval fails or returns unsupported data
- **THEN** the order is marked ineligible for splitting and no apparently complete allocation input is produced

### Requirement: Exact normalized money and item identity
Go SHALL preserve source identities, decimal quantities, prices and adjustments without financial rounding or collapsing ledger occurrences. Groovy SHALL deduplicate repeated item display groups and distinguish gross/net prices and explicit discounts without multiplying extended costs by quantity or adding discounts twice. Unknown pricing semantics, currency or precision SHALL prevent category allocation.

#### Scenario: Weighted product and repeated presentation groups
- **WHEN** one weighted product appears in multiple Walmart presentation groups
- **THEN** its actual extended line cost is counted once and its decimal quantity is preserved

#### Scenario: Net price includes a separately identified discount
- **WHEN** the source supplies an item net price of $15 with an explicit corresponding $5 discount
- **THEN** normalization can identify its $20 pre-discount basis and $5 discount separately, but does not add $5 again if the source already supplies the $20 gross price

### Requirement: Bounded and secret-safe retrieval
Fetching SHALL use bounded requests/process execution, normal TLS verification and Walmart-only destinations without following external redirects. The helper SHALL receive only required runtime/session access, omit unrelated credentials, and omit cookies, addresses, raw responses and secrets from output/logs.

#### Scenario: Session expires or output exceeds limits
- **WHEN** fetching encounters stale authentication, a timeout, nonzero exit, oversized output or invalid schema
- **THEN** it produces no usable success payload for that failed fetch and reports a fixed non-secret failure category without terminating unrelated retailer processing

#### Scenario: Sensitive upstream fields are present
- **WHEN** Walmart returns customer addresses, session cookies or other fields unnecessary for matching
- **THEN** normalized output excludes them and retains only identifiers, dates, items, pricing and required payment evidence

### Requirement: Reproducible optional installation
The optional helper SHALL be built from the reviewed pinned client source with verified dependency checksums. Standard application installation SHALL remain usable without the helper, and documentation SHALL distinguish process bounds/environment filtering from OS isolation.

#### Scenario: Operator builds the helper
- **WHEN** the documented optional build is followed
- **THEN** it selects the reviewed source revision without dynamic latest-version resolution and requires no live account credentials

### Requirement: Opt-in Gradle Go lifecycle
Gradle SHALL integrate Go through `-Pwalmart`: compile/test compilation, unit/integration tests, check/build, clean and distribution tasks SHALL cover the helper with failures propagated. Base builds SHALL NOT resolve/download Go tooling/modules. Enabled builds SHALL use a pinned verified host SDK, read-only modules and no implicit SDK switching. Cached offline builds SHALL work; missing offline prerequisites SHALL fail without network access.

#### Scenario: Existing build and test commands opt into Walmart
- **WHEN** existing Gradle lifecycle tasks run with `-Pwalmart`
- **THEN** corresponding Go compilation/tests run without manual Go commands, test compilation alone does not execute tests, aggregate tasks avoid duplicate Go runs and Go failures fail Gradle with published reports

#### Scenario: Base and Walmart builds share a worktree
- **WHEN** enabled and disabled builds run in either order, followed by clean or incremental builds
- **THEN** declared inputs/outputs prevent stale helper reuse or packaging in base archives, clean removes generated outputs without deleting shared SDK caches, and base tasks require no Go installation

### Requirement: Prebuilt target-specific Walmart distributions
Walmart-enabled Gradle distributions SHALL contain the compiled helper and onboarding script, identify their supported OS/architecture and run without Go installed. They SHALL NOT contain compiler/toolchains, model weights or secrets, or compile/download Go at runtime. Base packages SHALL remain usable without the helper. CI SHALL verify both build variants and supported native release targets with offline account fixtures.

#### Scenario: Operator installs a Walmart-enabled archive
- **WHEN** the matching native distribution runs without Go on PATH
- **THEN** it discovers and executes its bundled helper without compilation; unsupported/missing-helper cases report actionable errors rather than runtime downloads, and runtime Walmart enablement remains explicit

### Requirement: Protected session provisioning
Session import SHALL parse browser-request data without executing copied shell commands, keep bearer material in protected local storage, and avoid secret-bearing process arguments. Documentation SHALL explain stale-session recovery, capture-file disposal and filesystem exposure.

#### Scenario: Operator imports a browser session
- **WHEN** the operator explicitly provisions a session from a local capture
- **THEN** import does not execute its shell contents, session files use owner-only POSIX permissions or equivalent ACLs, and neither the capture nor cookies enter logs or source control
