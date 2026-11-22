## Why

Walmart can post several final card charges for one order. Most items belong to the user's Default Walmart Category (typically Groceries). We want to isolate non-default items in as few transactions as possible, categorize the remaining amounts directly to the default, and retain a direct order URL. This mirrors the user's manual workflow without inventing which items Walmart charged together. The nonworking Playwright guest/login fetchers, dependency and obsolete configuration have been removed without backwards compatibility. Fetching is unavailable until this proposal is implemented. The reviewed `walmart-client-go` provides purchase history, item details and the payment ledger needed for the replacement source.

## What Changes

- Add an opt-in, repository-owned Go bridge using `github.com/eshaffer321/walmart-client-go/v2` v2.2.1, reviewed at commit `d908f3d31ae1be088ad4a02ebe7f0b3dd2f70244`, with reproducible module checksums and a bounded minimal JSON contract.
- Make the Go ledger source the sole Walmart fetching path, with no guest/login modes or compatibility aliases. It uses a protected browser-session import, not passwords in application YAML or model/tool arguments, and does not launch a browser during fetching. Until implemented, only `walmart.enabled` remains recognized, defaults to `false`, and reports an explicit unavailable error when `true`.
- Match each YNAB purchase transaction to a distinct final-charge occurrence using exact amount, charge date and payment-method/account evidence. Multiple charges may point to the same order; holds, refunds and ambiguous matches must not become purchase matches.
- Add only order metadata to each parent memo: order date, full order total with currency, order ID and direct Walmart order link. Preserve existing user text within YNAB's limit; no generated item summaries or third-party URL shorteners. Item descriptions belong on individual split lines.
- Replace proportional splitting with explicitly enabled exception-first allocation. Choose the fewest charge transactions whose combined capacity covers the non-default items; write item lines only where needed, with a default-category remainder, and categorize other transactions directly to the default without splits. This is an accounting allocation, not an assertion about actual item-to-charge assignment.
- Require a configured Default Walmart Category ID from the existing budget. Assign taxes, fees, tips and all separately identified discounts to that default; do not spread them into exception items. Reconcile exact cents and require every final charge to match an eligible transaction before category allocation, including gift-card payments; otherwise permit only trustworthy link-only enrichment.
- Map products only to an explicitly configured shortlist of existing category IDs, including the Default Walmart Category, using approved product mappings before local decision-model classification. Plan providers for Laya (the model referred to as Layla) and Kev, with CPU, memory and retail-quality acceptance gates per checkpoint. Uncertain decisions remain uncategorized exceptions for YNAB review, not silently default spending. Never create, rename or suggest new categories.
- Keep model inference offline with pinned local artifacts, a separate locked runtime, bounded CPU/memory use and one resident model. No automatic downloads, remote model code, hosted providers or cloud fallback during categorization; hosted Jev/OpenAI support is deferred.
- Add an interactive onboarding script: choose Laya or Kev, enter a read-scoped Hugging Face token through hidden input, download and verify the pinned model artifacts, fetch existing YNAB categories, choose the shortlist by displayed numbers and select its default. Confirm and save category IDs/model settings without creating categories, retaining the token in YAML or enabling automatic writes.
- Use YNAB as the human review and feedback interface: write changes only to eligible unapproved, unsplit transactions, keep them unapproved, and learn unambiguous item-category mappings from subsequent human approval/corrections. Unapproved changes already affect budget balances; they are not drafts.
- Preserve existing splits and transaction identity/state. Add dry-run preview, retry-safe per-charge category/split completion and drift detection; never replace bank transactions or rewrite human edits.
- Keep link-only behavior as the default, preserve Amazon hardening, and cover the new contracts with offline fixtures, allocation invariants, HTTP contract tests and operator documentation.

## Capabilities

### New Capabilities

- `walmart-order-fetching`: Optional Go-backed consumer history/details/ledger retrieval, normalized money and payment identities, secret-safe session handling and failure/completeness contracts.
- `walmart-transaction-enrichment`: One-charge-per-transaction matching, stable order links, exception-first allocation, existing-category mapping, YNAB-only review/learning, reconciliation, safe updates and retry behavior.

### Modified Capabilities

None. Existing OpenSpec capabilities cover Amazon fetching and the runtime baseline, not Walmart enrichment. Historical Walmart behavior is documented in `.kiro/specs/walmart-transaction-support/`; its removed browser source modes are not a compatibility contract. Existing Walmart models, matcher and processor remain as a basis for the stricter ledger workflow.

## Impact

- Services/models: `WalmartService`, a new Go-backed fetcher/bridge, Walmart item/charge models, `TransactionMatcher`, `TransactionMatch`, `TransactionProcessor`, and `YNABService`/transaction DTOs.
- Configuration: new helper executable/session settings, payment-method mapping where necessary, an existing default category ID, an explicit category-ID shortlist with optional decision descriptions, local model/checkpoint/resource settings, and explicit `link_only` versus `exception_first` enrichment selection, gated by `walmart.enabled` (default `false`). These are future settings, not currently recognized configuration. No proportional mode, legacy mode migration, source fallback or automatic split migration.
- Build/distribution: Gradle owns the pinned Go SDK/module build behind `-Pwalmart`; existing compile/test/check/distribution commands include Go when opted in. Base builds require/download no Go. Target-labeled Walmart distributions bundle the compiled helper and onboarding script, not the compiler or model weights; consumers need no Go installation. CI covers both variants. Java 25 remains unchanged.
- External systems: unofficial Walmart consumer endpoints and YNAB transaction update APIs. No live authentication, production writes or browser installation occurs during proposal creation or default verification.
- Limitations: USD purchase outflows first; no refund/foreign-exchange allocation, new category creation/suggestions, hosted classification, editing existing splits, or account-wide rollback. Missing/ineligible payments, unexplained pricing and a negative default-category remainder prohibit category allocation. Detailed policy, worked examples and implementation tasks accompany this proposal.
