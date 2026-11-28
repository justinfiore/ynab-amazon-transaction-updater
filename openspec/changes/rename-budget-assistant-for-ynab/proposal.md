## Why

The Amazon-focused repository identity no longer describes an application that supports Amazon, Walmart and Sheetz and is moving from memo enhancement toward automated purchase categorization. Justin's goal is to take the toil out of maintaining an accurate YNAB budget. Costco, Sam's Club and other retailers are planned, not implemented; supporting other budgeting systems is not a goal.

Justin has finalized **Budget Assistant for YNAB** as the display name and **`ynab-budget-assistant`** as both the GitHub repository name and deployed checkout-directory basename. The `ynab-` prefix deliberately keeps related repositories and deployed directories together when sorted. These are decisions, not alternatives to reopen in this proposal.

## What Changes

- Adopt the display name in current documentation, startup/completion messages, setup examples and project metadata. Explain purchase categorization and existing opt-in safeguards without promising that all retailers or all purchases are automatically categorized.
- Make the Gradle application/distribution identity explicitly `ynab-budget-assistant`, independent of the checkout basename. Update launchers, packaging examples and repository-owned links, including the local Go helper module path when the GitHub rename occurs. Preserve retailer-specific identifiers that describe real retailer behavior.
- Handle the old Amazon login notice as persisted data, not just a string replacement: recognize both old and new branding without duplicating notices or treating one as purchase evidence.
- **BREAKING**: Replace the unversioned root-level YAML properties with `schema_version: 2`, `budget.ynab`, `runtime` and `retailers.{amazon,walmart,sheetz}`. Do not provide runtime aliases or backward-compatible parsing of old properties. Preserve effective settings and state through an explicit offline migration, not startup conversion.
- Provide `scripts/migrate-config.sh` and a Windows counterpart, backed by the application's existing YAML library, to validate and migrate legacy properties into the new structure. Require explicit old working-directory information for path resolution, protected output, no source overwrite, no network calls and no state resets.
- Detect unmigrated, mixed-schema and unsupported-version application configs before initialization or side effects. Abort with a nonzero exit code; unmigrated/mixed-schema errors identify the file and point to the migration script and its usage. Update all setup, category-selection, payment-account and disconnect config readers/writers to the new schema.
- Specify a staged, separately authorized GitHub rename and Nextcloud checkout cutover with compatibility, preview validation, single-writer operation and rollback. Live deployment inventory is still required.
- Require architecture naming cleanup in this change: `BudgetAssistant`, root package/group `com.ynab.budgetassistant`, `AmazonTransactionMatcher`, and consolidation of Walmart-only compatibility wrappers onto their existing shared implementations. Keep genuine multi-retailer classes neutral; do not introduce a generic budgeting/integration framework.

## Capabilities

### New Capabilities

- `application-identity`: Public and technical names, required architecture cleanup, launch identity and compatibility with persisted branded notices.
- `rename-migration`: Breaking config schema, offline property migration, startup rejection, state continuity and controlled repository/deployment migration.

### Modified Capabilities

None. Retailer matching, classification, allocation, write safeguards and runtime baseline remain governed by existing capabilities. The new capabilities define identity and config migration behavior, not new retailer functionality.

## Impact

Future implementation affects active docs, Gradle identity, Groovy source/test packages and imports, launchers, reflection tests, logging references, all application-config readers/writers, a migration script, notice parsing, repository-linked Go metadata and eventually external GitHub/Nextcloud references. Existing config files cannot be used by the new runtime until migrated. Old fully qualified Java entrypoints are not retained; repository-owned callers must move together.

This change contains proposal/specification files only. It does not change runtime code, local runtime configuration, repository settings, remotes, deployed files or schedules. Implementation tasks remain unchecked. External mutations require Justin's separate approval.

## Sources and baseline

- Repository baseline: remote default branch `origin/master`, commit `7ce6ccbd91e06e0cd010cb38f741b23c4eb7d8c3`, fetched and equal to this checkout's HEAD on 2026-11-28; no naming-thread commits or edits were transferred.
- [Final naming discussion](https://ampcode.com/threads/T-01a222a3-c089-7775-8c10-a8d4c3f62230) and [naming research](https://ampcode.com/artifacts/ynab-naming-research-b411dfaf-c0a1-40df-b467-4b43aa11f817).
- [YNAB OAuth naming requirements](https://api.ynab.com/#oauth-requirements), checked 2026-11-28: application and DNS names containing YNAB require preceding “for”. The text does not explicitly address repository slugs or local directories; it is not formal approval of the technical slug. No public hostname is selected here.
- Future OAuth distribution needs a separate eligibility/privacy review: the published policy restricts AI-assistant integrations and may decline applications sending YNAB user data to third-party AI models. This is separate from the current personal-access-token deployment; the naming change neither establishes OAuth eligibility nor changes authentication or data-sharing consent.
