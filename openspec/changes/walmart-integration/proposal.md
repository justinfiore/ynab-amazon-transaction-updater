## Why

Walmart can post several final card charges for one order. We want each matching YNAB transaction to retain a direct order URL and optionally contain item-level splits, without counting the order's items once per charge or inventing which items Walmart charged together. The nonworking Playwright guest/login fetchers, dependency and obsolete configuration have been removed without backwards compatibility. Fetching is unavailable until this proposal is implemented. The reviewed `walmart-client-go` provides purchase history, item details and the payment ledger needed for the replacement source.

## What Changes

- Add an opt-in, repository-owned Go bridge using `github.com/eshaffer321/walmart-client-go/v2` v2.2.1, reviewed at commit `d908f3d31ae1be088ad4a02ebe7f0b3dd2f70244`, with reproducible module checksums and a bounded minimal JSON contract.
- Make the Go ledger source the sole Walmart fetching path, with no guest/login modes or compatibility aliases. It uses a protected browser-session import, not passwords in application YAML or model/tool arguments, and does not launch a browser during fetching. Until implemented, only `walmart.enabled` remains recognized, defaults to `false`, and reports an explicit unavailable error when `true`.
- Match each YNAB purchase transaction to a distinct final-charge occurrence using exact amount, charge date and payment-method/account evidence. Multiple charges may point to the same order; holds, refunds and ambiguous matches must not become purchase matches.
- Add only order metadata to each parent memo: order date, full order total with currency, order ID and direct Walmart order link. Preserve existing user text within YNAB's limit; no generated item summaries or third-party URL shorteners. Item descriptions belong on individual split lines.
- Add an explicitly enabled proportional-split mode. Each item and explicit tax/fee/tip component is spread across all final charges in proportion to each charge's amount. This is an accounting allocation, not an assertion about shipment contents or actual item-to-charge assignment.
- Balance split amounts exactly in integer currency units, include unmatched/gift-card charges in the denominator, and refuse splitting when item/component totals do not reconcile to the complete final-charge ledger.
- Preserve existing splits and transaction identity/state. Add preview, retry-safe per-charge completion and drift detection; never replace bank transactions or silently rewrite previously applied allocations.
- Keep link-only behavior as the default, preserve Amazon hardening, and cover the new contracts with offline fixtures, allocation invariants, HTTP contract tests and operator documentation.

## Capabilities

### New Capabilities

- `walmart-order-fetching`: Optional Go-backed consumer history/details/ledger retrieval, normalized money and payment identities, secret-safe session handling and failure/completeness contracts.
- `walmart-transaction-enrichment`: One-charge-per-transaction matching, stable order links, proportional item splits, reconciliation, safe YNAB updates and retry behavior.

### Modified Capabilities

None. Existing OpenSpec capabilities cover Amazon fetching and the runtime baseline, not Walmart enrichment. Historical Walmart behavior is documented in `.kiro/specs/walmart-transaction-support/`; its removed browser source modes are not a compatibility contract. Existing Walmart models, matcher and processor remain as a basis for the stricter ledger workflow.

## Impact

- Services/models: `WalmartService`, a new Go-backed fetcher/bridge, Walmart item/charge models, `TransactionMatcher`, `TransactionMatch`, `TransactionProcessor`, and `YNABService`/transaction DTOs.
- Configuration: new helper executable/session settings, payment-method mapping where necessary, and explicit `link_only` versus `proportional` enrichment selection, gated by `walmart.enabled` (default `false`). These are future settings, not currently recognized configuration. No legacy mode migration, source fallback or automatic split migration.
- Build/distribution: a dedicated pinned Go module and documented helper build; Go is needed to build the optional helper, not to run an existing email/CSV installation. Java 25 remains unchanged.
- External systems: unofficial Walmart consumer endpoints and YNAB transaction update APIs. No live authentication, production writes or browser installation occurs during proposal creation or default verification.
- Limitations: USD purchase outflows first; no refund/foreign-exchange allocation, automatic category inference, editing existing splits, or account-wide rollback. Detailed policy, worked examples and implementation tasks accompany this proposal.
