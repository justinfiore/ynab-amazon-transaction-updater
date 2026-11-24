# item-classification Specification

## Purpose

Define shared retailer-neutral item classification with independent category policies and consent, scoped approved mappings and cached decisions, bounded provider access and protected retailer-targeted onboarding, without owning financial allocation or YNAB writes.

## Requirements

### Requirement: Retailer-neutral item classification
Amazon and Walmart SHALL consume one classification service accepting neutral item IDs/titles and explicit retailer, plan and category policy. It SHALL resolve mappings/cache/provider decisions into correlated nullable category decisions with provenance. Shared code SHALL NOT depend on Walmart models/configuration/fingerprint helpers or own financial allocation and YNAB writes.

#### Scenario: Both retailers classify products
- **WHEN** Amazon and Walmart submit neutral item evidence using their respective policies
- **THEN** the same service and provider adapters resolve decisions without constructing Walmart items for Amazon or duplicating inference implementations

### Requirement: Independent retailer category policies
Each retailer SHALL select its own nonempty shortlist of eligible existing category IDs for financial enrichment, with optional boundary descriptions. Shared catalog validation SHALL reject duplicate, hidden, deleted and internal targets without requiring a default; only Walmart's allocation policy SHALL require its default. The service SHALL NOT create categories or use source category strings as YNAB authority.

#### Scenario: Amazon and Walmart lists differ
- **WHEN** Amazon selects Electronics and Pets while Walmart selects Groceries and Household
- **THEN** Amazon decisions can target only its own list and neither setup nor inference changes the Walmart list or introduces a default for Amazon

### Requirement: Scoped decisions and approved learning
Approved evidence SHALL take precedence over inference only within the active shortlist. Conflicting approved evidence SHALL remain unresolved. Inferred cache identity SHALL include retailer, plan, product/title, category policy, provider/model/route, prompt/version, acceptance gates and connection identity. Learned mappings SHALL require trustworthy approved item provenance and be scoped by retailer, plan and product; missing product identity SHALL prohibit persistent product learning.

#### Scenario: Identifiers collide across retailers
- **WHEN** Amazon and Walmart use the same product identifier or title with different category policies
- **THEN** neither retailer reuses the other's cached decision or approved mapping

#### Scenario: Approved evidence conflicts
- **WHEN** trustworthy approved rows disagree on a product category
- **THEN** the decision remains unresolved and model output does not arbitrate the human conflict

### Requirement: Shared bounded providers and isolated consent
Shared providers SHALL preserve existing endpoint/model/auth restrictions, validation, abstention, acceptance gates, bounds and metrics. Errors SHALL remain distinct from abstention. Only titles and selected labels/descriptions SHALL be disclosed after retailer-specific consent, without provider fallback or cross-retailer batching. Credentials SHALL remain protected and absent from YAML values, arguments, logs and fingerprints.

#### Scenario: Only Walmart consent exists
- **WHEN** Amazon is configured for item categorization without Amazon remote-disclosure consent
- **THEN** no Amazon titles reach a provider; approved mappings and valid local cache may be used without granting implicit consent

#### Scenario: A shared credential is disconnected
- **WHEN** one retailer stops using a provider credential still referenced by the other retailer
- **THEN** setup removes only the target reference and does not delete the still-referenced protected key or change the other's configuration

### Requirement: Retailer-targeted onboarding and offline preview
Shared onboarding SHALL target an explicit retailer, display numbered existing group/category choices, revalidate and atomically confirm changes while preserving unrelated settings. Amazon SHALL NOT prompt for a default; Walmart SHALL retain default selection. Setup SHALL NOT enable writes or make paid probes. Dry runs SHALL use local mappings/cache without inference, YNAB writes or state mutation and SHALL identify missing inference explicitly.

#### Scenario: Amazon category selection is cancelled
- **WHEN** the operator cancels Amazon onboarding or its category snapshot becomes stale
- **THEN** both retailers' active settings and credential references remain unchanged
