## ADDED Requirements

### Requirement: Shared classification without Walmart behavior drift
Walmart SHALL use neutral classification, category onboarding/validation, credentials and guarded YNAB operations shared with Amazon, without shared dependencies on Walmart models or default accounting. Its retrieval, matching, default/exception allocation, memo/refund policy, eligibility, review and recovery SHALL remain unchanged. Existing configuration, CLI, consent, credentials, journals and unchanged-policy caches SHALL remain usable without forced resets.

#### Scenario: Existing Walmart state survives extraction
- **WHEN** a Walmart-only operator upgrades with existing settings, inferred decisions, approved mappings and a pending journaled write
- **THEN** the same policy resolves categories and confirms/resumes pending intent without duplicate writes, credential replacement or Amazon setup

#### Scenario: Amazon uses a different allocation policy
- **WHEN** Amazon item categorization runs without a default alongside Walmart exception-first mode
- **THEN** Walmart still aggregates its default spending and adjustments, retains its metadata-only parent memo and does not adopt Amazon's itemization or proportional adjustment policy
