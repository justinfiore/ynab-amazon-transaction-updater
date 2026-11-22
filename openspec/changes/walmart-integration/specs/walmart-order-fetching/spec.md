## Purpose

Provide optional, secret-safe Walmart consumer order history, item costs and final payment charges for transaction linking and reconciled item allocation.

## ADDED Requirements

### Requirement: Explicit ledger source selection
The application SHALL offer an opt-in session-backed Walmart ledger source without changing existing guest/login defaults or Amazon behavior. Ledger mode SHALL NOT require Walmart passwords or email credentials in application YAML.

#### Scenario: Operator selects ledger mode
- **WHEN** ledger mode has valid helper/session paths and positive time/output bounds
- **THEN** Walmart retrieval uses the ledger source without launching a login browser or connecting to IMAP

#### Scenario: Operator keeps existing configuration
- **WHEN** ledger mode is not selected
- **THEN** existing source selection and Amazon hardening remain unchanged

### Requirement: Normalized history and complete charge evidence
The source SHALL return versioned order/item records and distinct final purchase-charge occurrences with amount, currency, charge date and payment identity. It SHALL distinguish known-complete data from failed or unsupported retrieval and SHALL NOT treat missing data as zero charges.

#### Scenario: One order has multiple final charges
- **WHEN** a fulfilled order has two final charges, a temporary hold and a refund
- **THEN** its two purchase charges remain distinct, including equal-valued occurrences, and neither the hold nor refund is emitted as a purchase charge

#### Scenario: Details or ledger cannot be trusted
- **WHEN** required pricing or ledger retrieval fails or returns unsupported data
- **THEN** the order is marked ineligible for splitting and no apparently complete allocation input is produced

### Requirement: Exact normalized money and item identity
The source SHALL preserve stable order/item identities, decimal quantities and extended line prices without double-counting duplicate presentation groups or multiplying an already extended price by quantity. Monetary conversion SHALL reject unsupported currency or ambiguous precision rather than silently round a match into existence.

#### Scenario: Weighted product and repeated presentation groups
- **WHEN** one weighted product appears in multiple Walmart presentation groups
- **THEN** its actual extended line cost is counted once and its decimal quantity is preserved

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

### Requirement: Protected session provisioning
Session import SHALL parse browser-request data without executing copied shell commands, keep bearer material in protected local storage, and avoid secret-bearing process arguments. Documentation SHALL explain stale-session recovery, capture-file disposal and filesystem exposure.

#### Scenario: Operator imports a browser session
- **WHEN** the operator explicitly provisions a session from a local capture
- **THEN** import does not execute its shell contents, session files use owner-only POSIX permissions or equivalent ACLs, and neither the capture nor cookies enter logs or source control
