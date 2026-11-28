## ADDED Requirements

### Requirement: Distinct display and technical identities

The application SHALL use **Budget Assistant for YNAB** as its display name and **`ynab-budget-assistant`** as the canonical repository slug and deployed checkout-directory basename. Active documentation SHALL distinguish current Amazon, Walmart and Sheetz support from planned Costco, Sam's Club and other retailers. The identity change SHALL NOT imply other budgeting-system support, official YNAB endorsement or OAuth approval of the technical slug.

#### Scenario: An operator reads the renamed project's introduction

- **WHEN** the operator opens current setup or overview documentation
- **THEN** the display name identifies a YNAB-specific purchase categorization assistant, planned retailers are labeled as planned, and repository/checkout instructions use the `ynab-`-prefixed technical slug

### Requirement: Deterministic distribution identity and launcher transition

Distributions and launchers SHALL be named `ynab-budget-assistant` regardless of checkout basename. Instructions SHALL use verified packaged entrypoints. The old executable name SHALL remain a deprecated alias for at least one transition release, preserving arguments, exit status and shared config/state. Removal SHALL require an announced breaking release. Old absolute deployment paths SHALL be migrated explicitly.

#### Scenario: Building outside the canonical checkout

- **WHEN** a clean build runs in a directory named neither the old nor new repository slug
- **THEN** base and optional Walmart packages still expose the same canonical launcher identity and the Walmart setup wrapper invokes the packaged application

#### Scenario: A caller uses the previous executable name

- **WHEN** the caller invokes the legacy alias during the transition release
- **THEN** the same application receives the same arguments and returns its exit status without starting an independent state store

### Requirement: Persisted Amazon notice compatibility

The application SHALL emit `[ERROR: Amazon Login Required on ynab-budget-assistant]` for new notices and indefinitely recognize it and `[ERROR: Amazon Login Required on ynab-amazon-transaction-updater]` in every notice/evidence check. It SHALL preserve user text, memo limits and write safeguards, avoid duplicate notices, and SHALL NOT bulk rewrite transactions for branding.

#### Scenario: A transaction contains the legacy notice

- **WHEN** the renamed application examines a memo containing user text and the legacy notice
- **THEN** it recognizes the notice rather than treating it as order evidence or completed enrichment, preserves the user text and does not append a second auth notice

#### Scenario: A recovered session processes a branded memo

- **WHEN** normal eligible processing replaces a memo containing either notice variant or both variants
- **THEN** generated notices are handled consistently without losing user text, exceeding the memo limit or bypassing normal write eligibility

### Requirement: Naming changes preserve retailer semantics

Branding and source cleanup SHALL preserve retailer matching, allocation, policy/cache identities, account/category selection, consent, enablement and financial-write semantics. The breaking property migration SHALL preserve those effective settings under the new paths, not accept the old schema. Retailer CLI flags and genuine retailer models/source names SHALL remain descriptive and unchanged.

#### Scenario: A migrated configuration is used with the renamed release

- **WHEN** a legacy config has been explicitly migrated to version 2 and all referenced paths remain valid
- **THEN** the renamed release retains the same retailer scope and write permissions and proposes the same transaction changes apart from the specified branding transition

### Requirement: Required retailer-neutral architecture

The change SHALL move source/test packages and Gradle group to `com.ynab.budgetassistant`, rename the entrypoint `BudgetAssistant` and Amazon-only matcher `AmazonTransactionMatcher`, and consolidate empty Walmart onboarding/classifier/key wrappers onto shared implementations. All callers, imports, reflection tests and launchers SHALL move together without old class shims. These changes SHALL be required, not deferred optional work.

#### Scenario: The packaged application and Walmart setup launch

- **WHEN** the release is built with or without the native Walmart helper
- **THEN** launchers invoke `com.ynab.budgetassistant.BudgetAssistant`, and Walmart setup uses shared onboarding with explicit retailer selection

#### Scenario: Shared processing keeps its multi-retailer responsibility

- **WHEN** architecture cleanup updates service and model names
- **THEN** the genuinely multi-retailer `TransactionProcessor` and `TransactionMatch` remain neutral, and policy IDs, wire contracts and state formats do not change
